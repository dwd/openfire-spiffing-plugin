package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Clearance;
import io.cridland.spiffing.Label;
import io.cridland.spiffing.Site;
import io.cridland.spiffing.Spif;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;

/** A privately owned policy registry and clearance, never mutated after publication. */
public final class PolicyConfiguration {
    public static final String NAMESPACE = "urn:xmpp:sec-label:0";
    public static final String ESS_NAMESPACE = "urn:xmpp:sec-label:ess:0";
    public static final String NATO_NAMESPACE = "urn:nato:stanag:4774:confidentialitymetadatalabel:1:0";
    public static final String XML_NAMESPACE = "http://surevine.com/xmlns/spiffy";
    static final QName ENVELOPE = QName.get("securitylabel", NAMESPACE);
    private final Settings settings;
    private final Site site;
    private final Spif policy;
    private final Clearance clearance;
    private final Label defaultLabel;
    private final Element defaultEnvelope;

    public PolicyConfiguration(Settings settings) {
        this.settings = settings;
        site = new Site();
        try {
            policy = site.load(settings.policy());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The policy is not a valid Open XML SPIF.");
        }
        try {
            clearance = site.clearance(settings.clearanceFormat().decode(settings.clearance()), settings.clearanceFormat().format);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The clearance is invalid or does not belong to the policy.");
        }
        try {
            defaultLabel = site.label(settings.labelFormat().decode(settings.defaultLabel()), settings.labelFormat().format);
            authorize(defaultLabel);
            defaultEnvelope = encode(defaultLabel, settings.outputFormat());
            // Serialization must not change the access decision or label semantics.
            Label roundTrip = payload(defaultEnvelope.element(QName.get("label", NAMESPACE)).elements().get(0));
            authorize(roundTrip);
            if (!equivalent(defaultLabel, roundTrip)) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The default label is invalid, exceeds the server clearance, or cannot be represented in the output format.");
        }
    }

    public Settings settings() { return settings; }

    public Element defaultEnvelope() { return defaultEnvelope.createCopy(); }

    /**
     * Validates an arbitrary label payload against this policy and clearance and encodes it as a
     * XEP-0258 envelope, exactly like the default label. Used for label catalogue entries, which are
     * independent of the configured default.
     */
    public Element encodeCatalogLabel(String payload, LabelFormat format) {
        Label label;
        try {
            label = site.label(format.decode(payload), format.format);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The label is not valid input for the selected format.");
        }
        authorize(label);
        return encode(label, format);
    }

    /** Returns a stamped copy for an empty primary label; otherwise retains the original envelope. */
    Element check(Element envelope) {
        boundTree(envelope);
        if (!ENVELOPE.equals(envelope.getQName()) || !envelope.getTextTrim().isEmpty()) throw malformed();
        List<Element> children = envelope.elements();
        int index = 0;
        if (!children.isEmpty() && named(children.get(0), "displaymarking")) {
            if (!children.get(0).elements().isEmpty()) throw malformed();
            index++;
        }
        if (index >= children.size() || !named(children.get(index), "label")) throw malformed();
        Element primary = children.get(index++);
        Label effective = contained(primary, true);
        boolean useDefault = effective == null;
        if (useDefault) effective = defaultLabel;
        authorize(effective);
        while (index < children.size()) {
            Element other = children.get(index++);
            if (!named(other, "equivalentlabel")) throw malformed();
            Label equivalent = contained(other, false);
            authorize(equivalent);
            // One configured policy: never trust an unverified cross-policy equivalence claim.
            if (!equivalent(effective, equivalent)) throw malformed();
        }
        return useDefault ? defaultEnvelope() : envelope;
    }

    private Label contained(Element holder, boolean allowEmpty) {
        if (!holder.getTextTrim().isEmpty() || holder.elements().size() > 1) throw malformed();
        if (holder.elements().isEmpty()) {
            if (allowEmpty) return null;
            throw malformed();
        }
        return payload(holder.elements().get(0));
    }

    private Label payload(Element payload) {
        String ns = payload.getNamespaceURI();
        if (ESS_NAMESPACE.equals(ns) && "esssecuritylabel".equals(payload.getName())) {
            if (!payload.elements().isEmpty()) throw malformed();
            return site.label(LabelFormat.ESS.decode(payload.getText()), LabelFormat.ESS.format);
        }
        if (NATO_NAMESPACE.equals(ns) && "originatorConfidentialityLabel".equals(payload.getName())) {
            return site.label(LabelFormat.NATO.decode(payload.asXML()), LabelFormat.NATO.format);
        }
        if (XML_NAMESPACE.equals(ns) && "label".equals(payload.getName())) {
            return site.label(LabelFormat.XML.decode(payload.asXML()), LabelFormat.XML.format);
        }
        throw malformed();
    }

    private void authorize(Label label) {
        policy.assertValid(label);
        if (!policy.acdf(label, clearance)) throw new IllegalArgumentException("Label denied by server clearance.");
    }

    private static boolean equivalent(Label first, Label second) {
        return first.policy() == second.policy() && first.classification() == second.classification()
            && first.categories().equals(second.categories());
    }

    private Element encode(Label label, LabelFormat format) {
        Element envelope = DocumentHelper.createElement(ENVELOPE);
        String marking = policy.displayMarking(label);
        if (marking != null && !marking.isEmpty()) envelope.addElement(QName.get("displaymarking", NAMESPACE)).setText(marking);
        Element holder = envelope.addElement(QName.get("label", NAMESPACE));
        byte[] bytes = label.write(format.format);
        if (format == LabelFormat.ESS) {
            holder.addElement(QName.get("esssecuritylabel", ESS_NAMESPACE)).setText(Base64.getEncoder().encodeToString(bytes));
        } else {
            holder.add(SecureXml.parse(new String(bytes, StandardCharsets.UTF_8), SecureXml.MAX_LABEL));
        }
        boundTree(envelope);
        return envelope;
    }

    private static boolean named(Element element, String local) {
        return local.equals(element.getName()) && NAMESPACE.equals(element.getNamespaceURI());
    }

    private static IllegalArgumentException malformed() { return new IllegalArgumentException("Invalid or unsupported security label."); }

    /** Bound work before recursively serializing XML supplied by a peer. */
    private static void boundTree(Element root) {
        record Entry(Element element, int depth) {}
        var pending = new ArrayDeque<Entry>();
        pending.add(new Entry(root, 0));
        long size = 0;
        int nodes = 0;
        while (!pending.isEmpty()) {
            Entry entry = pending.removeFirst();
            Element element = entry.element();
            if (++nodes > 1024 || entry.depth() > 32) throw malformed();
            size += element.getText().length() + element.getQualifiedName().length() + element.getNamespaceURI().length();
            for (var attribute : element.attributes()) size += attribute.getQualifiedName().length() + attribute.getValue().length();
            if (size > SecureXml.MAX_LABEL) throw malformed();
            for (Element child : element.elements()) {
                if (pending.size() >= 1024) throw malformed();
                pending.add(new Entry(child, entry.depth() + 1));
            }
        }
    }
}
