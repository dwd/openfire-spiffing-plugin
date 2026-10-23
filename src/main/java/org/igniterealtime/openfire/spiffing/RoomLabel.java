package org.igniterealtime.openfire.spiffing;

/**
 * A MUC room's own ambient classification. Unlike {@link RoomClearance} (which gates labels of
 * messages entering the room), the room label is checked against the clearance of a user or peer
 * attempting to join the room: a local user needs a {@link UserClearance} that authorizes this
 * label, and a remote user needs the peer clearance for their domain to authorize it; without a
 * passing clearance, joining is denied.
 * <p>
 * {@code defaultLabel}/{@code defaultLabelFormat} are independently optional: they stamp the
 * room's own outgoing unlabelled messages and are <em>not</em> automatically the same as the room
 * label, though they default to it when unset (see {@link PolicyConfiguration#effectiveDefaultRoomLabel}).
 * Stored in the {@code ofSpiffingRoomLabel} table (see {@link DatabaseRoomLabelStore}), one row per
 * room (a room has exactly one label, unlike the multiple-policies-at-once {@link RoomClearance}).
 */
public record RoomLabel(
    String roomJid,
    String policyId,
    String label,
    LabelFormat format,
    String defaultLabel,
    LabelFormat defaultLabelFormat
) {
    public RoomLabel {
        if (roomJid == null || roomJid.isBlank()) throw new IllegalArgumentException("Room JID is required.");
        if (policyId == null || policyId.isBlank()) throw new IllegalArgumentException("Policy ID is required.");
        if (label == null || label.isBlank()) throw new IllegalArgumentException("Room label payload is required.");
        SecureXml.bounded(label, SecureXml.MAX_LABEL);
        if (format == null) throw new IllegalArgumentException("Format is required.");
        if (defaultLabel != null && defaultLabel.isBlank()) defaultLabel = null;
        if (defaultLabel != null) {
            SecureXml.bounded(defaultLabel, SecureXml.MAX_LABEL);
            if (defaultLabelFormat == null) throw new IllegalArgumentException("Default label format is required when a default label is set.");
        } else {
            defaultLabelFormat = null;
        }
    }

    /** Whether this room has its own default label, distinct from the room label itself. */
    public boolean hasOwnDefaultLabel() { return defaultLabel != null; }
}
