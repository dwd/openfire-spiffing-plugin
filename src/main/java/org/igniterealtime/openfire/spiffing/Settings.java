package org.igniterealtime.openfire.spiffing;

import java.util.Objects;

/** All settings are persisted together, so readers never see a partial policy update. */
public record Settings(String policy, String clearance, LabelFormat clearanceFormat,
                       String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                       EnforcementMode enforcementMode) {
    public Settings {
        SecureXml.bounded(policy, SecureXml.MAX_DOCUMENT);
        SecureXml.bounded(clearance, SecureXml.MAX_LABEL);
        SecureXml.bounded(defaultLabel, SecureXml.MAX_LABEL);
        Objects.requireNonNull(clearanceFormat);
        Objects.requireNonNull(labelFormat);
        Objects.requireNonNull(outputFormat);
        Objects.requireNonNull(enforcementMode);
    }

    /** Convenience constructor for existing call sites; defaults to the safe "warn" enforcement mode. */
    public Settings(String policy, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat) {
        this(policy, clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, EnforcementMode.WARN);
    }
}
