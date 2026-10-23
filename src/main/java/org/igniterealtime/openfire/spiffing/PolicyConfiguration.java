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

    /** A loaded policy's id and name, as declared in its SPIF document; used to answer {@link PolicyIqHandler}
     * "list the loaded policies" requests and to look up a policy by either attribute. The document itself
     * is not retained here: {@link #exportedDocument} re-derives it from the {@link Spif} on each request
     * via {@link Spif#write}, the same call used for an optional future clearance-filtered export. */
    public record LoadedPolicy(String id, String name) {}

    public PolicyConfiguration(List<SecurityPolicy> policies, List<PeerClearance> peerClearanceList, LabelFormat outputFormat) {
        this.outputFormat = outputFormat == null ? LabelFormat.ESS : outputFormat;
        site = new Site();
        List<LoadedPolicy> loaded = new ArrayList<>();
        if (policies != null) {
            for (SecurityPolicy p : policies) {
                try {
                    SecureXml.parse(p.spif(), SecureXml.MAX_DOCUMENT);
                    Spif s = site.load(p.spif());
                    loaded.add(new LoadedPolicy(s.policyId(), s.name()));
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

    /** Whether an administrator has configured a peer clearance for any domain; if not, peer-clearance checks are no-ops. */
    public boolean hasPeerClearance() { return !peerClearancesByDomain.isEmpty(); }

    public LabelFormat outputFormat() { return outputFormat; }

    public Element defaultEnvelope() { return defaultEnvelope == null ? null : defaultEnvelope.createCopy(); }

    public String defaultDisplayMarking() { return defaultDisplayMarking; }

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

    /** Extracts the optional {@code <displaymarking>} text from a XEP-0258 envelope, or {@code null} if absent. */
    static String displayMarking(Element envelope) {
        Element marking = envelope.element(QName.get("displaymarking", NAMESPACE));
        return marking == null ? null : marking.getTextTrim();
    }

    /**
     * Exports the given loaded policy's Open XML SPIF document via {@link Spif#write}, re-serializing it
     * from the parsed model rather than returning a previously retained copy of the original document text.
     * No clearance filter is applied (equivalent to {@code Spif.write(Format.XML, null)}): every loaded
     * policy is exported in full, since filtering by clearance was not requested for this feature.
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

    /**
     * Authorizes a message's effective label, about to enter a room, against that room's own
     * clearance(s), one of which may apply if it is scoped to the label's policy (or a policy the
     * label can be translated to). A room with no clearance for any loaded policy is a no-op,
     * exactly like an unconfigured peer/user clearance: room clearances are optional, not
     * mandatory. Raw, undecoded clearances are passed in and resolved here against this registry's
     * {@code site}, mirroring {@link #checkUserClearance}.
     */
    public void checkRoomClearance(Element envelope, List<RoomClearance> roomClearances) {
        checkRoomClearanceOnLabel(decode(envelope), roomClearances);
    }

    /** Like {@link #checkRoomClearance}, but tests the configured (server-wide) default label itself, for an unlabelled message entering a room that has no room-specific default/label of its own. */
    public void checkDefaultRoomClearance(List<RoomClearance> roomClearances) {
        if (defaultLabel == null) return;
        checkRoomClearanceOnLabel(defaultLabel, roomClearances);
    }

    private Map<String, Clearance> resolveRoomClearances(List<RoomClearance> roomClearances) {
        if (roomClearances == null || roomClearances.isEmpty()) return Map.of();
        Map<String, Clearance> result = new LinkedHashMap<>();
        for (RoomClearance rc : roomClearances) {
            Spif spif = resolvePolicy(rc.policyId());
            if (spif == null) continue; // Stale clearance for a policy no longer loaded; ignore.
            try {
                if (rc.format() == LabelFormat.XML || rc.format() == LabelFormat.NATO) {
                    SecureXml.parse(rc.clearance(), SecureXml.MAX_LABEL);
                }
                Clearance c = site.clearance(rc.format().decode(rc.clearance()), rc.format().format);
                if (c.policy() != spif) continue; // Mismatched policy; ignore rather than fail the whole message.
                result.put(spif.policyId(), c);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The room clearance for policy '" + rc.policyId() + "' is invalid.", e);
            }
        }
        return result;
    }

    /**
     * Decodes and validates a room's own stored label payload against this registry, used both to
     * resolve the room's ambient classification for join-gating and to derive its default-label
     * envelope when it has no independently configured default (see {@link RoomLabel}).
     */
    public Label decodeRoomLabel(String payload, LabelFormat format) {
        try {
            if (format == LabelFormat.XML || format == LabelFormat.NATO) {
                SecureXml.parse(payload, SecureXml.MAX_LABEL);
            }
            Label label = site.label(format.decode(payload), format.format);
            if (!label.policy().valid(label)) {
                throw new IllegalArgumentException("The room label is not valid under its policy.");
            }
            return label;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The room label is invalid or cannot be decoded.", e);
        }
    }

    /**
     * Encodes the label that should stamp a room's own unlabelled outgoing messages: the room's
     * independently configured default label when it has one, otherwise the room label itself
     * (see {@link RoomLabel}'s javadoc for why these are allowed to diverge). Also authorizes the
     * resolved label against the room's own clearance(s) first, exactly like
     * {@link #checkDefaultPeerClearance}/{@link #checkDefaultUserClearance} test the server default
     * before it is attached.
     */
    public Element encodeRoomDefaultEnvelope(RoomLabel roomLabel, List<RoomClearance> roomClearances) {
        Label effective = roomLabel.hasOwnDefaultLabel()
            ? decodeRoomLabel(roomLabel.defaultLabel(), roomLabel.defaultLabelFormat())
            : decodeRoomLabel(roomLabel.label(), roomLabel.format());
        checkRoomClearanceOnLabel(effective, roomClearances);
        return encode(effective, outputFormat);
    }

    /** Authorizes an already-decoded label against a room's own clearance(s); shared by {@link #checkRoomClearance}, {@link #checkDefaultRoomClearance}, and {@link #encodeRoomDefaultEnvelope}. */
    private void checkRoomClearanceOnLabel(Label label, List<RoomClearance> roomClearances) {
        Map<String, Clearance> clearances = resolveRoomClearances(roomClearances);
        if (clearances.isEmpty()) return;
        authorizeAgainstClearances(label, clearances, "room ");
    }

    /**
     * Authorizes a room's label against a local joining user's own clearances; used to gate room
     * joins (see {@link RoomLabel}). Unlike {@link #checkUserClearance}, a joining user with
     * <em>no</em> applicable clearance at all is explicitly denied rather than treated as a no-op:
     * once a room has a label configured, the issue's requirement is that a user has "no ...
     * capability to join the room unless the label was passed by" their own clearance.
     */
    public void authorizeRoomJoinByUserClearance(Label roomLabel, List<UserClearance> userClearances) {
        Map<String, Clearance> clearances = resolveUserClearances(userClearances);
        if (clearances.isEmpty()) {
            throw new IllegalArgumentException("No user clearance available to authorize the room label.");
        }
        authorizeAgainstClearances(roomLabel, clearances, "user ");
    }

    /**
     * Authorizes a room's label against a federated peer's clearance for their domain; used to
     * gate room joins (see {@link RoomLabel}). Like {@link #authorizeRoomJoinByUserClearance}, an
     * absent/unconfigured peer clearance for that domain is explicitly denied, not a no-op.
     */
    public void authorizeRoomJoinByPeerClearance(Label roomLabel, String domain) {
        Map<String, Clearance> clearances = peerClearancesByDomain.get(domain == null ? "" : domain);
        if (clearances == null || clearances.isEmpty()) {
            clearances = peerClearancesByDomain.get("");
        }
        if (clearances == null || clearances.isEmpty()) {
            throw new IllegalArgumentException("No peer clearance available to authorize the room label.");
        }
        authorizeAgainstClearances(roomLabel, clearances, "peer ");
    }

    /**
     * Authorizes the message's effective label against the sender's own clearances, one of which
     * may apply if it is scoped to the label's policy (or a policy the label can be translated to).
     * A sender with no clearance for any loaded policy is a no-op, exactly like an unconfigured
     * peer clearance: user clearances are optional, not mandatory. Raw, undecoded clearances are
     * passed in and resolved here against this registry's {@code site}, since a user may hold a
     * stale clearance for a policy that is no longer loaded (silently ignored) or scoped to several
     * different policies at once.
     */
    public void checkUserClearance(Element envelope, List<UserClearance> userClearances) {
        Map<String, Clearance> clearances = resolveUserClearances(userClearances);
        if (clearances.isEmpty()) return;
        authorizeAgainstClearances(decode(envelope), clearances, "user ");
    }

    /** Like {@link #checkUserClearance}, but tests the configured default label itself, for a sender without a direct envelope. */
    public void checkDefaultUserClearance(List<UserClearance> userClearances) {
        if (defaultLabel == null) return;
        Map<String, Clearance> clearances = resolveUserClearances(userClearances);
        if (clearances.isEmpty()) return;
        authorizeAgainstClearances(defaultLabel, clearances, "user ");
    }

    private Map<String, Clearance> resolveUserClearances(List<UserClearance> userClearances) {
        if (userClearances == null || userClearances.isEmpty()) return Map.of();
        Map<String, Clearance> result = new LinkedHashMap<>();
        for (UserClearance uc : userClearances) {
            Spif spif = resolvePolicy(uc.policyId());
            if (spif == null) continue; // Stale clearance for a policy no longer loaded; ignore.
            try {
                if (uc.format() == LabelFormat.XML || uc.format() == LabelFormat.NATO) {
                    SecureXml.parse(uc.clearance(), SecureXml.MAX_LABEL);
                }
                Clearance c = site.clearance(uc.format().decode(uc.clearance()), uc.format().format);
                if (c.policy() != spif) continue; // Mismatched policy; ignore rather than fail the whole message.
                result.put(spif.policyId(), c);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("The user clearance for policy '" + uc.policyId() + "' is invalid.", e);
            }
        }
        return result;
    }

    private Spif resolvePolicy(String policyId) {
        try {
            return site.spif(policyId);
        } catch (RuntimeException ignored) {
            for (LoadedPolicy lp : loadedPolicies) {
                if (lp.id().equals(policyId)) return site.spif(lp.id());
            }
            return null;
        }
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
        authorizeAgainstClearances(label, serverClearances, "");
    }

    private void authorizePeerClearance(Label label, String domain) {
        if (peerClearancesByDomain.isEmpty()) return;
        Map<String, Clearance> clearances = peerClearancesByDomain.get(domain == null ? "" : domain);
        if (clearances == null || clearances.isEmpty()) {
            clearances = peerClearancesByDomain.get("");
        }
        if (clearances == null || clearances.isEmpty()) return;
        authorizeAgainstClearances(label, clearances, "peer ");
    }

    /**
     * Tests {@code label} directly against whichever of {@code clearances} is scoped to its
     * policy, translating to any other policy in the map otherwise; shared by server, peer, and
     * user clearance checks, which differ only in which map of policy id to {@link Clearance}
     * applies and how the failure message should refer to that kind of clearance.
     */
    private void authorizeAgainstClearances(Label label, Map<String, Clearance> clearances, String kindPrefix) {
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
        throw new IllegalArgumentException("No " + kindPrefix + "clearance available for policy: " + label.policy().name());
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
