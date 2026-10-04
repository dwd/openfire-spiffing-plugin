package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Site;
import io.cridland.spiffing.Spif;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.UUID;

/**
 * Validates form submissions from the Admin Console settings page.
 */
public final class SettingsForm {
    private SettingsForm() {}

    public static void validateCsrf(String method, String cookie, String token) {
        if (!"POST".equals(method) || cookie == null || token == null || token.isEmpty()
            || !MessageDigest.isEqual(cookie.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("The form expired. Please try again.");
        }
    }

    public static SecurityPolicy parsePolicy(Map<String, String> fields) {
        String spif = fields.get("spif");
        if (spif == null || spif.isBlank()) {
            throw new IllegalArgumentException("SPIF document is required.");
        }
        Site site = new Site();
        Spif s;
        try {
            s = site.load(spif);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The document is not a valid Open XML SPIF.", e);
        }

        String clearance = fields.get("clearance");
        if (clearance != null && clearance.isBlank()) clearance = null;
        String clearanceFormatStr = fields.get("clearanceFormat");
        LabelFormat clearanceFormat = clearance != null && clearanceFormatStr != null && !clearanceFormatStr.isBlank()
            ? LabelFormat.valueOf(clearanceFormatStr) : null;

        String defaultLabel = fields.get("defaultLabel");
        if (defaultLabel != null && defaultLabel.isBlank()) defaultLabel = null;
        String defaultLabelFormatStr = fields.get("defaultLabelFormat");
        LabelFormat defaultLabelFormat = defaultLabel != null && defaultLabelFormatStr != null && !defaultLabelFormatStr.isBlank()
            ? LabelFormat.valueOf(defaultLabelFormatStr) : null;

        boolean isDefault = Boolean.parseBoolean(fields.get("isDefault"));

        return new SecurityPolicy(s.policyId(), s.name(), spif, clearance, clearanceFormat, defaultLabel, defaultLabelFormat, isDefault);
    }

    public static PeerClearance parsePeerClearance(Map<String, String> fields) {
        String domain = fields.get("domain");
        if (domain == null) domain = "";
        String policyId = fields.get("policyId");
        if (policyId == null || policyId.isBlank()) {
            throw new IllegalArgumentException("Policy selection is required for peer clearance.");
        }
        String clearance = fields.get("clearance");
        if (clearance == null || clearance.isBlank()) {
            throw new IllegalArgumentException("Clearance document is required.");
        }
        String formatStr = fields.get("format");
        LabelFormat format = formatStr != null && !formatStr.isBlank() ? LabelFormat.valueOf(formatStr) : LabelFormat.ESS;
        String id = UUID.randomUUID().toString();
        return new PeerClearance(id, domain.trim(), policyId.trim(), clearance, format);
    }
}
