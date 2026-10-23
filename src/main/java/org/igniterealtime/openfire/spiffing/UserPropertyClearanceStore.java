package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.openfire.user.User;
import org.jivesoftware.openfire.user.UserManager;
import org.jivesoftware.openfire.user.UserNotFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Persists each user's clearances as a pair of {@code UserProperty} values per policy, under the
 * {@value #PREFIX} prefix: {@code plugin.spiffing.clearance.<policyId>} holds the raw clearance
 * payload, and {@code plugin.spiffing.clearance.<policyId>.format} holds its {@link LabelFormat}
 * name. A missing user, or one with no such properties, simply has no clearances.
 */
final class UserPropertyClearanceStore implements UserClearanceStore {
    static final String PREFIX = "plugin.spiffing.clearance.";
    private static final String FORMAT_SUFFIX = ".format";

    @Override
    public List<UserClearance> list(String username) {
        User user = findUser(username);
        if (user == null) return List.of();
        List<UserClearance> result = new ArrayList<>();
        Map<String, String> properties = user.getProperties();
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(PREFIX) || key.endsWith(FORMAT_SUFFIX)) continue;
            String policyId = key.substring(PREFIX.length());
            String clearance = entry.getValue();
            String formatStr = properties.get(key + FORMAT_SUFFIX);
            if (clearance == null || clearance.isBlank() || formatStr == null || formatStr.isBlank()) continue;
            try {
                result.add(new UserClearance(username, policyId, clearance, LabelFormat.valueOf(formatStr)));
            } catch (RuntimeException ignored) {
                // Skip a corrupted/unrecognized entry rather than failing the whole listing.
            }
        }
        return result;
    }

    @Override
    public void save(UserClearance userClearance) {
        User user = requireUser(userClearance.username());
        Map<String, String> properties = user.getProperties();
        properties.put(PREFIX + userClearance.policyId(), userClearance.clearance());
        properties.put(PREFIX + userClearance.policyId() + FORMAT_SUFFIX, userClearance.format().name());
    }

    @Override
    public void delete(String username, String policyId) {
        User user = findUser(username);
        if (user == null) return;
        Map<String, String> properties = user.getProperties();
        properties.remove(PREFIX + policyId);
        properties.remove(PREFIX + policyId + FORMAT_SUFFIX);
    }

    private static User findUser(String username) {
        try {
            return requireUser(username);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static User requireUser(String username) {
        try {
            return UserManager.getInstance().getUser(username);
        } catch (UserNotFoundException e) {
            throw new IllegalArgumentException("Unknown user: " + username, e);
        }
    }
}
