package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.xmpp.packet.*;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SecurityLabelInterceptorTest {
    private final PolicyConfiguration configuration = new PolicyConfiguration(Fixtures.settings());
    private final List<Message> replies = new ArrayList<>();
    private final SecurityLabelInterceptor interceptor = new SecurityLabelInterceptor(() -> configuration, replies::add);

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
        var interceptor = new SecurityLabelInterceptor(() -> null, replies::add);
        var message = message();
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message, null, true, false));
        assertNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        assertEquals(PacketError.Condition.service_unavailable, replies.get(0).getError().getCondition());
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
        var interceptor = new SecurityLabelInterceptor(() -> null, reply -> { throw new IllegalStateException("no route"); });
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message(), null, true, false));
    }

    @Test void absentSenderCannotCreateUnaddressedError() {
        var message = message();
        message.setFrom((JID) null);
        var interceptor = new SecurityLabelInterceptor(() -> null, replies::add);
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(message, null, true, false));
        assertTrue(replies.isEmpty());
    }

    @Test void readsExactlyOneSnapshotPerMessage() throws Exception {
        int[] reads = {0};
        var interceptor = new SecurityLabelInterceptor(() -> { reads[0]++; return configuration; }, replies::add);
        interceptor.interceptPacket(message(), null, true, false);
        assertEquals(1, reads[0]);
    }
}
