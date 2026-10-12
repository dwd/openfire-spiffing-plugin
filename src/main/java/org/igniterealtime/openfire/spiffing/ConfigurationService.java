package org.igniterealtime.openfire.spiffing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Manages persisted security policies and peer clearances, publishing immutable snapshots to message threads.
 */
public final class ConfigurationService {
    private static final Logger LOG = LoggerFactory.getLogger(ConfigurationService.class);

    private final PolicyStore store;
    private volatile PolicyConfiguration current;
    private volatile boolean corrupted;

    public ConfigurationService(PolicyStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public PolicyStore store() {
        return store;
    }

    public PolicyConfiguration current() {
        return current;
    }

    public boolean isCorrupted() {
        return corrupted;
    }

    public List<SecurityPolicy> policies() {
        return store.listPolicies();
    }

    public Optional<SecurityPolicy> policy(String id) {
        return store.getPolicy(id);
    }

    public List<PeerClearance> peerClearances() {
        return store.listPeerClearances();
    }

    public synchronized void reload() {
        try {
            List<SecurityPolicy> policies = store.listPolicies();
            List<PeerClearance> peerClearances = store.listPeerClearances();
            if (policies.isEmpty()) {
                current = null;
                corrupted = false;
            } else {
                LabelFormat outputFormat = SpiffingProperties.getOutputFormat();
                current = new PolicyConfiguration(policies, peerClearances, outputFormat);
                corrupted = false;
            }
        } catch (RuntimeException e) {
            current = null;
            corrupted = true;
            LOG.error("Stored Spiffing configuration is invalid: {}", e.getMessage());
            throw new IllegalArgumentException("Stored Spiffing configuration is invalid; inbound messages are blocked.", e);
        }
    }

    public synchronized void savePolicy(SecurityPolicy policy) {
        Objects.requireNonNull(policy);
        List<SecurityPolicy> existing = store.listPolicies();
        List<SecurityPolicy> candidatePolicies = new ArrayList<>();
        boolean found = false;
        for (SecurityPolicy p : existing) {
            if (p.id().equals(policy.id())) {
                candidatePolicies.add(policy);
                found = true;
            } else {
                candidatePolicies.add(p);
            }
        }
        if (!found) {
            candidatePolicies.add(policy);
        }
        List<PeerClearance> peerClearances = store.listPeerClearances();
        LabelFormat outputFormat = SpiffingProperties.getOutputFormat();

        // Validate candidate configuration before committing to store
        PolicyConfiguration candidate = new PolicyConfiguration(candidatePolicies, peerClearances, outputFormat);
        store.savePolicy(policy);
        current = candidate;
        corrupted = false;
    }

    public synchronized void deletePolicy(String id) {
        store.deletePolicy(id);
        reload();
    }

    public synchronized void setDefaultPolicy(String id) {
        store.setDefaultPolicy(id);
        reload();
    }

    public synchronized void savePeerClearance(PeerClearance peerClearance) {
        Objects.requireNonNull(peerClearance);
        List<SecurityPolicy> policies = store.listPolicies();
        List<PeerClearance> existing = store.listPeerClearances();
        List<PeerClearance> candidateList = new ArrayList<>();
        boolean found = false;
        for (PeerClearance pc : existing) {
            if (pc.id().equals(peerClearance.id())) {
                candidateList.add(peerClearance);
                found = true;
            } else {
                candidateList.add(pc);
            }
        }
        if (!found) {
            candidateList.add(peerClearance);
        }
        LabelFormat outputFormat = SpiffingProperties.getOutputFormat();
        PolicyConfiguration candidate = new PolicyConfiguration(policies, candidateList, outputFormat);
        store.savePeerClearance(peerClearance);
        current = candidate;
        corrupted = false;
    }

    public synchronized void deletePeerClearance(String id) {
        store.deletePeerClearance(id);
        reload();
    }
}
