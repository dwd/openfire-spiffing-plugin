package org.igniterealtime.openfire.spiffing;

import org.dom4j.Element;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.jivesoftware.openfire.session.IncomingServerSession;
import org.jivesoftware.openfire.session.OutgoingServerSession;
import org.jivesoftware.openfire.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.Message;
import org.xmpp.packet.Packet;
import org.xmpp.packet.PacketError;

import java.util.function.Consumer;
import java.util.function.Supplier;

public final class SecurityLabelInterceptor implements PacketInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(SecurityLabelInterceptor.class);
    private final Supplier<PolicyConfiguration> configuration;
    private final Consumer<Message> reply;

    public SecurityLabelInterceptor(Supplier<PolicyConfiguration> configuration, Consumer<Message> reply) {
        this.configuration = configuration;
        this.reply = reply;
    }

    @Override
    public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed) throws PacketRejectedException {
        if (!(packet instanceof Message message)) return;
        if (!incoming) {
            // Egress: before a message leaves for another server, check its effective label against the
            // configured peer clearance (a no-op when none is configured), following the same enforcement
            // mode as every other label check. This runs before the optional, best-effort default-label
            // stripping below, so a stripped label was still checked against the peer clearance first.
            if (!processed && session instanceof OutgoingServerSession) {
                checkPeerClearanceForFederation(message);
                stripDefaultLabelForFederation(message);
            }
            return;
        }
        if (processed) return;
        var labels = message.getElement().elements(PolicyConfiguration.ENVELOPE);
        // XEP-0258 §6: errors are not authorized or stamped, and labelled errors are discarded.
        if (message.getType() == Message.Type.error) {
            if (!labels.isEmpty()) throw new PacketRejectedException();
            return;
        }
        PolicyConfiguration snapshot = configuration.get();
        if (snapshot == null) {
            reject(message, PacketError.Condition.service_unavailable);
            return;
        }
        // Ingress: a message arriving from a federated peer is also checked against the peer clearance,
        // in addition to the server clearance that every inbound message is always checked against.
        boolean fromPeer = session instanceof IncomingServerSession;
        try {
            if (labels.size() > 1) throw new IllegalArgumentException();
            if (labels.isEmpty()) {
                if (fromPeer) snapshot.checkDefaultPeerClearance();
                message.getElement().add(snapshot.defaultEnvelope());
            } else {
                Element original = labels.get(0);
                Element checked = snapshot.check(original, fromPeer);
                if (checked != original) {
                    original.detach();
                    message.getElement().add(checked);
                }
            }
        } catch (RuntimeException e) {
            if (snapshot.settings().enforcementMode() == EnforcementMode.ENFORCE) {
                reject(message, PacketError.Condition.forbidden);
            } else {
                // Warn mode: log without the message body/label content and let the message through unchanged.
                LOG.warn("Security label check failed for message from {} to {} (id {}): {}. Enforcement mode is warn, so the message was allowed through.",
                    message.getFrom(), message.getTo(), message.getID(), e.getMessage());
            }
        }
    }

    /**
     * Checks an outbound message's effective label against the configured peer clearance before it leaves
     * for another server, following {@code Settings.enforcementMode} exactly like every other label check
     * (ENFORCE rejects, WARN logs and lets the message through unchanged). A missing/unconfigured snapshot,
     * no configured peer clearance, an absent label, or more than one label are all left unchecked here,
     * matching {@link #stripDefaultLabelForFederation}'s fail-open scope for those same conditions.
     */
    private void checkPeerClearanceForFederation(Message message) throws PacketRejectedException {
        PolicyConfiguration snapshot = configuration.get();
        if (snapshot == null) return;
        var labels = message.getElement().elements(PolicyConfiguration.ENVELOPE);
        if (labels.size() != 1) return;
        try {
            snapshot.checkPeerClearance(labels.get(0));
        } catch (RuntimeException e) {
            if (snapshot.settings().enforcementMode() == EnforcementMode.ENFORCE) {
                reject(message, PacketError.Condition.forbidden);
            } else {
                LOG.warn("Peer clearance check failed for message from {} to {} (id {}) leaving for another server: {}. Enforcement mode is warn, so the message was allowed through.",
                    message.getFrom(), message.getTo(), message.getID(), e.getMessage());
            }
        }
    }

    /**
     * Removes an outbound label whose display marking matches the configured default's, so that
     * federated traffic does not gratuitously carry the default label to another server. Only a single,
     * well-formed envelope with a non-empty marking equal to the default's is eligible; anything else
     * (no configuration, the option disabled, no label, more than one label, or a different/absent
     * marking) is left completely unchanged.
     */
    private void stripDefaultLabelForFederation(Message message) {
        PolicyConfiguration snapshot = configuration.get();
        if (snapshot == null || !snapshot.settings().stripDefaultLabelForFederation()) return;
        var labels = message.getElement().elements(PolicyConfiguration.ENVELOPE);
        if (labels.size() != 1) return;
        String defaultMarking = snapshot.defaultDisplayMarking();
        if (defaultMarking == null || defaultMarking.isEmpty()) return;
        try {
            if (defaultMarking.equals(PolicyConfiguration.displayMarking(labels.get(0)))) labels.get(0).detach();
        } catch (RuntimeException e) {
            // A malformed label is not our concern here; leave the message exactly as it arrived.
        }
    }

    private void reject(Message message, PacketError.Condition condition) throws PacketRejectedException {
        if (message.getFrom() != null) {
            Message error = new Message();
            error.setID(message.getID());
            error.setTo(message.getFrom());
            error.setFrom(message.getTo());
            error.setError(new PacketError(condition, PacketError.Type.cancel));
            try {
                reply.accept(error);
            } catch (RuntimeException e) {
                // Delivery failure must never allow the rejected original to proceed.
            }
        }
        // Replies are sent explicitly for both local and remote senders. No router-generated second reply.
        throw new PacketRejectedException();
    }
}
