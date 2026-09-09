package org.igniterealtime.openfire.spiffing;

/** Serializes configuration updates and publishes a complete snapshot to message threads. */
public final class ConfigurationService {
    public interface Store {
        String read();
        void write(String value);
    }

    private final Store store;
    private volatile PolicyConfiguration current;

    public ConfigurationService(Store store) { this.store = store; }

    public PolicyConfiguration current() { return current; }

    public synchronized void save(Settings settings) {
        PolicyConfiguration candidate = new PolicyConfiguration(settings);
        store.write(settings.toXml());
        current = candidate;
    }

    /** Missing/corrupted persisted configuration fails closed, including after a reload. */
    public synchronized void reload() {
        try {
            String stored = store.read();
            current = stored == null ? null : new PolicyConfiguration(Settings.fromXml(stored));
        } catch (RuntimeException e) {
            current = null;
            throw new IllegalArgumentException("Stored Spiffing configuration is invalid; inbound messages are blocked.");
        }
    }
}
