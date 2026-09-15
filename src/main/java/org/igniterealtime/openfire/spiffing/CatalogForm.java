package org.igniterealtime.openfire.spiffing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Validates catalogue admin console requests before invoking any mutation, mirroring {@link SettingsForm}. */
public final class CatalogForm {
    private CatalogForm() {}

    public static void add(String method, String cookie, String token, Map<String, String> fields, CatalogService service) {
        verifyCsrf(method, cookie, token);
        String selector = fields.get("selector");
        if (selector != null && selector.isBlank()) selector = null;
        boolean isDefault = "true".equals(fields.get("isDefault"));
        try {
            service.add(fields.get("name"), selector, LabelFormat.valueOf(fields.get("format")), fields.get("label"), isDefault);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Provide a name, a valid format, and a label that passes the policy and clearance.");
        }
    }

    public static void remove(String method, String cookie, String token, String id, CatalogService service) {
        verifyCsrf(method, cookie, token);
        if (id == null || id.isEmpty()) throw new IllegalArgumentException("No catalogue entry was selected.");
        service.remove(id);
    }

    private static void verifyCsrf(String method, String cookie, String token) {
        if (!"POST".equals(method) || cookie == null || token == null || token.isEmpty()
            || !MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("The form expired. Please try again.");
        }
    }
}
