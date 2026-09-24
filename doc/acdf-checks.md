# ACDF objects and message-lifetime checks

This document answers two specific questions about the current implementation,
cross-referenced against `doc/design.md`: which objects carry a label or a
clearance, and at which points in a message's lifetime the plugin actually
performs an access-control check. It does not introduce any new behavior; it
only describes what `PolicyConfiguration` and `SecurityLabelInterceptor`
already do, as of the "Fail-open when never configured" increment.

## Objects that have a clearance

There are up to **two** clearance objects in this plugin, and both belong to
**this server's configuration**, not to any specific peer:

- `Settings.clearance` (with `Settings.clearanceFormat`) is the single,
  mandatory, administrator-configured payload representing this server's own
  clearance, parsed once per configuration into a Spiffing `Clearance` and held
  by `PolicyConfiguration`. This clearance is used, unchanged, as the
  access-control decision input (`Spif.acdf(label, clearance)`) for **every**
  label the plugin evaluates: inbound messages regardless of origin (local
  client or federated peer), the administrator-configured default label, and
  every label catalogue entry (`PolicyConfiguration.encodeCatalogLabel`). Since
  multiple policies can be loaded (`doc/design.md`'s "Multiple policies"
  section), the clearance always belongs to exactly one of them, the *primary*
  policy (`Settings.policies().get(0)`); a label decoded under a different,
  secondary loaded policy is translated to the primary policy
  (`PolicyConfiguration.toPrimary`) before this same `acdf` call, so there is
  still only ever one clearance object and one `acdf` decision per label.
- `Settings.peerClearance` (with `Settings.peerClearanceFormat`) is an
  **optional**, administrator-configured payload representing a single default
  clearance for every federated peer (see `doc/design.md`'s "Peer clearance"
  section). When left empty (the default), `PolicyConfiguration.peerClearance`
  is `null` and every peer-clearance check point
  (`hasPeerClearance`/`checkDefaultPeerClearance`/`check(envelope, true)`/
  `checkPeerClearance(envelope)`) is a no-op; when configured, it is parsed and
  validated against the loaded policy exactly like the server clearance, and
  used as a *second*, independent `Spif.acdf(label, peerClearance)` decision
  input for federated ingress/egress only (see below). Unlike the server
  clearance, an invalid peer-clearance payload only blocks configuration when
  non-empty; the default label is not itself pre-validated against it.
- As already stated in `doc/design.md` ("The server clearance covers
  local-client and federated traffic. No per-user or per-room clearance is
  inferred from the server clearance."), there is still **no per-peer,
  per-user, or per-room clearance** distinct from these two configured
  objects. In particular, a remote/federated server is *not* modeled as an
  object with its *own*, individually configured clearance; the plugin does
  not read or derive a clearance from `session.getAddress()`, the remote
  domain, or any other property of an `IncomingServerSession`/
  `OutgoingServerSession`/`Session`. The (optional) peer clearance is one
  single, default value applied uniformly to every peer, not a per-peer
  lookup.
- Nothing else — no data class for a room, a contact, a component, or a
  specific domain — currently exists or is consulted as a clearance holder.
  Answering the issue's framing directly: today **the server itself** always
  has a clearance, and, when an administrator configures one, **every peer**
  collectively shares a second, single default clearance; **no individual
  peer server** has one of its own, and local clients are never checked
  against the peer clearance.

## Objects that have a label

Several distinct things carry a XEP-0258 label (a `<securitylabel/>` envelope
or the underlying Spiffing `Label`), each with its own lifecycle:

- **The configured default label**: `Settings.defaultLabel` (with
  `Settings.labelFormat`), parsed once into `PolicyConfiguration.defaultLabel`
  and validated (`assertValid` + `acdf`) at configuration time. It is
  re-encoded once into `PolicyConfiguration.defaultEnvelope`, which is copied
  (`defaultEnvelope()`) and attached to any inbound message that arrives
  without a label, or whose primary `<label/>` is empty.
- **Each inbound message**: `Message.getElement()` may already carry a
  `<securitylabel/>` envelope, supplied by whichever entity sent it (a local
  client or, after server-to-server delivery, a remote domain). This is the
  only label the plugin decodes from untrusted input, and it is the one
  bounded and validated by `PolicyConfiguration.check`/`boundTree` before
  being trusted.
- **Each outbound message to another server**: after inbound stamping, the
  same envelope may still be present when the stanza is later handed to an
  `OutgoingServerSession`. `SecurityLabelInterceptor`'s outbound path reads
  this label twice: once to run a real peer-clearance ACDF decision
  (`PolicyConfiguration.checkPeerClearance`, a no-op without a configured peer
  clearance), and once more, afterward, to compare its `<displaymarking>` text
  against the default's for stripping; see "Egress to a federated peer now
  performs a real, but narrower, ACDF check" below.
- **Each label catalogue entry**: `CatalogEntry.payload` (with its own
  `LabelFormat`), stored independently of the message-stamping default and
  validated against the active policy/clearance only when added
  (`CatalogService.add`, server clearance only) and when the catalogue is
  (re)built for a `get` request (`CatalogService.buildCatalog`, server
  clearance always, plus the peer clearance too when the request's `to=`
  names a non-local recipient); an entry that no longer validates against
  whichever checks apply to that request is omitted from the published
  `<catalog/>`, not rejected outright.
- **Equivalent labels**: `<equivalentlabel/>` children of an inbound envelope
  are also individually decoded, validated, and access-checked
  (`PolicyConfiguration.check`), though they never replace the primary label.
  An equivalent label under the primary policy is compared to the primary
  label directly; one under a secondary loaded policy is first translated to
  the primary policy (`toPrimary`, using that policy's own declared
  `equivalentPolicy`/`equivalentClassification`/`equivalentSecCategoryTag`
  mappings) and only then compared. Either way, the label is only accepted if
  it ends up with the same classification and category set as the primary
  label under the primary policy; an unverified cross-policy equivalence claim
  made by the message itself is never trusted.

There is no notion of a label belonging to a server, a peer, a room, or a
user as a standing property — a label is always attached to one specific
message (or catalogue entry) and evaluated per message. Contrast this with
the clearances above, which are fixed, standing properties of the server's
configuration (one mandatory, one optional).

## Checks made over a message's entire lifetime

Real ACDF checks (`Spif.assertValid` and/or `Spif.acdf`) happen at two points:
always on the **inbound** path before the message is handed off for delivery,
and, since the "Peer clearance" increment, also on the **egress** path to a
federated peer, but only for the narrower peer-clearance decision (see below).
Nothing else in a message's lifetime is re-checked. The `SecurityLabelInterceptor`
callback (`incoming && !processed`, i.e. before Openfire's `MessageRouter`
processes the packet) does the following, for every non-error message:

1. **Error messages bypass all checks.** An error carrying a direct
   XEP-0258 label is discarded (`PacketRejectedException`) without ever being
   authorized; XEP-0258 §6 does not require label checks on errors.
2. **A missing configuration snapshot is either fail-open or fail-closed,
   depending on why it is missing.** If persisted settings exist but failed to
   load/validate (`ConfigurationService.isCorrupted()` is `true`), every
   ordinary message is still rejected with `service-unavailable`, regardless
   of whether it carries a label — this is the only remaining unconditional
   block in the message's lifetime. If the plugin was simply never configured
   at all (no policy, default label, or server clearance ever saved;
   `isCorrupted()` is `false`), the message instead passes through completely
   unchecked and unmodified: no stamping, no access-control decision, no
   reply. This distinction was added so that installing the plugin can never
   disrupt existing traffic before an administrator configures it.
3. **Unlabelled messages are stamped, and, from a federated peer, also
   checked against the peer clearance first.** A message with no direct
   envelope receives a copy of the already-validated default; there is
   normally nothing to run server-clearance ACDF against, because the sender
   supplied nothing. However, when the message arrives via an
   `IncomingServerSession`, `PolicyConfiguration.checkDefaultPeerClearance()`
   first tests the *default* label itself against the peer clearance (a no-op
   without one configured), before it is attached.
4. **A single labelled envelope is validated and access-checked exactly
   once against the server clearance, plus once more against the peer
   clearance for federated senders.** `PolicyConfiguration.check` bounds the
   XML tree, decodes the primary label (and any equivalent labels) under
   whichever loaded policy the label itself declares, translates it to the
   primary policy first if it named a different, secondary loaded policy, and
   calls `assertValid` + `acdf` against the server clearance. When the message
   arrived via an `IncomingServerSession`, the
   same decoded effective label is also tested with `acdf` against the peer
   clearance (via `check`'s `checkPeerClearance` boolean parameter, a no-op
   without one configured); equivalent labels are **not** re-tested
   against the peer clearance, only the effective primary label. An empty
   primary `<label/>` is treated as an explicit request for the default and
   replaced with a fresh copy after validating any equivalents. More than one
   direct envelope is always rejected as malformed.
5. **A failed check is either enforced or only logged.** Depending on
   `Settings.enforcementMode`: `ENFORCE` rejects the message with a sanitized
   `forbidden` error and a `PacketRejectedException`; `WARN` logs the failure
   (sender, recipient, stanza ID, and reason — never the body or label
   payload) and lets the message through completely unchanged. This applies
   identically whether the failure came from the server-clearance or the
   peer-clearance decision — there is no separate enforcement knob for the
   peer clearance.

Everything else in the message's lifetime is explicitly **not** checked
again, with one narrower exception on egress to a federated peer:

- **After the inbound callback runs**, the (possibly stamped or
  already-validated) message proceeds through `MessageRouter` — local
  delivery, offline storage, multicast, carbons, or server-to-server
  routing — with no further server-clearance ACDF evaluation. The plugin
  does not re-validate a label at delivery time, at offline-storage read
  time, or when a room or component later reads the same stanza.
- **Egress to a federated peer now performs a real, but narrower, ACDF
  check.** The interceptor's `!incoming` branch, restricted to
  `!processed && session instanceof OutgoingServerSession`, calls
  `checkPeerClearanceForFederation` before the pre-existing default-label
  stripping. This decodes the outbound envelope's single primary label
  (`PolicyConfiguration.checkPeerClearance(Element)`, via the same `decode`
  header-parsing rules as `check`) and runs `acdf` against the peer clearance
  only — it never re-runs `assertValid` or the server-clearance `acdf` (the
  message already passed those on ingress), never rejects when no peer
  clearance is configured, and applies only to messages about to leave
  through an `OutgoingServerSession` with exactly one label element. A
  missing/unconfigured snapshot, no configured peer clearance, an absent
  label, or more than one label all leave this check a no-op, matching the
  fail-open scope already used by default-label stripping. Outbound messages
  to local clients or components are still not inspected in any way.
- **`postIntercept`/post-processed callbacks are not implemented.** Only the
  pre-processing hook (`processed == false`) is used; there is no check once
  Openfire has already committed to delivering (or having delivered) the
  packet.
- **IQ and presence stanzas carry no label check.** The interceptor ignores
  every packet that is not a `Message` (`if (!(packet instanceof Message
  message)) return;`), so a label placed on an IQ or presence stanza is
  never authorized, stamped, or stripped.
- **Forwarded/inner messages are not recursively evaluated.** Only direct
  children of the outer `<message/>` are inspected for a label; a label
  carried by a forwarded, carboned, or otherwise nested inner message does
  not authorize (or get checked against) the outer stanza, and is not itself
  separately checked.
- **The label catalogue IQ handler enforces locality on `from`, and now also
  reruns the ACDF pipeline per request for `to`.** `CatalogIqHandler` still
  rejects a `get` from a non-local `from` with `not-authorized` (an origin
  check, not an `assertValid`/`acdf` re-run on the requester). It additionally
  reads the request's optional `to=` attribute: when present and not local
  (per the same injected `isLocal` predicate), it asks
  `CatalogService.buildCatalog(true)` to re-run `acdf` against the peer
  clearance for every entry, in addition to the server clearance always
  checked; a `to=` that is local, or absent, only re-runs the server-clearance
  check that `buildCatalog` always performs. A malformed `to=` is rejected
  with `bad-request` before any entry is evaluated. This is a real, per-request
  ACDF re-evaluation of already-stored entries, not merely the add/build-time
  validation described above.
- **The policy discovery IQ handler performs no ACDF check at all, only a
  locality check.** `PolicyIqHandler` rejects a `get` from a non-local `from`
  with `not-authorized` (the same origin check as the catalogue handler), but
  it never decodes, validates, or access-checks a label or clearance; it only
  returns the already-loaded `Spif` documents' id/name/original text
  (`PolicyConfiguration.loadedPolicies`/`loadedPolicyById`/
  `loadedPolicyByName`), which are set once at configuration time and never
  re-derived per request.
- **Direct `RoutingTable`/session delivery paths that bypass
  `PacketInterceptor` entirely** (some server-generated messages, history
  replay, and room fan-out, per `doc/design.md`'s "Rejection and Openfire
  integration evidence" section) never reach this plugin's code at all, so
  they are checked neither on the way in nor on the way out.

In short: one server-clearance access-control decision (ACDF plus policy
validity) is made per ordinary inbound message, at the single pre-processing
inbound hook; a federated inbound or outbound message additionally gets one
peer-clearance-only `acdf` decision (a no-op without a configured peer
clearance), at that same inbound hook or at the pre-send outbound hook,
respectively. No message is re-checked at any other point in its lifetime.
This entire pipeline only runs once the plugin has an active or corrupted
configuration to react to; a plugin that has never been configured at all
performs no check whatsoever and never touches a message.

## Summary table

| Point in a message's lifetime | Real ACDF check (`assertValid`/`acdf`)? | What happens |
| --- | --- | --- |
| Inbound, before processing (error message) | No | Discarded if labelled; otherwise passed through unchecked |
| Inbound, before processing (corrupted stored configuration) | No | Rejected, `service-unavailable` |
| Inbound, before processing (never configured) | No | Passed through completely unchecked and unmodified |
| Inbound, before processing (unlabelled, local client) | Yes, `acdf` against the server clearance only, on the *default* label, already validated at config time | Stamped with a copy of the default |
| Inbound, before processing (unlabelled, federated peer) | Yes, `acdf` against the server clearance (at config time) **and** the peer clearance (per message, no-op if unconfigured), on the *default* label | Stamped with a copy of the default, or rejected/warned if the peer clearance denies it |
| Inbound, before processing (labelled, local client) | **Yes**, `acdf` against the server clearance only | Accepted unchanged, replaced by the default (empty `<label/>`), or rejected/warned |
| Inbound, before processing (labelled, federated peer) | **Yes**, `acdf` against the server clearance **and** the peer clearance (no-op if unconfigured) | Accepted unchanged, replaced by the default (empty `<label/>`), or rejected/warned |
| Inbound, after processing / delivery | No | No further check of any kind |
| Outbound, before send, to a local client/component | No | Untouched |
| Outbound, before send, to another server (`OutgoingServerSession`) | Yes, `acdf` against the peer clearance only (no-op if unconfigured); then a display-marking string comparison for stripping | Rejected/warned if the peer clearance denies the label; otherwise, label stripped if its marking equals the default's, else untouched |
| Outbound, after send (`processed`) | No | Untouched |
| IQ / presence, any direction | No | Ignored entirely (not a `Message`) |
| Label catalogue `get` request, `to=` absent or local | Yes, `acdf` against the server clearance only, re-run per entry | Served if `from` is local; entries failing the server clearance are omitted |
| Label catalogue `get` request, `to=` a non-local recipient | Yes, `acdf` against the server clearance **and** the peer clearance (no-op if unconfigured), re-run per entry | Served if `from` is local and `to=` is a well-formed JID; entries failing either check are omitted |
| Policy discovery `get` request (list or fetch by id/name) | No (locality check only) | Served if `from` is local; returns already-loaded policy id/name/document, not re-validated per request |
