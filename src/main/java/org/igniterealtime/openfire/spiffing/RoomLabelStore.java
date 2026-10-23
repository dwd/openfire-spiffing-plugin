package org.igniterealtime.openfire.spiffing;

import java.util.Optional;

/**
 * Storage interface for a room's own label (and optional distinct default label). A room has at
 * most one stored label at a time, unlike the multiple-policies-at-once {@link RoomClearance}.
 */
public interface RoomLabelStore {
    Optional<RoomLabel> get(String roomJid);
    void save(RoomLabel roomLabel);
    void delete(String roomJid);
}
