package org.igniterealtime.openfire.spiffing;

import org.dom4j.Element;
import org.junit.jupiter.api.Test;
import org.xmpp.forms.DataForm;
import org.xmpp.forms.FormField;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RoomConfigFormInterceptorTest {
    private static final String ROOM_JID = "room@conference.local.example";

    static final class InMemoryRoomClearanceStore implements RoomClearanceStore {
        final List<RoomClearance> stored = new ArrayList<>();
        public List<RoomClearance> list(String roomJid) { return stored.stream().filter(rc -> rc.roomJid().equals(roomJid)).toList(); }
        public void save(RoomClearance rc) { stored.removeIf(x -> x.roomJid().equals(rc.roomJid()) && x.policyId().equals(rc.policyId())); stored.add(rc); }
        public void delete(String roomJid, String policyId) { stored.removeIf(x -> x.roomJid().equals(roomJid) && x.policyId().equals(policyId)); }
    }

    static final class InMemoryRoomLabelStore implements RoomLabelStore {
        final List<RoomLabel> stored = new ArrayList<>();
        public Optional<RoomLabel> get(String roomJid) { return stored.stream().filter(rl -> rl.roomJid().equals(roomJid)).findFirst(); }
        public void save(RoomLabel rl) { stored.removeIf(x -> x.roomJid().equals(rl.roomJid())); stored.add(rl); }
        public void delete(String roomJid) { stored.removeIf(x -> x.roomJid().equals(roomJid)); }
    }

    private IQ ownerResultIq(DataForm.Type formType) {
        IQ iq = new IQ(IQ.Type.result);
        iq.setFrom(ROOM_JID);
        iq.setTo("alice@local.example/phone");
        Element query = iq.setChildElement("query", "http://jabber.org/protocol/muc#owner");
        DataForm form = new DataForm(formType);
        query.add(form.getElement());
        return iq;
    }

    private IQ ownerSubmitIq() {
        IQ iq = new IQ(IQ.Type.set);
        iq.setFrom("alice@local.example/phone");
        iq.setTo(ROOM_JID);
        Element query = iq.setChildElement("query", "http://jabber.org/protocol/muc#owner");
        DataForm form = new DataForm(DataForm.Type.submit);
        query.add(form.getElement());
        return iq;
    }

    @Test void injectsFieldsIntoTheOutgoingFormWhenThereIsASinglePolicy() throws Exception {
        var configuration = new ConfigurationService(new ConfigurationServiceTest.InMemoryPolicyStore());
        configuration.savePolicy(Fixtures.foodPolicy());
        var roomClearances = new RoomClearanceService(new InMemoryRoomClearanceStore());
        var roomLabels = new RoomLabelService(new InMemoryRoomLabelStore());
        roomClearances.save(new RoomClearance(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        roomLabels.save(new RoomLabel(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null));

        var interceptor = new RoomConfigFormInterceptor(() -> configuration, jid -> ROOM_JID.equals(jid.toBareJID().toString()), roomClearances, roomLabels);
        IQ iq = ownerResultIq(DataForm.Type.form);
        interceptor.interceptPacket(iq, null, false, false);

        DataForm form = new DataForm(iq.getChildElement().element(org.dom4j.QName.get("x", DataForm.NAMESPACE)));
        assertEquals("1.2.826.0.1.6726289.0.0", form.getField(RoomConfigFormInterceptor.VAR_POLICY).getFirstValue());
        assertEquals(normalizeWhitespace(Fixtures.read("food-clearance-all-okay")), normalizeWhitespace(form.getField(RoomConfigFormInterceptor.VAR_CLEARANCE).getFirstValue()));
        assertEquals("XML", form.getField(RoomConfigFormInterceptor.VAR_CLEARANCE_FORMAT).getFirstValue());
        assertEquals(normalizeWhitespace(Fixtures.read("food-label-milk-chocolate")), normalizeWhitespace(form.getField(RoomConfigFormInterceptor.VAR_LABEL).getFirstValue()));
        assertNull(form.getField(RoomConfigFormInterceptor.VAR_DEFAULT_LABEL));
    }

    @Test void doesNotInjectFieldsWhenThereAreMultipleAmbiguousPolicies() throws Exception {
        var configuration = new ConfigurationService(new ConfigurationServiceTest.InMemoryPolicyStore());
        configuration.savePolicy(new SecurityPolicy(Fixtures.foodPolicy().id(), "Food", Fixtures.read("food-policy"), null, null, null, null, false));
        configuration.savePolicy(Fixtures.drinkPolicy());
        var roomClearances = new RoomClearanceService(new InMemoryRoomClearanceStore());
        var roomLabels = new RoomLabelService(new InMemoryRoomLabelStore());

        var interceptor = new RoomConfigFormInterceptor(() -> configuration, jid -> ROOM_JID.equals(jid.toBareJID().toString()), roomClearances, roomLabels);
        IQ iq = ownerResultIq(DataForm.Type.form);
        interceptor.interceptPacket(iq, null, false, false);

        DataForm form = new DataForm(iq.getChildElement().element(org.dom4j.QName.get("x", DataForm.NAMESPACE)));
        assertNull(form.getField(RoomConfigFormInterceptor.VAR_POLICY));
    }

    @Test void ignoresFormsNotAddressedByARoom() throws Exception {
        var configuration = new ConfigurationService(new ConfigurationServiceTest.InMemoryPolicyStore());
        configuration.savePolicy(Fixtures.foodPolicy());
        var roomClearances = new RoomClearanceService(new InMemoryRoomClearanceStore());
        var roomLabels = new RoomLabelService(new InMemoryRoomLabelStore());

        var interceptor = new RoomConfigFormInterceptor(() -> configuration, jid -> false, roomClearances, roomLabels);
        IQ iq = ownerResultIq(DataForm.Type.form);
        interceptor.interceptPacket(iq, null, false, false);

        DataForm form = new DataForm(iq.getChildElement().element(org.dom4j.QName.get("x", DataForm.NAMESPACE)));
        assertNull(form.getField(RoomConfigFormInterceptor.VAR_POLICY));
    }

    @Test void readsSubmittedFieldsAndSavesTheRoomClearanceAndLabel() throws Exception {
        var configuration = new ConfigurationService(new ConfigurationServiceTest.InMemoryPolicyStore());
        configuration.savePolicy(Fixtures.foodPolicy());
        var roomClearances = new RoomClearanceService(new InMemoryRoomClearanceStore());
        var roomLabels = new RoomLabelService(new InMemoryRoomLabelStore());

        var interceptor = new RoomConfigFormInterceptor(() -> configuration, jid -> ROOM_JID.equals(jid.toBareJID().toString()), roomClearances, roomLabels);
        IQ iq = ownerSubmitIq();
        DataForm form = new DataForm(iq.getChildElement().element(org.dom4j.QName.get("x", DataForm.NAMESPACE)));
        form.addField(RoomConfigFormInterceptor.VAR_POLICY, null, FormField.Type.hidden).addValue("1.2.826.0.1.6726289.0.0");
        form.addField(RoomConfigFormInterceptor.VAR_CLEARANCE, null, FormField.Type.text_multi).addValue(Fixtures.read("food-clearance-all-okay"));
        form.addField(RoomConfigFormInterceptor.VAR_CLEARANCE_FORMAT, null, FormField.Type.text_single).addValue("XML");
        form.addField(RoomConfigFormInterceptor.VAR_LABEL, null, FormField.Type.text_multi).addValue(Fixtures.read("food-label-milk-chocolate"));
        form.addField(RoomConfigFormInterceptor.VAR_LABEL_FORMAT, null, FormField.Type.text_single).addValue("XML");

        interceptor.interceptPacket(iq, null, true, false);

        assertEquals(1, roomClearances.list(ROOM_JID).size());
        assertEquals(normalizeWhitespace(Fixtures.read("food-clearance-all-okay")), normalizeWhitespace(roomClearances.list(ROOM_JID).get(0).clearance()));
        assertTrue(roomLabels.get(ROOM_JID).isPresent());
        assertEquals(normalizeWhitespace(Fixtures.read("food-label-milk-chocolate")), normalizeWhitespace(roomLabels.get(ROOM_JID).get().label()));
    }

    @Test void blankSubmittedFieldsDeleteTheExistingRoomClearanceAndLabel() throws Exception {
        var configuration = new ConfigurationService(new ConfigurationServiceTest.InMemoryPolicyStore());
        configuration.savePolicy(Fixtures.foodPolicy());
        var roomClearances = new RoomClearanceService(new InMemoryRoomClearanceStore());
        var roomLabels = new RoomLabelService(new InMemoryRoomLabelStore());
        roomClearances.save(new RoomClearance(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-clearance-all-okay"), LabelFormat.XML));
        roomLabels.save(new RoomLabel(ROOM_JID, "1.2.826.0.1.6726289.0.0", Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, null, null));

        var interceptor = new RoomConfigFormInterceptor(() -> configuration, jid -> ROOM_JID.equals(jid.toBareJID().toString()), roomClearances, roomLabels);
        IQ iq = ownerSubmitIq();
        DataForm form = new DataForm(iq.getChildElement().element(org.dom4j.QName.get("x", DataForm.NAMESPACE)));
        form.addField(RoomConfigFormInterceptor.VAR_POLICY, null, FormField.Type.hidden).addValue("1.2.826.0.1.6726289.0.0");
        form.addField(RoomConfigFormInterceptor.VAR_CLEARANCE, null, FormField.Type.text_multi);
        form.addField(RoomConfigFormInterceptor.VAR_LABEL, null, FormField.Type.text_multi);

        interceptor.interceptPacket(iq, null, true, false);

        assertTrue(roomClearances.list(ROOM_JID).isEmpty());
        assertTrue(roomLabels.get(ROOM_JID).isEmpty());
    }

    private static String normalizeWhitespace(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    @Test void ignoresNonMucOwnerIqs() throws Exception {
        var configuration = new ConfigurationService(new ConfigurationServiceTest.InMemoryPolicyStore());
        var roomClearances = new RoomClearanceService(new InMemoryRoomClearanceStore());
        var roomLabels = new RoomLabelService(new InMemoryRoomLabelStore());
        var interceptor = new RoomConfigFormInterceptor(() -> configuration, jid -> true, roomClearances, roomLabels);
        assertDoesNotThrow(() -> interceptor.interceptPacket(new IQ(), null, true, false));
        assertDoesNotThrow(() -> interceptor.interceptPacket(new org.xmpp.packet.Message(), null, true, false));
    }
}
