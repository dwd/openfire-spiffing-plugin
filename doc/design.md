# XEP-0258 plugin design

Status: first server-enforcement increment implemented, September 9, 2026.
An enforcement-mode switch (warn/enforce) was added September 15, 2026.
Label catalogue discovery (add/list/remove in the Admin Console) was added
September 15, 2026. Settings storage was switched from an atomic file to
Openfire properties on September 22, 2026, per explicit user direction.

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
- Block ordinary inbound messages while unconfigured or after invalid startup
  configuration. A working configuration remains active when an Admin Console
  save is rejected.

The server clearance covers local-client and federated traffic. No per-user or
per-room clearance is inferred from the server clearance.

## Components and configuration lifecycle

`Settings` holds the policy, clearance, default-label payload, two input formats,
and one output format. It serializes these as a versioned XML document, with the
input documents escaped as text. The Admin Console accepts payloads, not full
XEP-0258 envelopes. ESS inputs are base64 ASN.1; XML inputs are literal XML.

`PolicyConfiguration` creates a private Spiffing `Site`, loads exactly one policy,
and parses the clearance and default label against that same registry. It calls
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
questions/answers in the issue history). On startup, missing or incomplete
properties leave enforcement unconfigured and block ordinary messages, exactly
as the file-based store did; administrators recover by saving valid settings
again. Configuration distribution and simultaneous administration across
cluster nodes are not supported; configure each node separately.

The Admin Console relies on Openfire's administrator authentication. Writes
require POST and matching CSRF cookie/token values. Submitted documents are
escaped on redisplay, and failed validation preserves the submitted form. The
page reports generic input errors without exposing parser details or document
contents in server logs. `SettingsForm` makes request validation testable without
starting the Admin Console.

## Message processing

`SpiffingPlugin` registers a global `SecurityLabelInterceptor` and advertises
`urn:xmpp:sec-label:0`. Plugin destruction removes both. The interceptor handles
only inbound `Message` callbacks before processing (`incoming && !processed`).
Outbound, postprocessing, IQ, and presence callbacks are outside this increment.

1. For error messages, bypass authorization and stamping. Discard an error with
   a direct XEP-0258 security label without generating a reply.
2. Read the active snapshot. If absent, reject with `service-unavailable`.
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

Missing configuration (no snapshot published yet, or a failed startup load)
still unconditionally blocks ordinary messages with `service-unavailable`.
There is no saved `EnforcementMode` to consult in that state, and defaulting
to open failure would contradict the fail-closed startup behavior documented
above; `WARN` only relaxes the outcome of an actual label check against a
valid, active configuration.

`WARN` is the safe default so administrators can roll out a new or changed
policy and observe real traffic against it before switching to `ENFORCE`.
Stored settings saved before this switch existed have no `enforcementMode`
property; `JiveGlobalsConfigurationStore.read` treats that as `WARN`, matching
the new default and never silently upgrading an existing deployment to
rejection behavior.

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

With only one configured policy, equivalent labels must independently parse,
validate, pass access control, and represent the same classification/categories
under that policy. Foreign-policy equivalents and unknown primary policies are
rejected. This is a deliberately restricted profile: the broader XEP permits
selecting an appropriate equivalent label and default fallback when no applicable
label exists. We do not trust unverifiable equivalence claims or silently replace
an explicit unsupported security label with a potentially less restrictive one.
Future cross-policy support needs trusted equivalence mappings and explicit policy
registry management.

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
  sanitized rejection addressing, missing configuration, errors without loops,
  outbound/non-message exclusions, and failed reply delivery.
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

## Deferred features

Per-user and per-room clearances; MUC history and recipient filtering; catalogue
discovery for remote/federated entities; cross-policy translation; recursive
forwarded-message handling; cluster configuration distribution; and live
configuration reload from external file edits. Do not describe this increment
as complete XEP-0258 support.
