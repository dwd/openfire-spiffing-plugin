package org.igniterealtime.openfire.spiffing;

import java.util.List;
import java.util.Objects;

/**
 * Manages per-room clearances. Like {@link UserClearanceService}, this does not publish a
 * validated snapshot: a room's clearances are looked up live, per message, against whichever
 * {@link PolicyConfiguration} snapshot is current at that moment (see
 * {@link PolicyConfiguration#checkRoomClearance} and {@link PolicyConfiguration#checkDefaultRoomClearance}).
 */
public final class RoomClearanceService {
    private final RoomClearanceStore store;

    public RoomClearanceService(RoomClearanceStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public List<RoomClearance> list(String roomJid) {
        if (roomJid == null || roomJid.isBlank()) return List.of();
        return store.list(roomJid);
    }

    public void save(RoomClearance roomClearance) {
        Objects.requireNonNull(roomClearance);
        store.save(roomClearance);
    }

    public void delete(String roomJid, String policyId) {
        store.delete(roomJid, policyId);
    }
}
