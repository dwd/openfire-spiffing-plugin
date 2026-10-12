package org.igniterealtime.openfire.spiffing;

import java.util.List;
import java.util.Optional;

/**
 * Storage interface for SPIF security policies and peer clearances.
 */
public interface PolicyStore {
    List<SecurityPolicy> listPolicies();
    Optional<SecurityPolicy> getPolicy(String id);
    void savePolicy(SecurityPolicy policy);
    void deletePolicy(String id);
    void setDefaultPolicy(String id);

    List<PeerClearance> listPeerClearances();
    void savePeerClearance(PeerClearance peerClearance);
    void deletePeerClearance(String id);
}
