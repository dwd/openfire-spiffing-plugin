package org.igniterealtime.openfire.spiffing;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.jivesoftware.openfire.IQHandlerInfo;
import org.jivesoftware.openfire.handler.IQHandler;
import org.xmpp.packet.IQ;
import org.xmpp.packet.JID;
import org.xmpp.packet.PacketError;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Answers requests for the Spiffing policies currently loaded into the active configuration
 * ({@code <policy/>}, {@link #NAMESPACE}), a namespace extending XEP-0258's {@code urn:xmpp:sec-label:0}.
 * Only requests from local entities are served, since a loaded policy's full content is administrator-
 * curated server configuration, not something this plugin publishes to federated peers.
 * <p>
 * A request with neither an {@code id} nor a {@code name} attribute lists every loaded policy's id and
 * name, in load order (the primary policy first). A request naming a specific loaded policy via either
 * attribute returns that policy's original Open XML SPIF document; an {@code id} attribute takes priority
 * over a {@code name} attribute if both are present.
 */
public final class PolicyIqHandler extends IQHandler {
    public static final String NAMESPACE = "urn:xmpp:sec-label:policy:0";
    private final IQHandlerInfo info = new IQHandlerInfo("policy", NAMESPACE);
    private final Supplier<PolicyConfiguration> configuration;
    private final Predicate<JID> isLocal;

    public PolicyIqHandler(Supplier<PolicyConfiguration> configuration, Predicate<JID> isLocal) {
        super("Spiffing Policy Handler");
        this.configuration = configuration;
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
        PolicyConfiguration current = configuration.get();
        if (current == null) {
            return error(packet, PacketError.Condition.service_unavailable);
        }
        Element request = packet.getChildElement();
        String id = request == null ? null : request.attributeValue("id");
        String name = request == null ? null : request.attributeValue("name");
        Element result;
        if (id == null && name == null) {
            result = buildList(current);
        } else {
            PolicyConfiguration.LoadedPolicy loaded;
            try {
                loaded = id != null ? current.loadedPolicyById(id) : current.loadedPolicyByName(name);
            } catch (IllegalArgumentException e) {
                return error(packet, PacketError.Condition.item_not_found);
            }
            result = buildDocument(current, loaded);
        }
        IQ response = IQ.createResultIQ(packet);
        response.setChildElement(result);
        return response;
    }

    private static Element buildList(PolicyConfiguration current) {
        Element policy = DocumentHelper.createElement(QName.get("policy", NAMESPACE));
        for (PolicyConfiguration.LoadedPolicy loaded : current.loadedPolicies()) {
            Element item = policy.addElement(QName.get("item", NAMESPACE));
            item.addAttribute("id", loaded.id());
            item.addAttribute("name", loaded.name());
        }
        return policy;
    }

    private static Element buildDocument(PolicyConfiguration current, PolicyConfiguration.LoadedPolicy loaded) {
        Element policy = DocumentHelper.createElement(QName.get("policy", NAMESPACE));
        policy.addAttribute("id", loaded.id());
        policy.addAttribute("name", loaded.name());
        policy.add(SecureXml.parse(current.exportedDocument(loaded), SecureXml.MAX_DOCUMENT));
        return policy;
    }

    private static IQ error(IQ packet, PacketError.Condition condition) {
        IQ response = IQ.createResultIQ(packet);
        response.setError(condition);
        return response;
    }

    @Override
    public IQHandlerInfo getInfo() { return info; }
}
