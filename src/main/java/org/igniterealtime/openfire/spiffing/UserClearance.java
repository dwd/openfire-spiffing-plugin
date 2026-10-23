package org.igniterealtime.openfire.spiffing;

/**
 * A local user's own clearance for one loaded policy, stored as a pair of {@code UserProperty}
 * values on that user (see {@link UserPropertyClearanceStore}). A user may have at most one
 * clearance per policy id, but may hold clearances for several different loaded policies at
 * once; the natural key is {@code (username, policyId)}, unlike {@link PeerClearance} which
 * carries its own synthetic id.
 */
public record UserClearance(
    String username,
    String policyId,
    String clearance,
    LabelFormat format
) {
    public UserClearance {
        if (username == null || username.isBlank()) throw new IllegalArgumentException("Username is required.");
        if (policyId == null || policyId.isBlank()) throw new IllegalArgumentException("Policy ID is required.");
        if (clearance == null || clearance.isBlank()) throw new IllegalArgumentException("Clearance payload is required.");
        SecureXml.bounded(clearance, SecureXml.MAX_LABEL);
        if (format == null) throw new IllegalArgumentException("Format is required.");
    }
}
