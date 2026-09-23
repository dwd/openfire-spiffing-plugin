package org.igniterealtime.openfire.spiffing;

import java.util.Objects;

/** All settings are persisted together, so readers never see a partial policy update. */
public record Settings(String policy, String clearance, LabelFormat clearanceFormat,
                       String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                       EnforcementMode enforcementMode, boolean stripDefaultLabelForFederation,
                       String peerClearance, LabelFormat peerClearanceFormat) {
    public Settings {
        SecureXml.bounded(policy, SecureXml.MAX_DOCUMENT);
        SecureXml.bounded(clearance, SecureXml.MAX_LABEL);
        SecureXml.bounded(defaultLabel, SecureXml.MAX_LABEL);
        Objects.requireNonNull(clearanceFormat);
        Objects.requireNonNull(labelFormat);
        Objects.requireNonNull(outputFormat);
        Objects.requireNonNull(enforcementMode);
        // Unlike the mandatory server clearance, the peer clearance is optional: an empty payload means
        // no peer clearance is configured, and ingress/egress peer-clearance checks stay inactive.
        if (peerClearance == null) peerClearance = "";
        if (!peerClearance.isEmpty()) SecureXml.bounded(peerClearance, SecureXml.MAX_LABEL);
        Objects.requireNonNull(peerClearanceFormat);
    }

    /** Convenience constructor for existing call sites; defaults to no configured peer clearance. */
    public Settings(String policy, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                     EnforcementMode enforcementMode, boolean stripDefaultLabelForFederation) {
        this(policy, clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, enforcementMode,
            stripDefaultLabelForFederation, "", LabelFormat.ESS);
    }

    /** Convenience constructor for existing call sites; defaults to not stripping the default label for federation. */
    public Settings(String policy, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                     EnforcementMode enforcementMode) {
        this(policy, clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, enforcementMode, false);
    }

    /** Convenience constructor for existing call sites; defaults to the safe "warn" enforcement mode and no stripping. */
    public Settings(String policy, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat) {
        this(policy, clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, EnforcementMode.WARN, false);
    }
}
