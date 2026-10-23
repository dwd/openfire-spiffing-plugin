package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.openfire.muc.MUCEventDelegate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.JID;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RoomJoinGateTest {
    private static final String ROOM_JID = "room@conference.local.example";
    private final PolicyConfiguration configuration = Fixtures.configuration();

    @BeforeEach
    void setUp() {
        SpiffingProperties.setEnforcementMode(EnforcementMode.ENFORCE);
    }

    private RoomJoinGate gate(java.util.function.Function<String, Optional<RoomLabel>> roomLabel, java.util.function.Function<String, List<UserClearance>> userClearances, java.util.function.Predicate<JID> isLocal) {
        return new RoomJoinGate(() -> configuration, roomLabel, userClearances, isLocal, null);
    }

    @Test void roomWithNoConfiguredLabelAllowsAnyoneToJoin() {
        var gate = gate(jid -> Optional.empty(), username -> List.of(), jid -> true);
        assertTrue(gate.checkJoin(ROOM_JID, new JID("alice@local.example")));
    }

    @Test void localUserWithAPassingClearanceIsPermitted() {
        var rl = new RoomLabel(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        var uc = new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        var gate = gate(jid -> Optional.of(rl), username -> List.of(uc), jid -> true);
        assertTrue(gate.checkJoin(ROOM_JID, new JID("alice@local.example")));
    }

    @Test void localUserWithNoClearanceIsDeniedInEnforceMode() {
        var rl = new RoomLabel(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        var gate = gate(jid -> Optional.of(rl), username -> List.of(), jid -> true);
        assertFalse(gate.checkJoin(ROOM_JID, new JID("alice@local.example")));
    }

    @Test void localUserWithAFailingClearanceIsDeniedInEnforceMode() {
        var rl = new RoomLabel(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        var uc = new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML);
        var gate = gate(jid -> Optional.of(rl), username -> List.of(uc), jid -> true);
        assertFalse(gate.checkJoin(ROOM_JID, new JID("alice@local.example")));
    }

    @Test void deniedJoinIsAllowedThroughInWarnMode() {
        SpiffingProperties.setEnforcementMode(EnforcementMode.WARN);
        var rl = new RoomLabel(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        var gate = gate(jid -> Optional.of(rl), username -> List.of(), jid -> true);
        assertTrue(gate.checkJoin(ROOM_JID, new JID("alice@local.example")));
    }

    @Test void remoteUserWithAPassingPeerClearanceIsPermitted() {
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-all-okay");
        var rl = new RoomLabel(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        var gate = new RoomJoinGate(() -> peerConfiguration, jid -> Optional.of(rl), username -> List.of(), jid -> false, null);
        assertTrue(gate.checkJoin(ROOM_JID, new JID("bob@peer.example.com")));
    }

    @Test void remoteUserWithNoPeerClearanceConfiguredIsDenied() {
        var rl = new RoomLabel(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        var gate = gate(jid -> Optional.of(rl), username -> List.of(), jid -> false);
        assertFalse(gate.checkJoin(ROOM_JID, new JID("bob@peer.example.com")));
    }

    @Test void noLoadedPoliciesIsANoOp() {
        var gate = new RoomJoinGate(() -> null, jid -> { throw new AssertionError("must not look up room label without a loaded policy"); }, username -> List.of(), jid -> true, null);
        assertTrue(gate.checkJoin(ROOM_JID, new JID("alice@local.example")));
    }

    @Test void wrappedDelegateDenialShortCircuitsBeforeTheLabelCheck() {
        var denyingDelegate = new MUCEventDelegate() {
            @Override public boolean joiningRoom(org.jivesoftware.openfire.muc.MUCRoom room, JID userjid) { return false; }
            @Override public InvitationResult sendingInvitation(org.jivesoftware.openfire.muc.MUCRoom room, JID inviteeJID, JID inviterJID, String inviteMessage) { throw new AssertionError("unused"); }
            @Override public InvitationRejectionResult sendingInvitationRejection(org.jivesoftware.openfire.muc.MUCRoom room, JID to, JID from, String reason) { throw new AssertionError("unused"); }
            @Override public java.util.Map<String, String> getRoomConfig(String roomName) { throw new AssertionError("unused"); }
            @Override public boolean destroyingRoom(String roomName, JID userjid) { throw new AssertionError("unused"); }
            @Override public boolean shouldRecreate(String roomName, JID userjid) { throw new AssertionError("unused"); }
        };
        var rl = new RoomLabel(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        var uc = new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        var gate = new RoomJoinGate(() -> configuration, jid -> Optional.of(rl), username -> List.of(uc), jid -> true, denyingDelegate);
        assertFalse(gate.joiningRoom(null, new JID("alice@local.example")));
    }

    @Test void delegateMethodsFallBackToPermissiveDefaultsWhenThereIsNoWrappedDelegate() {
        var gate = gate(jid -> Optional.empty(), username -> List.of(), jid -> true);
        assertEquals(MUCEventDelegate.InvitationResult.HANDLED_BY_OPENFIRE, gate.sendingInvitation(null, null, null, null));
        assertEquals(MUCEventDelegate.InvitationRejectionResult.HANDLED_BY_OPENFIRE, gate.sendingInvitationRejection(null, null, null, null));
        assertNull(gate.getRoomConfig("room"));
        assertTrue(gate.destroyingRoom("room", null));
        assertFalse(gate.shouldRecreate("room", null));
    }
}
