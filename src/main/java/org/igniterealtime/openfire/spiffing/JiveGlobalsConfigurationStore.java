package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.util.JiveGlobals;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists settings as individual Openfire properties (backed by the {@code ofProperty} database
 * table, or the standalone XML properties file outside a database installation), the usual idiom for
 * Openfire and its plugins. Each field is a separate {@code JiveGlobals} property, so unlike the
 * previous file-based store, a save is not a single atomic operation: a failure partway through
 * {@link #write} can leave a mix of old and new field values, and {@code JiveGlobals} itself does not
 * surface every underlying persistence failure to the caller.
 * <p>
 * The policy list is persisted manually as a {@code policy.count} property plus one indexed
 * {@code policy.<n>} property per entry, using only the basic scalar {@code JiveGlobals} property
 * methods. {@code JiveGlobals}' own list-property support ({@code setProperty(String, List)}/
 * {@code getProperties(String)}) is deliberately not used here: that overload is not reliably present
 * on every Openfire build this plugin targets, and calling it can fail with a {@link NoSuchMethodError}
 * at save time. On write, any indexed entries left over from a previously larger list are removed, so a
 * shrinking list never leaves stale entries behind. Settings saved before multiple policies were supported
 * instead have a single scalar {@code policy} property with no {@code policy.count}; {@link #read()} falls
 * back to that value, and the first subsequent {@link #write} migrates it to the indexed form.
 */
final class JiveGlobalsConfigurationStore implements ConfigurationService.Store {
    private static final String PREFIX = "plugin.spiffing.settings.";
    private static final String POLICY_COUNT = PREFIX + "policy.count";

    @Override
    public Settings read() {
        List<String> policies;
        int count = JiveGlobals.getIntProperty(POLICY_COUNT, -1);
        if (count < 0) {
            String legacyPolicy = JiveGlobals.getProperty(PREFIX + "policy");
            if (legacyPolicy == null) return null;
            policies = List.of(legacyPolicy);
        } else {
            policies = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String p = JiveGlobals.getProperty(PREFIX + "policy." + i);
                if (p == null) throw new IllegalArgumentException("Stored Spiffing settings are incomplete or corrupted.");
                policies.add(p);
            }
        }
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
            return new Settings(policies, clearance, clearanceFormat, defaultLabel, labelFormat, outputFormat, enforcementMode,
                stripDefaultLabelForFederation, peerClearance, peerClearanceFormat);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Stored Spiffing settings are incomplete or corrupted.", e);
        }
    }

    @Override
    public void write(Settings settings) {
        List<String> policies = settings.policies();
        int previousCount = JiveGlobals.getIntProperty(POLICY_COUNT, 0);
        JiveGlobals.setProperty(POLICY_COUNT, String.valueOf(policies.size()));
        for (int i = 0; i < policies.size(); i++) {
            JiveGlobals.setProperty(PREFIX + "policy." + i, policies.get(i));
        }
        for (int i = policies.size(); i < previousCount; i++) {
            JiveGlobals.deleteProperty(PREFIX + "policy." + i);
        }
        // Superseded by the indexed form above; remove so a stale value is never read back by mistake.
        JiveGlobals.deleteProperty(PREFIX + "policy");
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
