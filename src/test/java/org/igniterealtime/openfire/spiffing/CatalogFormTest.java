package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CatalogFormTest {
    private static Map<String, String> validFields() {
        Map<String, String> fields = new HashMap<>();
        fields.put("name", "Milk chocolate");
        fields.put("selector", "Food|Chocolate");
        fields.put("format", "XML");
        fields.put("label", Fixtures.read("food-label-milk-chocolate"));
        fields.put("isDefault", "true");
        return fields;
    }

    @Test void rejectsWrongMethodOrMismatchedCsrf() {
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> new PolicyConfiguration(Fixtures.settings()));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("GET", "token", "token", validFields(), service));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", "token", "other", validFields(), service));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", null, "token", validFields(), service));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", "token", "", validFields(), service));
    }

    @Test void addsAValidEntryAndDefaultsBlankSelectorToNull() {
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> new PolicyConfiguration(Fixtures.settings()));
        var fields = validFields();
        fields.put("selector", " ");
        CatalogForm.add("POST", "token", "token", fields, service);
        assertEquals(1, service.entries().size());
        assertNull(service.entries().get(0).selector());
        assertTrue(service.entries().get(0).isDefault());
    }

    @Test void invalidFormatOrMissingFieldsFailValidation() {
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> new PolicyConfiguration(Fixtures.settings()));
        var fields = validFields();
        fields.put("format", "NOT_A_FORMAT");
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", "token", "token", fields, service));
        assertTrue(service.entries().isEmpty());
    }

    @Test void rejectsALabelDeniedByThePolicy() {
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> new PolicyConfiguration(Fixtures.settings()));
        var fields = validFields();
        fields.put("label", Fixtures.read("food-label-bacon"));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", "token", "token", fields, service));
        assertTrue(service.entries().isEmpty());
    }

    @Test void removeRequiresCsrfAndAnId() {
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> new PolicyConfiguration(Fixtures.settings()));
        CatalogForm.add("POST", "token", "token", validFields(), service);
        var id = service.entries().get(0).id();
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.remove("POST", "token", "other", id, service));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.remove("POST", "token", "token", null, service));
        assertEquals(1, service.entries().size());
        CatalogForm.remove("POST", "token", "token", id, service);
        assertTrue(service.entries().isEmpty());
    }
}
