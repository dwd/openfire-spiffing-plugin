package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UserClearanceServiceTest {
    static final class InMemoryUserClearanceStore implements UserClearanceStore {
        final List<UserClearance> stored = new ArrayList<>();

        @Override
        public List<UserClearance> list(String username) {
            return stored.stream().filter(uc -> uc.username().equals(username)).toList();
        }

        @Override
        public void save(UserClearance userClearance) {
            stored.removeIf(uc -> uc.username().equals(userClearance.username()) && uc.policyId().equals(userClearance.policyId()));
            stored.add(userClearance);
        }

        @Override
        public void delete(String username, String policyId) {
            stored.removeIf(uc -> uc.username().equals(username) && uc.policyId().equals(policyId));
        }
    }

    @Test void emptyOrNullUsernameReturnsNoClearances() {
        var store = new InMemoryUserClearanceStore();
        var service = new UserClearanceService(store);
        assertTrue(service.list(null).isEmpty());
        assertTrue(service.list("").isEmpty());
        assertTrue(service.list("  ").isEmpty());
    }

    @Test void savesAndListsClearancesPerUser() {
        var store = new InMemoryUserClearanceStore();
        var service = new UserClearanceService(store);
        var aliceFood = new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        var aliceDrink = new UserClearance("alice", "1.2.826.0.1.6726289.0.1", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        var bobFood = new UserClearance("bob", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML);
        service.save(aliceFood);
        service.save(aliceDrink);
        service.save(bobFood);

        assertEquals(2, service.list("alice").size());
        assertEquals(1, service.list("bob").size());
        assertEquals(Fixtures.read("food-clearance-lactose-intolerant"), service.list("bob").get(0).clearance());
    }

    @Test void savingAgainForTheSamePolicyReplacesThePreviousClearance() {
        var store = new InMemoryUserClearanceStore();
        var service = new UserClearanceService(store);
        service.save(new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        service.save(new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML));

        var clearances = service.list("alice");
        assertEquals(1, clearances.size());
        assertEquals(Fixtures.read("food-clearance-lactose-intolerant"), clearances.get(0).clearance());
    }

    @Test void deletesAClearanceForOnePolicyOnly() {
        var store = new InMemoryUserClearanceStore();
        var service = new UserClearanceService(store);
        service.save(new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        service.save(new UserClearance("alice", "1.2.826.0.1.6726289.0.1", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));

        service.delete("alice", "1.2.826.0.1.6726289.0.0");

        var remaining = service.list("alice");
        assertEquals(1, remaining.size());
        assertEquals("1.2.826.0.1.6726289.0.1", remaining.get(0).policyId());
    }

    @Test void rejectsNullClearanceOnSave() {
        var service = new UserClearanceService(new InMemoryUserClearanceStore());
        assertThrows(NullPointerException.class, () -> service.save(null));
    }
}
