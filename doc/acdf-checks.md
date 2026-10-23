# ACDF objects and message-lifetime checks

This document answers two specific questions about the current implementation,
cross-referenced against `doc/design.md`: which objects carry a label or a
clearance, and at which points in a message's lifetime the plugin actually
performs an access-control check. It reflects the database-backed policies
and partial configuration architecture.

## Objects that have a clearance

Clearances are stored in the database (`ofSpiffingPolicy` and `ofSpiffingClearance` tables):

- **Server Clearances**: Each configured `SecurityPolicy` in `ofSpiffingPolicy`
  may contain an optional (nullable) server clearance (`clearance`, `clearanceFormat`).
  When present, each policy's clearance is loaded into a Spiffing `Clearance` object.
  When an inbound/outbound label is evaluated:
  - If a server clearance exists directly for that label's policy, `Spif.acdf(label, clearance)`
    is evaluated directly.
  - If no direct clearance exists for that policy, Spiffing translates (`label.encrypt(...)`)
    the label to each policy for which a server clearance is available and evaluates
    authorization against that clearance.
  - In a partial configuration where no policy has a server clearance, policy validity
    rules are enforced, but clearance authorization checks are bypassed (or warned).
- **Peer Clearances**: Configured in the `ofSpiffingClearance` database table,
  each peer clearance is associated with a domain (`""` representing the default peer
  clearance for all federated peers) and a specific `policyID`. When inbound messages
  arrive from a peer (`IncomingServerSession`) or egress to a federated peer
  (`OutgoingServerSession`), the matching peer clearance (or default peer clearance)
  is consulted, translating across loaded policies if needed.
- **User Clearances**: Stored as `UserProperty` pairs on the local `User`
  object itself (`plugin.spiffing.clearance.<policyId>` and
  `plugin.spiffing.clearance.<policyId>.format`), not in `ofSpiffingPolicy`/
  `ofSpiffingClearance`. A user may hold at most one clearance per loaded
  policy, but one for several different policies at once. When a message
  arrives from a local client (i.e. not via an `IncomingServerSession`), the
  sender JID's node is looked up (`UserClearanceService.list`) and every
  returned clearance is consulted against the message's effective label,
  translating across loaded policies exactly like server/peer clearances.
- **Room Clearances**: Configured in the `ofSpiffingRoomClearance` database
  table, keyed by `(roomJID, policyID)`, independently of `ofSpiffingPolicy`/
  `ofSpiffingClearance`. A room may hold a clearance for
  several different loaded policies at once, like a user clearance. When a
  message is addressed to a room's bare JID, every room clearance for that
  room is consulted against the message's effective label, translating
  across loaded policies exactly like server/peer/user clearances. A room
  clearance gates labels of messages *entering* the room; it does not gate
  who may *join* the room (that is the room label's job, below).

## Objects that have a label

Several distinct things carry a XEP-0258 label (a `<securitylabel/>` envelope
or the underlying Spiffing `Label`), each with its own lifecycle:

- **The configured default label**: Each `SecurityPolicy` may have an optional
  `defaultLabel` (and format). One policy is flagged as the default stamping policy (`isDefault`).
  When configured, `PolicyConfiguration.defaultEnvelope` is attached to any inbound message
  that arrives without a label, or whose primary `<label/>` is empty. If no default label
  is configured across any policy, unlabelled messages pass through unstamped.
- **Each inbound message**: `Message.getElement()` may already carry a
  `<securitylabel/>` envelope, supplied by whichever entity sent it (a local
  client or a remote domain). This is decoded, bounded, and validated by
  `PolicyConfiguration.check`/`boundTree`.
- **Each outbound message to another server**: after inbound stamping, the
  same envelope may still be present when the stanza is handed to an
  `OutgoingServerSession`. `SecurityLabelInterceptor` reads this label on egress
  to run peer-clearance validation (`PolicyConfiguration.checkPeerClearance`) and to strip
  default-stamped labels when `stripDefaultLabelForFederation` is enabled.
- **Each label catalogue entry**: `CatalogEntry.payload` (with its own
  `LabelFormat`), stored in `ofSpiffingCatalog` independently of the message-stamping
  default and validated against the active policy/clearance when added
  (`CatalogService.add`) and when the catalogue is discovered (`CatalogService.buildCatalog`).
- **Equivalent labels**: `<equivalentlabel/>` children of an inbound envelope
  are individually decoded, validated, and translated/verified for equivalence against
  the primary label.

- **The room label**: Configured in the `ofSpiffingRoomLabel` database table,
  one row per room JID (a room has exactly one label, unlike the
  multiple-policies-at-once room clearance). A room's label is an ambient
  classification, checked against a *joining* user's/peer's clearance, not
  against a message's label; a room's own, independently optional default
  label (`defaultLabel`/`defaultLabelFormat`) stamps that room's unlabelled
  outgoing messages, falling back to the room label itself when unset.

User clearances themselves do not carry a label; they are consulted as an
additional clearance (see above), the same way server and peer clearances are.
Room clearances are likewise consulted, not carried; the room label above is
the one new object in this increment that is actually a label, not a
clearance.

## Points in a message's lifetime where checks occur

Real ACDF checks (`Spif.assertValid` and/or `Spif.acdf`) happen at two points:
always on the **inbound** path before the message is handed off for delivery,
and, since the "Peer clearance" increment, also on the **egress** path to a
federated peer, but only for the narrower peer-clearance decision (see below).
Since the "User clearance" increment, the inbound path's server-clearance
decision is additionally joined by a user-clearance decision when the sender
is a local client (see step 3/4 below). Since the "Room clearance and room
label" increment, the inbound path's checks are additionally joined by a
room-clearance decision whenever the message is addressed to a room (also
steps 3/4 below); joining a room is a wholly separate event from sending a
message and is checked at a different hook entirely (see "Room joins" further
below). Nothing else in a message's lifetime
is re-checked. The `SecurityLabelInterceptor` callback (`incoming &&
!processed`, i.e. before Openfire's `MessageRouter` processes the packet)
does the following, for every non-error message:

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
3. **Unlabelled messages are stamped, and, from a federated peer, a local
   sender with a clearance, or a message addressed to a room, also checked
   first.** A message with no direct envelope receives a copy of the
   already-validated default (or, when addressed to a room with its own
   `RoomLabel` configured, the room's own default label, falling back to the
   room label itself — see below); there is normally nothing to run
   server-clearance ACDF against, because the sender supplied nothing.
   However, when the message arrives via an
   `IncomingServerSession`, `PolicyConfiguration.checkDefaultPeerClearance()`
   first tests the *default* label itself against the peer clearance (a no-op
   without one configured); and, for a local sender, `checkDefaultUserClearance()`
   likewise tests it against any clearances configured for that sender's
   username (a no-op if they have none) — both before the default is attached.
   **When the message is addressed to a room**, the label that will actually
   be stamped (the room's own default label, or the room label, or — if
   the room has no `RoomLabel` configured at all — the server-wide default)
   is additionally tested against every room clearance configured for that
   room (`PolicyConfiguration.checkDefaultRoomClearance()`/
   `encodeRoomDefaultEnvelope()`, a no-op without one configured), before it
   is attached.
4. **A single labelled envelope is validated and access-checked exactly
   once against the server clearance, plus once more against the peer
   clearance for federated senders, the user clearance for local senders, or
   the room clearance for a room-addressed message (these are not mutually
   exclusive with each other).**
   `PolicyConfiguration.check` bounds the
   XML tree, decodes the primary label (and any equivalent labels) under
   whichever loaded policy the label itself declares, translates it to the
   primary policy first if it named a different, secondary loaded policy, and
   calls `assertValid` + `acdf` against the server clearance. When the message
   arrived via an `IncomingServerSession`, the
   same decoded effective label is also tested with `acdf` against the peer
   clearance (via `check`'s `checkPeerClearance` boolean parameter, a no-op
   without one configured). When the message instead came from a local client,
   `PolicyConfiguration.checkUserClearance` tests the same effective label
   against any clearances configured for the sender's username (a no-op
   without any); a federated peer's message is never checked against a user
   clearance, since its sender JID's node is a remote user this server has no
   authority over. **Independently of sender locality**, if the message is
   addressed to a room's bare JID, `PolicyConfiguration.checkRoomClearance`
   tests the same effective label against every room clearance configured for
   that room (a no-op without any) — a message can therefore be checked
   against the server clearance, one of peer/user clearance, *and* the room
   clearance, all for the same message. In all cases, equivalent labels are **not**
   re-tested
   against the peer, user, or room clearance, only the effective primary label. An empty
   primary `<label/>` is treated as an explicit request for the default and
   replaced with a fresh copy after validating any equivalents. More than one
   direct envelope is always rejected as malformed.
5. **A failed check is either enforced or only logged.** Depending on
   `Settings.enforcementMode`: `ENFORCE` rejects the message with a sanitized
   `forbidden` error and a `PacketRejectedException`; `WARN` logs the failure
   (sender, recipient, stanza ID, and reason — never the body or label
   payload) and lets the message through completely unchanged. This applies
   identically whether the failure came from the server-clearance, the
   peer-clearance, the user-clearance, or the room-clearance decision — there
   is no separate enforcement knob for the peer, user, or room clearance.

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
  returns the already-loaded `Spif` documents' id/name
  (`PolicyConfiguration.loadedPolicies`/`loadedPolicyById`/
  `loadedPolicyByName`, set once at configuration time) and, for a single-policy
  request, that `Spif`'s document, re-exported per request via
  `PolicyConfiguration.exportedDocument` (`Spif.write` with no clearance
  filter, so every loaded policy is always returned in full).
- **Direct `RoutingTable`/session delivery paths that bypass
  `PacketInterceptor` entirely** (some server-generated messages, history
  replay, and room fan-out, per `doc/design.md`'s "Rejection and Openfire
  integration evidence" section) never reach this plugin's code at all, so
  they are checked neither on the way in nor on the way out. This includes
  the room-clearance check above: it only sees the stanza addressed to the
  room's bare JID as it enters the server, not any per-occupant fan-out copy.

## Room joins

Unlike every other check in this document, a room join is not a message at
all, and is checked at a completely different Openfire extension point: there
is no `PacketInterceptor` callback for a MUC join, so `RoomJoinGate`
(installed via `MultiUserChatService.setMUCDelegate`) hooks
`MUCEventDelegate.joiningRoom(MUCRoom, JID)` instead.

- **A room with no configured `RoomLabel` performs no check at all** (and
  defers entirely to any pre-existing wrapped delegate's own decision,
  Openfire allows only one delegate per service). This is the same opt-in
  default as every other clearance/label feature in this plugin: installing
  it cannot newly block joins to a room nobody has configured a label for.
- **A room with a configured `RoomLabel` performs exactly one `acdf` check
  per join attempt, against the joining party's own clearance — never against
  a message label, since there isn't one.** `PolicyConfiguration.decodeRoomLabel`
  decodes and validates the room's label under its claimed policy (raising if
  it fails to parse, exactly like any other stored clearance/label); then,
  depending on whether the joining JID is local or remote
  (`isLocal.test(userjid)`): `authorizeRoomJoinByUserClearance` runs `acdf`
  against every `UserClearance` the local user holds, or
  `authorizeRoomJoinByPeerClearance` runs it against the peer clearance for
  the remote user's domain.
- **Unlike every message-level check in this document, an absent clearance
  here is an explicit denial, not a no-op.** A local user with zero
  configured clearances, or a remote domain with no peer clearance
  configured at all, is denied the join outright once the room has a label
  — there is no "clearance not configured, so allow through" fallback the way
  there is for peer/user/room *message* clearance. This matches the issue's
  explicit wording that a user should have "no visibility or capability to
  join the room unless the label was passed by" their own clearance.
- **Enforcement mode is honored the same way as every other check**:
  `ENFORCE` denies the join outright (`joiningRoom` returns `false`); `WARN`
  logs the denial and allows the join through unchanged. There is no separate
  enforcement knob for room joins.
- **A pre-existing wrapped delegate's own denial short-circuits before the
  label check ever runs**, and every other `MUCEventDelegate` callback
  (invitations, room config, destruction, recreation) is passed straight
  through to the wrapped delegate unchanged, or a permissive default when
  there is none — `RoomJoinGate` only ever adds a new way to *deny* a join,
  never a new way to approve one a pre-existing delegate already denied.

In short: one server-clearance access-control decision (ACDF plus policy
validity) is made per ordinary inbound message, at the single pre-processing
inbound hook; a federated inbound or outbound message additionally gets one
peer-clearance-only `acdf` decision (a no-op without a configured peer
clearance), at that same inbound hook or at the pre-send outbound hook,
respectively; a local-client inbound message instead additionally gets one
user-clearance-only `acdf` decision per clearance the sender holds (a no-op
without any), at that same inbound hook; a message addressed to a room
additionally gets one room-clearance-only `acdf` decision per room clearance
configured for that room (a no-op without any), at that same inbound hook,
independently of whichever of the above also applies. No message is
re-checked at any
other point in its lifetime. Joining a room is a separate event, checked at a
different hook (`MUCEventDelegate`, not `PacketInterceptor`): a room with a
configured label gets exactly one `acdf` decision per join attempt, against
the joining party's own user or peer clearance, with an absent clearance
treated as an explicit denial rather than a no-op. This entire pipeline only runs once the plugin
has an active or corrupted configuration to react to; a plugin that has never
been configured at all performs no check whatsoever and never touches a
message or a join.

## Summary table

| Point in a message's lifetime | Real ACDF check (`assertValid`/`acdf`)? | What happens |
| --- | --- | --- |
| Inbound, before processing (error message) | No | Discarded if labelled; otherwise passed through unchecked |
| Inbound, before processing (corrupted stored configuration) | No | Rejected, `service-unavailable` |
| Inbound, before processing (never configured) | No | Passed through completely unchecked and unmodified |
| Inbound, before processing (unlabelled, local client) | Yes, `acdf` against the server clearance (at config time) **and** the user clearance (per message, no-op if the sender has none), on the *default* label | Stamped with a copy of the default, or rejected/warned if the sender's own clearance denies it |
| Inbound, before processing (unlabelled, federated peer) | Yes, `acdf` against the server clearance (at config time) **and** the peer clearance (per message, no-op if unconfigured), on the *default* label | Stamped with a copy of the default, or rejected/warned if the peer clearance denies it |
| Inbound, before processing (labelled, local client) | **Yes**, `acdf` against the server clearance **and** the user clearance (no-op if the sender has none) | Accepted unchanged, replaced by the default (empty `<label/>`), or rejected/warned |
| Inbound, before processing (labelled, federated peer) | **Yes**, `acdf` against the server clearance **and** the peer clearance (no-op if unconfigured) | Accepted unchanged, replaced by the default (empty `<label/>`), or rejected/warned |
| Inbound, after processing / delivery | No | No further check of any kind |
| Outbound, before send, to a local client/component | No | Untouched |
| Outbound, before send, to another server (`OutgoingServerSession`) | Yes, `acdf` against the peer clearance only (no-op if unconfigured); then a display-marking string comparison for stripping | Rejected/warned if the peer clearance denies the label; otherwise, label stripped if its marking equals the default's, else untouched |
| Outbound, after send (`processed`) | No | Untouched |
| IQ / presence, any direction | No | Ignored entirely (not a `Message`) |
| Label catalogue `get` request, `to=` absent or local | Yes, `acdf` against the server clearance only, re-run per entry | Served if `from` is local; entries failing the server clearance are omitted |
| Label catalogue `get` request, `to=` a non-local recipient | Yes, `acdf` against the server clearance **and** the peer clearance (no-op if unconfigured), re-run per entry | Served if `from` is local and `to=` is a well-formed JID; entries failing either check are omitted |
| Policy discovery `get` request (list or fetch by id/name) | No (locality check only) | Served if `from` is local; returns already-loaded policy id/name/document, not re-validated per request |
| Inbound, before processing (addressed to a room) | Yes, `acdf` against every room clearance configured for that room (no-op if none), **in addition to** whichever server/peer/user row above also applies | Accepted/stamped unchanged, or rejected/warned, same as the corresponding row above |
| MUC join attempt, room has no configured room label | No | Allowed (deferring to any pre-existing wrapped delegate) |
| MUC join attempt, room has a configured room label | Yes, `acdf` against the joining local user's own clearance(s), or the joining remote peer's domain clearance | Allowed if any clearance passes; denied/warned otherwise — an absent clearance is an explicit denial, not a no-op |
