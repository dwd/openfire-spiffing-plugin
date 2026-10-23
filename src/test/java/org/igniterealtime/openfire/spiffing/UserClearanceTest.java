package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserClearanceTest {
    @Test void constructsAValidUserClearance() {
        var uc = new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        assertEquals("alice", uc.username());
        assertEquals("1.2.826.0.1.6726289.0.0", uc.policyId());
        assertEquals(Fixtures.read("food-clearance-all-okay"), uc.clearance());
        assertEquals(LabelFormat.XML, uc.format());
    }

    @Test void rejectsMissingUsername() {
        assertThrows(IllegalArgumentException.class, () ->
            new UserClearance(null, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        assertThrows(IllegalArgumentException.class, () ->
            new UserClearance("  ", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
    }

    @Test void rejectsMissingPolicyId() {
        assertThrows(IllegalArgumentException.class, () ->
            new UserClearance("alice", null, Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        assertThrows(IllegalArgumentException.class, () ->
            new UserClearance("alice", "", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
    }

    @Test void rejectsMissingClearance() {
        assertThrows(IllegalArgumentException.class, () ->
            new UserClearance("alice", "1.2.826.0.1.6726289.0.0", null, LabelFormat.XML));
        assertThrows(IllegalArgumentException.class, () ->
            new UserClearance("alice", "1.2.826.0.1.6726289.0.0", "   ", LabelFormat.XML));
    }

    @Test void rejectsMissingFormat() {
        assertThrows(IllegalArgumentException.class, () ->
            new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), null));
    }

    @Test void rejectsOversizedClearance() {
        String huge = "a".repeat(SecureXml.MAX_LABEL + 1);
        assertThrows(IllegalArgumentException.class, () ->
            new UserClearance("alice", "1.2.826.0.1.6726289.0.0", huge, LabelFormat.XML));
    }
}
