package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomLabelTest {
    @Test void constructsAValidRoomLabelWithoutAnOwnDefault() {
        var rl = new RoomLabel("room@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        assertEquals("room@conference.example.com", rl.roomJid());
        assertEquals("1.2.826.0.1.6726289.0.0", rl.policyId());
        assertEquals(Fixtures.read("food-label-milk-chocolate"), rl.label());
        assertEquals(LabelFormat.XML, rl.format());
        assertFalse(rl.hasOwnDefaultLabel());
        assertNull(rl.defaultLabel());
        assertNull(rl.defaultLabelFormat());
    }

    @Test void constructsAValidRoomLabelWithItsOwnDistinctDefault() {
        var rl = new RoomLabel(
            "room@conference.example.com", "1.2.826.0.1.6726289.0.0",
            Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML,
            Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML
        );
        assertTrue(rl.hasOwnDefaultLabel());
        assertEquals(Fixtures.read("food-label-milk-chocolate"), rl.defaultLabel());
        assertEquals(LabelFormat.XML, rl.defaultLabelFormat());
    }

    @Test void blankDefaultLabelIsTreatedAsUnset() {
        var rl = new RoomLabel("room@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, "   ", LabelFormat.XML);
        assertFalse(rl.hasOwnDefaultLabel());
        assertNull(rl.defaultLabel());
        assertNull(rl.defaultLabelFormat());
    }

    @Test void rejectsADefaultLabelWithoutAFormat() {
        assertThrows(IllegalArgumentException.class, () ->
            new RoomLabel("room@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), null));
    }

    @Test void rejectsMissingRoomJid() {
        assertThrows(IllegalArgumentException.class, () ->
            new RoomLabel(null, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null));
    }

    @Test void rejectsMissingPolicyId() {
        assertThrows(IllegalArgumentException.class, () ->
            new RoomLabel("room@conference.example.com", null, Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null));
    }

    @Test void rejectsMissingLabel() {
        assertThrows(IllegalArgumentException.class, () ->
            new RoomLabel("room@conference.example.com", "1.2.826.0.1.6726289.0.0", null, LabelFormat.XML, null, null));
        assertThrows(IllegalArgumentException.class, () ->
            new RoomLabel("room@conference.example.com", "1.2.826.0.1.6726289.0.0", "  ", LabelFormat.XML, null, null));
    }

    @Test void rejectsMissingFormat() {
        assertThrows(IllegalArgumentException.class, () ->
            new RoomLabel("room@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), null, null, null));
    }

    @Test void rejectsOversizedLabel() {
        String huge = "a".repeat(SecureXml.MAX_LABEL + 1);
        assertThrows(IllegalArgumentException.class, () ->
            new RoomLabel("room@conference.example.com", "1.2.826.0.1.6726289.0.0", huge, LabelFormat.XML, null, null));
    }
}
