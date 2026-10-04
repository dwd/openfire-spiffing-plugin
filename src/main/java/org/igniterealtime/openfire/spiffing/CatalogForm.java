package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Label;
import io.cridland.spiffing.Spif;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collection;
import java.util.Map;

/** Validates catalogue admin console requests before invoking any mutation, mirroring {@link SettingsForm}. */
public final class CatalogForm {
    private CatalogForm() {}

    public static void add(String method, String cookie, String token, Map<String, String> fields, CatalogService service) {
        add(method, cookie, token, fields, null, null, service);
    }

    public static void add(
        String method,
        String cookie,
        String token,
        Map<String, String> fields,
        Collection<String> categories,
        Spif spif,
        CatalogService service
    ) {
        verifyCsrf(method, cookie, token);
        String name = fields.get("name");
        String selector = fields.get("selector");
        if (selector != null && selector.isBlank()) selector = null;
        boolean isDefault = "true".equals(fields.get("isDefault"));

        String mode = fields.get("mode");
        if (mode == null || mode.isBlank()) mode = fields.get("labelMode");

        String formatStr = fields.get("format");
        LabelFormat format = formatStr != null && !formatStr.isBlank() ? LabelFormat.valueOf(formatStr) : LabelFormat.ESS;
        String labelPayload;

        if ("builder".equalsIgnoreCase(mode)) {
            String classificationLacv = fields.get("classification");
            if (classificationLacv == null || classificationLacv.isBlank()) {
                throw new IllegalArgumentException("A classification must be selected for the catalogue entry in builder mode.");
            }
            if (spif == null) {
                throw new IllegalArgumentException("A security policy is required to build a catalogue label.");
            }
            Label label = SettingsForm.buildLabel(spif, classificationLacv, categories);
            labelPayload = format.encode(label);
        } else {
            labelPayload = fields.get("label");
            if (labelPayload == null || labelPayload.isBlank()) {
                throw new IllegalArgumentException("Label payload is required.");
            }
        }

        try {
            service.add(name, selector, format, labelPayload, isDefault);
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
