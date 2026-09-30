package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Clearance;
import io.cridland.spiffing.Format;
import io.cridland.spiffing.Label;
import io.cridland.spiffing.Site;
import io.cridland.spiffing.Spif;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
    /** Null when no peer clearance is configured; ingress/egress peer-clearance checks are then no-ops. */
    private final Clearance peerClearance;
    private final Label defaultLabel;
    private final Element defaultEnvelope;
    private final String defaultDisplayMarking;
    private final List<LoadedPolicy> loadedPolicies;

    /** A loaded policy's id and name, as declared in its SPIF document; used to answer {@link PolicyIqHandler}
     * "list the loaded policies" requests and to look up a policy by either attribute. The document itself
     * is not retained here: {@link #exportedDocument} re-derives it from the {@link Spif} on each request
     * via {@link Spif#write}, the same call used for an optional future clearance-filtered export. */
    public record LoadedPolicy(String id, String name) {}

    public PolicyConfiguration(Settings settings) {
        this.settings = settings;
        site = new Site();
        try {
            Spif primary = null;
            List<LoadedPolicy> loaded = new ArrayList<>();
            for (String p : settings.policies()) {
                Spif s = site.load(p);
                loaded.add(new LoadedPolicy(s.policyId(), s.name()));
                if (primary == null) primary = s;
            }
            policy = primary;
            loadedPolicies = List.copyOf(loaded);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("A policy is not a valid Open XML SPIF, or duplicates another loaded policy.");
        }
        try {
            clearance = site.clearance(settings.clearanceFormat().decode(settings.clearance()), settings.clearanceFormat().format);
            if (clearance.policy() != policy) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The clearance is invalid or does not belong to the primary policy.");
        }
        if (settings.peerClearance().isEmpty()) {
            peerClearance = null;
        } else {
            try {
                peerClearance = site.clearance(settings.peerClearanceFormat().decode(settings.peerClearance()), settings.peerClearanceFormat().format);
                if (peerClearance.policy() != policy) throw new IllegalArgumentException();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The peer clearance is invalid or does not belong to the primary policy.");
            }
        }
        try {
            defaultLabel = toPrimary(site.label(settings.labelFormat().decode(settings.defaultLabel()), settings.labelFormat().format));
            authorize(defaultLabel);
            defaultEnvelope = encode(defaultLabel, settings.outputFormat());
            defaultDisplayMarking = displayMarking(defaultEnvelope);
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
     * The default label's display marking (as produced by {@link #defaultEnvelope()}), or {@code null}/empty
     * if the policy has none. Used to identify "the same label" as the configured default by its rendered
     * marking rather than by re-deriving full classification/category equivalence, when stripping the
     * default label before sending a message to another server.
     */
    public String defaultDisplayMarking() { return defaultDisplayMarking; }

    /** Extracts the optional {@code <displaymarking>} text from a XEP-0258 envelope, or {@code null} if absent. */
    static String displayMarking(Element envelope) {
        Element marking = envelope.element(QName.get("displaymarking", NAMESPACE));
        return marking == null ? null : marking.getTextTrim();
    }

    /** Whether an administrator has configured a peer clearance; if not, peer-clearance checks are no-ops. */
    public boolean hasPeerClearance() { return peerClearance != null; }

    /** Every loaded policy's id and name, in load order (the primary policy,
     * used for the server/peer clearance and default label, is always first). Used by {@link PolicyIqHandler}
     * to answer "list the loaded policies" requests and to look up a requested policy's id/name before
     * exporting its document via {@link #exportedDocument}. */
    public List<LoadedPolicy> loadedPolicies() { return loadedPolicies; }

    /** The loaded policy with the given id.
     * @throws IllegalArgumentException if no loaded policy has that id. */
    public LoadedPolicy loadedPolicyById(String id) {
        return loadedPolicies.stream().filter(p -> p.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown policy id: " + id));
    }

    /** The loaded policy with the given name.
     * @throws IllegalArgumentException if no loaded policy has that name. */
    public LoadedPolicy loadedPolicyByName(String name) {
        return loadedPolicies.stream().filter(p -> p.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown policy name: " + name));
    }

    /**
     * Exports the given loaded policy's Open XML SPIF document via {@link Spif#write}, re-serializing it
     * from the parsed model rather than returning a previously retained copy of the original document text.
     * No clearance filter is applied (equivalent to {@code Spif.write(Format.XML, null)}): every loaded
     * policy, primary or secondary, is exported in full, since this deployment's single configured
     * clearance only belongs to the primary policy and filtering was not requested for this feature.
     */
    public String exportedDocument(LoadedPolicy loaded) {
        Spif target = site.spif(loaded.id());
        return new String(target.write(Format.XML), StandardCharsets.UTF_8);
    }

    /**
     * Validates an arbitrary label payload against this policy and clearance and encodes it as a
     * XEP-0258 envelope, exactly like the default label. Used for label catalogue entries, which are
     * independent of the configured default. When {@code checkPeerClearance} is {@code true}, the label
     * is also tested against the configured peer clearance (a no-op when none is configured); used when
     * building a catalogue for a requested {@code to=} recipient that is not local to this server, since
     * a message carrying this label to that recipient would also have to pass the egress peer-clearance
     * check before leaving for that federated peer.
     */
    public Element encodeCatalogLabel(String payload, LabelFormat format, boolean checkPeerClearance) {
        Label label;
        try {
            label = toPrimary(site.label(format.decode(payload), format.format));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The label is not valid input for the selected format.");
        }
        authorize(label);
        if (checkPeerClearance) authorizePeer(label);
        return encode(label, format);
    }

    /** Returns a stamped copy for an empty primary label; otherwise retains the original envelope. Does not
     * check the peer clearance; see {@link #check(Element, boolean)}. */
    Element check(Element envelope) { return check(envelope, false); }

    /**
     * Same as {@link #check(Element)}, but when {@code checkPeerClearance} is {@code true} also tests the
     * effective primary label against the configured peer clearance (a no-op when none is configured). Used
     * for inbound messages arriving from a federated peer, in addition to the always-performed server
     * clearance check.
     */
    Element check(Element envelope, boolean checkPeerClearance) {
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
        effective = useDefault ? defaultLabel : toPrimary(effective);
        authorize(effective);
        if (checkPeerClearance) authorizePeer(effective);
        while (index < children.size()) {
            Element other = children.get(index++);
            if (!named(other, "equivalentlabel")) throw malformed();
            Label equivalent = toPrimary(contained(other, false));
            authorize(equivalent);
            // A label from a different loaded policy is only trusted here once translated to the primary
            // policy via that policy's own declared equivalence mappings (see toPrimary); this still never
            // trusts an unverified cross-policy equivalence claim from the label itself.
            if (!equivalent(effective, equivalent)) throw malformed();
        }
        return useDefault ? defaultEnvelope() : envelope;
    }

    /** Tests the already-validated default label against the configured peer clearance (a no-op when none is
     * configured). Used when an inbound message from a federated peer arrives without a label and is about
     * to be stamped with the default. */
    void checkDefaultPeerClearance() { authorizePeer(defaultLabel); }

    /**
     * Decodes an outbound envelope's primary label and tests it against the configured peer clearance (a
     * no-op when none is configured). Unlike {@link #check}, this never re-runs server-clearance
     * authorization (already done on ingress) or stamps a default; it is used only for the egress
     * peer-clearance check before a message leaves for another server.
     */
    void checkPeerClearance(Element envelope) {
        if (peerClearance == null) return;
        authorizePeer(decode(envelope));
    }

    private Label decode(Element envelope) {
        boundTree(envelope);
        if (!ENVELOPE.equals(envelope.getQName()) || !envelope.getTextTrim().isEmpty()) throw malformed();
        List<Element> children = envelope.elements();
        int index = 0;
        if (!children.isEmpty() && named(children.get(0), "displaymarking")) {
            if (!children.get(0).elements().isEmpty()) throw malformed();
            index++;
        }
        if (index >= children.size() || !named(children.get(index), "label")) throw malformed();
        Element primary = children.get(index);
        Label effective = contained(primary, true);
        return effective == null ? defaultLabel : toPrimary(effective);
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

    private void authorizePeer(Label label) {
        if (peerClearance == null) return;
        if (!policy.acdf(label, peerClearance)) throw new IllegalArgumentException("Label denied by peer clearance.");
    }

    private static boolean equivalent(Label first, Label second) {
        return first.policy() == second.policy() && first.classification() == second.classification()
            && first.categories().equals(second.categories());
    }

    /**
     * Translates a label from a secondary loaded policy to the primary policy, so the single configured
     * clearance can still authorize it; a label already under the primary policy is returned unchanged.
     * Translation relies exclusively on the label's own policy's declared {@code equivalentPolicy}/
     * {@code equivalentClassification}/{@code equivalentSecCategoryTag} mappings (a trusted, policy-authored
     * equivalence, not a claim made by the message itself); a policy without such a mapping for its
     * classification or any of its categories fails translation and the label is rejected as unsupported.
     */
    private Label toPrimary(Label label) {
        if (label.policy() == policy) return label;
        try {
            return label.encrypt(policy.policyId(), site);
        } catch (RuntimeException e) {
            throw malformed();
        }
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
