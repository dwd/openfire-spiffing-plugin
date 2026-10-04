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

    @Test void buildsClearanceFromClassificationsAndCategories() {
        io.cridland.spiffing.Site site = new io.cridland.spiffing.Site();
        io.cridland.spiffing.Spif spif = site.load(Fixtures.read("food-policy"));

        io.cridland.spiffing.Clearance clearance = SettingsForm.buildClearance(
            spif,
            java.util.List.of("52", "51"),
            java.util.List.of("1.2.826.0.1.6726289.0.0.1|permissive|3", "1.2.826.0.1.6726289.0.0.2|restrictive|0")
        );

        assertTrue(clearance.hasClassification(52));
        assertTrue(clearance.hasClassification(51));
        assertFalse(clearance.hasClassification(50));
        assertEquals(2, clearance.classifications().size());
        assertEquals(2, clearance.categories().size());

        String xml = LabelFormat.XML.encode(clearance);
        assertTrue(xml.contains("<classification lacv=\"52\"") || xml.contains("<classification lacv='52'"));
        assertTrue(xml.contains("Sweet") || xml.contains("lacv=\"3\"") || xml.contains("lacv='3'"));

        String nato = LabelFormat.NATO.encode(clearance);
        assertTrue(nato.contains("ConfidentialityClearance"));

        String ess = LabelFormat.ESS.encode(clearance);
        assertNotNull(ess);
        assertFalse(ess.isBlank());
    }

    @Test void buildClearanceRejectsInvalidLacvOrCategory() {
        io.cridland.spiffing.Site site = new io.cridland.spiffing.Site();
        io.cridland.spiffing.Spif spif = site.load(Fixtures.read("food-policy"));

        assertThrows(IllegalArgumentException.class, () ->
            SettingsForm.buildClearance(spif, java.util.List.of("not-a-number"), java.util.List.of())
        );

        assertThrows(IllegalArgumentException.class, () ->
            SettingsForm.buildClearance(spif, java.util.List.of(), java.util.List.of("invalid|spec|999"))
        );
    }

    @Test void parsesPolicyInBuilderMode() {
        Map<String, String> fields = new HashMap<>();
        fields.put("spif", Fixtures.read("food-policy"));
        fields.put("clearanceMode", "builder");
        fields.put("clearanceFormat", "XML");

        SecurityPolicy policy = SettingsForm.parsePolicy(
            fields,
            java.util.List.of("52"),
            java.util.List.of("1.2.826.0.1.6726289.0.0.1|permissive|3")
        );

        assertEquals("1.2.826.0.1.6726289.0.0", policy.id());
        assertNotNull(policy.clearance());
        assertEquals(LabelFormat.XML, policy.clearanceFormat());
        assertTrue(policy.clearance().contains("52"));
    }

    @Test void parsesPolicyInBuilderModeWithNoSelections() {
        Map<String, String> fields = new HashMap<>();
        fields.put("spif", Fixtures.read("food-policy"));
        fields.put("clearanceMode", "builder");
        fields.put("clearanceFormat", "XML");

        SecurityPolicy policy = SettingsForm.parsePolicy(
            fields,
            java.util.List.of(),
            java.util.List.of()
        );

        assertEquals("1.2.826.0.1.6726289.0.0", policy.id());
        assertNull(policy.clearance());
        assertNull(policy.clearanceFormat());
    }

    @Test void parsesPeerClearanceInBuilderMode() {
        io.cridland.spiffing.Site site = new io.cridland.spiffing.Site();
        io.cridland.spiffing.Spif spif = site.load(Fixtures.read("food-policy"));

        Map<String, String> fields = new HashMap<>();
        fields.put("domain", "federated.example");
        fields.put("policyId", "1.2.826.0.1.6726289.0.0");
        fields.put("clearanceMode", "builder");
        fields.put("format", "NATO");

        PeerClearance pc = SettingsForm.parsePeerClearance(
            fields,
            java.util.List.of("52"),
            java.util.List.of(),
            spif
        );

        assertEquals("federated.example", pc.domain());
        assertEquals("1.2.826.0.1.6726289.0.0", pc.policyId());
        assertEquals(LabelFormat.NATO, pc.format());
        assertTrue(pc.clearance().contains("ConfidentialityClearance"));
    }

    @Test void parsesPeerClearanceInBuilderModeWithNoSelectionsThrows() {
        io.cridland.spiffing.Site site = new io.cridland.spiffing.Site();
        io.cridland.spiffing.Spif spif = site.load(Fixtures.read("food-policy"));

        Map<String, String> fields = new HashMap<>();
        fields.put("domain", "federated.example");
        fields.put("policyId", "1.2.826.0.1.6726289.0.0");
        fields.put("clearanceMode", "builder");
        fields.put("format", "XML");

        assertThrows(IllegalArgumentException.class, () ->
            SettingsForm.parsePeerClearance(fields, java.util.List.of(), java.util.List.of(), spif)
        );
    }

    @Test void inspectsPolicyAndExtractsMetadata() {
        SecurityPolicy policy = Fixtures.foodPolicy();
        SettingsForm.PolicyClearanceMetadata meta = SettingsForm.inspectPolicy(policy);

        assertNotNull(meta);
        assertEquals("1.2.826.0.1.6726289.0.0", meta.policyId());
        assertEquals("Food", meta.policyName());
        assertFalse(meta.classifications().isEmpty());
        assertFalse(meta.tagSets().isEmpty());
        assertTrue(meta.activeClassificationLacvs().contains("52"));
        assertTrue(meta.activeClassificationLacvs().contains("51"));
        assertNotNull(meta.displayMarking());
        assertEquals("52", meta.activeLabelClassificationLacv());
        assertTrue(meta.activeLabelCategorySpecs().contains("1.2.826.0.1.6726289.0.0.1|permissive|3"));
        assertNotNull(meta.defaultLabelDisplayMarking());
    }

    @Test void buildsLabelFromClassificationAndCategories() {
        io.cridland.spiffing.Site site = new io.cridland.spiffing.Site();
        io.cridland.spiffing.Spif spif = site.load(Fixtures.read("food-policy"));

        io.cridland.spiffing.Label label = SettingsForm.buildLabel(
            spif,
            "52",
            java.util.List.of("1.2.826.0.1.6726289.0.0.1|permissive|3", "1.2.826.0.1.6726289.0.0.2|restrictive|0")
        );

        assertNotNull(label.classification());
        assertEquals("52", label.classification().lacv().toString());
        assertEquals(2, label.categories().size());

        String xml = LabelFormat.XML.encode(label);
        assertTrue(xml.contains("52"));
        assertTrue(xml.contains("Sweet") || xml.contains("lacv=\"3\"") || xml.contains("lacv='3'"));

        String nato = LabelFormat.NATO.encode(label);
        assertTrue(nato.contains("ConfidentialityInformation"));

        String ess = LabelFormat.ESS.encode(label);
        assertNotNull(ess);
        assertFalse(ess.isBlank());
    }

    @Test void buildLabelRejectsMissingOrInvalidClassificationOrCategory() {
        io.cridland.spiffing.Site site = new io.cridland.spiffing.Site();
        io.cridland.spiffing.Spif spif = site.load(Fixtures.read("food-policy"));

        assertThrows(IllegalArgumentException.class, () ->
            SettingsForm.buildLabel(spif, null, java.util.List.of())
        );

        assertThrows(IllegalArgumentException.class, () ->
            SettingsForm.buildLabel(spif, "   ", java.util.List.of())
        );

        assertThrows(IllegalArgumentException.class, () ->
            SettingsForm.buildLabel(spif, "invalid-lacv", java.util.List.of())
        );

        assertThrows(IllegalArgumentException.class, () ->
            SettingsForm.buildLabel(spif, "52", java.util.List.of("nonexistent|permissive|999"))
        );
    }

    @Test void parsesPolicyInLabelBuilderMode() {
        Map<String, String> fields = new HashMap<>();
        fields.put("spif", Fixtures.read("food-policy"));
        fields.put("clearanceMode", "raw");
        fields.put("defaultLabelMode", "builder");
        fields.put("defaultLabelFormat", "NATO");

        SecurityPolicy policy = SettingsForm.parsePolicy(
            fields,
            java.util.List.of(),
            java.util.List.of(),
            "52",
            java.util.List.of("1.2.826.0.1.6726289.0.0.1|permissive|3")
        );

        assertEquals("1.2.826.0.1.6726289.0.0", policy.id());
        assertNotNull(policy.defaultLabel());
        assertEquals(LabelFormat.NATO, policy.defaultLabelFormat());
        assertTrue(policy.defaultLabel().contains("ConfidentialityInformation"));
    }

    @Test void parsesPolicyInLabelBuilderModeWithNoSelection() {
        Map<String, String> fields = new HashMap<>();
        fields.put("spif", Fixtures.read("food-policy"));
        fields.put("defaultLabelMode", "builder");
        fields.put("defaultLabelFormat", "XML");

        SecurityPolicy policy = SettingsForm.parsePolicy(
            fields,
            java.util.List.of(),
            java.util.List.of(),
            "",
            java.util.List.of()
        );

        assertEquals("1.2.826.0.1.6726289.0.0", policy.id());
        assertNull(policy.defaultLabel());
        assertNull(policy.defaultLabelFormat());
    }
}
