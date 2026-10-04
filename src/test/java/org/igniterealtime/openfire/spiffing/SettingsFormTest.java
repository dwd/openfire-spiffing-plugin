package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettingsFormTest {
    @ParameterizedTest @CsvSource({"GET,token,token", "POST,,token", "POST,token,", "POST,token,wrong", "POST,'',''"})
    void csrfOrMethodFailureThrows(String method, String cookie, String token) {
        assertThrows(IllegalArgumentException.class, () -> SettingsForm.validateCsrf(method, cookie, token));
    }

    @Test void validCsrfPasses() {
        assertDoesNotThrow(() -> SettingsForm.validateCsrf("POST", "secret", "secret"));
    }

    @Test void parsesValidPolicy() {
        Map<String, String> fields = new HashMap<>();
        fields.put("spif", Fixtures.read("food-policy"));
        fields.put("clearance", Fixtures.read("food-clearance-all-okay"));
        fields.put("clearanceFormat", "XML");
        fields.put("defaultLabel", Fixtures.read("food-label-milk-chocolate"));
        fields.put("defaultLabelFormat", "XML");
        fields.put("isDefault", "true");

        SecurityPolicy policy = SettingsForm.parsePolicy(fields);
        assertEquals("1.2.826.0.1.6726289.0.0", policy.id());
        assertEquals("Food", policy.name());
        assertEquals(Fixtures.read("food-clearance-all-okay"), policy.clearance());
        assertEquals(LabelFormat.XML, policy.clearanceFormat());
        assertEquals(Fixtures.read("food-label-milk-chocolate"), policy.defaultLabel());
        assertEquals(LabelFormat.XML, policy.defaultLabelFormat());
        assertTrue(policy.isDefault());
    }

    @Test void parsesPolicyWithNullClearanceAndDefaultLabel() {
        Map<String, String> fields = new HashMap<>();
        fields.put("spif", Fixtures.read("food-policy"));

        SecurityPolicy policy = SettingsForm.parsePolicy(fields);
        assertEquals("1.2.826.0.1.6726289.0.0", policy.id());
        assertEquals("Food", policy.name());
        assertNull(policy.clearance());
        assertNull(policy.clearanceFormat());
        assertNull(policy.defaultLabel());
        assertNull(policy.defaultLabelFormat());
        assertFalse(policy.isDefault());
    }

    @Test void invalidSpifThrows() {
        Map<String, String> fields = new HashMap<>();
        fields.put("spif", "not a valid spif");
        assertThrows(IllegalArgumentException.class, () -> SettingsForm.parsePolicy(fields));
    }

    @Test void missingOrBlankSpifThrows() {
        Map<String, String> missing = new HashMap<>();
        assertThrows(IllegalArgumentException.class, () -> SettingsForm.parsePolicy(missing));

        Map<String, String> empty = new HashMap<>();
        empty.put("spif", "");
        assertThrows(IllegalArgumentException.class, () -> SettingsForm.parsePolicy(empty));

        Map<String, String> blank = new HashMap<>();
        blank.put("spif", "   \n\t  ");
        assertThrows(IllegalArgumentException.class, () -> SettingsForm.parsePolicy(blank));
    }

    @Test void parsesPolicyWithSurroundingWhitespace() {
        Map<String, String> fields = new HashMap<>();
        fields.put("spif", "\n  " + Fixtures.read("food-policy") + "\n  ");

        SecurityPolicy policy = SettingsForm.parsePolicy(fields);
        assertEquals("1.2.826.0.1.6726289.0.0", policy.id());
        assertEquals("Food", policy.name());
    }

    @Test void parsesPeerClearance() {
        Map<String, String> fields = new HashMap<>();
        fields.put("domain", "example.com");
        fields.put("policyId", "1.3.6.1.4.1.5923.99.1");
        fields.put("clearance", Fixtures.read("food-clearance-all-okay"));
        fields.put("format", "XML");

        PeerClearance pc = SettingsForm.parsePeerClearance(fields);
        assertEquals("example.com", pc.domain());
        assertEquals("1.3.6.1.4.1.5923.99.1", pc.policyId());
        assertEquals(Fixtures.read("food-clearance-all-okay"), pc.clearance());
        assertEquals(LabelFormat.XML, pc.format());
        assertNotNull(pc.id());
    }
}
