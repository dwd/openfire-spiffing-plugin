package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import static org.junit.jupiter.api.Assertions.*;

class CatalogIqHandlerTest {
    private static IQ request(IQ.Type type, String from) {
        return request(type, from, null);
    }

    private static IQ request(IQ.Type type, String from, String to) {
        IQ iq = new IQ(type);
        iq.setFrom(new JID(from));
        iq.setTo("example.com");
        var catalog = iq.setChildElement("catalog", CatalogService.NAMESPACE);
        if (to != null) catalog.addAttribute("to", to);
        return iq;
    }

    /** Local to this server ({@code example.com}); a different domain is treated as a federated peer. */
    private static final java.util.function.Predicate<JID> IS_LOCAL_DOMAIN = jid -> "example.com".equals(jid.getDomain());

    @Test void returnsCatalogForALocalGetRequest() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> configuration);
        service.add("Milk chocolate", null, LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), true);
        var handler = new CatalogIqHandler(service, jid -> true);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com"));
        assertEquals(IQ.Type.result, response.getType());
        assertEquals(CatalogService.NAMESPACE, response.getChildElement().getNamespaceURI());
        assertFalse(response.getChildElement().elements().isEmpty());
    }

    @Test void rejectsNonGetRequests() {
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> new PolicyConfiguration(Fixtures.settings()));
        var handler = new CatalogIqHandler(service, jid -> true);
        IQ response = handler.handleIQ(request(IQ.Type.set, "user@example.com"));
        assertEquals(PacketError.Condition.bad_request, response.getError().getCondition());
    }

    @Test void rejectsNonLocalRequesters() {
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> new PolicyConfiguration(Fixtures.settings()));
        var handler = new CatalogIqHandler(service, jid -> false);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@remote.example"));
        assertEquals(PacketError.Condition.not_authorized, response.getError().getCondition());
    }

    @Test void missingConfigurationFailsClosed() {
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> null);
        var handler = new CatalogIqHandler(service, jid -> true);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com"));
        assertEquals(PacketError.Condition.service_unavailable, response.getError().getCondition());
    }

    @Test void aLocalToAttributeDoesNotRequireThePeerClearance() {
        // Denied by the peer clearance, but permitted by the server clearance.
        var configuration = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-lactose-intolerant", EnforcementMode.ENFORCE));
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> configuration);
        service.add("Milk chocolate", null, LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), true);
        var handler = new CatalogIqHandler(service, IS_LOCAL_DOMAIN);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com", "other@example.com"));
        assertEquals(IQ.Type.result, response.getType());
        assertFalse(response.getChildElement().elements().isEmpty());
    }

    @Test void aFederatedToAttributeAlsoRequiresThePeerClearance() {
        // Denied by the peer clearance, but permitted by the server clearance.
        var configuration = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-lactose-intolerant", EnforcementMode.ENFORCE));
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> configuration);
        service.add("Milk chocolate", null, LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), true);
        var handler = new CatalogIqHandler(service, IS_LOCAL_DOMAIN);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com", "user@remote.example"));
        assertEquals(IQ.Type.result, response.getType());
        assertTrue(response.getChildElement().elements().isEmpty());
    }

    @Test void aMalformedToAttributeIsRejectedAsABadRequest() {
        var service = new CatalogService(new CatalogServiceTest.Store(), () -> new PolicyConfiguration(Fixtures.settings()));
        var handler = new CatalogIqHandler(service, IS_LOCAL_DOMAIN);
        IQ response = handler.handleIQ(request(IQ.Type.get, "user@example.com", "not a valid jid"));
        assertEquals(PacketError.Condition.bad_request, response.getError().getCondition());
    }
}
