package org.igniterealtime.openfire.spiffing;

/**
 * A MUC room's own clearance for one loaded policy: labels of messages entering the room are
 * checked against it, in addition to the server/peer/user clearances already enforced. Stored in
 * the {@code ofSpiffingRoomClearance} database table (see {@link DatabaseRoomClearanceStore}),
 * keyed by {@code (roomJid, policyId)}, mirroring {@link UserClearance}'s natural key: a room may
 * hold a clearance for several different loaded policies at once, but at most one per policy id.
 */
public record RoomClearance(
    String roomJid,
    String policyId,
    String clearance,
    LabelFormat format
) {
    public RoomClearance {
        if (roomJid == null || roomJid.isBlank()) throw new IllegalArgumentException("Room JID is required.");
        if (policyId == null || policyId.isBlank()) throw new IllegalArgumentException("Policy ID is required.");
        if (clearance == null || clearance.isBlank()) throw new IllegalArgumentException("Clearance payload is required.");
        SecureXml.bounded(clearance, SecureXml.MAX_LABEL);
        if (format == null) throw new IllegalArgumentException("Format is required.");
    }
}
