# XEP-0258 plugin design

Status: first server-enforcement increment implemented, September 9, 2026.
An enforcement-mode switch (warn/enforce) was added September 15, 2026.
Label catalogue discovery (add/list/remove in the Admin Console) was added
September 15, 2026. Settings storage was switched from an atomic file to
Openfire properties on September 22, 2026, per explicit user direction. An
optional outbound default-label stripping switch for server-to-server traffic
was added September 22, 2026 (see "Outbound default-label stripping for
federation" below). `doc/acdf-checks.md` documents, as a standalone reference,
which objects carry a label/clearance and which points in a message's
lifetime perform a real access-control check, added September 23, 2026. An
optional default peer clearance, checked on federated ingress and egress, was
added September 23, 2026 (see "Peer clearance" below). A never-configured
plugin (no policy, default label, or server clearance ever saved) now fails
open instead of closed, added September 24, 2026 (see "Fail-open when never
configured" below); `doc/acdf-checks.md` was updated accordingly. Support for
loading multiple policies into one registry, with cross-policy label
translation via each policy's own declared equivalence mappings, was added
September 24, 2026 (see "Multiple policies" below); `doc/acdf-checks.md` was
updated accordingly. Catalogue retrieval now runs the peer-clearance ACDF
check, in addition to the always-checked server clearance, against a
requested `<catalog/>` `to=` recipient that is not local to this server,
added September 24, 2026 (see "Label catalogue" below); `doc/acdf-checks.md`
was updated accordingly. A local-only IQ handler for discovering the currently
loaded policies (listing every loaded policy's id/name, and fetching a
specific policy's full Open XML SPIF document by either) was added
September 24, 2026 (see "Policy discovery" below). Saving settings with
multiple policies could throw `NoSuchMethodError` and render a mostly blank
Admin Console page on some Openfire builds; `JiveGlobalsConfigurationStore`
was changed to stop depending on `JiveGlobals`' list-property overload, fixed
September 25, 2026 (see "Build and dependency compatibility" below).

## Objective and confirmed requirements

Replace the example skeleton with a plugin using the Java port in
`../spiffing-java`. Administrators configure an Open XML SPIF policy, server
clearance, and default message label in the Openfire Admin Console. Validate the
default against both the policy and clearance before accepting configuration.
Check inbound messages against the server clearance. Unlabelled messages assume
and are stamped with the default label.

Maintain this document with implementation decisions and add useful tests,
including negative cases and policy boundaries, as functionality changes.

## Initial choices awaiting user refinement

The following defaults were proposed in clarification questions and used for this
increment; they can be revised independently of the confirmed requirements:

- Accept ESS, NATO XML, and Spiffy XML labels. Expose independent input format
  selectors and a default-label output selector, initially ESS.
- Fail open (let ordinary messages through unchecked) while never configured;
  fail closed (block ordinary messages) after invalid/corrupted startup
  configuration. See "Fail-open when never configured" below for the
  distinction and its rationale. A working configuration remains active when
  an Admin Console save is rejected.

The server clearance covers local-client and federated traffic. No per-user or
per-room clearance is inferred from the server clearance.

## Components and configuration lifecycle

`Settings` holds the policy, clearance, default-label payload, two input formats,
and one output format. It serializes these as a versioned XML document, with the
input documents escaped as text. The Admin Console accepts payloads, not full
XEP-0258 envelopes. ESS inputs are base64 ASN.1; XML inputs are literal XML.

`PolicyConfiguration` creates a private Spiffing `Site` and loads one or more
policies into it (see "Multiple policies" below), then parses the clearance and
default label against the first (primary) loaded policy in that same registry. It calls
both `Spif.assertValid(label)` and `Spif.acdf(label, clearance)`. Spiffing's ACDF
does not imply policy validity; the two checks are intentionally independent.
It encodes a default envelope with a policy-derived display marking, parses it
back, and checks that its security semantics survive the selected serialization.
This also catches representations that cannot preserve a policy's category types.

`ConfigurationService` serializes writers. It validates a complete candidate,
persists it, and only then publishes the new snapshot through a volatile
reference. Message threads read one snapshot per message without waiting for
saves. Registries and policy objects are privately owned and never mutated after
publication; mutable envelope templates are copied for each stamped message.

`JiveGlobalsConfigurationStore` persists each `Settings` field as its own
`JiveGlobals` property under the `plugin.spiffing.settings.*` prefix. This
replaces an earlier atomic-file store (`FileConfigurationStore`, writing
`OPENFIRE_HOME/conf/spiffing.xml`), at the user's explicit request to use
"database and properties, as is the usual idiom for Openfire and its plugins"
instead of a plugin-managed XML file. `JiveGlobals` persists to the `ofProperty`
database table for a database-backed installation, or to a standalone XML
properties file otherwise; either way, the plugin no longer manages its own
configuration file.

This change knowingly gives up two properties the file-based store had: saving
all fields is no longer one atomic operation (`write` issues one `setProperty`
call per field, so a failure partway through can leave a mix of old and new
values), and `JiveGlobals`/`JiveProperties` can swallow an underlying SQL write
failure while still updating its in-memory cache, so a failed save is not
guaranteed to surface as an error to the administrator. Both trade-offs were
raised with, and accepted by, the user in favor of the more idiomatic storage
mechanism (see the "Catalog storage" and "Storage mechanism" clarification
questions/answers in the issue history). On startup, entirely missing
properties (nothing ever saved) leave the plugin inactive without blocking
ordinary messages; incomplete/corrupted properties (something was saved but
fails to load) still block ordinary messages, exactly as the file-based store
did — see "Fail-open when never configured" below for how these two states are
distinguished. Administrators recover from the corrupted case by saving valid
settings again. Configuration distribution and simultaneous administration
across cluster nodes are not supported; configure each node separately.

The Admin Console relies on Openfire's administrator authentication. Writes
require POST and matching CSRF cookie/token values. Submitted documents are
escaped on redisplay, and failed validation preserves the submitted form. The
page reports generic input errors without exposing parser details or document
contents in server logs. `SettingsForm` makes request validation testable without
starting the Admin Console.

## Message processing

`SpiffingPlugin` registers a global `SecurityLabelInterceptor` and advertises
`urn:xmpp:sec-label:0`. Plugin destruction removes both. The interceptor's
inbound stamping/checking logic below only handles inbound `Message` callbacks
before processing (`incoming && !processed`); its separate outbound handling is
described in "Outbound default-label stripping for federation". Postprocessing,
IQ, and presence callbacks remain outside this increment.

1. For error messages, bypass authorization and stamping. Discard an error with
   a direct XEP-0258 security label without generating a reply.
2. Read the active snapshot. If absent because stored settings are corrupted,
   reject with `service-unavailable`; if absent because the plugin was simply
   never configured, let the message through unchanged instead (see
   "Fail-open when never configured" below).
3. If no direct XEP-0258 envelope exists, attach a copy of the validated default.
4. Otherwise require exactly one envelope and check its structure and payload.
   An empty primary `<label/>` explicitly requests the default; replace that
   envelope with the generated default after validating any equivalents.
5. Validate the effective label under the configured policy and test access using
   the server clearance. A malformed, unsupported, policy-invalid, or denied
   label is rejected with a generic `forbidden` error, unless the configured
   enforcement mode is "warn" (see below). Permitted existing labels
   and unrelated extensions remain unchanged.

Label selection is namespace-aware. A same-named element in another namespace
does not authorize a message. Only direct children of the outer message supply
its label; forwarded inner messages do not authorize the outer stanza and are
not recursively evaluated by this increment.

All non-error message types are subject to server enforcement, including chat
state/receipt messages and groupchat subject messages. This deliberately follows
the requested all-inbound-message scope; XEP-0258's recommendation to exempt MUC
subject-only changes can be considered with future room-specific handling.

## Enforcement mode: warn vs. enforce

`EnforcementMode` (`WARN` or `ENFORCE`) is part of `Settings`, saved and
validated together with the rest of the configuration, and selectable in the
Admin Console. It defaults to `WARN`. This only governs step 5 above (a
malformed, unsupported, policy-invalid, or denied label on a message that
already has a configuration to check against):

- `ENFORCE` rejects the message exactly as in the initial increment: a
  sanitized `forbidden` error reply, then `PacketRejectedException`.
- `WARN` logs a single-line warning (sender, recipient, stanza ID, and the
  generic validation failure reason, but never the message body, thread, or
  label payload, consistent with the existing rejection-logging rule) and lets
  the message continue unmodified, exactly as it arrived. No reply is sent and
  no default label is stamped over an existing, uncheckable envelope.

Corrupted stored configuration (a failed startup/reload load) still
unconditionally blocks ordinary messages with `service-unavailable`; there is
no saved `EnforcementMode` to consult in that state. A plugin that was simply
never configured is a different state and no longer blocks anything (see
"Fail-open when never configured" below); `WARN`/`ENFORCE` only govern the
outcome of an actual label check against a valid, active configuration.

`WARN` is the safe default so administrators can roll out a new or changed
policy and observe real traffic against it before switching to `ENFORCE`.
Stored settings saved before this switch existed have no `enforcementMode`
property; `JiveGlobalsConfigurationStore.read` treats that as `WARN`, matching
the new default and never silently upgrading an existing deployment to
rejection behavior.

## Fail-open when never configured

Previously, `current() == null` was a single state meaning "block ordinary
messages," covering both a genuinely never-configured plugin (nothing has ever
been saved: no policy, default label, or server clearance) and corrupted
persisted settings (something was saved but fails to load/validate, e.g. after
an out-of-band edit). The user asked that the never-configured case instead
"simply not enforce," noting on the configuration page that the plugin isn't
active, so that installing the plugin never disrupts existing traffic. The
corrupted case keeps failing closed, since something was actually saved and is
now broken — that remains a configuration error an administrator must fix, not
a safe default.

- **Distinguishing the two states**: `ConfigurationService` gained a
  `corrupted` boolean, set `true` only when `Store.read()` returns non-null
  data that then fails to parse/validate in `reload()`, and reset to `false`
  on every successful `reload()`/`save()`. `Store.read()` returning `null`
  (nothing ever saved) leaves `corrupted` `false`; `current()` is `null` in
  both cases, so `isCorrupted()` is the only way to tell them apart.
- **Interceptor behavior**: `SecurityLabelInterceptor` takes a new
  `Supplier<Boolean> corrupted` constructor parameter (wired to
  `ConfigurationService::isCorrupted` in `SpiffingPlugin`). When the active
  snapshot is `null`, it only rejects with `service-unavailable` when
  `corrupted.get()` is `true`; otherwise the message is returned completely
  untouched (no stamping, no check, no reply). The pre-existing two-argument
  constructor defaults `corrupted` to always `true`, preserving the original
  fail-closed behavior for any caller that does not opt into the new
  distinction.
- **Egress is unaffected**: `stripDefaultLabelForFederation` and the
  peer-clearance egress/ingress checks were already fail-open on a missing
  snapshot (see their sections below); this change only affects the inbound
  pre-processing path's own missing-snapshot branch.
- **Admin Console**: `SpiffingPlugin.isCorrupted()` exposes the same flag.
  `spiffing-settings.jsp` now shows one of three states instead of two:
  actively checking messages (`configured`), blocked due to corrupted stored
  settings (`corrupted`), or inactive because nothing has been configured yet
  (neither flag set) — the last case explicitly states that messages pass
  through unaffected.
- **README**: updated to describe the plugin as safe to install without
  service disruption before it is configured, while still calling out that
  corrupted stored settings continue to block traffic until fixed.

## Outbound default-label stripping for federation

Optional, off-by-default `Settings.stripDefaultLabelForFederation` (a boolean,
independent of `enforcementMode`) lets an administrator strip a message's
security label before it leaves for another server, when that label is "the
same" as the configured default. Confirmed with the user before implementation:

- **Trigger point**: `SecurityLabelInterceptor` already implements
  `PacketInterceptor`; it now also handles the `!incoming` (outbound) callback,
  restricted to `!processed && session instanceof OutgoingServerSession`. This
  matches `LocalSession.process`, which invokes interceptors with
  `read=false, processed=false` immediately before writing a packet to a
  session's connection; `OutgoingServerSession` (implemented by
  `LocalOutgoingServerSession`) is the public Openfire type for a server-to-server
  session to a remote domain. Local client/component sessions and any other
  outbound path are untouched, matching the issue's "sending to another server"
  wording; this is narrower than every outbound message.
- **"The same label"**: rather than re-deriving full policy equivalence (as
  `PolicyConfiguration.check` already does for `<equivalentlabel>`, which requires
  decoding both labels under the loaded policy), this feature compares only the
  rendered `<displaymarking>` text of the outbound envelope against
  `PolicyConfiguration.defaultDisplayMarking()`. The issue text explicitly allows
  this simplification ("'the same display marking' ... is acceptable if actual
  label equivalence is too difficult"). A `null`/empty default marking, more than
  one label element, or a mismatched/absent marking on the outbound label all
  leave the message unchanged; only an exact, non-empty match is stripped.
- **Fail open, never reject**: unlike inbound enforcement, a missing/invalid
  configuration snapshot, the option being disabled, or any exception while
  reading the outbound label never blocks or errors the message; it is simply
  left as sent. This is a best-effort minimization, not an enforcement control,
  and must not add a new way to break federation.
- **Configuration and persistence**: added as an eighth `Settings` record
  component; two additional convenience constructors (six- and seven-argument)
  preserve every existing call site, defaulting the new field to `false`.
  `JiveGlobalsConfigurationStore` persists/reads it as
  `plugin.spiffing.settings.stripDefaultLabelForFederation`
  (`JiveGlobals.getBooleanProperty`, default `false`, so settings saved before
  this switch existed are unaffected). `SettingsForm` and
  `spiffing-settings.jsp` expose it as a single checkbox in the existing
  "Enforcement" section; like an HTML checkbox in general, an absent form field
  means unchecked/`false`, exactly like the `enforcementMode` missing-field
  convention already used there.

## Peer clearance

Optional, unset-by-default `Settings.peerClearance` (with `Settings.peerClearanceFormat`,
reusing the existing `LabelFormat` selector mechanism) lets an administrator configure
a single, default clearance representing every federated peer, and have the plugin
check a message's effective label against it, in addition to the always-checked
server clearance. Confirmed with the user before implementation:

- **A single default, not per-peer**: this increment adds exactly one clearance for
  all peers, mirroring how the issue explicitly frames it as "for now, just a
  default." There is still no notion of a specific remote domain having its own
  clearance; see `doc/acdf-checks.md` for the full inventory of clearance/label
  objects. Distinguishing clearances per remote domain is deferred (see "Deferred
  features").
- **Optional, off when unset**: unlike the mandatory server clearance, an empty
  peer-clearance payload (the default) means the feature is inactive; every peer-
  clearance check point (`PolicyConfiguration.hasPeerClearance`,
  `checkDefaultPeerClearance`, `check(envelope, true)`, `checkPeerClearance(envelope)`)
  is then a no-op, so existing deployments are unaffected until an administrator
  configures one. When configured, it is parsed and validated against the loaded
  policy exactly like the server clearance (`PolicyConfiguration`'s constructor
  rejects a policy-invalid peer clearance at configuration time), but the default
  label is *not* pre-validated against it at configuration time (only the server
  clearance gates configuration; peer-clearance checks decide per message at ingress/
  egress) so an administrator can knowingly configure a default label above what
  every peer is cleared for and rely on stripping/warnings instead of being blocked
  from saving.
- **Ingress scope**: only messages arriving from a federated peer are checked, i.e.
  `SecurityLabelInterceptor`'s existing inbound pre-processing hook additionally
  tests `session instanceof IncomingServerSession`. A local client's message is
  never checked against the peer clearance, even when one is configured. This
  applies both to an explicit inbound label
  (`PolicyConfiguration.check(envelope, true)`, which adds the peer-clearance ACDF
  test right after the existing server-clearance test on the same decoded label)
  and to an unlabelled message about to be stamped with the default
  (`PolicyConfiguration.checkDefaultPeerClearance()`, run before the default is
  attached).
- **Egress scope**: only messages leaving through an `OutgoingServerSession` are
  checked, matching the existing outbound default-label-stripping feature's scope
  and the issue's "sending to another server" framing. `SecurityLabelInterceptor`
  adds `checkPeerClearanceForFederation`, called before
  `stripDefaultLabelForFederation` in the same `!incoming && !processed &&
  session instanceof OutgoingServerSession` branch, so a message's *original*
  label is what gets tested, even if the default-marking stripping feature would
  otherwise remove it afterward. Like ingress, only a well-formed, single label
  element is decoded (`PolicyConfiguration.checkPeerClearance(Element)`, which
  reuses the same header-parsing rules as `check` but never re-runs server-
  clearance authorization or stamps a default, since the message already passed
  ingress); a missing/unconfigured snapshot, no configured peer clearance, an
  absent label, or more than one label are all left unchecked here.
- **Failure handling**: a denied or malformed label is handled by
  `Settings.enforcementMode`, exactly like every existing label check: `ENFORCE`
  rejects with a sanitized `forbidden` error (the same `reject` helper and reply
  path used elsewhere), `WARN` logs a single-line warning (sender, recipient,
  stanza ID, and the generic reason, never the body or label payload) and lets the
  message through unchanged. There is no separate, independent on/off switch for
  peer-clearance enforcement; it follows the one enforcement-mode knob the
  administrator already controls for the server-clearance check.
- **Configuration and persistence**: added as a ninth and tenth `Settings` record
  component (`peerClearance`, `peerClearanceFormat`); a new eight-argument
  convenience constructor preserves every existing call site, defaulting
  `peerClearance` to `""` (unset) and `peerClearanceFormat` to `ESS` (unused while
  unset). `JiveGlobalsConfigurationStore` persists/reads them as
  `plugin.spiffing.settings.peerClearance`/`peerClearanceFormat`; a missing
  `peerClearance` property reads as `""` and a missing `peerClearanceFormat`
  defaults to `ESS`, so settings saved before this feature existed are unaffected.
  `SettingsForm` and `spiffing-settings.jsp` expose both as a new "Peer clearance"
  section (a format selector and an optional textarea), with the same
  missing-field-means-unset convention.

## Multiple policies

Administrators may now load more than one Open XML SPIF policy document into
the same private Spiffing `Site` registry (`Settings.policies()`, an ordered,
validated, non-empty list, replacing the previous single `Settings.policy`
field). Confirmed with the user before implementation, choosing among several
possible interpretations of "allow multiple policies to be loaded":

- **One registry, not independent configurations**: all loaded policies share
  the plugin's single `PolicyConfiguration`, `Site`, server clearance, peer
  clearance, default label, and enforcement mode; this is not a mechanism for
  running several unrelated per-domain or per-purpose configurations side by
  side (that remains a possible future direction, not this increment).
- **A single primary policy for the clearance and default label**: the first
  entry in `Settings.policies()` is the *primary* policy. The mandatory server
  clearance, the optional peer clearance, and the default label are all still
  validated against it alone, exactly as when only one policy could be
  loaded; `PolicyConfiguration`'s constructor now explicitly rejects a
  clearance or peer clearance encoded under a different, non-primary loaded
  policy, rather than silently doing nothing useful with it. This was chosen
  over asking the administrator to configure one clearance per loaded policy,
  since Spiffing's `Spif.acdf` already requires an exact policy match between
  a label and the clearance checking it, so a single clearance can only ever
  decide access for one policy's labels at a time; secondary policies exist to
  widen which *labels* are recognized, not to add independent access-control
  decisions.
- **Cross-policy trust via each policy's own declared equivalence, not a
  plugin-invented comparison**: a message's primary label, an
  `<equivalentlabel/>`, or a label catalogue entry may be encoded under any
  loaded policy, not just the primary one. Before such a label is authorized
  (or compared to another decoded label), `PolicyConfiguration.toPrimary`
  translates it to the primary policy using `Label.encrypt`/`Spif.translate`,
  which follows the SPIF's own `equivalentPolicies`/`equivalentClassification`/
  `equivalentSecCategoryTag` declarations — a trusted, policy-authored mapping,
  not a claim made by the message itself. A policy without such a declared
  mapping for its classification or any of its label's categories fails
  translation, and the label is rejected as unsupported, exactly like any
  other malformed or unrecognized label. This directly resolves the previous
  "Label envelope and policy semantics" section's stated limitation ("With
  only one configured policy... Future cross-policy support needs trusted
  equivalence mappings and explicit policy registry management") using
  Spiffing's own existing, standards-based translation mechanism, rather than
  inventing a new one; a label already under the primary policy is returned
  unchanged (`toPrimary` is then a no-op), so single-policy behavior is
  unaffected.
- **Admin Console**: `spiffing-settings.jsp` replaces the single policy
  textarea with a repeatable list (one `<textarea name="policy">` per loaded
  policy, submitted as repeated same-named fields). "Add another policy" and
  per-row "Remove this policy" buttons redisplay the form with one more/fewer
  row without saving (at least one policy is always required, so the last row
  cannot be removed); only the actual "Save settings" button invokes
  `SettingsForm.save`. The first row is labelled as the primary policy.
- **Configuration and persistence**: `SettingsForm` gained a second `save`
  overload taking an explicit `List<String> policies` parameter (used by the
  updated JSP); the original `Map`-only overload is preserved unchanged for
  every existing caller, internally wrapping the map's single `"policy"` field
  into a one-element list. `Settings` keeps its familiar single-policy
  constructors and a `policy()` convenience accessor (the primary policy)
  alongside the new list-based ones, so no existing call site needed to
  change. `JiveGlobalsConfigurationStore` originally persisted the list using
  `JiveGlobals`' own list-property support (`setProperty(String, List)`/
  `getProperties(String)`); this was replaced by a manual `policy.count` plus
  indexed `policy.<n>` scheme after that overload was found to throw
  `NoSuchMethodError` on a reported deployment (see "Build and dependency
  compatibility" below for details). Either way, a shrinking list never
  leaves stale entries behind; settings saved before this feature existed
  have a single scalar `policy` property with no `policy.count`, which
  `read()` falls back to, and the next save migrates to the indexed form.

## Label envelope and policy semantics

Supported payloads inside `<label>` or `<equivalentlabel>`:

| Encoding | Payload element | Namespace |
| --- | --- | --- |
| ESS | `esssecuritylabel` containing base64 BER/DER | `urn:xmpp:sec-label:ess:0` |
| NATO XML | `originatorConfidentialityLabel` | `urn:nato:stanag:4774:confidentialitymetadatalabel:1:0` |
| Spiffy XML | `label` | `http://surevine.com/xmlns/spiffy` |

The envelope contains an optional display marking followed by exactly one primary
label and zero or more equivalent labels. This follows the specification's prose;
its embedded schema inconsistently makes the display marking mandatory. Display
markings are presentation, never authorization inputs. Existing authorized
markings are preserved; generated defaults use Spiffing's marking.

Equivalent labels must independently parse, validate, pass access control, and
represent the same classification/categories as the primary label once both are
resolved to the primary policy (see "Multiple policies" above for how a label
under a secondary loaded policy is translated there first). A primary label or
equivalent naming a policy that is not loaded at all is rejected, as is one
naming a loaded policy that lacks a declared translation to the primary policy.
This remains a deliberately restricted profile: the broader XEP permits
selecting an appropriate equivalent label and default fallback when no applicable
label exists. We do not trust unverifiable equivalence claims made by the message
itself or silently replace an explicit unsupported security label with a
potentially less restrictive one; cross-policy trust comes only from a policy's
own declared equivalence mappings, never from the label.

Spiffing requires explicit classification membership. Hierarchy does not grant
access to lower classifications. Restrictive categories require all applicable
privileges; permissive tags require a matching privilege per represented tag;
informative categories do not affect access. Validation enforces the loaded
policy's required and excluded combinations. The plugin delegates these semantics
to Spiffing instead of recreating an access algorithm.

## Rejection and Openfire integration evidence

The adjacent Openfire source establishes the intended hook:

- `net/StanzaHandler.processMessage`, `SessionPacketRouter`, and
  `spi/PacketRouterImpl` route inbound messages to `MessageRouter`.
- `MessageRouter.route` invokes inbound interceptors before route delivery,
  offline handling, multicast delivery, and carbon generation. Its client-session
  lookup can yield null for remote senders; enforcement never requires a session.
- `MessageRouter` only generates its own interception-rejection reply when a
  client session exists. The plugin instead sends a new error via
  `RoutingTable.routePacket` for either local or remote senders, then throws an
  empty `PacketRejectedException` to block the original without a duplicate reply.
- Errors retain the stanza ID, reverse addresses, and include an error condition,
  but no original body, thread, extensions, or label. There is no reply when the
  sender is absent. Reply delivery failure still rejects the original.
- Internal components that use `PacketRouter` also reach this hook. Direct
  `RoutingTable`/session delivery paths, including some server-generated messages,
  history replay, and room fan-out, do not necessarily re-enter it. The plugin does
  not claim per-recipient delivery filtering on those paths.

Protocol behavior was checked against XEP-0258, both the local
`../xeps/xep-0258.xml` and the retrieved published specification. The error-message
exceptions are required by its business rules.

## Input bounds and XML handling

Policy input is limited to 1,048,576 Java characters; clearance/default payloads
to 65,536 characters each; these limits are enforced by `Settings`'s compact
constructor regardless of how a candidate settings value is constructed.
Incoming label trees are checked iteratively before recursive serialization:
maximum depth 32, 1,024 elements, and a 65,536-character text/name/attribute budget.
Openfire's stanza-size limits remain an additional boundary. These limits bound
plugin work on label data; they are not whole-stanza or whole-server quotas.

Spiffing disables DTDs and external XML resources in policy/label parsing.
`SecureXml` separately disables DTDs, external entities, and external DTD loading
for settings and generated XML. Base64 decoding allows only XML whitespace in
addition to base64 characters, rather than accepting arbitrary MIME garbage.
Message bodies and full policy/clearance documents are not logged on rejection.

## Build and dependency compatibility

Target Openfire 5.0.0 APIs and Java 17 bytecode, matching Spiffing's minimum runtime.
Spiffing dropped its baseline from Java 22 to Java 17; this plugin's build property,
plugin descriptor, CI matrix, and documentation were updated to match. The plugin
source and tests previously used a few Java 21+ conveniences
(`List.getFirst()`/`getLast()`, and one `ExecutorService` try-with-resources,
which requires Java 19's `AutoCloseable` support); these were rewritten with
`get(0)`/index-based access and an explicit `shutdown()` so the actual
Java 17 build (not just a syntax scan) compiles and passes. The plugin
descriptor declares both minimum versions. The plugin depends on
`io.cridland:spiffing:1.0-SNAPSHOT`; CI checks out and installs Spiffing commit
`60c474434fc57f9a1ecab7e9549773f3a6656614`. Tests copy MIT-licensed Food policy
fixtures so the test runtime does not depend on the sibling checkout.

`JiveGlobals.setProperty(String, List<String>)`/`getProperties(String)` (used
by an earlier version of `JiveGlobalsConfigurationStore` to persist the policy
list, see "Multiple policies" above) is present in this project's own
`xmppserver` build artifact, but a real deployment reported
`NoSuchMethodError: JiveGlobals.setProperty(String, List)` when saving
settings, since `JiveGlobals` is declared `provided` and is actually supplied
by whatever `xmppserver` jar the running server ships, not the one this plugin
compiles against. Because that error is a `java.lang.Error`, not a
`RuntimeException`, it was not caught by the settings page's existing
`catch (RuntimeException e)` handling and escaped uncaught, which Jetty/Jasper
rendered as a mostly blank page instead of the plugin's own error message.
Fixed by having `JiveGlobalsConfigurationStore` persist the policy list using
only the oldest, universally-available scalar `JiveGlobals` methods
(`getProperty`/`setProperty(String, String)`/`deleteProperty`/`getIntProperty`)
instead of the list-property overload, removing the dependency on an API whose
availability cannot be guaranteed across every Openfire build this plugin
targets. `JiveGlobalsConfigurationStore` remains untested directly (see "Test
coverage" below), so this was verified by inspecting the compiled
`xmppserver-5.0.0.jar` method signatures and by `mvn verify`, not by
reproducing the runtime error against an actual mismatched server build.

Openfire's parent classloader supplies Bouncy Castle. Declare `bcprov-jdk18on`
provided and test against Openfire 5.0.0's version 1.78.1. A second compatibility
run uses version 1.84 from the adjacent Openfire checkout. The archive bundles
Spiffing and the plugin, without a competing Bouncy Castle JAR. The snapshot
library dependency must be released/pinned to a published version before a
release build can satisfy the parent POM's release-dependency rule.

## Tests and remaining verification

The automated suite exercises real Spiffing policy decisions and real XMPP/dom4j
message objects. Coverage includes:

- All input/output formats, serialization equivalence, immutable default copies,
  accepted and denied policy fixtures, policy-invalid but ACDF-permitted labels,
  classification membership, restrictive privileges, and informative categories.
- Envelope structure, unknown policies/formats, duplicate labels, empty defaults,
  equivalence checks, namespace confusion, base64 handling, excessive depth/size,
  and external entities in every administrator document.
- Every ordinary message type, local-session and null-session handling, default
  stamping and repeat callbacks, preservation of existing labels/extensions,
  sanitized rejection addressing, corrupted configuration blocking, a
  never-configured plugin letting labelled and unlabelled messages through
  untouched, errors without loops, outbound/non-message exclusions, and failed
  reply delivery.
- Configuration round trips, complete-save/restart restoration, invalid saves,
  storage failures, startup recovery, concurrent snapshot reads,
  CSRF/method/missing-field rejection, and plugin registration/removal/discovery
  lifecycle. `JiveGlobalsConfigurationStore` itself is not unit-tested directly,
  since `JiveGlobals` requires a running Openfire server context; `ConfigurationService`
  is tested against an in-memory fake of the `Store` interface instead, the same
  pattern already used for `CatalogService`/`DatabaseCatalogStore`.
- Warn-vs-enforce behavior: a denied/malformed label is logged and passed through
  unchanged in warn mode versus rejected in enforce mode, the Admin Console form
  field, and defaulting to warn both for a brand-new configuration and for
  settings saved before this switch existed (no `enforcementMode` property).
- Outbound default-label stripping: a matching-marking label is removed only for
  an `OutgoingServerSession` before send, is left untouched when the option is
  disabled, when the marking differs or is absent, for local-session delivery,
  after send (`processed`), with more than one label, or without a published
  configuration; plus the `defaultDisplayMarking()` accessor and the
  `SettingsForm`/JSP checkbox's missing-field-means-off convention.
- Peer clearance: `PolicyConfiguration` rejects an invalid peer clearance at
  configuration time, `hasPeerClearance()`/`checkDefaultPeerClearance()`/
  `check(envelope, true)`/`checkPeerClearance(envelope)` are no-ops without one
  configured, and each correctly permits a peer-clearance-allowed label while
  rejecting one the peer clearance denies even though the server clearance
  permits it. `SecurityLabelInterceptor` coverage includes: a federated inbound
  labelled or unlabelled (default-stamped) message permitted or denied by the
  peer clearance in both enforce (rejected) and warn (logged, unchanged) modes; a
  local inbound message is never checked against the peer clearance even when one
  is configured; an outbound message to another server is checked before the
  default-label-stripping feature runs, in both enforce and warn modes; and the
  egress check is a no-op without a configured peer clearance, for local
  delivery, and after send (`processed`). Plus `SettingsForm`/JSP coverage of the
  missing-fields-mean-unset convention for the new peer-clearance fields.
- Multiple policies: a two-policy fixture (`drink-policy.xml`, declaring an
  `equivalentPolicies` mapping back to the existing `food-policy.xml`) exercises
  loading several policies into one registry, a primary-policy label passing
  through unchanged, a secondary-policy primary label and equivalent label each
  translated and correctly permitted, a secondary-policy label lacking a
  declared equivalence correctly rejected, a genuinely mismatched translated
  equivalent correctly rejected, catalogue-label translation, a clearance
  encoded under a non-primary loaded policy correctly rejected at configuration
  time, and duplicate policies rejected. `SettingsTest`/`SettingsFormTest` cover
  the new policy-list validation (empty, too many, blank/oversized entries,
  defensive copying) and the new multi-policy `SettingsForm.save` overload
  (ordering, CSRF enforcement, empty-list rejection) alongside every
  pre-existing single-policy constructor/accessor/form call site, unchanged.

Local verification: **79 tests passed**, with no failures or skips, against both
Bouncy Castle 1.78.1 and 1.84 on Java 25. A clean build and JSP compilation passed.
After adding the enforcement-mode switch, `mvn verify` was re-run in this session
against the project's configured Bouncy Castle 1.78.1: **84 tests passed**, with
no failures or skips, the Admin Console JSP compiled, and the plugin assembly jar
was built. The 1.84 compatibility variant was not re-run in this session.

`mvn verify` compiles the Admin Console JSP, runs the suite, and builds the plugin
archive. Archive inspection checks generated servlet mappings and bundled JARs.
Lifecycle tests inject an Openfire adapter rather than booting a server. No live
Openfire installation, browser session, database, or federated XMPP pair was
exercised in this workspace.

Once the sibling `../spiffing-java` checkout was updated to build cleanly on
Java 17, a full local `mvn verify` running javac under `--release 17` (Temurin
17.0.20) was executed end to end: all 79 tests passed, the Admin Console JSP
compiled, and the plugin assembly jar was built. This superseded an earlier,
incomplete verification that only syntax-scanned the plugin's own sources with
`javac --release 17` while skipping test compilation; that scan had missed the
Java 19+/21+ APIs described above, which only surfaced once the dependency
actually built and the full module (including tests) was compiled.

Before production use, perform live local/federated routing and Admin Console
smoke tests, including plugin reload, startup failure recovery, and actual server
classloader behavior. Browser rendering/escaping is reviewed in the JSP but is
not exercised by a browser automation test. CI workflow execution on Java 17 is
configured for CI, not separately exercised there in this session.

After adding outbound default-label stripping, `mvn verify` was re-run in this
session against the project's configured Bouncy Castle 1.78.1: **108 tests
passed**, with no failures or skips, the Admin Console JSP compiled, and the
plugin assembly jar was built. Live server-to-server delivery through an actual
`LocalOutgoingServerSession` was not exercised; the outbound behavior is covered
by direct `SecurityLabelInterceptor.interceptPacket` calls with an
`OutgoingServerSession` test double, matching this suite's existing approach for
the inbound path. The 1.84 compatibility variant was not re-run in this session.

After adding the default peer clearance, `mvn verify` was re-run in this session
against the project's configured Bouncy Castle 1.78.1: **133 tests passed**, with
no failures or skips, the Admin Console JSP compiled, and the plugin assembly jar
was built. As with the existing federated-session tests, both ingress and egress
peer-clearance coverage uses `IncomingServerSession`/`OutgoingServerSession` test
doubles rather than a live federated pair. The 1.84 compatibility variant was not
re-run in this session.

After adding fail-open-when-never-configured behavior, `mvn verify` was re-run
in this session against the project's configured Bouncy Castle 1.78.1: **133
tests passed**, with no failures or skips (the net test count was unchanged,
since new coverage for the never-configured/corrupted distinction replaced
what had been a single, now-split, missing-configuration scenario), the Admin
Console JSP compiled, and the plugin assembly jar was built. The 1.84
compatibility variant was not re-run in this session.

After adding multiple-policy support, `mvn verify` was re-run in this session
against the project's configured Bouncy Castle 1.78.1: **154 tests passed**,
with no failures or skips, the updated Admin Console JSP (the repeatable policy
list) compiled, and the plugin assembly jar was built. Live add/remove-row
interaction with the new Admin Console list was not exercised by a browser
automation test, only the underlying `SettingsForm.save`/`Settings` validation
and the JSP's own compilation. The 1.84 compatibility variant was not re-run in
this session.

After making catalogue retrieval run the peer-clearance ACDF check for a
requested `to=` recipient that is not local, `mvn verify` was re-run in this
session against the project's configured Bouncy Castle 1.78.1: **159 tests
passed**, with no failures or skips, the Admin Console JSP compiled, and the
plugin assembly jar was built. Coverage uses direct `CatalogIqHandler`/
`CatalogService`/`PolicyConfiguration` calls with fixture `isLocal` predicates
distinguishing a local from a federated `to=` domain, not a live federated
catalogue request. The 1.84 compatibility variant was not re-run in this
session.

After adding the policy-discovery IQ handler, `mvn verify` was re-run in this
session against the project's configured Bouncy Castle 1.78.1: **170 tests
passed**, with no failures or skips, the Admin Console JSP compiled, and the
plugin assembly jar was built. Coverage is via direct `PolicyIqHandler`/
`PolicyConfiguration` calls (listing, id/name lookup, id-over-name priority,
unknown-id/name, non-`get`, non-local, missing-configuration) with a fixture
`isLocal` predicate, not a live IQ round-trip through a running server. The
1.84 compatibility variant was not re-run in this session.

## Label catalogue (XEP-0258 `urn:xmpp:sec-label:catalog:2`)

The catalogue is a separate, administrator-curated list of named security labels
offered to clients for selection, independent from the single message-stamping
default label in `Settings`/`PolicyConfiguration`. Design decisions, confirmed
with the user before implementation:

- **Storage**: catalogue entries are persisted in the Openfire database, in a
  new `ofSpiffingCatalog` table (schema in `src/main/database/spiffing_*.sql`,
  registered via `plugin.xml`'s `<databaseKey>`/`<databaseVersion>`), distinct
  from the `JiveGlobals` properties used for policy/clearance/default-label
  settings. This keeps the catalogue's own lifecycle (frequent, independent
  add/remove of a list of records) on direct JDBC via `DbConnectionManager`,
  matching how Openfire plugins normally store lists of records, separately
  from the settings' small, mostly-static set of scalar/document fields.
  `DatabaseCatalogStore` implements the `CatalogStore` interface using
  `DbConnectionManager`, mirroring the JDBC patterns used elsewhere in Openfire.
- **Access control**: only requests from local entities are served (checked via
  `XMPPServer.isLocal(from)` through an injected predicate); federated/remote
  catalogue requests receive `not-authorized`. This is stricter than the base
  XEP-0258 recommendation ("any entity") but matches the user's explicit choice
  for this deployment; it can be relaxed later if federated catalogue sharing is
  required.
- **The requested `to=` attribute now drives an extra ACDF check per entry**,
  rather than being ignored: `CatalogIqHandler` reads the optional `to=`
  attribute of the `<catalog/>` request element and, when it names a recipient
  that `isLocal` reports as *not* local to this server, asks
  `CatalogService.buildCatalog(true)` to also test every entry against the
  configured peer clearance (`PolicyConfiguration.encodeCatalogLabel`'s new
  `checkPeerClearance` parameter, calling the same `authorizePeer` used for
  federated message egress) before publishing it — matching the real check a
  message to that recipient would have to pass on egress. A local (or absent)
  `to=` only runs the always-performed server-clearance check, as before. A
  `to=` that is not a well-formed JID is rejected with `bad-request`. This
  still publishes a single, server-wide catalogue (no per-recipient catalogue
  content is added beyond this ACDF filtering) and does not change the
  locality check above.
- **Entry content**: each entry has a name, an optional XEP-0258 `selector`
  (validated as a `|`-separated non-empty path), a `LabelFormat` (ESS, NATO XML,
  or Spiffy XML, reusing the existing enum), and its own label payload. The
  payload is independently validated against the currently active policy and
  clearance via `PolicyConfiguration.encodeCatalogLabel`, exactly like the
  default label, rather than only referencing already-known labels.
- **Default flag**: exactly one entry may be flagged as the catalogue's
  `<item default="true"/>` (`CatalogEntry.isDefault`). Adding a new default
  entry clears the flag on any previous default (`CatalogStore.clearDefault`)
  before inserting; clearing and inserting are two separate statements, not one
  transaction, so a crash between them can leave zero default entries (see
  `DatabaseCatalogStore`'s Javadoc). This flag is unrelated to the
  message-stamping default label configured on the settings page.

Components: `CatalogEntry` (validated record), `CatalogStore`/
`DatabaseCatalogStore` (persistence), `CatalogService` (validation against the
active `PolicyConfiguration`, default-entry exclusivity, and `<catalog/>`
element construction), `CatalogIqHandler` (answers `get` IQs in the
`urn:xmpp:sec-label:catalog:2` namespace, registered with Openfire's
`IQRouter`), and `CatalogForm` (CSRF/field validation for the Admin Console,
mirroring `SettingsForm`). `SpiffingPlugin` registers the IQ handler and
advertises the `urn:xmpp:sec-label:catalog:2` disco feature alongside the
existing `urn:xmpp:sec-label:0` feature, and unregisters both on destroy.

A policy/clearance change can leave a previously valid catalogue entry unable
to authorize. `CatalogService.buildCatalog` fails safe per entry: an entry that
no longer validates is omitted from the published catalogue (and logged), while
the rest of the catalogue and the plugin's message enforcement are unaffected.
If no configuration is active at all, the whole catalogue request is rejected
with `service-unavailable`, consistent with the message-enforcement fail-closed
behavior.

The Admin Console page `spiffing-catalog.jsp` lists current entries (name,
selector, format, default flag) with a per-row remove action, and a form to add
a new entry. It follows the same CSRF-cookie pattern, generic error reporting,
and escaped redisplay as `spiffing-settings.jsp`.

## Policy discovery (`urn:xmpp:sec-label:policy:0`)

Administrators (and, by extension, any local client) may need to inspect which
SPIF policy documents are currently loaded, e.g. to render classification/
category names offline or to detect a configuration change. `PolicyIqHandler`
answers this over a namespace extending XEP-0258's own `urn:xmpp:sec-label:0`:

- **Namespace and element**: a single `<policy xmlns='urn:xmpp:sec-label:policy:0'/>`
  child element is used for both requests, following the existing `<catalog/>`
  handler's pattern of one element name per handler/namespace pair (Openfire's
  `IQRouter` dispatches by that pair, so a second, differently-named element
  would need a second handler registration for no real benefit here).
- **Listing**: a request with neither an `id` nor a `name` attribute returns
  every loaded policy's id and name as `<item id='...' name='.../>` children,
  in load order (the primary policy — used for the server/peer clearance and
  default label, see "Multiple policies" above — is always listed first).
- **Fetching one policy**: a request with an `id` or `name` attribute returns
  that policy's original Open XML SPIF document, re-parsed via the same bounded
  `SecureXml.parse` used elsewhere for administrator-supplied XML, embedded
  inside a `<policy id='...' name='...'>` response element carrying both
  identifying attributes regardless of which one was requested by. If both
  attributes are present, `id` takes priority and `name` is ignored, since a
  request naming a specific policy is expected to use exactly one selector. A
  reference to an id/name that is not currently loaded is rejected with
  `item-not-found`, distinct from `bad-request` (malformed IQ) and
  `service-unavailable` (no active configuration at all).
- **Access control**: only requests from local entities are served, matching
  the existing catalogue handler's `isLocal` check; a loaded policy's full
  document is administrator-curated configuration, not something published to
  federated peers.
- **Storage**: `PolicyConfiguration` now retains each loaded policy's id, name,
  and original document text as a `LoadedPolicy` record
  (`loadedPolicies()`/`loadedPolicyById`/`loadedPolicyByName`), captured
  alongside the existing `Site.load` loop at construction time, rather than
  re-deriving them from `Spif` (which does not retain the original document
  text after parsing) on each request.

`SpiffingPlugin` registers `PolicyIqHandler` and advertises the
`urn:xmpp:sec-label:policy:0` disco feature alongside the existing
`urn:xmpp:sec-label:0` and `urn:xmpp:sec-label:catalog:2` features, and
unregisters both on destroy. The plugin's `Runtime` interface's
`addIqHandler`/`removeIqHandler` methods were generalized from taking a
`CatalogIqHandler` specifically to the common `IQHandler` supertype, so both
handlers share the same registration methods.

## Deferred features

Per-user and per-room clearances; a distinct clearance per specific federated
peer/remote domain (today's peer clearance is a single default applied to every
peer); a distinct server clearance or default label per loaded policy (today's
clearance and default label are both scoped to a single primary policy, even
when multiple policies are loaded); MUC history and recipient filtering;
catalogue discovery for remote/federated entities (a request's `from=` must
still be local; only the requested `to=` recipient's locality now affects which
checks apply); policy discovery for remote/federated entities (like the
catalogue, only local `from=` is served); recursive forwarded-message
handling; cluster configuration distribution; and live configuration reload
from external file edits. Do not describe this increment as complete XEP-0258
support.
