package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecurityPolicyTest {
    @Test void validatesRequiredFields() {
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy(null, "Name", Fixtures.read("food-policy"), null, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("", "Name", Fixtures.read("food-policy"), null, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("id", null, Fixtures.read("food-policy"), null, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("id", "", Fixtures.read("food-policy"), null, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("id", "Name", null, null, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("id", "Name", "", null, null, null, null, false));
    }

    @Test void allowsNullableClearanceAndDefaultLabel() {
        var policy = new SecurityPolicy("id", "Name", Fixtures.read("food-policy"), null, null, null, null, false);
        assertNull(policy.clearance());
        assertNull(policy.clearanceFormat());
        assertNull(policy.defaultLabel());
        assertNull(policy.defaultLabelFormat());
        assertFalse(policy.isDefault());
    }

    @Test void requiresFormatWhenClearanceOrDefaultLabelProvided() {
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("id", "Name", Fixtures.read("food-policy"), "clearance", null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("id", "Name", Fixtures.read("food-policy"), null, null, "label", null, false));
    }

    @Test void boundsSize() {
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("id", "Name", "x".repeat(SecureXml.MAX_DOCUMENT + 1), null, null, null, null, false));
        assertThrows(IllegalArgumentException.class, () -> new SecurityPolicy("id", "Name", Fixtures.read("food-policy"), "x".repeat(SecureXml.MAX_LABEL + 1), LabelFormat.XML, null, null, false));
    }
}
