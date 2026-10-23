package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomClearanceServiceTest {
    static final class InMemoryRoomClearanceStore implements RoomClearanceStore {
        final List<RoomClearance> stored = new ArrayList<>();

        @Override
        public List<RoomClearance> list(String roomJid) {
            return stored.stream().filter(rc -> rc.roomJid().equals(roomJid)).toList();
        }

        @Override
        public void save(RoomClearance roomClearance) {
            stored.removeIf(rc -> rc.roomJid().equals(roomClearance.roomJid()) && rc.policyId().equals(roomClearance.policyId()));
            stored.add(roomClearance);
        }

        @Override
        public void delete(String roomJid, String policyId) {
            stored.removeIf(rc -> rc.roomJid().equals(roomJid) && rc.policyId().equals(policyId));
        }
    }

    @Test void emptyOrNullRoomJidReturnsNoClearances() {
        var service = new RoomClearanceService(new InMemoryRoomClearanceStore());
        assertTrue(service.list(null).isEmpty());
        assertTrue(service.list("").isEmpty());
        assertTrue(service.list("  ").isEmpty());
    }

    @Test void savesAndListsClearancesPerRoom() {
        var store = new InMemoryRoomClearanceStore();
        var service = new RoomClearanceService(store);
        var room1Food = new RoomClearance("room1@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        var room1Drink = new RoomClearance("room1@conference.example.com", "1.2.826.0.1.6726289.0.1", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        var room2Food = new RoomClearance("room2@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML);
        service.save(room1Food);
        service.save(room1Drink);
        service.save(room2Food);

        assertEquals(2, service.list("room1@conference.example.com").size());
        assertEquals(1, service.list("room2@conference.example.com").size());
        assertEquals(Fixtures.read("food-clearance-lactose-intolerant"), service.list("room2@conference.example.com").get(0).clearance());
    }

    @Test void savingAgainForTheSamePolicyReplacesThePreviousClearance() {
        var store = new InMemoryRoomClearanceStore();
        var service = new RoomClearanceService(store);
        service.save(new RoomClearance("room1@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        service.save(new RoomClearance("room1@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML));

        var clearances = service.list("room1@conference.example.com");
        assertEquals(1, clearances.size());
        assertEquals(Fixtures.read("food-clearance-lactose-intolerant"), clearances.get(0).clearance());
    }

    @Test void deletesAClearanceForOnePolicyOnly() {
        var store = new InMemoryRoomClearanceStore();
        var service = new RoomClearanceService(store);
        service.save(new RoomClearance("room1@conference.example.com", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        service.save(new RoomClearance("room1@conference.example.com", "1.2.826.0.1.6726289.0.1", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));

        service.delete("room1@conference.example.com", "1.2.826.0.1.6726289.0.0");

        var remaining = service.list("room1@conference.example.com");
        assertEquals(1, remaining.size());
        assertEquals("1.2.826.0.1.6726289.0.1", remaining.get(0).policyId());
    }

    @Test void rejectsNullClearanceOnSave() {
        var service = new RoomClearanceService(new InMemoryRoomClearanceStore());
        assertThrows(NullPointerException.class, () -> service.save(null));
    }
}
