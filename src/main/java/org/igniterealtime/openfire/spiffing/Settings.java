package org.igniterealtime.openfire.spiffing;

import java.util.List;
import java.util.Objects;

/** All settings are persisted together, so readers never see a partial policy update. */
public record Settings(List<String> policies, String clearance, LabelFormat clearanceFormat,
                       String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                       EnforcementMode enforcementMode, boolean stripDefaultLabelForFederation,
                       String peerClearance, LabelFormat peerClearanceFormat) {
    /** Maximum number of Open XML SPIF documents that may be loaded into one registry at once. */
    static final int MAX_POLICIES = 16;

    public Settings {
        if (policies == null || policies.isEmpty()) throw new IllegalArgumentException("At least one policy is required.");
        if (policies.size() > MAX_POLICIES) throw new IllegalArgumentException("Too many policies.");
        for (String p : policies) SecureXml.bounded(p, SecureXml.MAX_DOCUMENT);
        policies = List.copyOf(policies);
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

    /**
     * The primary policy: the server clearance, optional peer clearance, and default label are all validated
     * against it. Additional loaded policies (see {@link #policies()}) may still supply a message's primary
     * or equivalent label; such labels are translated to this policy via each policy's own declared
     * equivalence mappings ({@code equivalentPolicy}/{@code equivalentClassification}/
     * {@code equivalentSecCategoryTag}) before being authorized, since Spiffing's access-control check
     * requires an exact policy match between a label and the clearance checking it.
     */
    public String policy() { return policies.get(0); }

    /** Convenience constructor for a single policy document, preserving every pre-multi-policy call site. */
    public Settings(String policy, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                     EnforcementMode enforcementMode, boolean stripDefaultLabelForFederation,
                     String peerClearance, LabelFormat peerClearanceFormat) {
        this(List.of(policy), clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, enforcementMode,
            stripDefaultLabelForFederation, peerClearance, peerClearanceFormat);
    }

    /** Convenience constructor for existing call sites; defaults to no configured peer clearance. */
    public Settings(List<String> policies, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                     EnforcementMode enforcementMode, boolean stripDefaultLabelForFederation) {
        this(policies, clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, enforcementMode,
            stripDefaultLabelForFederation, "", LabelFormat.ESS);
    }

    /** Convenience constructor for a single policy document, preserving every pre-multi-policy call site. */
    public Settings(String policy, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                     EnforcementMode enforcementMode, boolean stripDefaultLabelForFederation) {
        this(List.of(policy), clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, enforcementMode,
            stripDefaultLabelForFederation);
    }

    /** Convenience constructor for existing call sites; defaults to not stripping the default label for federation. */
    public Settings(List<String> policies, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                     EnforcementMode enforcementMode) {
        this(policies, clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, enforcementMode, false);
    }

    /** Convenience constructor for a single policy document, preserving every pre-multi-policy call site. */
    public Settings(String policy, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat,
                     EnforcementMode enforcementMode) {
        this(List.of(policy), clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, enforcementMode);
    }

    /** Convenience constructor for existing call sites; defaults to the safe "warn" enforcement mode and no stripping. */
    public Settings(List<String> policies, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat) {
        this(policies, clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, EnforcementMode.WARN, false);
    }

    /** Convenience constructor for a single policy document, preserving every pre-multi-policy call site. */
    public Settings(String policy, String clearance, LabelFormat clearanceFormat,
                     String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat) {
        this(List.of(policy), clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat);
    }
}
