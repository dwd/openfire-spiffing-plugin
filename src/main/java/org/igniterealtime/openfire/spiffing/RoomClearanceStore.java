package org.igniterealtime.openfire.spiffing;

import java.util.List;

/**
 * Storage interface for per-room clearances. Implementations persist each room's clearances
 * independently of the {@link PolicyStore}-backed server/peer clearances and the
 * {@link UserClearanceStore}-backed per-user clearances.
 */
public interface RoomClearanceStore {
    List<RoomClearance> list(String roomJid);
    void save(RoomClearance roomClearance);
    void delete(String roomJid, String policyId);
}
