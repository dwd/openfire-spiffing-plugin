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
    private final Supplier<Boolean> corrupted;
    private final Consumer<Message> reply;

    public SecurityLabelInterceptor(Supplier<PolicyConfiguration> configuration, Consumer<Message> reply) {
        this(configuration, () -> false, reply);
    }

    public SecurityLabelInterceptor(Supplier<PolicyConfiguration> configuration, Supplier<Boolean> corrupted, Consumer<Message> reply) {
        this.configuration = configuration;
        this.corrupted = corrupted;
        this.reply = reply;
    }

    @Override
    public void interceptPacket(Packet packet, Session session, boolean incoming, boolean processed) throws PacketRejectedException {
        if (!(packet instanceof Message message)) return;
        if (!incoming) {
            if (!processed && session instanceof OutgoingServerSession) {
                checkPeerClearanceForFederation(message);
                stripDefaultLabelForFederation(message);
            }
            return;
        }
        if (processed) return;
        var labels = message.getElement().elements(PolicyConfiguration.ENVELOPE);
        if (message.getType() == Message.Type.error) {
            if (!labels.isEmpty()) throw new PacketRejectedException();
            return;
        }
        PolicyConfiguration snapshot = configuration.get();
        if (snapshot == null || !snapshot.hasPolicies()) {
            if (corrupted.get()) reject(message, PacketError.Condition.service_unavailable);
            return;
        }
        boolean fromPeer = session instanceof IncomingServerSession;
        String peerDomain = fromPeer && message.getFrom() != null ? message.getFrom().getDomain() : "";
        EnforcementMode enforcementMode = SpiffingProperties.getEnforcementMode();
        try {
            if (labels.size() > 1) throw new IllegalArgumentException("Multiple security label envelopes are not permitted.");
            if (labels.isEmpty()) {
                if (snapshot.hasDefaultLabel()) {
                    if (fromPeer) snapshot.checkDefaultPeerClearance(peerDomain);
                    message.getElement().add(snapshot.defaultEnvelope());
                }
            } else {
                Element original = labels.get(0);
                Element checked = snapshot.check(original, fromPeer, peerDomain);
                if (checked != original) {
                    original.detach();
                    message.getElement().add(checked);
                }
            }
        } catch (RuntimeException e) {
            if (enforcementMode == EnforcementMode.ENFORCE) {
                reject(message, PacketError.Condition.forbidden);
            } else {
                LOG.warn("Security label check failed for message from {} to {} (id {}): {}. Enforcement mode is warn, so the message was allowed through.",
                    message.getFrom(), message.getTo(), message.getID(), e.getMessage());
            }
        }
    }

    private void checkPeerClearanceForFederation(Message message) throws PacketRejectedException {
        PolicyConfiguration snapshot = configuration.get();
        if (snapshot == null || !snapshot.hasPolicies()) return;
        var labels = message.getElement().elements(PolicyConfiguration.ENVELOPE);
        if (labels.size() != 1) return;
        String peerDomain = message.getTo() != null ? message.getTo().getDomain() : "";
        EnforcementMode enforcementMode = SpiffingProperties.getEnforcementMode();
        try {
            snapshot.checkPeerClearance(labels.get(0), peerDomain);
        } catch (RuntimeException e) {
            if (enforcementMode == EnforcementMode.ENFORCE) {
                reject(message, PacketError.Condition.forbidden);
            } else {
                LOG.warn("Peer clearance check failed for message from {} to {} (id {}) leaving for another server: {}. Enforcement mode is warn, so the message was allowed through.",
                    message.getFrom(), message.getTo(), message.getID(), e.getMessage());
            }
        }
    }

    private void stripDefaultLabelForFederation(Message message) {
        PolicyConfiguration snapshot = configuration.get();
        if (snapshot == null || !SpiffingProperties.isStripDefaultLabelForFederation()) return;
        var labels = message.getElement().elements(PolicyConfiguration.ENVELOPE);
        if (labels.size() != 1) return;
        String defaultMarking = snapshot.defaultDisplayMarking();
        if (defaultMarking == null || defaultMarking.isEmpty()) return;
        try {
            if (defaultMarking.equals(PolicyConfiguration.displayMarking(labels.get(0)))) {
                labels.get(0).detach();
            }
        } catch (RuntimeException e) {
            // Ignore malformed label here
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
        throw new PacketRejectedException();
    }
}
