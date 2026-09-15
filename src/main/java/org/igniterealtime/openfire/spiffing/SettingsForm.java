package org.igniterealtime.openfire.spiffing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Consumer;

/** Validates the write request before invoking any configuration mutation. */
public final class SettingsForm {
    private SettingsForm() {}

    public static void save(String method, String cookie, String token, Map<String, String> fields, Consumer<Settings> save) {
        if (!"POST".equals(method) || cookie == null || token == null || token.isEmpty()
            || !MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("The form expired. Please try again.");
        }
        Settings settings;
        try {
            // Missing field (e.g. a form saved before this switch existed) defaults to the safe "warn" mode.
            String enforcement = fields.get("enforcementMode");
            EnforcementMode enforcementMode = enforcement == null ? EnforcementMode.WARN : EnforcementMode.valueOf(enforcement);
            settings = new Settings(fields.get("policy"), fields.get("clearance"), LabelFormat.valueOf(fields.get("clearanceFormat")),
                fields.get("label"), LabelFormat.valueOf(fields.get("labelFormat")), LabelFormat.valueOf(fields.get("outputFormat")),
                enforcementMode);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Provide all documents and select valid formats.");
        }
        save.accept(settings);
    }
}
