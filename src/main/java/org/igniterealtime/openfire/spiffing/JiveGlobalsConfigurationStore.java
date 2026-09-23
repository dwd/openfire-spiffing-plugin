package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.util.JiveGlobals;

/**
 * Persists settings as individual Openfire properties (backed by the {@code ofProperty} database
 * table, or the standalone XML properties file outside a database installation), the usual idiom for
 * Openfire and its plugins. Each field is a separate {@code JiveGlobals} property, so unlike the
 * previous file-based store, a save is not a single atomic operation: a failure partway through
 * {@link #write} can leave a mix of old and new field values, and {@code JiveGlobals} itself does not
 * surface every underlying persistence failure to the caller.
 */
final class JiveGlobalsConfigurationStore implements ConfigurationService.Store {
    private static final String PREFIX = "plugin.spiffing.settings.";

    @Override
    public Settings read() {
        String policy = JiveGlobals.getProperty(PREFIX + "policy");
        if (policy == null) return null;
        try {
            String clearance = require("clearance");
            LabelFormat clearanceFormat = LabelFormat.valueOf(require("clearanceFormat"));
            String defaultLabel = require("defaultLabel");
            LabelFormat labelFormat = LabelFormat.valueOf(require("labelFormat"));
            LabelFormat outputFormat = LabelFormat.valueOf(require("outputFormat"));
            // Properties saved before the enforcement switch existed have none; default to the safe "warn" mode.
            String enforcement = JiveGlobals.getProperty(PREFIX + "enforcementMode");
            EnforcementMode enforcementMode = enforcement == null ? EnforcementMode.WARN : EnforcementMode.valueOf(enforcement);
            // Properties saved before this switch existed have none; default to not stripping.
            boolean stripDefaultLabelForFederation = JiveGlobals.getBooleanProperty(PREFIX + "stripDefaultLabelForFederation", false);
            // Properties saved before this switch existed have none; default to no configured peer clearance.
            String peerClearance = JiveGlobals.getProperty(PREFIX + "peerClearance", "");
            String peerClearanceFormatName = JiveGlobals.getProperty(PREFIX + "peerClearanceFormat");
            LabelFormat peerClearanceFormat = peerClearanceFormatName == null ? LabelFormat.ESS : LabelFormat.valueOf(peerClearanceFormatName);
            return new Settings(policy, clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, enforcementMode,
                stripDefaultLabelForFederation, peerClearance, peerClearanceFormat);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Stored Spiffing settings are incomplete or corrupted.", e);
        }
    }

    @Override
    public void write(Settings settings) {
        JiveGlobals.setProperty(PREFIX + "policy", settings.policy());
        JiveGlobals.setProperty(PREFIX + "clearance", settings.clearance());
        JiveGlobals.setProperty(PREFIX + "clearanceFormat", settings.clearanceFormat().name());
        JiveGlobals.setProperty(PREFIX + "defaultLabel", settings.defaultLabel());
        JiveGlobals.setProperty(PREFIX + "labelFormat", settings.labelFormat().name());
        JiveGlobals.setProperty(PREFIX + "outputFormat", settings.outputFormat().name());
        JiveGlobals.setProperty(PREFIX + "enforcementMode", settings.enforcementMode().name());
        JiveGlobals.setProperty(PREFIX + "stripDefaultLabelForFederation", Boolean.toString(settings.stripDefaultLabelForFederation()));
        JiveGlobals.setProperty(PREFIX + "peerClearance", settings.peerClearance());
        JiveGlobals.setProperty(PREFIX + "peerClearanceFormat", settings.peerClearanceFormat().name());
    }

    private static String require(String key) {
        String value = JiveGlobals.getProperty(PREFIX + key);
        if (value == null) throw new IllegalArgumentException("Missing Spiffing settings property: " + key);
        return value;
    }
}
