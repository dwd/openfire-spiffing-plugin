package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CatalogEntryTest {
    @Test void blankOrMissingSelectorIsNormalizedToNull() {
        assertNull(new CatalogEntry("id", "Public", null, LabelFormat.XML, "x", false).selector());
        assertNull(new CatalogEntry("id", "Public", "", LabelFormat.XML, "x", false).selector());
    }

    @Test void validSelectorIsPreserved() {
        assertEquals("Classified|SECRET", new CatalogEntry("id", "Secret", "Classified|SECRET", LabelFormat.XML, "x", false).selector());
    }

    @ParameterizedTest @ValueSource(strings = {"|", "Classified|", "|SECRET", "Classified||SECRET"})
    void rejectsMalformedSelector(String selector) {
        assertThrows(IllegalArgumentException.class, () -> new CatalogEntry("id", "Secret", selector, LabelFormat.XML, "x", false));
    }

    @Test void rejectsBlankOrOversizedName() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogEntry("id", " ", null, LabelFormat.XML, "x", false));
        assertThrows(IllegalArgumentException.class, () -> new CatalogEntry("id", "x".repeat(CatalogEntry.MAX_NAME + 1), null, LabelFormat.XML, "x", false));
    }

    @Test void rejectsBlankOrOversizedPayload() {
        assertThrows(IllegalArgumentException.class, () -> new CatalogEntry("id", "Public", null, LabelFormat.XML, " ", false));
        assertThrows(IllegalArgumentException.class, () -> new CatalogEntry("id", "Public", null, LabelFormat.XML, "x".repeat(SecureXml.MAX_LABEL + 1), false));
    }

    @Test void requiresIdAndFormat() {
        assertThrows(NullPointerException.class, () -> new CatalogEntry(null, "Public", null, LabelFormat.XML, "x", false));
        assertThrows(NullPointerException.class, () -> new CatalogEntry("id", "Public", null, null, "x", false));
    }
}
