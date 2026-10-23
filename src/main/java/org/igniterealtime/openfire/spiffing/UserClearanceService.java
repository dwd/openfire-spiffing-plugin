package org.igniterealtime.openfire.spiffing;

import java.util.List;
import java.util.Objects;

/**
 * Manages per-user clearances. Unlike {@link ConfigurationService}, this does not publish a
 * validated snapshot: a user's clearances are looked up live, per message, against whichever
 * {@link PolicyConfiguration} snapshot is current at that moment (see
 * {@link PolicyConfiguration#checkUserClearance} and {@link PolicyConfiguration#checkDefaultUserClearance}),
 * since there may be very many users and their clearances change independently of policy configuration.
 */
public final class UserClearanceService {
    private final UserClearanceStore store;

    public UserClearanceService(UserClearanceStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public List<UserClearance> list(String username) {
        if (username == null || username.isBlank()) return List.of();
        return store.list(username);
    }

    public void save(UserClearance userClearance) {
        Objects.requireNonNull(userClearance);
        store.save(userClearance);
    }

    public void delete(String username, String policyId) {
        store.delete(username, policyId);
    }
}
