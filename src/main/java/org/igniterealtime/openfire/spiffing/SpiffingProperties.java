package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.util.SystemProperty;

/**
 * Global operational settings managed via Openfire's SystemProperty framework.
 */
public final class SpiffingProperties {
    private static volatile String enforcementModeOverride;
    private static volatile Boolean stripDefaultOverride;
    private static volatile String outputFormatOverride;
    private static volatile String bootstrapPolicyFileOverride;
    private static volatile boolean bootstrapPolicyFileOverrideSet;

    @SuppressWarnings("unchecked")
    public static final SystemProperty<String> ENFORCEMENT_MODE =
        (SystemProperty<String>) SystemProperty.getProperty("plugin.spiffing.enforcementMode")
            .orElseGet(() -> SystemProperty.Builder.ofType(String.class)
                .setKey("plugin.spiffing.enforcementMode")
                .setDefaultValue("WARN")
                .setDynamic(true)
                .build());

    @SuppressWarnings("unchecked")
    public static final SystemProperty<Boolean> STRIP_DEFAULT_LABEL_FOR_FEDERATION =
        (SystemProperty<Boolean>) SystemProperty.getProperty("plugin.spiffing.stripDefaultLabelForFederation")
            .orElseGet(() -> SystemProperty.Builder.ofType(Boolean.class)
                .setKey("plugin.spiffing.stripDefaultLabelForFederation")
                .setDefaultValue(false)
                .setDynamic(true)
                .build());

    @SuppressWarnings("unchecked")
    public static final SystemProperty<String> OUTPUT_FORMAT =
        (SystemProperty<String>) SystemProperty.getProperty("plugin.spiffing.outputFormat")
            .orElseGet(() -> SystemProperty.Builder.ofType(String.class)
                .setKey("plugin.spiffing.outputFormat")
                .setDefaultValue("ESS")
                .setDynamic(true)
                .build());

    /**
     * Absolute path to a SPIF document that should be loaded automatically as the server's
     * default policy on plugin startup, but only while no policy has been configured yet (i.e.
     * on a fresh install). Intended for test/dev deployments (e.g. Wimsy's Maestro Openfire
     * instance; see that project's maestro/docker-compose.yml) that need a known security-label
     * policy in place without manual Admin Console setup, rather than for production use.
     * <p>
     * Resolved, in order of precedence, from: an explicit {@link #setBootstrapPolicyFile(String)}
     * override (mainly for tests); the {@code spiffing.bootstrapPolicyFile} JVM system property;
     * the {@code SPIFFING_BOOTSTRAP_POLICY_FILE} environment variable; and finally this
     * database-backed {@link SystemProperty}. The system property/environment variable forms
     * exist because this needs to be readable during a fresh container's very first boot (e.g.
     * Docker/Maestro setup), before Openfire's database-backed property store is necessarily
     * populated or even reachable.
     */
    @SuppressWarnings("unchecked")
    public static final SystemProperty<String> BOOTSTRAP_POLICY_FILE =
        (SystemProperty<String>) SystemProperty.getProperty("plugin.spiffing.bootstrapPolicyFile")
            .orElseGet(() -> SystemProperty.Builder.ofType(String.class)
                .setKey("plugin.spiffing.bootstrapPolicyFile")
                .setDefaultValue(null)
                .setDynamic(true)
                .build());

    public static EnforcementMode getEnforcementMode() {
        if (enforcementModeOverride != null) {
            return EnforcementMode.valueOf(enforcementModeOverride);
        }
        String val = ENFORCEMENT_MODE.getValue();
        return val == null ? EnforcementMode.WARN : EnforcementMode.valueOf(val);
    }

    public static void setEnforcementMode(EnforcementMode mode) {
        enforcementModeOverride = mode.name();
        try {
            ENFORCEMENT_MODE.setValue(mode.name());
        } catch (Exception ignored) {}
    }

    public static LabelFormat getOutputFormat() {
        if (outputFormatOverride != null) {
            return LabelFormat.valueOf(outputFormatOverride);
        }
        String val = OUTPUT_FORMAT.getValue();
        return val == null ? LabelFormat.ESS : LabelFormat.valueOf(val);
    }

    public static void setOutputFormat(LabelFormat format) {
        outputFormatOverride = format.name();
        try {
            OUTPUT_FORMAT.setValue(format.name());
        } catch (Exception ignored) {}
    }

    public static String getBootstrapPolicyFile() {
        if (bootstrapPolicyFileOverrideSet) {
            return bootstrapPolicyFileOverride;
        }
        String sysProp = System.getProperty("spiffing.bootstrapPolicyFile");
        if (sysProp != null && !sysProp.isBlank()) {
            return sysProp;
        }
        String envVar = System.getenv("SPIFFING_BOOTSTRAP_POLICY_FILE");
        if (envVar != null && !envVar.isBlank()) {
            return envVar;
        }
        return BOOTSTRAP_POLICY_FILE.getValue();
    }

    public static void setBootstrapPolicyFile(String path) {
        bootstrapPolicyFileOverride = path;
        bootstrapPolicyFileOverrideSet = true;
        try {
            BOOTSTRAP_POLICY_FILE.setValue(path);
        } catch (Exception ignored) {}
    }

    public static boolean isStripDefaultLabelForFederation() {
        if (stripDefaultOverride != null) {
            return stripDefaultOverride;
        }
        Boolean val = STRIP_DEFAULT_LABEL_FOR_FEDERATION.getValue();
        return val != null && val;
    }

    public static void setStripDefaultLabelForFederation(boolean strip) {
        stripDefaultOverride = strip;
        try {
            STRIP_DEFAULT_LABEL_FOR_FEDERATION.setValue(strip);
        } catch (Exception ignored) {}
    }

    private SpiffingProperties() {}
}
