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
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A privately owned policy registry and compiled clearances, never mutated after publication.
 */
public final class PolicyConfiguration {
    public static final String NAMESPACE = "urn:xmpp:sec-label:0";
    public static final String ESS_NAMESPACE = "urn:xmpp:sec-label:ess:0";
    public static final String NATO_NAMESPACE = "urn:nato:stanag:4774:confidentialitymetadatalabel:1:0";
    public static final String XML_NAMESPACE = "http://surevine.com/xmlns/spiffy";
    static final QName ENVELOPE = QName.get("securitylabel", NAMESPACE);

    private final Site site;
    private final List<LoadedPolicy> loadedPolicies;
    private final Map<String, Clearance> serverClearances = new LinkedHashMap<>();
    private final Map<String, Label> defaultLabels = new LinkedHashMap<>();
    private final Map<String, Map<String, Clearance>> peerClearancesByDomain = new HashMap<>();

    private final String defaultPolicyId;
    private final Label defaultLabel;
    private final Element defaultEnvelope;
    private final String defaultDisplayMarking;
    private final LabelFormat outputFormat;

    /**
     * A loaded policy's id and name (as declared in its SPIF document) plus its original document text;
     * used to answer {@link PolicyIqHandler} requests without re-deriving either from the {@link Spif}.
     */
    public record LoadedPolicy(String id, String name, String document) {}

    public PolicyConfiguration(List<SecurityPolicy> policies, List<PeerClearance> peerClearanceList, LabelFormat outputFormat) {
        this.outputFormat = outputFormat == null ? LabelFormat.ESS : outputFormat;
        site = new Site();
        List<LoadedPolicy> loaded = new ArrayList<>();
        if (policies != null) {
            for (SecurityPolicy p : policies) {
                try {
                    SecureXml.parse(p.spif(), SecureXml.MAX_DOCUMENT);
                    Spif s = site.load(p.spif());
                    loaded.add(new LoadedPolicy(s.policyId(), s.name(), p.spif()));
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Policy '" + p.name() + "' is not a valid Open XML SPIF, or duplicates another loaded policy.", e);
                }
            }
        }
        loadedPolicies = List.copyOf(loaded);

        if (policies != null) {
            for (SecurityPolicy p : policies) {
                Spif spif = null;
                try {
                    spif = site.spif(p.id());
                } catch (RuntimeException ignored) {
                    for (LoadedPolicy lp : loaded) {
                        if (lp.id().equals(p.id()) || lp.name().equals(p.name())) {
                            spif = site.spif(lp.id());
                            break;
                        }
                    }
                }
                if (spif == null) continue;
                String policyId = spif.policyId();
                if (p.clearance() != null && !p.clearance().isBlank()) {
                    try {
                        if (p.clearanceFormat() == LabelFormat.XML || p.clearanceFormat() == LabelFormat.NATO) {
                            SecureXml.parse(p.clearance(), SecureXml.MAX_LABEL);
                        }
                        Clearance c = site.clearance(p.clearanceFormat().decode(p.clearance()), p.clearanceFormat().format);
                        if (c.policy() != spif) {
                            throw new IllegalArgumentException("Clearance does not belong to policy: " + p.name());
                        }
                        serverClearances.put(policyId, c);
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("The clearance for policy '" + p.name() + "' is invalid or does not belong to the policy.", e);
                    }
                }
                if (p.defaultLabel() != null && !p.defaultLabel().isBlank()) {
                    try {
                        if (p.defaultLabelFormat() == LabelFormat.XML || p.defaultLabelFormat() == LabelFormat.NATO) {
                            SecureXml.parse(p.defaultLabel(), SecureXml.MAX_LABEL);
                        }
                        Label l = site.label(p.defaultLabelFormat().decode(p.defaultLabel()), p.defaultLabelFormat().format);
                        if (!l.policy().valid(l)) {
                            throw new IllegalArgumentException("The default label is invalid under policy: " + p.name());
                        }
                        Clearance c = serverClearances.get(policyId);
                        if (c != null) {
                            authorize(c, l);
                        }
                        defaultLabels.put(policyId, l);
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("The default label for policy '" + p.name() + "' is invalid, exceeds the server clearance, or cannot be decoded.", e);
                    }
                }
            }
        }

        if (peerClearanceList != null) {
            for (PeerClearance pc : peerClearanceList) {
                Spif spif = null;
                try {
                    spif = site.spif(pc.policyId());
                } catch (RuntimeException ignored) {
                    for (LoadedPolicy lp : loaded) {
                        if (lp.id().equals(pc.policyId())) {
                            spif = site.spif(lp.id());
                            break;
                        }
                    }
                }
                if (spif == null) {
                    throw new IllegalArgumentException("Peer clearance policy not found: " + pc.policyId());
                }
                try {
                    if (pc.format() == LabelFormat.XML || pc.format() == LabelFormat.NATO) {
                        SecureXml.parse(pc.clearance(), SecureXml.MAX_LABEL);
                    }
                    Clearance c = site.clearance(pc.format().decode(pc.clearance()), pc.format().format);
                    if (c.policy() != spif) {
                        throw new IllegalArgumentException("Peer clearance does not belong to policy: " + pc.policyId());
                    }
                    peerClearancesByDomain.computeIfAbsent(pc.domain(), k -> new LinkedHashMap<>()).put(spif.policyId(), c);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException("Peer clearance for domain '" + pc.domain() + "' is invalid.", e);
                }
            }
        }

        // Determine active default label for stamping
        String defId = null;
        Label defLbl = null;
        if (policies != null) {
            for (SecurityPolicy p : policies) {
                if (p.isDefault() && (defaultLabels.containsKey(p.id()) || defaultLabels.containsKey(site.spif(p.id()).policyId()))) {
                    String pid = defaultLabels.containsKey(p.id()) ? p.id() : site.spif(p.id()).policyId();
                    defId = pid;
                    defLbl = defaultLabels.get(pid);
                    break;
                }
            }
            if (defLbl == null && !defaultLabels.isEmpty()) {
                Map.Entry<String, Label> first = defaultLabels.entrySet().iterator().next();
                defId = first.getKey();
                defLbl = first.getValue();
            }
        }

        defaultPolicyId = defId;
        defaultLabel = defLbl;
        if (defaultLabel != null) {
            try {
                defaultEnvelope = encode(defaultLabel, this.outputFormat);
                defaultDisplayMarking = displayMarking(defaultEnvelope);
                Label roundTrip = payload(defaultEnvelope.element(QName.get("label", NAMESPACE)).elements().get(0));
                Clearance c = serverClearances.get(defaultLabel.policy().policyId());
                if (c != null) {
                    authorize(c, roundTrip);
                }
                if (!equivalent(defaultLabel, roundTrip)) {
                    throw new IllegalArgumentException("Default label round trip equivalence failed.");
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The default label cannot be represented in the output format.", e);
            }
        } else {
            defaultEnvelope = null;
            defaultDisplayMarking = null;
        }
    }

    public boolean hasPolicies() { return !loadedPolicies.isEmpty(); }

    public boolean hasClearance() { return !serverClearances.isEmpty(); }

    public boolean hasDefaultLabel() { return defaultLabel != null; }

    public boolean hasPeerClearance() { return !peerClearancesByDomain.isEmpty(); }

    public LabelFormat outputFormat() { return outputFormat; }

    public Element defaultEnvelope() { return defaultEnvelope == null ? null : defaultEnvelope.createCopy(); }

    public String defaultDisplayMarking() { return defaultDisplayMarking; }

    public List<LoadedPolicy> loadedPolicies() { return loadedPolicies; }

    public LoadedPolicy loadedPolicyById(String id) {
        return loadedPolicies.stream().filter(p -> p.id().equals(id)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown policy id: " + id));
    }

    public LoadedPolicy loadedPolicyByName(String name) {
        return loadedPolicies.stream().filter(p -> p.name().equals(name)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown policy name: " + name));
    }

    /** Extracts the optional {@code <displaymarking>} text from a XEP-0258 envelope, or {@code null} if absent. */
    static String displayMarking(Element envelope) {
        Element marking = envelope.element(QName.get("displaymarking", NAMESPACE));
        return marking == null ? null : marking.getTextTrim();
    }

    public Element encodeCatalogLabel(String payload, LabelFormat format, boolean checkPeerClearance) {
        return encodeCatalogLabel(payload, format, checkPeerClearance, "");
    }

    public Element encodeCatalogLabel(String payload, LabelFormat format, boolean checkPeerClearance, String peerDomain) {
        Label label;
        try {
            label = site.label(format.decode(payload), format.format);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The label is not valid input for the selected format.");
        }
        if (!label.policy().valid(label)) {
            throw new IllegalArgumentException("The label is not valid under policy " + label.policy().name());
        }
        authorizeServerClearance(label);
        if (checkPeerClearance) {
            authorizePeerClearance(label, peerDomain);
        }
        return encode(label, format);
    }

    public Element check(Element envelope) {
        return check(envelope, false, "");
    }

    public Element check(Element envelope, boolean checkPeerClearance) {
        return check(envelope, checkPeerClearance, "");
    }

    public Element check(Element envelope, boolean checkPeerClearance, String peerDomain) {
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
        if (useDefault) {
            if (defaultLabel == null) throw new IllegalArgumentException("No default label configured.");
            effective = defaultLabel;
        } else {
            if (!effective.policy().valid(effective)) throw malformed();
        }
        authorizeServerClearance(effective);
        if (checkPeerClearance) {
            authorizePeerClearance(effective, peerDomain);
        }
        while (index < children.size()) {
            Element other = children.get(index++);
            if (!named(other, "equivalentlabel")) throw malformed();
            Label equivalent = contained(other, false);
            if (!equivalent.policy().valid(equivalent)) throw malformed();
            authorizeServerClearance(equivalent);
            if (!equivalent(effective, equivalent)) throw malformed();
        }
        return useDefault ? defaultEnvelope() : envelope;
    }

    public void checkDefaultPeerClearance(String peerDomain) {
        if (defaultLabel != null) {
            authorizePeerClearance(defaultLabel, peerDomain);
        }
    }

    public void checkPeerClearance(Element envelope, String peerDomain) {
        if (peerClearancesByDomain.isEmpty()) return;
        authorizePeerClearance(decode(envelope), peerDomain);
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
        if (effective == null) {
            if (defaultLabel == null) throw new IllegalArgumentException("No default label configured.");
            return defaultLabel;
        }
        return effective;
    }

    private void authorizeServerClearance(Label label) {
        if (serverClearances.isEmpty()) {
            return; // Partial configuration without clearances passes validity check
        }
        Clearance direct = serverClearances.get(label.policy().policyId());
        if (direct != null) {
            authorize(direct, label);
            return;
        }
        // Translate to any policy for which we have a server clearance
        for (Clearance c : serverClearances.values()) {
            try {
                Label translated = label.encrypt(c.policy().policyId(), site);
                if (translated != null) {
                    authorize(c, translated);
                    return;
                }
            } catch (RuntimeException ignored) {
            }
        }
        throw new IllegalArgumentException("No clearance available for policy: " + label.policy().name());
    }

    private void authorizePeerClearance(Label label, String domain) {
        if (peerClearancesByDomain.isEmpty()) return;
        Map<String, Clearance> clearances = peerClearancesByDomain.get(domain == null ? "" : domain);
        if (clearances == null || clearances.isEmpty()) {
            clearances = peerClearancesByDomain.get("");
        }
        if (clearances == null || clearances.isEmpty()) return;

        Clearance direct = clearances.get(label.policy().policyId());
        if (direct != null) {
            authorize(direct, label);
            return;
        }
        for (Clearance c : clearances.values()) {
            try {
                Label translated = label.encrypt(c.policy().policyId(), site);
                if (translated != null) {
                    authorize(c, translated);
                    return;
                }
            } catch (RuntimeException ignored) {
            }
        }
        throw new IllegalArgumentException("No peer clearance available for policy: " + label.policy().name());
    }

    private void authorize(Clearance clearance, Label label) {
        if (!clearance.policy().acdf(label, clearance)) {
            throw new IllegalArgumentException("The label exceeds clearance authorization.");
        }
    }

    private boolean equivalent(Label left, Label right) {
        if (left.policy().equals(right.policy())) {
            return matches(left, right);
        }
        try {
            Label translatedRight = right.encrypt(left.policyId(), site);
            if (translatedRight != null && matches(left, translatedRight)) {
                return true;
            }
        } catch (RuntimeException ignored) {}
        try {
            Label translatedLeft = left.encrypt(right.policyId(), site);
            if (translatedLeft != null && matches(translatedLeft, right)) {
                return true;
            }
        } catch (RuntimeException ignored) {}
        return false;
    }

    private boolean matches(Label a, Label b) {
        return a.classification().lacv().equals(b.classification().lacv()) && a.categories().equals(b.categories());
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
        try {
            if (ESS_NAMESPACE.equals(payload.getNamespaceURI()) && "esssecuritylabel".equals(payload.getName())) {
                if (!payload.elements().isEmpty()) throw malformed();
                return site.label(Base64.getDecoder().decode(payload.getTextTrim()), LabelFormat.ESS.format);
            }
            if (payload.getNamespaceURI().contains("4774") || NATO_NAMESPACE.equals(payload.getNamespaceURI())) {
                return site.label(payload.asXML().getBytes(StandardCharsets.UTF_8), LabelFormat.NATO.format);
            }
            if (XML_NAMESPACE.equals(payload.getNamespaceURI()) && "label".equals(payload.getName())) {
                return site.label(payload.asXML().getBytes(StandardCharsets.UTF_8), LabelFormat.XML.format);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw malformed();
        }
        throw malformed();
    }

    private Element encode(Label label, LabelFormat format) {
        Element envelope = DocumentHelper.createElement(ENVELOPE);
        String marking = label.policy().displayMarking(label);
        if (marking != null && !marking.isBlank()) {
            envelope.addElement(QName.get("displaymarking", NAMESPACE)).setText(marking);
        }
        Element holder = envelope.addElement(QName.get("label", NAMESPACE));
        byte[] encoded = label.write(format.format);
        switch (format) {
            case ESS -> holder.addElement(QName.get("esssecuritylabel", ESS_NAMESPACE))
                .setText(Base64.getEncoder().encodeToString(encoded));
            case NATO -> holder.add(SecureXml.parse(new String(encoded, StandardCharsets.UTF_8)));
            case XML -> holder.add(SecureXml.parse(new String(encoded, StandardCharsets.UTF_8)));
        }
        return envelope;
    }

    private static boolean named(Element element, String localName) {
        return localName.equals(element.getName()) && NAMESPACE.equals(element.getNamespaceURI());
    }

    private static void boundTree(Element root) {
        if (root.asXML().length() > SecureXml.MAX_LABEL) throw malformed();
        record Node(Element element, int depth) {}
        var queue = new ArrayDeque<Node>();
        queue.add(new Node(root, 1));
        int elements = 0;
        while (!queue.isEmpty()) {
            var current = queue.poll();
            elements++;
            if (elements > SecureXml.MAX_TREE_ELEMENTS || current.depth > SecureXml.MAX_TREE_DEPTH) throw malformed();
            for (Element child : current.element.elements()) {
                queue.add(new Node(child, current.depth + 1));
            }
        }
    }

    private static IllegalArgumentException malformed() {
        return new IllegalArgumentException("The security label is malformed.");
    }
}
