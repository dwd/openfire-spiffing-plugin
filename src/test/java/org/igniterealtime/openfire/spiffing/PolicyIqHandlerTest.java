package org.igniterealtime.openfire.spiffing;

import org.dom4j.QName;
import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import static org.junit.jupiter.api.Assertions.*;

class PolicyIqHandlerTest {
    private static IQ request(IQ.Type type, String from, String id, String name) {
        IQ iq = new IQ(type);
        iq.setFrom(new JID(from));
        iq.setTo("example.com");
        var policy = iq.setChildElement("policy", PolicyIqHandler.NAMESPACE);
        if (id != null) policy.addAttribute("id", id);
        if (name != null) policy.addAttribute("name", name);
        return iq;
    }

    @Test void listsEveryLoadedPolicyByIdAndName() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithSecondPolicy());
        var handler = new PolicyIqHandler(() -> configuration, jid -> true);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com", null, null));
        assertEquals(IQ.Type.result, response.getType());
        var items = response.getChildElement().elements(QName.get("item", PolicyIqHandler.NAMESPACE));
        assertEquals(2, items.size());
        assertEquals("1.2.826.0.1.6726289.0.0", items.get(0).attributeValue("id"));
        assertEquals("Food", items.get(0).attributeValue("name"));
        assertEquals("1.2.826.0.1.6726289.0.1", items.get(1).attributeValue("id"));
        assertEquals("Drink", items.get(1).attributeValue("name"));
    }

    @Test void returnsTheRequestedPolicyDocumentById() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var handler = new PolicyIqHandler(() -> configuration, jid -> true);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com", "1.2.826.0.1.6726289.0.0", null));
        assertEquals(IQ.Type.result, response.getType());
        var policy = response.getChildElement();
        assertEquals("1.2.826.0.1.6726289.0.0", policy.attributeValue("id"));
        assertEquals("Food", policy.attributeValue("name"));
        assertNotNull(policy.element("SPIF"));
    }

    @Test void returnsTheRequestedPolicyDocumentByName() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var handler = new PolicyIqHandler(() -> configuration, jid -> true);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com", null, "Food"));
        assertEquals(IQ.Type.result, response.getType());
        assertEquals("1.2.826.0.1.6726289.0.0", response.getChildElement().attributeValue("id"));
    }

    @Test void anIdAttributeTakesPriorityOverANameAttribute() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithSecondPolicy());
        var handler = new PolicyIqHandler(() -> configuration, jid -> true);
        // A mismatched name is ignored, since the id attribute is present and matches.
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com", "1.2.826.0.1.6726289.0.1", "Food"));
        assertEquals(IQ.Type.result, response.getType());
        assertEquals("Drink", response.getChildElement().attributeValue("name"));
    }

    @Test void anUnknownIdOrNameIsRejectedAsItemNotFound() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var handler = new PolicyIqHandler(() -> configuration, jid -> true);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com", "not-a-policy", null));
        assertEquals(PacketError.Condition.item_not_found, response.getError().getCondition());
    }

    @Test void rejectsNonGetRequests() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var handler = new PolicyIqHandler(() -> configuration, jid -> true);
        IQ response = handler.handleIQ(request(IQ.Type.set, "user@example.com", null, null));
        assertEquals(PacketError.Condition.bad_request, response.getError().getCondition());
    }

    @Test void rejectsNonLocalRequesters() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var handler = new PolicyIqHandler(() -> configuration, jid -> false);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@remote.example", null, null));
        assertEquals(PacketError.Condition.not_authorized, response.getError().getCondition());
    }

    @Test void missingConfigurationFailsClosed() {
        var handler = new PolicyIqHandler(() -> null, jid -> true);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com", null, null));
        assertEquals(PacketError.Condition.service_unavailable, response.getError().getCondition());
    }
}
