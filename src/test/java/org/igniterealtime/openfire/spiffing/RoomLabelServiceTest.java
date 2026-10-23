package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RoomLabelServiceTest {
    static final class InMemoryRoomLabelStore implements RoomLabelStore {
        final List<RoomLabel> stored = new ArrayList<>();

        @Override
        public Optional<RoomLabel> get(String roomJid) {
            return stored.stream().filter(rl -> rl.roomJid().equals(roomJid)).findFirst();
        }

        @Override
        public void save(RoomLabel roomLabel) {
            stored.removeIf(rl -> rl.roomJid().equals(roomLabel.roomJid()));
            stored.add(roomLabel);
        }

        @Override
        public void delete(String roomJid) {
            stored.removeIf(rl -> rl.roomJid().equals(roomJid));
        }
    }

    @Test void emptyOrNullRoomJidReturnsEmpty() {
        var service = new RoomLabelService(new InMemoryRoomLabelStore());
        assertTrue(service.get(null).isEmpty());
        assertTrue(service.get("").isEmpty());
        assertTrue(service.get("  ").isEmpty());
    }

    @Test void savesAndGetsARoomLabel() {
        var store = new InMemoryRoomLabelStore();
        var service = new RoomLabelService(store);
        var label = new RoomLabel("room1@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        service.save(label);

        var found = service.get("room1@conference.example.com");
        assertTrue(found.isPresent());
        assertFalse(found.get().hasOwnDefaultLabel());
        assertTrue(service.get("other-room@conference.example.com").isEmpty());
    }

    @Test void savingAgainReplacesTheExistingLabel() {
        var store = new InMemoryRoomLabelStore();
        var service = new RoomLabelService(store);
        service.save(new RoomLabel("room1@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null));
        service.save(new RoomLabel(
            "room1@conference.example.com", "1.2.826.0.1.6726289.0.0",
            Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML,
            Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML
        ));

        var found = service.get("room1@conference.example.com");
        assertTrue(found.isPresent());
        assertTrue(found.get().hasOwnDefaultLabel());
    }

    @Test void deletesARoomLabel() {
        var store = new InMemoryRoomLabelStore();
        var service = new RoomLabelService(store);
        service.save(new RoomLabel("room1@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null));
        service.delete("room1@conference.example.com");
        assertTrue(service.get("room1@conference.example.com").isEmpty());
    }

    @Test void rejectsNullLabelOnSave() {
        var service = new RoomLabelService(new InMemoryRoomLabelStore());
        assertThrows(NullPointerException.class, () -> service.save(null));
    }
}
