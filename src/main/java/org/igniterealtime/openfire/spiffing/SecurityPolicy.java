package org.igniterealtime.openfire.spiffing;

/**
 * A security policy stored in the database, containing its SPIF document and optional
 * (nullable) server clearance and default message label.
 */
public record SecurityPolicy(
    String id,
    String name,
    String spif,
    String clearance,
    LabelFormat clearanceFormat,
    String defaultLabel,
    LabelFormat defaultLabelFormat,
    boolean isDefault
) {
    public SecurityPolicy {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Policy ID is required.");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Policy name is required.");
        if (spif == null || spif.isBlank()) throw new IllegalArgumentException("SPIF document is required.");
        SecureXml.bounded(spif, SecureXml.MAX_DOCUMENT);
        if (clearance != null && !clearance.isBlank()) {
            SecureXml.bounded(clearance, SecureXml.MAX_LABEL);
            if (clearanceFormat == null) throw new IllegalArgumentException("Clearance format is required when clearance is present.");
        } else {
            clearance = null;
            clearanceFormat = null;
        }
        if (defaultLabel != null && !defaultLabel.isBlank()) {
            SecureXml.bounded(defaultLabel, SecureXml.MAX_LABEL);
            if (defaultLabelFormat == null) throw new IllegalArgumentException("Default label format is required when default label is present.");
        } else {
            defaultLabel = null;
            defaultLabelFormat = null;
        }
    }
}
