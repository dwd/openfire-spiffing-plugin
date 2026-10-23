package org.igniterealtime.openfire.spiffing;

import java.util.Objects;
import java.util.Optional;

/**
 * Manages each room's own label. Like {@link RoomClearanceService}/{@link UserClearanceService},
 * this does not publish a validated snapshot: a room's label is looked up live, per message or
 * join attempt, against whichever {@link PolicyConfiguration} snapshot is current at that moment.
 */
public final class RoomLabelService {
    private final RoomLabelStore store;

    public RoomLabelService(RoomLabelStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public Optional<RoomLabel> get(String roomJid) {
        if (roomJid == null || roomJid.isBlank()) return Optional.empty();
        return store.get(roomJid);
    }

    public void save(RoomLabel roomLabel) {
        Objects.requireNonNull(roomLabel);
        store.save(roomLabel);
    }

    public void delete(String roomJid) {
        store.delete(roomJid);
    }
}
