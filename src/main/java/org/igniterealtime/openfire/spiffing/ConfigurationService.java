package org.igniterealtime.openfire.spiffing;

/** Serializes configuration updates and publishes a complete snapshot to message threads. */
public final class ConfigurationService {
    public interface Store {
        /** Returns {@code null} when nothing has been saved yet. */
        Settings read();
        void write(Settings settings);
    }

    private final Store store;
    private volatile PolicyConfiguration current;
    /** True only when persisted settings exist but failed to load/validate; distinguishes that fail-closed
     * state from simply never having been configured at all, which instead fails open (see
     * {@link SecurityLabelInterceptor}). */
    private volatile boolean corrupted;

    public ConfigurationService(Store store) { this.store = store; }

    public PolicyConfiguration current() { return current; }

    /** Whether the last load attempt found persisted settings that failed to parse/validate. False both
     * when a valid configuration is active and when nothing has ever been saved. */
    public boolean isCorrupted() { return corrupted; }

    public synchronized void save(Settings settings) {
        PolicyConfiguration candidate = new PolicyConfiguration(settings);
        store.write(settings);
        current = candidate;
        corrupted = false;
    }

    /** Corrupted persisted configuration fails closed, including after a reload; nothing ever having been
     * saved is not corruption and does not set this state (see {@link #isCorrupted()}). */
    public synchronized void reload() {
        try {
            Settings stored = store.read();
            current = stored == null ? null : new PolicyConfiguration(stored);
            corrupted = false;
        } catch (RuntimeException e) {
            current = null;
            corrupted = true;
            throw new IllegalArgumentException("Stored Spiffing configuration is invalid; inbound messages are blocked.");
        }
    }
}
