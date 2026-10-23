package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Label;
import org.jivesoftware.openfire.muc.MUCEventDelegate;
import org.jivesoftware.openfire.muc.MUCRoom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Gates room joins against a room's own label (see {@link RoomLabel}): a remote user needs their
 * domain's peer clearance to authorize the room label, and a local user needs one of their own
 * user clearances to authorize it; without a passing clearance, the join is denied. A room with no
 * configured label is unaffected (the feature is opt-in per room). Wraps any pre-existing
 * {@link MUCEventDelegate} (Openfire allows only one per {@code MultiUserChatService}), delegating
 * every other event to it unchanged; see {@code doc/design.md}'s "Room clearance and room label"
 * section for this wrapping's known limitations.
 */
final class RoomJoinGate extends MUCEventDelegate {
    private static final Logger LOG = LoggerFactory.getLogger(RoomJoinGate.class);

    private final Supplier<PolicyConfiguration> configuration;
    private final Function<String, Optional<RoomLabel>> roomLabel;
    private final Function<String, List<UserClearance>> userClearances;
    private final Predicate<JID> isLocal;
    private final MUCEventDelegate wrapped;

    RoomJoinGate(
        Supplier<PolicyConfiguration> configuration,
        Function<String, Optional<RoomLabel>> roomLabel,
        Function<String, List<UserClearance>> userClearances,
        Predicate<JID> isLocal,
        MUCEventDelegate wrapped
    ) {
        this.configuration = configuration;
        this.roomLabel = roomLabel;
        this.userClearances = userClearances;
        this.isLocal = isLocal;
        this.wrapped = wrapped;
    }

    @Override
    public boolean joiningRoom(MUCRoom room, JID userjid) {
        if (wrapped != null && !wrapped.joiningRoom(room, userjid)) return false;
        return checkJoin(room.getJID().toBareJID().toString(), userjid);
    }

    /**
     * The actual room-label join check, factored out of {@link #joiningRoom} so it can be unit
     * tested without needing a live {@link MUCRoom} instance (which has heavyweight construction
     * dependencies on a running Openfire server).
     */
    boolean checkJoin(String roomJid, JID userjid) {
        PolicyConfiguration snapshot = configuration.get();
        if (snapshot == null || !snapshot.hasPolicies()) return true;
        Optional<RoomLabel> configured = roomLabel.apply(roomJid);
        if (configured.isEmpty()) return true;
        RoomLabel rl = configured.get();
        EnforcementMode enforcementMode = SpiffingProperties.getEnforcementMode();
        try {
            Label label = snapshot.decodeRoomLabel(rl.label(), rl.format());
            if (isLocal.test(userjid)) {
                snapshot.authorizeRoomJoinByUserClearance(label, userClearances.apply(userjid.getNode()));
            } else {
                snapshot.authorizeRoomJoinByPeerClearance(label, userjid.getDomain());
            }
            return true;
        } catch (RuntimeException e) {
            if (enforcementMode == EnforcementMode.ENFORCE) {
                LOG.warn("Denied {} from joining room {}: {}", userjid, roomJid, e.getMessage());
                return false;
            }
            LOG.warn("Room label check failed for {} joining room {}: {}. Enforcement mode is warn, so the join was allowed.",
                userjid, roomJid, e.getMessage());
            return true;
        }
    }

    @Override
    public InvitationResult sendingInvitation(MUCRoom room, JID inviteeJID, JID inviterJID, String inviteMessage) {
        return wrapped != null ? wrapped.sendingInvitation(room, inviteeJID, inviterJID, inviteMessage) : InvitationResult.HANDLED_BY_OPENFIRE;
    }

    @Override
    public InvitationRejectionResult sendingInvitationRejection(MUCRoom room, JID to, JID from, String reason) {
        return wrapped != null ? wrapped.sendingInvitationRejection(room, to, from, reason) : InvitationRejectionResult.HANDLED_BY_OPENFIRE;
    }

    @Override
    public Map<String, String> getRoomConfig(String roomName) {
        return wrapped != null ? wrapped.getRoomConfig(roomName) : null;
    }

    @Override
    public boolean destroyingRoom(String roomName, JID userjid) {
        return wrapped == null || wrapped.destroyingRoom(roomName, userjid);
    }

    @Override
    public boolean shouldRecreate(String roomName, JID userjid) {
        return wrapped != null && wrapped.shouldRecreate(roomName, userjid);
    }
}
