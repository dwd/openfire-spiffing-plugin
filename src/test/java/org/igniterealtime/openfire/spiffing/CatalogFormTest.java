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
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("GET", "token", "token", validFields(), service));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", "token", "other", validFields(), service));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", null, "token", validFields(), service));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", "token", "", validFields(), service));
    }

    @Test void addsAValidEntryAndDefaultsBlankSelectorToNull() {
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        var fields = validFields();
        fields.put("selector", " ");
        CatalogForm.add("POST", "token", "token", fields, service);
        assertEquals(1, service.entries().size());
        assertNull(service.entries().get(0).selector());
        assertTrue(service.entries().get(0).isDefault());
    }

    @Test void addsValidEntryWithOmittedOrBlankNameInRawMode() {
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        var fields = validFields();
        fields.remove("name");
        CatalogForm.add("POST", "token", "token", fields, service);
        assertEquals(1, service.entries().size());
        assertEquals("[ Food Sweet/Yummy Chocolate Brown Contains Lactose Not Vegan Animal Rights ]", service.entries().get(0).name());

        fields.put("name", "   ");
        CatalogForm.add("POST", "token", "token", fields, service);
        assertEquals(2, service.entries().size());
        assertEquals("[ Food Sweet/Yummy Chocolate Brown Contains Lactose Not Vegan Animal Rights ]", service.entries().get(1).name());
    }

    @Test void invalidFormatOrMissingFieldsFailValidation() {
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        var fields = validFields();
        fields.put("format", "NOT_A_FORMAT");
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", "token", "token", fields, service));
        assertTrue(service.entries().isEmpty());
    }

    @Test void rejectsALabelDeniedByThePolicy() {
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        var fields = validFields();
        fields.put("label", Fixtures.read("food-label-bacon"));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.add("POST", "token", "token", fields, service));
        assertTrue(service.entries().isEmpty());
    }

    @Test void removeRequiresCsrfAndAnId() {
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        CatalogForm.add("POST", "token", "token", validFields(), service);
        var id = service.entries().get(0).id();
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.remove("POST", "token", "other", id, service));
        assertThrows(IllegalArgumentException.class, () -> CatalogForm.remove("POST", "token", "token", null, service));
        assertEquals(1, service.entries().size());
        CatalogForm.remove("POST", "token", "token", id, service);
        assertTrue(service.entries().isEmpty());
    }

    @Test void addsValidEntryInBuilderMode() {
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        io.cridland.spiffing.Site site = new io.cridland.spiffing.Site();
        io.cridland.spiffing.Spif spif = site.load(Fixtures.read("food-policy"));

        Map<String, String> fields = new HashMap<>();
        fields.put("name", "Sweet treat");
        fields.put("selector", "Food|Sweet");
        fields.put("format", "XML");
        fields.put("mode", "builder");
        fields.put("classification", "52");
        fields.put("isDefault", "false");

        CatalogForm.add("POST", "token", "token", fields,
            java.util.List.of("1.2.826.0.1.6726289.0.0.1|permissive|3"),
            spif,
            service
        );

        assertEquals(1, service.entries().size());
        CatalogEntry entry = service.entries().get(0);
        assertEquals("Sweet treat", entry.name());
        assertEquals("Food|Sweet", entry.selector());
        assertEquals(LabelFormat.XML, entry.format());
        assertFalse(entry.isDefault());
        assertTrue(entry.payload().contains("52"));
    }

    @Test void addsValidEntryWithOmittedOrBlankNameInBuilderMode() {
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        io.cridland.spiffing.Site site = new io.cridland.spiffing.Site();
        io.cridland.spiffing.Spif spif = site.load(Fixtures.read("food-policy"));

        Map<String, String> fields = new HashMap<>();
        fields.put("selector", "Food|Sweet");
        fields.put("format", "XML");
        fields.put("mode", "builder");
        fields.put("classification", "52");
        fields.put("isDefault", "false");

        CatalogForm.add("POST", "token", "token", fields,
            java.util.List.of("1.2.826.0.1.6726289.0.0.1|permissive|3"),
            spif,
            service
        );

        assertEquals(1, service.entries().size());
        CatalogEntry entry = service.entries().get(0);
        assertEquals("[ Food Luxury Sweet ]", entry.name());
    }

    @Test void builderModeRejectsMissingClassificationOrNullSpif() {
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        io.cridland.spiffing.Site site = new io.cridland.spiffing.Site();
        io.cridland.spiffing.Spif spif = site.load(Fixtures.read("food-policy"));

        Map<String, String> fieldsWithoutCls = new HashMap<>();
        fieldsWithoutCls.put("name", "Test");
        fieldsWithoutCls.put("format", "XML");
        fieldsWithoutCls.put("mode", "builder");
        fieldsWithoutCls.put("classification", "");

        assertThrows(IllegalArgumentException.class, () ->
            CatalogForm.add("POST", "token", "token", fieldsWithoutCls, java.util.List.of(), spif, service)
        );

        Map<String, String> validCls = new HashMap<>();
        validCls.put("name", "Test");
        validCls.put("format", "XML");
        validCls.put("mode", "builder");
        validCls.put("classification", "52");

        assertThrows(IllegalArgumentException.class, () ->
            CatalogForm.add("POST", "token", "token", validCls, java.util.List.of(), null, service)
        );
    }

    @Test void rawModeRejectsMissingLabelPayload() {
        var service = new CatalogService(new CatalogServiceTest.Store(), Fixtures::configuration);
        Map<String, String> fields = new HashMap<>();
        fields.put("name", "Test");
        fields.put("format", "XML");
        fields.put("mode", "raw");
        fields.put("label", "");

        assertThrows(IllegalArgumentException.class, () ->
            CatalogForm.add("POST", "token", "token", fields, service)
        );
    }
}
