package org.igniterealtime.openfire.spiffing;

import org.dom4j.Element;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.handler.IQHandler;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import java.util.function.Predicate;

/**
 * Answers XEP-0258 label catalogue discovery ({@code <catalog/>}, {@code urn:xmpp:sec-label:catalog:2}).
 * Only requests from local entities are served. This plugin still only ever publishes one, server-wide
 * catalogue, but the requested {@code to=} attribute inside {@code <catalog/>} is used to decide which
 * ACDF checks each entry must additionally pass: when {@code to=} names a recipient that is not local to
 * this server, a message carrying a catalogue label to that recipient would also have to pass the egress
 * peer-clearance check, so entries that would fail it are omitted from the response exactly like entries
 * that fail the (always-checked) server clearance.
 */
public final class CatalogIqHandler extends IQHandler {
    private final IQHandlerInfo info = new IQHandlerInfo("catalog", CatalogService.NAMESPACE);
    private final CatalogService catalog;
    private final Predicate<JID> isLocal;

    public CatalogIqHandler(CatalogService catalog, Predicate<JID> isLocal) {
        super("Spiffing Security Label Catalogue Handler");
        this.catalog = catalog;
        this.isLocal = isLocal;
    }

    @Override
    public IQ handleIQ(IQ packet) {
        if (packet.getType() != IQ.Type.get) {
            return error(packet, PacketError.Condition.bad_request);
        }
        if (!isLocal.test(packet.getFrom())) {
            return error(packet, PacketError.Condition.not_authorized);
        }
        boolean checkPeerClearance;
        try {
            checkPeerClearance = requiresPeerClearance(packet);
        } catch (IllegalArgumentException e) {
            return error(packet, PacketError.Condition.bad_request);
        }
        Element result;
        try {
            result = catalog.buildCatalog(checkPeerClearance);
        } catch (IllegalStateException e) {
            return error(packet, PacketError.Condition.service_unavailable);
        }
        IQ response = IQ.createResultIQ(packet);
        response.setChildElement(result);
        return response;
    }

    /**
     * Whether a message carrying a catalogue label to the requested {@code to=} recipient would also be
     * subject to the egress peer-clearance check, i.e. whether that recipient is not local to this server.
     * A missing or empty {@code to=} attribute names no specific recipient, so only the (always-checked)
     * server clearance applies.
     *
     * @throws IllegalArgumentException if {@code to=} is present but not a valid JID.
     */
    private boolean requiresPeerClearance(IQ packet) {
        Element request = packet.getChildElement();
        String to = request == null ? null : request.attributeValue("to");
        if (to == null || to.isEmpty()) return false;
        return !isLocal.test(new JID(to));
    }

    private static IQ error(IQ packet, PacketError.Condition condition) {
        IQ response = IQ.createResultIQ(packet);
        response.setError(condition);
        return response;
    }

    @Override
    public IQHandlerInfo getInfo() { return info; }
}
