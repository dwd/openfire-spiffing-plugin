package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.util.SystemProperty;

/**
 * Global operational settings managed via Openfire's SystemProperty framework.
 */
public final class SpiffingProperties {
    private static volatile String enforcementModeOverride;
    private static volatile Boolean stripDefaultOverride;
    private static volatile String outputFormatOverride;

    public static final SystemProperty<String> ENFORCEMENT_MODE =
        SystemProperty.Builder.ofType(String.class)
            .setKey("plugin.spiffing.enforcementMode")
            .setDefaultValue("WARN")
            .setDynamic(true)
            .build();

    public static final SystemProperty<Boolean> STRIP_DEFAULT_LABEL_FOR_FEDERATION =
        SystemProperty.Builder.ofType(Boolean.class)
            .setKey("plugin.spiffing.stripDefaultLabelForFederation")
            .setDefaultValue(false)
            .setDynamic(true)
            .build();

    public static final SystemProperty<String> OUTPUT_FORMAT =
        SystemProperty.Builder.ofType(String.class)
            .setKey("plugin.spiffing.outputFormat")
            .setDefaultValue("ESS")
            .setDynamic(true)
            .build();

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
