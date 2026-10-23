package org.igniterealtime.openfire.spiffing;

import java.util.List;

/**
 * Storage interface for per-user clearances. Implementations persist each user's clearances
 * independently of the {@link PolicyStore}-backed server/peer clearances.
 */
public interface UserClearanceStore {
    List<UserClearance> list(String username);
    void save(UserClearance userClearance);
    void delete(String username, String policyId);
}
