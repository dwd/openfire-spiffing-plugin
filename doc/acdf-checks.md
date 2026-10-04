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

## Points in a message's lifetime where checks occur

1. **Ingress (Local & Federated Messages)**:
   - Evaluated at `SecurityLabelInterceptor.interceptPacket` (incoming, `!processed`).
   - Unlabelled messages: stamped with the default label (if configured); checked against
     default peer clearance if arriving via `IncomingServerSession`.
   - Labelled messages: decoded, verified for structural validity and XML bounds, validated
     against loaded SPIF policies, authorized against server clearances (with cross-policy
     translation if needed), and checked against peer clearances for federated traffic.
   - Malformed/denied labels are rejected with `forbidden` when `ENFORCE` is active, or logged
     and allowed through when `WARN` is active.
2. **Egress (Federated Traffic to Other Servers)**:
   - Evaluated at `SecurityLabelInterceptor.interceptPacket` (outgoing, `!processed`, `OutgoingServerSession`).
   - If peer clearances exist, verified via `snapshot.checkPeerClearance(label, peerDomain)`.
   - If `stripDefaultLabelForFederation` is true and the label matches `defaultDisplayMarking()`,
     the security label envelope is detached before departure.
