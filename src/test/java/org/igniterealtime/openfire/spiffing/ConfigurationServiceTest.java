package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ConfigurationServiceTest {
    static class InMemoryPolicyStore implements PolicyStore {
        final Map<String, SecurityPolicy> policies = new LinkedHashMap<>();
        final Map<String, PeerClearance> peerClearances = new LinkedHashMap<>();
        int writes = 0;
        boolean fail = false;

        @Override
        public List<SecurityPolicy> listPolicies() {
            return new ArrayList<>(policies.values());
        }

        @Override
        public Optional<SecurityPolicy> getPolicy(String id) {
            return Optional.ofNullable(policies.get(id));
        }

        @Override
        public void savePolicy(SecurityPolicy policy) {
            if (fail) throw new IllegalStateException("storage unavailable");
            policies.put(policy.id(), policy);
            writes++;
        }

        @Override
        public void deletePolicy(String id) {
            if (fail) throw new IllegalStateException("storage unavailable");
            policies.remove(id);
            peerClearances.values().removeIf(pc -> pc.policyId().equals(id));
            writes++;
        }

        @Override
        public void setDefaultPolicy(String id) {
            if (fail) throw new IllegalStateException("storage unavailable");
            for (String key : policies.keySet()) {
                SecurityPolicy p = policies.get(key);
                policies.put(key, new SecurityPolicy(p.id(), p.name(), p.spif(), p.clearance(), p.clearanceFormat(), p.defaultLabel(), p.defaultLabelFormat(), p.id().equals(id)));
            }
            writes++;
        }

        @Override
        public List<PeerClearance> listPeerClearances() {
            return new ArrayList<>(peerClearances.values());
        }

        @Override
        public void savePeerClearance(PeerClearance peerClearance) {
            if (fail) throw new IllegalStateException("storage unavailable");
            peerClearances.put(peerClearance.id(), peerClearance);
            writes++;
        }

        @Override
        public void deletePeerClearance(String id) {
            if (fail) throw new IllegalStateException("storage unavailable");
            peerClearances.remove(id);
            writes++;
        }
    }

    @Test void savesAndLoadsValidatedPolicy() {
        var store = new InMemoryPolicyStore();
        var service = new ConfigurationService(store);
        service.reload();
        assertNull(service.current());

        service.savePolicy(Fixtures.foodPolicy());
        assertEquals(1, store.writes);
        assertNotNull(service.current());
        assertTrue(service.current().hasPolicies());
        assertTrue(service.current().hasClearance());
        assertTrue(service.current().hasDefaultLabel());

        var restored = new ConfigurationService(store);
        restored.reload();
        assertEquals(service.current().defaultEnvelope().asXML(), restored.current().defaultEnvelope().asXML());
    }

    @Test void neverConfiguredIsNotCorrupted() {
        var store = new InMemoryPolicyStore();
        var service = new ConfigurationService(store);
        service.reload();
        assertNull(service.current());
        assertFalse(service.isCorrupted());
    }

    @Test void allowsPartialConfigurationWithNullClearanceAndDefaultLabel() {
        var store = new InMemoryPolicyStore();
        var service = new ConfigurationService(store);
        SecurityPolicy partial = new SecurityPolicy(Fixtures.foodPolicy().id(), "Food", Fixtures.read("food-policy"), null, null, null, null, false);
        service.savePolicy(partial);
        assertNotNull(service.current());
        assertTrue(service.current().hasPolicies());
        assertFalse(service.current().hasClearance());
        assertFalse(service.current().hasDefaultLabel());
        assertNull(service.current().defaultEnvelope());
    }

    @Test void allowsUpdatingExistingPolicySpifAndClearance() {
        var store = new InMemoryPolicyStore();
        var service = new ConfigurationService(store);
        service.savePolicy(Fixtures.foodPolicy(null, null));
        assertFalse(service.current().hasClearance());

        // Update SPIF and add clearance
        service.savePolicy(Fixtures.foodPolicy());
        assertTrue(service.current().hasClearance());
        assertTrue(service.current().hasDefaultLabel());
    }

    @Test void invalidPolicyUpdatePreservesSavedAndActiveConfiguration() {
        var store = new InMemoryPolicyStore();
        var service = new ConfigurationService(store);
        service.savePolicy(Fixtures.foodPolicy());
        var before = service.current();
        SecurityPolicy invalid = new SecurityPolicy(Fixtures.foodPolicy().id(), "Food", Fixtures.read("food-policy"),
            Fixtures.read("food-clearance-all-okay"), LabelFormat.XML, Fixtures.read("food-label-water"), LabelFormat.XML, true);
        assertThrows(IllegalArgumentException.class, () -> service.savePolicy(invalid));
        assertSame(before, service.current());
        assertEquals(1, store.writes);
    }

    @Test void persistenceFailureDoesNotPublishCandidate() {
        var store = new InMemoryPolicyStore();
        var service = new ConfigurationService(store);
        service.savePolicy(Fixtures.foodPolicy());
        var before = service.current();
        store.fail = true;
        assertThrows(IllegalStateException.class, () -> service.savePolicy(Fixtures.drinkPolicy()));
        assertSame(before, service.current());
    }

    @Test void invalidPersistedPolicyFailsClosedAndCanRecover() {
        var store = new InMemoryPolicyStore();
        var service = new ConfigurationService(store);
        service.savePolicy(Fixtures.foodPolicy());
        // Corrupt stored SPIF
        store.policies.put(Fixtures.foodPolicy().id(), new SecurityPolicy(Fixtures.foodPolicy().id(), "Corrupt", "not a valid SPIF", null, null, null, null, false));
        assertThrows(IllegalArgumentException.class, service::reload);
        assertNull(service.current());
        assertTrue(service.isCorrupted());

        // Fix stored policy
        store.policies.put(Fixtures.foodPolicy().id(), Fixtures.foodPolicy());
        service.reload();
        assertNotNull(service.current());
        assertFalse(service.isCorrupted());
    }

    @Test void savesAndDeletesPeerClearance() {
        var store = new InMemoryPolicyStore();
        var service = new ConfigurationService(store);
        service.savePolicy(Fixtures.foodPolicy());
        PeerClearance pc = new PeerClearance("pc1", "example.com", Fixtures.foodPolicy().id(), Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        service.savePeerClearance(pc);
        assertTrue(service.current().hasPeerClearance());
        assertEquals(1, service.peerClearances().size());

        service.deletePeerClearance("pc1");
        assertFalse(service.current().hasPeerClearance());
        assertEquals(0, service.peerClearances().size());
    }

    @Test void readersSeePreviousCompleteSnapshotUntilSaveFinishes() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var store = new InMemoryPolicyStore() {
            @Override
            public void savePolicy(SecurityPolicy policy) {
                if (started.getCount() > 0) {
                    started.countDown();
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("timeout");
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                }
                super.savePolicy(policy);
            }
        };
        store.policies.put(Fixtures.foodPolicy().id(), Fixtures.foodPolicy());
        var service = new ConfigurationService(store);
        service.reload();
        var before = service.current();
        var executor = Executors.newSingleThreadExecutor();
        try {
            var saved = executor.submit(() -> service.savePolicy(Fixtures.drinkPolicy()));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertSame(before, service.current());
                assertDoesNotThrow(() -> before.check(before.defaultEnvelope()));
            } finally { release.countDown(); }
            saved.get(5, TimeUnit.SECONDS);
            assertEquals(2, service.policies().size());
        } finally {
            executor.shutdown();
        }
    }
}
