package org.igniterealtime.openfire.spiffing;

/**
 * A peer clearance stored in the database, bound to a specific domain ("" for server default peer clearance)
 * and policy ID.
 */
public record PeerClearance(
    String id,
    String domain,
    String policyId,
    String clearance,
    LabelFormat format
) {
    public PeerClearance {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Clearance ID is required.");
        if (domain == null) domain = "";
        if (policyId == null || policyId.isBlank()) throw new IllegalArgumentException("Policy ID is required.");
        if (clearance == null || clearance.isBlank()) throw new IllegalArgumentException("Clearance payload is required.");
        SecureXml.bounded(clearance, SecureXml.MAX_LABEL);
        if (format == null) throw new IllegalArgumentException("Format is required.");
    }
}
