package org.igniterealtime.openfire.spiffing;

import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.forms.DataForm;
import org.xmpp.forms.FormField;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.Packet;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Surfaces a room's Spiffing clearance and label as extra fields in the standard {@code muc#owner}
 * room configuration form, alongside the dedicated Admin Console page ({@code spiffing-room-clearance.jsp}).
 * There is no official Openfire extension point for adding fields to this form, so this works by
 * injecting/reading extra {@code <field/>} elements, namespaced under {@link PolicyConfiguration#NAMESPACE}
 * so they cannot collide with XEP-0045's own field vars; {@code IQOwnerHandler} silently ignores fields it
 * does not recognize, so this is safe even though it is not an official API (see doc/design.md's "Room
 * clearance and room label" section for this coupling's known limitations: it is tied to the current shape
 * of Openfire's {@code muc#owner} form and could silently stop working if that ever changes).
 * <p>
 * Only applies when there is a single resolvable policy (the server's default policy, or the sole
 * configured policy); with zero or multiple ambiguous policies, the extra fields are omitted, and the
 * Admin Console page remains the only way to manage that room's clearance/label.
 */
final class RoomConfigFormInterceptor implements PacketInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(RoomConfigFormInterceptor.class);

    static final String VAR_POLICY = "{" + PolicyConfiguration.NAMESPACE + "}muc#roomconfig_spiffingpolicy";
    static final String VAR_CLEARANCE = "{" + PolicyConfiguration.NAMESPACE + "}muc#roomconfig_clearance";
    static final String VAR_CLEARANCE_FORMAT = "{" + PolicyConfiguration.NAMESPACE + "}muc#roomconfig_clearanceformat";
    static final String VAR_LABEL = "{" + PolicyConfiguration.NAMESPACE + "}muc#roomconfig_label";
    static final String VAR_LABEL_FORMAT = "{" + PolicyConfiguration.NAMESPACE + "}muc#roomconfig_labelformat";
    static final String VAR_DEFAULT_LABEL = "{" + PolicyConfiguration.NAMESPACE + "}muc#roomconfig_defaultlabel";
    static final String VAR_DEFAULT_LABEL_FORMAT = "{" + PolicyConfiguration.NAMESPACE + "}muc#roomconfig_defaultlabelformat";

    private static final String MUC_OWNER_NAMESPACE = "http://jabber.org/protocol/muc#owner";

    private final Supplier<ConfigurationService> configurationService;
    private final Predicate<JID> isRoom;
    private final RoomClearanceService roomClearances;
    private final RoomLabelService roomLabels;

    RoomConfigFormInterceptor(
        Supplier<ConfigurationService> configurationService,
        Predicate<JID> isRoom,
        RoomClearanceService roomClearances,
        RoomLabelService roomLabels
    ) {
        this.configurationService = configurationService;
        this.isRoom = isRoom;
        this.roomClearances = roomClearances;
        this.roomLabels = roomLabels;
    }

    @Override
    public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed) throws PacketRejectedException {
        if (processed || !(packet instanceof IQ iq)) return;
        Element query = iq.getChildElement();
        if (query == null || !"query".equals(query.getName()) || !MUC_OWNER_NAMESPACE.equals(query.getNamespaceURI())) {
            return;
        }
        Element xElement = query.element(QName.get(DataForm.ELEMENT_NAME, DataForm.NAMESPACE));
        if (xElement == null) return;

        if (!incoming) {
            // Outgoing config-form response: inject our fields into the form being shown to the owner.
            if (iq.getType() != IQ.Type.result) return;
            JID from = iq.getFrom();
            if (from == null || !isRoom.test(from)) return;
            DataForm form = new DataForm(xElement);
            if (form.getType() != DataForm.Type.form) return;
            injectFields(form, from.toBareJID().toString());
            return;
        }

        // Incoming config-form submission: read our fields back out before IQOwnerHandler processes them.
        if (iq.getType() != IQ.Type.set) return;
        JID to = iq.getTo();
        if (to == null || !isRoom.test(to)) return;
        DataForm form = new DataForm(xElement);
        if (form.getType() != DataForm.Type.submit) return;
        try {
            readFields(form, to.toBareJID().toString());
        } catch (RuntimeException e) {
            LOG.warn("Ignoring invalid Spiffing room configuration submitted for {}: {}", to, e.getMessage());
        }
    }

    private void injectFields(DataForm form, String roomJid) {
        ConfigurationService configService = configurationService.get();
        if (configService == null) return;
        Optional<SecurityPolicy> policy = resolvePolicy(configService);
        if (policy.isEmpty()) return;
        String policyId = policy.get().id();

        form.addField(VAR_POLICY, "Spiffing policy", FormField.Type.hidden).addValue(policyId);

        roomClearances.list(roomJid).stream()
            .filter(rc -> rc.policyId().equals(policyId))
            .findFirst()
            .ifPresent(rc -> {
                form.addField(VAR_CLEARANCE, "Spiffing room clearance", FormField.Type.text_multi).addValue(rc.clearance());
                form.addField(VAR_CLEARANCE_FORMAT, "Spiffing room clearance format", FormField.Type.text_single).addValue(rc.format().name());
            });

        roomLabels.get(roomJid).filter(rl -> rl.policyId().equals(policyId)).ifPresent(rl -> {
            form.addField(VAR_LABEL, "Spiffing room label", FormField.Type.text_multi).addValue(rl.label());
            form.addField(VAR_LABEL_FORMAT, "Spiffing room label format", FormField.Type.text_single).addValue(rl.format().name());
            if (rl.hasOwnDefaultLabel()) {
                form.addField(VAR_DEFAULT_LABEL, "Spiffing room default label", FormField.Type.text_multi).addValue(rl.defaultLabel());
                form.addField(VAR_DEFAULT_LABEL_FORMAT, "Spiffing room default label format", FormField.Type.text_single).addValue(rl.defaultLabelFormat().name());
            }
        });
    }

    private void readFields(DataForm form, String roomJid) {
        FormField policyField = form.getField(VAR_POLICY);
        String policyId = policyField != null ? policyField.getFirstValue() : null;
        if (policyId == null || policyId.isBlank()) return;

        FormField clearanceField = form.getField(VAR_CLEARANCE);
        if (clearanceField != null) {
            String clearance = clearanceField.getFirstValue();
            if (clearance == null || clearance.isBlank()) {
                roomClearances.delete(roomJid, policyId);
            } else {
                FormField clearanceFormatField = form.getField(VAR_CLEARANCE_FORMAT);
                String formatStr = clearanceFormatField != null ? clearanceFormatField.getFirstValue() : null;
                LabelFormat format = formatStr != null && !formatStr.isBlank() ? LabelFormat.valueOf(formatStr) : LabelFormat.ESS;
                roomClearances.save(new RoomClearance(roomJid, policyId, clearance, format));
            }
        }

        FormField labelField = form.getField(VAR_LABEL);
        if (labelField == null) return;
        String label = labelField.getFirstValue();
        if (label == null || label.isBlank()) {
            roomLabels.delete(roomJid);
            return;
        }

        FormField labelFormatField = form.getField(VAR_LABEL_FORMAT);
        String labelFormatStr = labelFormatField != null ? labelFormatField.getFirstValue() : null;
        LabelFormat labelFormat = labelFormatStr != null && !labelFormatStr.isBlank() ? LabelFormat.valueOf(labelFormatStr) : LabelFormat.XML;

        FormField defaultLabelField = form.getField(VAR_DEFAULT_LABEL);
        String defaultLabel = defaultLabelField != null ? defaultLabelField.getFirstValue() : null;
        LabelFormat defaultLabelFormat = null;
        if (defaultLabel != null && !defaultLabel.isBlank()) {
            FormField defaultLabelFormatField = form.getField(VAR_DEFAULT_LABEL_FORMAT);
            String defaultLabelFormatStr = defaultLabelFormatField != null ? defaultLabelFormatField.getFirstValue() : null;
            defaultLabelFormat = defaultLabelFormatStr != null && !defaultLabelFormatStr.isBlank() ? LabelFormat.valueOf(defaultLabelFormatStr) : LabelFormat.XML;
        } else {
            defaultLabel = null;
        }

        roomLabels.save(new RoomLabel(roomJid, policyId, label, labelFormat, defaultLabel, defaultLabelFormat));
    }

    private static Optional<SecurityPolicy> resolvePolicy(ConfigurationService configService) {
        List<SecurityPolicy> policies = configService.policies();
        if (policies.isEmpty()) return Optional.empty();
        Optional<SecurityPolicy> def = policies.stream().filter(SecurityPolicy::isDefault).findFirst();
        if (def.isPresent()) return def;
        return policies.size() == 1 ? Optional.of(policies.get(0)) : Optional.empty();
    }
}
