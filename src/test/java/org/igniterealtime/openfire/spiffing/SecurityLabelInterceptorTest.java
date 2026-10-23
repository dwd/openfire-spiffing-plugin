package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.xmpp.packet.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SecurityLabelInterceptorTest {
    private final PolicyConfiguration configuration = Fixtures.configuration();
    private final List<Message> replies = new ArrayList<>();
    private final SecurityLabelInterceptor interceptor = new SecurityLabelInterceptor(() -> configuration, replies::add);

    @BeforeEach
    void setUp() {
        SpiffingProperties.setEnforcementMode(EnforcementMode.ENFORCE);
        SpiffingProperties.setStripDefaultLabelForFederation(false);
        SpiffingProperties.setOutputFormat(LabelFormat.ESS);
        replies.clear();
    }

    private Message message() {
        Message message = new Message();
        message.setFrom("alice@remote.example/phone");
        message.setTo("bob@local.example");
        message.setID("test-42");
        message.setBody("sensitive content");
        return message;
    }

    @ParameterizedTest @EnumSource(value=Message.Type.class, names={"normal", "chat", "groupchat", "headline"})
    void stampsEveryNonErrorMessageTypeWithoutRequiringLocalSession(Message.Type type) throws Exception {
        var message = message();
        message.setType(type);
        interceptor.interceptPacket(message, null, true, false);
        assertEquals(configuration.defaultEnvelope().asXML(), message.getElement().element(PolicyConfiguration.ENVELOPE).asXML());
        assertEquals("sensitive content", message.getBody());
        assertEquals(type, message.getType());
        interceptor.interceptPacket(message, null, true, false);
        interceptor.interceptPacket(message, null, true, true);
        assertEquals(1, message.getElement().elements(PolicyConfiguration.ENVELOPE).size());
        assertTrue(replies.isEmpty());
    }

    @Test void localClientSessionUsesTheSameEnforcementAndReplyPath() throws Exception {
        var session = (org.jivesoftware.openfire.session.Session) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{org.jivesoftware.openfire.session.Session.class},
            (proxy, method, args) -> { throw new AssertionError("Enforcement must not depend on session state"); });
        var message = message();
        message.setFrom("alice@local.example/phone");
        interceptor.interceptPacket(message, session, true, false);
        assertNotNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        message.getElement().element(PolicyConfiguration.ENVELOPE).detach();
        message.getElement().add(Fixtures.envelope("food-label-water", LabelFormat.ESS));
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message, session, true, false));
        assertEquals(message.getFrom(), replies.get(0).getTo());
    }

    @Test void retainsExistingAuthorizedEnvelopeAndOtherExtensions() throws Exception {
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.NATO));
        message.addChildElement("request", "urn:xmpp:receipts");
        String before = message.toXML();
        interceptor.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
    }

    @Test void rejectsBeforeMutatingDeniedMessageAndSendsSanitizedError() {
        var message = message();
        message.setThread("sensitive thread");
        message.getElement().add(Fixtures.envelope("food-label-water", LabelFormat.ESS));
        String before = message.toXML();
        PacketRejectedException rejection = assertThrows(PacketRejectedException.class,
            () -> interceptor.interceptPacket(message, null, true, false));
        assertNull(rejection.getRejectionError(), "Openfire must not send a second reply");
        assertNull(rejection.getRejectionMessage());
        assertEquals(before, message.toXML());
        assertEquals(1, replies.size());
        var reply = replies.get(0);
        assertEquals(message.getID(), reply.getID());
        assertEquals(message.getFrom(), reply.getTo());
        assertEquals(message.getTo(), reply.getFrom());
        assertEquals(Message.Type.error, reply.getType());
        assertEquals(PacketError.Condition.forbidden, reply.getError().getCondition());
        assertNull(reply.getBody());
        assertNull(reply.getThread());
        assertNull(reply.getElement().element(PolicyConfiguration.ENVELOPE));
    }

    @Test void duplicateEnvelopesAreRejected() {
        var message = message();
        message.getElement().add(configuration.defaultEnvelope());
        message.getElement().add(configuration.defaultEnvelope());
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message, null, true, false));
    }

    @Test void warnModeLogsAndAllowsDeniedMessageThroughUnchanged() throws Exception {
        SpiffingProperties.setEnforcementMode(EnforcementMode.WARN);
        var warnInterceptor = new SecurityLabelInterceptor(() -> configuration, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-water", LabelFormat.ESS));
        String before = message.toXML();
        warnInterceptor.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void warnModeLogsAndAllowsDuplicateEnvelopesThrough() throws Exception {
        SpiffingProperties.setEnforcementMode(EnforcementMode.WARN);
        var warnInterceptor = new SecurityLabelInterceptor(() -> configuration, replies::add);
        var message = message();
        message.getElement().add(configuration.defaultEnvelope());
        message.getElement().add(configuration.defaultEnvelope());
        String before = message.toXML();
        warnInterceptor.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void explicitDefaultIsStamped() throws Exception {
        var message = message();
        message.getElement().add(Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/></securitylabel>"));
        interceptor.interceptPacket(message, null, true, false);
        assertEquals(configuration.defaultEnvelope().asXML(), message.getElement().element(PolicyConfiguration.ENVELOPE).asXML());
    }

    @Test void namespaceLookalikeIsNotAnAuthoritativeLabel() throws Exception {
        var message = message();
        message.addChildElement("securitylabel", "urn:unrelated");
        interceptor.interceptPacket(message, null, true, false);
        assertNotNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertEquals(2, message.getElement().elements("securitylabel").size());
    }

    @Test void forwardedInnerLabelCannotAuthorizeOuterMessage() throws Exception {
        var message = message();
        var inner = message.addChildElement("forwarded", "urn:xmpp:forward:0").addElement("message");
        inner.add(Fixtures.envelope("food-label-water", LabelFormat.ESS));
        interceptor.interceptPacket(message, null, true, false);
        assertNotNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertNotNull(inner.element(PolicyConfiguration.ENVELOPE));
    }

    @Test void ignoresOutboundProcessedIqAndPresence() throws Exception {
        var message = message();
        String before = message.toXML();
        interceptor.interceptPacket(message, null, false, false);
        interceptor.interceptPacket(message, null, true, true);
        interceptor.interceptPacket(message, null, false, true);
        interceptor.interceptPacket(new IQ(), null, true, false);
        interceptor.interceptPacket(new Presence(), null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void missingConfigurationBlocksUnlabelledMessages() {
        var interceptor = new SecurityLabelInterceptor(() -> null, () -> true, replies::add);
        var message = message();
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message, null, true, false));
        assertNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertEquals(PacketError.Condition.service_unavailable, replies.get(0).getError().getCondition());
    }

    @Test void corruptedConfigurationBlocksUnlabelledMessages() {
        var interceptor = new SecurityLabelInterceptor(() -> null, () -> true, replies::add);
        var message = message();
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message, null, true, false));
        assertNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertEquals(PacketError.Condition.service_unavailable, replies.get(0).getError().getCondition());
    }

    @Test void neverConfiguredLetsMessagesThroughUnaffected() throws Exception {
        var interceptor = new SecurityLabelInterceptor(() -> null, () -> false, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-water", LabelFormat.ESS)); // would be denied if checked
        String before = message.toXML();
        interceptor.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void neverConfiguredLeavesUnlabelledMessagesUnstamped() throws Exception {
        var interceptor = new SecurityLabelInterceptor(() -> null, () -> false, replies::add);
        var message = message();
        interceptor.interceptPacket(message, null, true, false);
        assertNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertTrue(replies.isEmpty());
    }

    @Test void errorsBypassAuthorizationEvenWhenUnconfigured() throws Exception {
        var interceptor = new SecurityLabelInterceptor(() -> { throw new AssertionError("must not consult clearance"); }, replies::add);
        var message = message();
        message.setError(PacketError.Condition.forbidden);
        String before = message.toXML();
        interceptor.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        message.getElement().add(configuration.defaultEnvelope());
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message, null, true, false));
        assertTrue(replies.isEmpty());
    }

    @Test void replyDeliveryFailureStillRejectsOriginal() {
        var interceptor = new SecurityLabelInterceptor(() -> null, () -> true, reply -> { throw new IllegalStateException("no route"); });
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message(), null, true, false));
    }

    @Test void absentSenderCannotCreateUnaddressedError() {
        var message = message();
        message.setFrom((JID) null);
        var interceptor = new SecurityLabelInterceptor(() -> null, () -> true, replies::add);
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message, null, true, false));
        assertTrue(replies.isEmpty());
    }

    @Test void readsExactlyOneSnapshotPerMessage() throws Exception {
        int[] reads = {0};
        var interceptor = new SecurityLabelInterceptor(() -> { reads[0]++; return configuration; }, replies::add);
        interceptor.interceptPacket(message(), null, true, false);
        assertEquals(1, reads[0]);
    }

    private static org.jivesoftware.openfire.session.OutgoingServerSession outgoingServerSession() {
        return (org.jivesoftware.openfire.session.OutgoingServerSession) java.lang.reflect.Proxy.newProxyInstance(
            SecurityLabelInterceptorTest.class.getClassLoader(),
            new Class<?>[]{org.jivesoftware.openfire.session.OutgoingServerSession.class},
            (proxy, method, args) -> { throw new AssertionError("Stripping must not depend on session state"); });
    }

    @Test void stripsLabelMatchingDefaultMarkingBeforeSendingToAnotherServer() throws Exception {
        SpiffingProperties.setStripDefaultLabelForFederation(true);
        var strippingInterceptor = new SecurityLabelInterceptor(() -> configuration, replies::add);
        var message = message();
        message.getElement().add(configuration.defaultEnvelope());
        strippingInterceptor.interceptPacket(message, outgoingServerSession(), false, false);
        assertNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertTrue(replies.isEmpty());
    }

    @Test void doesNotStripWhenOptionIsDisabled() throws Exception {
        SpiffingProperties.setStripDefaultLabelForFederation(false);
        var message = message();
        message.getElement().add(configuration.defaultEnvelope());
        String before = message.toXML();
        interceptor.interceptPacket(message, outgoingServerSession(), false, false);
        assertEquals(before, message.toXML());
    }

    @Test void doesNotStripLabelWithADifferentDisplayMarking() throws Exception {
        SpiffingProperties.setStripDefaultLabelForFederation(true);
        var strippingInterceptor = new SecurityLabelInterceptor(() -> configuration, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-water", LabelFormat.XML));
        String before = message.toXML();
        strippingInterceptor.interceptPacket(message, outgoingServerSession(), false, false);
        assertEquals(before, message.toXML());
    }

    @Test void doesNotStripForLocalClientDelivery() throws Exception {
        SpiffingProperties.setStripDefaultLabelForFederation(true);
        var strippingInterceptor = new SecurityLabelInterceptor(() -> configuration, replies::add);
        var message = message();
        message.getElement().add(configuration.defaultEnvelope());
        String before = message.toXML();
        var localSession = (org.jivesoftware.openfire.session.Session) java.lang.reflect.Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{org.jivesoftware.openfire.session.Session.class},
            (proxy, method, args) -> { throw new AssertionError("Stripping must not depend on session state"); });
        strippingInterceptor.interceptPacket(message, localSession, false, false);
        strippingInterceptor.interceptPacket(message, null, false, false);
        assertEquals(before, message.toXML());
    }

    @Test void doesNotStripWithoutConfiguration() throws Exception {
        SpiffingProperties.setStripDefaultLabelForFederation(true);
        var unconfigured = new SecurityLabelInterceptor(() -> null, replies::add);
        var message = message();
        message.getElement().add(configuration.defaultEnvelope());
        String before = message.toXML();
        unconfigured.interceptPacket(message, outgoingServerSession(), false, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void doesNotStripAfterProcessedOrMultipleLabels() throws Exception {
        SpiffingProperties.setStripDefaultLabelForFederation(true);
        var strippingInterceptor = new SecurityLabelInterceptor(() -> configuration, replies::add);
        var afterSend = message();
        afterSend.getElement().add(configuration.defaultEnvelope());
        String beforeAfterSend = afterSend.toXML();
        strippingInterceptor.interceptPacket(afterSend, outgoingServerSession(), false, true);
        assertEquals(beforeAfterSend, afterSend.toXML());

        var duplicated = message();
        duplicated.getElement().add(configuration.defaultEnvelope());
        duplicated.getElement().add(configuration.defaultEnvelope());
        String beforeDuplicated = duplicated.toXML();
        strippingInterceptor.interceptPacket(duplicated, outgoingServerSession(), false, false);
        assertEquals(beforeDuplicated, duplicated.toXML());
    }

    private static org.jivesoftware.openfire.session.IncomingServerSession incomingServerSession() {
        return (org.jivesoftware.openfire.session.IncomingServerSession) java.lang.reflect.Proxy.newProxyInstance(
            SecurityLabelInterceptorTest.class.getClassLoader(),
            new Class<?>[]{org.jivesoftware.openfire.session.IncomingServerSession.class},
            (proxy, method, args) -> { throw new AssertionError("Peer clearance checking must not depend on session state"); });
    }

    @Test void federatedInboundLabelPermittedByPeerClearanceIsAccepted() throws Exception {
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-all-okay");
        var peerInterceptor = new SecurityLabelInterceptor(() -> peerConfiguration, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        peerInterceptor.interceptPacket(message, incomingServerSession(), true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void federatedInboundLabelDeniedByPeerClearanceIsRejectedInEnforceMode() {
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        var peerInterceptor = new SecurityLabelInterceptor(() -> peerConfiguration, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        assertThrows(PacketRejectedException.class, () -> peerInterceptor.interceptPacket(message, incomingServerSession(), true, false));
        assertEquals(PacketError.Condition.forbidden, replies.get(0).getError().getCondition());
    }

    @Test void federatedInboundLabelDeniedByPeerClearanceIsWarnedAboutInWarnMode() throws Exception {
        SpiffingProperties.setEnforcementMode(EnforcementMode.WARN);
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        var peerInterceptor = new SecurityLabelInterceptor(() -> peerConfiguration, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        peerInterceptor.interceptPacket(message, incomingServerSession(), true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void localInboundIsNotCheckedAgainstPeerClearanceEvenWhenConfigured() throws Exception {
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        var peerInterceptor = new SecurityLabelInterceptor(() -> peerConfiguration, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        peerInterceptor.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void unlabelledFederatedInboundIsCheckedAgainstPeerClearanceBeforeStampingDefault() {
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        var peerInterceptor = new SecurityLabelInterceptor(() -> peerConfiguration, replies::add);
        var message = message();
        assertThrows(PacketRejectedException.class, () -> peerInterceptor.interceptPacket(message, incomingServerSession(), true, false));
        assertNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertEquals(PacketError.Condition.forbidden, replies.get(0).getError().getCondition());
    }

    @Test void egressLabelPermittedByPeerClearanceIsSentUnchanged() throws Exception {
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-all-okay");
        var peerInterceptor = new SecurityLabelInterceptor(() -> peerConfiguration, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        peerInterceptor.interceptPacket(message, outgoingServerSession(), false, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void egressLabelDeniedByPeerClearanceIsRejectedInEnforceMode() {
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        var peerInterceptor = new SecurityLabelInterceptor(() -> peerConfiguration, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        assertThrows(PacketRejectedException.class, () -> peerInterceptor.interceptPacket(message, outgoingServerSession(), false, false));
        assertEquals(PacketError.Condition.forbidden, replies.get(0).getError().getCondition());
    }

    @Test void egressLabelDeniedByPeerClearanceIsWarnedAboutAndLeavesUnchangedInWarnMode() throws Exception {
        SpiffingProperties.setEnforcementMode(EnforcementMode.WARN);
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        var peerInterceptor = new SecurityLabelInterceptor(() -> peerConfiguration, replies::add);
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        peerInterceptor.interceptPacket(message, outgoingServerSession(), false, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void egressPeerClearanceCheckIsANoOpWithoutAConfiguredPeerClearance() throws Exception {
        var message = message();
        message.getElement().add(Fixtures.envelope("food-label-water", LabelFormat.XML));
        String before = message.toXML();
        interceptor.interceptPacket(message, outgoingServerSession(), false, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void egressPeerClearanceCheckIsSkippedForLocalDeliveryAndAfterProcessed() throws Exception {
        var peerConfiguration = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        var peerInterceptor = new SecurityLabelInterceptor(() -> peerConfiguration, replies::add);
        var localMessage = message();
        localMessage.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String beforeLocal = localMessage.toXML();
        peerInterceptor.interceptPacket(localMessage, null, false, false);
        assertEquals(beforeLocal, localMessage.toXML());

        var afterSend = message();
        afterSend.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String beforeAfterSend = afterSend.toXML();
        peerInterceptor.interceptPacket(afterSend, outgoingServerSession(), false, true);
        assertEquals(beforeAfterSend, afterSend.toXML());
        assertTrue(replies.isEmpty());
    }

    private Message localMessage() {
        Message message = message();
        message.setFrom("alice@local.example/phone");
        return message;
    }

    @Test void inboundLocalLabelPermittedByUserClearanceIsAccepted() throws Exception {
        var userClearance = new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        var userInterceptor = new SecurityLabelInterceptor(() -> configuration, () -> false, username -> List.of(userClearance), replies::add);
        var message = localMessage();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        userInterceptor.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void inboundLocalLabelDeniedByUserClearanceIsRejectedInEnforceMode() {
        var userClearance = new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML);
        var userInterceptor = new SecurityLabelInterceptor(() -> configuration, () -> false, username -> List.of(userClearance), replies::add);
        var message = localMessage();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        assertThrows(PacketRejectedException.class, () -> userInterceptor.interceptPacket(message, null, true, false));
        assertEquals(PacketError.Condition.forbidden, replies.get(0).getError().getCondition());
    }

    @Test void inboundLocalLabelDeniedByUserClearanceIsWarnedAboutInWarnMode() throws Exception {
        SpiffingProperties.setEnforcementMode(EnforcementMode.WARN);
        var userClearance = new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML);
        var userInterceptor = new SecurityLabelInterceptor(() -> configuration, () -> false, username -> List.of(userClearance), replies::add);
        var message = localMessage();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        userInterceptor.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void unlabelledLocalMessageIsCheckedAgainstUserClearanceBeforeStampingDefault() {
        var userClearance = new UserClearance("alice", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML);
        var userInterceptor = new SecurityLabelInterceptor(() -> configuration, () -> false, username -> List.of(userClearance), replies::add);
        var message = localMessage();
        assertThrows(PacketRejectedException.class, () -> userInterceptor.interceptPacket(message, null, true, false));
        assertNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertEquals(PacketError.Condition.forbidden, replies.get(0).getError().getCondition());
    }

    @Test void userClearanceCheckIsANoOpWithoutAnyConfiguredUserClearance() throws Exception {
        var message = localMessage();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        interceptor.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void federatedInboundMessagesAreNotCheckedAgainstTheSenderNodeAsAUserClearance() throws Exception {
        boolean[] consulted = {false};
        var userInterceptor = new SecurityLabelInterceptor(() -> configuration, () -> false,
            username -> { consulted[0] = true; return List.of(); }, replies::add);
        var message = message(); // from a remote domain, routed via a federated session
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        userInterceptor.interceptPacket(message, incomingServerSession(), true, false);
        assertFalse(consulted[0], "Federated messages must be checked against peer clearance, not sender-node user clearance");
    }

    private Message roomMessage() {
        var message = message();
        message.setTo("room@conference.local.example");
        return message;
    }

    private SecurityLabelInterceptor roomInterceptor(java.util.function.Function<String, List<RoomClearance>> roomClearances, java.util.function.Function<String, java.util.Optional<RoomLabel>> roomLabel) {
        return new SecurityLabelInterceptor(
            () -> configuration, () -> false, username -> List.of(),
            jid -> "room@conference.local.example".equals(jid.toBareJID()), roomClearances, roomLabel, replies::add
        );
    }

    @Test void roomClearanceCheckIsANoOpWithoutAnyConfiguredRoomClearance() throws Exception {
        var noRoomClearance = roomInterceptor(jid -> List.of(), jid -> java.util.Optional.empty());
        var message = roomMessage();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        noRoomClearance.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void labelPermittedByRoomClearanceIsAccepted() throws Exception {
        var rc = new RoomClearance("room@conference.local.example", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML);
        var permissive = roomInterceptor(jid -> List.of(rc), jid -> java.util.Optional.empty());
        var message = roomMessage();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        permissive.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void labelDeniedByRoomClearanceIsRejectedInEnforceMode() {
        var rc = new RoomClearance("room@conference.local.example", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML);
        var restrictive = roomInterceptor(jid -> List.of(rc), jid -> java.util.Optional.empty());
        var message = roomMessage();
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        assertThrows(PacketRejectedException.class, () -> restrictive.interceptPacket(message, null, true, false));
        assertEquals(PacketError.Condition.forbidden, replies.get(0).getError().getCondition());
    }

    @Test void messagesNotAddressedToARoomAreUnaffectedByRoomClearance() throws Exception {
        var rc = new RoomClearance("room@conference.local.example", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML);
        var restrictive = roomInterceptor(jid -> List.of(rc), jid -> java.util.Optional.empty());
        var message = message(); // "to" is bob@local.example, not the room
        message.getElement().add(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML));
        String before = message.toXML();
        restrictive.interceptPacket(message, null, true, false);
        assertEquals(before, message.toXML());
        assertTrue(replies.isEmpty());
    }

    @Test void unlabelledMessageToARoomWithNoRoomLabelFallsBackToTheServerDefault() throws Exception {
        var noRoomLabel = roomInterceptor(jid -> List.of(), jid -> java.util.Optional.empty());
        var message = roomMessage();
        noRoomLabel.interceptPacket(message, null, true, false);
        assertEquals(configuration.defaultEnvelope().asXML(), message.getElement().element(PolicyConfiguration.ENVELOPE).asXML());
    }

    @Test void unlabelledMessageToARoomWithARoomLabelIsStampedWithTheRoomLabelItself() throws Exception {
        var rl = new RoomLabel("room@conference.local.example", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        var withRoomLabel = roomInterceptor(jid -> List.of(), jid -> java.util.Optional.of(rl));
        var message = roomMessage();
        withRoomLabel.interceptPacket(message, null, true, false);
        var expected = configuration.encodeRoomDefaultEnvelope(rl, List.of());
        assertEquals(expected.asXML(), message.getElement().element(PolicyConfiguration.ENVELOPE).asXML());
    }

    @Test void unlabelledMessageToARoomWithItsOwnDistinctDefaultIsStampedWithThatDefault() throws Exception {
        var rl = new RoomLabel(
            "room@conference.local.example", "1.2.826.0.1.6726289.0.0",
            Fixtures.read("food-label-water"), LabelFormat.XML,
            Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML
        );
        var withOwnDefault = roomInterceptor(jid -> List.of(), jid -> java.util.Optional.of(rl));
        var message = roomMessage();
        withOwnDefault.interceptPacket(message, null, true, false);
        // The default label (milk chocolate) is stamped, not the room label itself (water).
        var expected = configuration.encodeRoomDefaultEnvelope(rl, List.of());
        assertEquals(expected.asXML(), message.getElement().element(PolicyConfiguration.ENVELOPE).asXML());
        assertNotEquals(Fixtures.envelope("food-label-water", LabelFormat.ESS).asXML(), message.getElement().element(PolicyConfiguration.ENVELOPE).asXML());
    }

    @Test void unlabelledMessageToARoomIsCheckedAgainstTheRoomClearanceBeforeStamping() {
        var rl = new RoomLabel("room@conference.local.example", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null);
        var rc = new RoomClearance("room@conference.local.example", "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML);
        var restrictive = roomInterceptor(jid -> List.of(rc), jid -> java.util.Optional.of(rl));
        var message = roomMessage();
        assertThrows(PacketRejectedException.class, () -> restrictive.interceptPacket(message, null, true, false));
        assertNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertEquals(PacketError.Condition.forbidden, replies.get(0).getError().getCondition());
    }
}
