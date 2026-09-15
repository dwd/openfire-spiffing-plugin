package org.igniterealtime.openfire.spiffing;

import org.dom4j.Element;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
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
        if (!incoming || processed || !(packet instanceof Message message)) return;
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
        try {
            if (labels.size() > 1) throw new IllegalArgumentException();
            if (labels.isEmpty()) {
                message.getElement().add(snapshot.defaultEnvelope());
            } else {
                Element original = labels.get(0);
                Element checked = snapshot.check(original);
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
