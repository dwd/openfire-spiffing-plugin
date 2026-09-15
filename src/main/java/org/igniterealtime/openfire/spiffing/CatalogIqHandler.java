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
 * Only requests from local entities are served; the requested {@code to=} attribute inside
 * {@code <catalog/>} is ignored, since this plugin only ever publishes one, server-wide catalogue.
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
        Element result;
        try {
            result = catalog.buildCatalog();
        } catch (IllegalStateException e) {
            return error(packet, PacketError.Condition.service_unavailable);
        }
        IQ response = IQ.createResultIQ(packet);
        response.setChildElement(result);
        return response;
    }

    private static IQ error(IQ packet, PacketError.Condition condition) {
        IQ response = IQ.createResultIQ(packet);
        response.setError(condition);
        return response;
    }

    @Override
    public IQHandlerInfo getInfo() { return info; }
}
