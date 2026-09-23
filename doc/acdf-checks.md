# ACDF objects and message-lifetime checks

This document answers two specific questions about the current implementation,
cross-referenced against `doc/design.md`: which objects carry a label or a
clearance, and at which points in a message's lifetime the plugin actually
performs an access-control check. It does not introduce any new behavior; it
only describes what `PolicyConfiguration` and `SecurityLabelInterceptor`
already do, as of the "Outbound default-label stripping for federation"
increment.

## Objects that have a clearance

There is exactly **one** clearance object in this plugin, and it belongs to
**this server**, not to any peer:

- `Settings.clearance` (with `Settings.clearanceFormat`) is the single
  administrator-configured payload, parsed once per configuration into a
  Spiffing `Clearance` and held by `PolicyConfiguration`.
- This clearance is used, unchanged, as the access-control decision input
  (`Spif.acdf(label, clearance)`) for **every** label the plugin evaluates:
  inbound messages regardless of origin (local client or federated peer), the
  administrator-configured default label, and every label catalogue entry
  (`PolicyConfiguration.encodeCatalogLabel`).
- As already stated in `doc/design.md` ("The server clearance covers
  local-client and federated traffic. No per-user or per-room clearance is
  inferred from the server clearance."), there is **no per-peer, per-user, or
  per-room clearance**. In particular, a remote/federated server is *not*
  itself modeled as an object with its own clearance; the plugin does not read
  or derive a clearance from `session.getAddress()`, the remote domain, or any
  other property of an `OutgoingServerSession`/`Session`. The one configured
  clearance stands in for "this server's own clearance" against which every
  label, from any source, is tested.
- Nothing else — no data class for a room, a contact, a component, or a
  domain — currently exists or is consulted as a clearance holder. Answering
  the issue's framing directly: today only **the server itself** has a
  clearance; **peer servers do not** have one of their own, and are not
  distinguished from local clients for clearance purposes.

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
  this label again, but only to compare its `<displaymarking>` text against
  the default's; see "Outbound delivery performs no ACDF check at all" below.
- **Each label catalogue entry**: `CatalogEntry.payload` (with its own
  `LabelFormat`), stored independently of the message-stamping default and
  validated against the active policy/clearance only when added
  (`CatalogService.add`) and when the catalogue is (re)built for a `get`
  request (`CatalogService.buildCatalog`); an entry that no longer validates
  is omitted from the published `<catalog/>`, not rejected outright.
- **Equivalent labels**: `<equivalentlabel/>` children of an inbound envelope
  are also individually decoded, validated, and access-checked
  (`PolicyConfiguration.check`), though they never replace the primary label
  and only need to be equivalent to it under the one configured policy.

There is no notion of a label belonging to a server, a peer, a room, or a
user as a standing property — a label is always attached to one specific
message (or catalogue entry) and evaluated per message. Contrast this with
the clearance above, which is one fixed, standing property of the server.

## Checks made over a message's entire lifetime

Today, real ACDF checks (`Spif.assertValid` + `Spif.acdf`) only happen on the
**inbound** path, before the message is handed off for delivery. Nothing
downstream of that point is re-checked. The `SecurityLabelInterceptor`
callback (`incoming && !processed`, i.e. before Openfire's `MessageRouter`
processes the packet) does the following, for every non-error message:

1. **Error messages bypass all checks.** An error carrying a direct
   XEP-0258 label is discarded (`PacketRejectedException`) without ever being
   authorized; XEP-0258 §6 does not require label checks on errors.
2. **Missing configuration blocks the message.** If no valid
   `PolicyConfiguration` snapshot has ever been published, every ordinary
   message is rejected with `service-unavailable`, regardless of whether it
   carries a label.
3. **Unlabelled messages are stamped, not checked against an incoming
   label.** A message with no direct envelope receives a copy of the
   already-validated default; there is nothing to run ACDF against because
   the sender supplied nothing.
4. **A single labelled envelope is validated and access-checked exactly
   once.** `PolicyConfiguration.check` bounds the XML tree, decodes the
   primary label (and any equivalent labels) under the configured policy, and
   calls `assertValid` + `acdf` against the one server clearance. An empty
   primary `<label/>` is treated as an explicit request for the default and
   replaced with a fresh copy after validating any equivalents. More than one
   direct envelope is always rejected as malformed.
5. **A failed check is either enforced or only logged.** Depending on
   `Settings.enforcementMode`: `ENFORCE` rejects the message with a sanitized
   `forbidden` error and a `PacketRejectedException`; `WARN` logs the failure
   (sender, recipient, stanza ID, and reason — never the body or label
   payload) and lets the message through completely unchanged.

That is the entire set of points at which a real access-control decision is
made. Everything else in the message's lifetime is explicitly **not**
checked again:

- **After the inbound callback runs**, the (possibly stamped or
  already-validated) message proceeds through `MessageRouter` — local
  delivery, offline storage, multicast, carbons, or server-to-server
  routing — with no further ACDF evaluation. The plugin does not re-validate
  a label at delivery time, at offline-storage read time, or when a room or
  component later reads the same stanza.
- **Outbound delivery performs no ACDF check at all.** The interceptor's
  `!incoming` branch (added for the "Outbound default-label stripping for
  federation" feature) only compares a `<displaymarking>` string against the
  default's; it never calls `assertValid`/`acdf`, never rejects a message,
  and applies only to messages about to leave through an
  `OutgoingServerSession`. Outbound messages to local clients or components
  are not inspected in any way.
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
- **The label catalogue IQ handler enforces locality, not ACDF, per
  request.** `CatalogIqHandler` rejects a `get` from a non-local `from` with
  `not-authorized`, but that is an origin check, not a re-run of `assertValid`/
  `acdf` on the requester; the entries it returns were already validated once,
  at add/build time (see above), not per request against the requester.
- **Direct `RoutingTable`/session delivery paths that bypass
  `PacketInterceptor` entirely** (some server-generated messages, history
  replay, and room fan-out, per `doc/design.md`'s "Rejection and Openfire
  integration evidence" section) never reach this plugin's code at all, so
  they are checked neither on the way in nor on the way out.

In short: today, exactly one access-control decision (ACDF plus policy
validity) is made per ordinary inbound message, at the single pre-processing
inbound hook; the same message is never re-checked again at any later point
in its lifetime, including on the outbound/federated path, where only a
non-authoritative display-marking comparison is performed.

## Summary table

| Point in a message's lifetime | Real ACDF check (`assertValid`/`acdf`)? | What happens |
| --- | --- | --- |
| Inbound, before processing (error message) | No | Discarded if labelled; otherwise passed through unchecked |
| Inbound, before processing (no configuration) | No | Rejected, `service-unavailable` |
| Inbound, before processing (unlabelled) | Yes, on the *default* label, already validated at config time | Stamped with a copy of the default |
| Inbound, before processing (labelled) | **Yes** | Accepted unchanged, replaced by the default (empty `<label/>`), or rejected/warned |
| Inbound, after processing / delivery | No | No further check of any kind |
| Outbound, before send, to a local client/component | No | Untouched |
| Outbound, before send, to another server (`OutgoingServerSession`) | No (display-marking string comparison only) | Label stripped if its marking equals the default's; otherwise untouched |
| Outbound, after send (`processed`) | No | Untouched |
| IQ / presence, any direction | No | Ignored entirely (not a `Message`) |
| Label catalogue `get` request | No (locality check only) | Served if `from` is local; entries were validated at add/build time, not per request |
