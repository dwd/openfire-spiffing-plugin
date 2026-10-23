package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomClearanceTest {
    @Test void constructsAValidRoomClearance() {
        var rc = new RoomClearance("room@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        assertEquals("room@conference.example.com", rc.roomJid());
        assertEquals("1.2.826.0.1.6726289.0.0", rc.policyId());
        assertEquals(Fixtures.read("food-clearance-all-okay"), rc.clearance());
        assertEquals(LabelFormat.XML, rc.format());
    }

    @Test void rejectsMissingRoomJid() {
        assertThrows(IllegalArgumentException.class, () ->
            new RoomClearance(null, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        assertThrows(IllegalArgumentException.class, () ->
            new RoomClearance("  ", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
    }

    @Test void rejectsMissingPolicyId() {
        assertThrows(IllegalArgumentException.class, () ->
            new RoomClearance("room@conference.example.com", null, Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        assertThrows(IllegalArgumentException.class, () ->
            new RoomClearance("room@conference.example.com", "", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
    }

    @Test void rejectsMissingClearance() {
        assertThrows(IllegalArgumentException.class, () ->
            new RoomClearance("room@conference.example.com", "1.2.826.0.1.6726289.0.0", null, LabelFormat.XML));
        assertThrows(IllegalArgumentException.class, () ->
            new RoomClearance("room@conference.example.com", "1.2.826.0.1.6726289.0.0", "   ", LabelFormat.XML));
    }

    @Test void rejectsMissingFormat() {
        assertThrows(IllegalArgumentException.class, () ->
            new RoomClearance("room@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), null));
    }

    @Test void rejectsOversizedClearance() {
        String huge = "a".repeat(SecureXml.MAX_LABEL + 1);
        assertThrows(IllegalArgumentException.class, () ->
            new RoomClearance("room@conference.example.com", "1.2.826.0.1.6726289.0.0", huge, LabelFormat.XML));
    }
}
