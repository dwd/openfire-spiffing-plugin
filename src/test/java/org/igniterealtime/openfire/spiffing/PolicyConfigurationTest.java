package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Site;
import org.dom4j.Element;
import org.dom4j.QName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PolicyConfigurationTest {
    @ParameterizedTest @EnumSource(LabelFormat.class)
    void defaultCanBeStampedInEachFormat(LabelFormat format) {
        var configuration = Fixtures.configuration(format);
        Element envelope = configuration.defaultEnvelope();
        assertSame(envelope, configuration.check(envelope));
        assertFalse(envelope.element(QName.get("displaymarking", PolicyConfiguration.NAMESPACE)).getText().isBlank());
        String expectedNamespace = switch (format) {
            case ESS -> PolicyConfiguration.ESS_NAMESPACE;
            case NATO -> PolicyConfiguration.NATO_NAMESPACE;
            case XML -> PolicyConfiguration.XML_NAMESPACE;
        };
        assertEquals(expectedNamespace, envelope.element(QName.get("label", PolicyConfiguration.NAMESPACE)).elements().get(0).getNamespaceURI());
        envelope.clearContent();
        assertFalse(configuration.defaultEnvelope().elements().isEmpty(), "stanzas cannot mutate the configured template");
    }

    @Test void defaultDisplayMarkingMatchesTheStampedEnvelopesMarking() {
        var configuration = Fixtures.configuration();
        Element marking = configuration.defaultEnvelope().element(QName.get("displaymarking", PolicyConfiguration.NAMESPACE));
        assertEquals(marking.getTextTrim(), configuration.defaultDisplayMarking());
        assertFalse(configuration.defaultDisplayMarking().isEmpty());
    }

    @ParameterizedTest @EnumSource(LabelFormat.class)
    void permitsInboundLabelInEachFormat(LabelFormat format) {
        var configuration = Fixtures.configuration();
        Element envelope = Fixtures.envelope("food-label-milk-chocolate", format);
        assertSame(envelope, configuration.check(envelope));
    }

    @ParameterizedTest @EnumSource(LabelFormat.class)
    void configurationAcceptsEachInputFormat(LabelFormat format) {
        Site site = new Site();
        site.load(Fixtures.read("food-policy"));
        byte[] label = site.label(Fixtures.read("food-label-milk-chocolate")).write(format.format);
        byte[] clearance = site.clearance(Fixtures.read("food-clearance-all-okay")).write(format.format);
        var policy = new SecurityPolicy("1.2.826.0.1.6726289.0.0", "Food", Fixtures.read("food-policy"), text(clearance, format), format, text(label, format), format, true);
        assertDoesNotThrow(() -> new PolicyConfiguration(List.of(policy), List.of(), LabelFormat.ESS));
    }

    private static String text(byte[] data, LabelFormat format) {
        return format == LabelFormat.ESS ? Base64.getEncoder().encodeToString(data) : new String(data, StandardCharsets.UTF_8);
    }

    @ParameterizedTest @ValueSource(strings={"food-label-cheap-milk-chocolate", "food-label-meaty-milk-chocolate", "food-label-bacon", "food-label-water"})
    void rejectsInvalidOrDeniedLabels(String fixture) {
        var configuration = Fixtures.configuration();
        assertThrows(RuntimeException.class, () -> configuration.check(Fixtures.envelope(fixture, LabelFormat.XML)));
    }

    @Test void clearanceMatchIsInsufficientWithoutPolicyValidity() {
        Site site = new Site();
        var policy = site.load(Fixtures.read("food-policy"));
        var label = site.label(Fixtures.read("food-label-cheap-milk-chocolate"));
        assertTrue(policy.acdf(label, site.clearance(Fixtures.read("food-clearance-all-okay"))));
        assertFalse(policy.valid(label));
        var p = new SecurityPolicy("1.2.826.0.1.6726289.0.0", "Food", Fixtures.read("food-policy"),
            Fixtures.read("food-clearance-all-okay"), LabelFormat.XML,
            Fixtures.read("food-label-cheap-milk-chocolate"), LabelFormat.XML, true);
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(List.of(p), List.of(), LabelFormat.ESS));
    }

    @Test void restrictiveCategoryMissingFromClearanceDeniesDefault() {
        var p = new SecurityPolicy("1.2.826.0.1.6726289.0.0", "Food", Fixtures.read("food-policy"),
            Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML,
            Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, true);
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(List.of(p), List.of(), LabelFormat.ESS));
    }

    @Test void classificationMembershipIsExplicitNotHierarchical() {
        String onlyHigher = Fixtures.read("food-clearance-all-okay").replace("lacv='52'", "lacv='53'");
        var p = new SecurityPolicy("1.2.826.0.1.6726289.0.0", "Food", Fixtures.read("food-policy"),
            onlyHigher, LabelFormat.XML,
            Fixtures.read("food-label-milk-chocolate"), LabelFormat.XML, true);
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(List.of(p), List.of(), LabelFormat.ESS));
    }

    @Test void informativeCategoryDoesNotRequireClearancePrivilege() {
        var config = Fixtures.configuration();
        assertDoesNotThrow(() -> config.check(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML)));
        assertTrue(Fixtures.read("food-label-milk-chocolate").contains("informative"));
        assertFalse(Fixtures.read("food-clearance-all-okay").contains("informative"));
    }

    @Test void emptyPrimaryUsesDefaultAndReplacesMisleadingMarking() {
        var config = Fixtures.configuration();
        var empty = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><displaymarking>PUBLIC</displaymarking><label/></securitylabel>");
        assertEquals(config.defaultEnvelope().asXML(), config.check(empty).asXML());
    }

    @Test void displayMarkingCannotGrantAccess() {
        var config = Fixtures.configuration();
        var envelope = Fixtures.envelope("food-label-water", LabelFormat.XML);
        var holder = envelope.elements().get(0);
        holder.detach();
        envelope.add(Fixtures.xml("<displaymarking xmlns='urn:xmpp:sec-label:0'>ALLOWED</displaymarking>"));
        envelope.add(holder);
        assertThrows(RuntimeException.class, () -> config.check(envelope));
    }

    @ParameterizedTest @ValueSource(strings={
        "", "<displaymarking>PUBLIC</displaymarking>", "<label/><label/>",
        "<label>garbage</label>", "<label><unknown xmlns='urn:unknown'/></label>",
        "<label><esssecuritylabel xmlns='urn:xmpp:sec-label:ess:0'>%%%!</esssecuritylabel></label>",
        "<label><esssecuritylabel xmlns='urn:xmpp:sec-label:ess:0'>AAAA</esssecuritylabel></label>",
        "<label><label xmlns='http://surevine.com/xmlns/spiffy'/><label xmlns='http://surevine.com/xmlns/spiffy'/></label>",
        "<label/><equivalentlabel/>", "<label/><displaymarking>wrong order</displaymarking>",
        "<label xmlns='urn:wrong'/>", "<label/><unexpected/>", "text<label/>"})
    void rejectsMalformedOrUnsupportedEnvelope(String children) {
        var configuration = Fixtures.configuration();
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'>" + children + "</securitylabel>");
        assertThrows(RuntimeException.class, () -> configuration.check(envelope));
    }

    @Test void permitsEquivalentEncodingOfSamePolicyLabel() {
        var config = Fixtures.configuration();
        var envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.ESS);
        var equivalent = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML).elements().get(0);
        equivalent.detach();
        equivalent.setQName(QName.get("equivalentlabel", PolicyConfiguration.NAMESPACE));
        envelope.add(equivalent);
        assertSame(envelope, config.check(envelope));
    }

    @Test void rejectsUnknownPolicyAndFalseEquivalence() {
        var config = Fixtures.configuration();
        var envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML);
        var equivalent = envelope.elements().get(0).createCopy();
        equivalent.setQName(QName.get("equivalentlabel", PolicyConfiguration.NAMESPACE));
        var equivalentCategories = equivalent.elements().get(0).elements();
        equivalentCategories.get(equivalentCategories.size() - 1).detach();
        envelope.add(equivalent);
        assertThrows(RuntimeException.class, () -> config.check(envelope));
        var foreign = Fixtures.xml(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML).asXML().replace("1.2.826.0.1.6726289.0.0\"", "1.2.3.4\""));
        assertThrows(RuntimeException.class, () -> config.check(foreign));
    }

    @Test void acceptsXmlWhitespaceButNotMimeGarbageInBase64() {
        var config = Fixtures.configuration();
        var envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.ESS);
        var payload = envelope.elements().get(0).elements().get(0);
        payload.setText(" \n\t" + payload.getText() + "\r\n");
        assertDoesNotThrow(() -> config.check(envelope));
        payload.setText(payload.getText() + "!");
        assertThrows(RuntimeException.class, () -> config.check(envelope));
    }

    @Test void rejectsOversizedAndDeepLabels() {
        var config = Fixtures.configuration();
        var envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.ESS);
        envelope.addAttribute("padding", "x".repeat(SecureXml.MAX_LABEL));
        assertThrows(RuntimeException.class, () -> config.check(envelope));
        var deep = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/></securitylabel>");
        var cursor = deep.elements().get(0);
        for (int i = 0; i < 40; i++) cursor = cursor.addElement("nested");
        assertThrows(RuntimeException.class, () -> config.check(deep));
    }

    @Test void rejectsExternalEntitiesInEveryAdministratorXmlDocument() {
        String xxe = "<!DOCTYPE x [<!ENTITY steal SYSTEM 'file:///etc/passwd'>]><x>&steal;</x>";
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(List.of(new SecurityPolicy("id", "Name", xxe, null, null, null, null, false)), List.of(), LabelFormat.ESS));
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(List.of(new SecurityPolicy("1.2.826.0.1.6726289.0.0", "Food", Fixtures.read("food-policy"), xxe, LabelFormat.XML, null, null, false)), List.of(), LabelFormat.ESS));
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(List.of(new SecurityPolicy("1.2.826.0.1.6726289.0.0", "Food", Fixtures.read("food-policy"), null, null, xxe, LabelFormat.XML, false)), List.of(), LabelFormat.ESS));
    }

    @Test void noPeerClearanceConfiguredByDefault() {
        var configuration = Fixtures.configuration();
        assertFalse(configuration.hasPeerClearance());
    }

    @Test void peerClearanceCanBeConfigured() {
        var configuration = Fixtures.configurationWithPeerClearance("food-clearance-all-okay");
        assertTrue(configuration.hasPeerClearance());
    }

    @Test void invalidPeerClearanceIsRejectedAtConfigurationTime() {
        PeerClearance invalid = new PeerClearance("pc1", "", "1.2.826.0.1.6726289.0.0", "not a valid clearance", LabelFormat.XML);
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(List.of(Fixtures.foodPolicy()), List.of(invalid), LabelFormat.ESS));
    }

    @Test void checkPermitsLabelWhenPeerClearanceIsPermissive() {
        var configuration = Fixtures.configurationWithPeerClearance("food-clearance-all-okay");
        Element envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML);
        assertSame(envelope, configuration.check(envelope, true));
    }

    @Test void checkRejectsLabelDeniedByPeerClearanceEvenWhenServerClearancePermitsIt() {
        var configuration = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        Element envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML);
        assertDoesNotThrow(() -> configuration.check(envelope));
        assertDoesNotThrow(() -> configuration.check(envelope, false));
        assertThrows(RuntimeException.class, () -> configuration.check(envelope, true));
    }

    @Test void checkDefaultPeerClearanceRejectsWhenPeerClearanceDeniesTheDefault() {
        var permissive = Fixtures.configurationWithPeerClearance("food-clearance-all-okay");
        assertDoesNotThrow(() -> permissive.checkDefaultPeerClearance(""));
        var restrictive = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        assertThrows(RuntimeException.class, () -> restrictive.checkDefaultPeerClearance(""));
    }

    @Test void checkDefaultPeerClearanceIsANoOpWithoutAConfiguredPeerClearance() {
        var configuration = Fixtures.configuration();
        assertDoesNotThrow(() -> configuration.checkDefaultPeerClearance(""));
    }

    @Test void checkPeerClearanceOnEnvelopeIsANoOpWithoutAConfiguredPeerClearance() {
        var configuration = Fixtures.configuration();
        Element envelope = Fixtures.envelope("food-label-water", LabelFormat.XML);
        assertDoesNotThrow(() -> configuration.checkPeerClearance(envelope, ""));
    }

    @Test void checkPeerClearanceOnEnvelopeRejectsADeniedLabel() {
        var permissive = Fixtures.configurationWithPeerClearance("food-clearance-all-okay");
        assertDoesNotThrow(() -> permissive.checkPeerClearance(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML), ""));
        var restrictive = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        assertThrows(RuntimeException.class, () -> restrictive.checkPeerClearance(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML), ""));
    }

    private static Element foodSpiffyLabel(String classificationLacv, String tagLacv) {
        return Fixtures.xml("<label xmlns='http://surevine.com/xmlns/spiffy'>" +
            "<policy id='1.2.826.0.1.6726289.0.0'>Food</policy>" +
            "<classification lacv='" + classificationLacv + "'>Luxury</classification>" +
            "<tag type='permissive' id='1.2.826.0.1.6726289.0.0.1' lacv='" + tagLacv + "'>Sweet</tag></label>");
    }

    private static Element drinkSpiffyLabel(String classificationLacv, String tagLacv) {
        String tag = tagLacv == null ? "" : "<tag type='permissive' id='1.2.826.0.1.6726289.0.1.1' lacv='" + tagLacv + "'>Sweet</tag>";
        return Fixtures.xml("<label xmlns='http://surevine.com/xmlns/spiffy'>" +
            "<policy id='1.2.826.0.1.6726289.0.1'>Drink</policy>" +
            "<classification lacv='" + classificationLacv + "'>House Wine</classification>" + tag + "</label>");
    }

    @Test void loadsMultiplePoliciesIntoOneRegistry() {
        var configuration = Fixtures.configurationWithSecondPolicy();
        Element envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML);
        assertSame(envelope, configuration.check(envelope));
    }

    @Test void permitsAPrimaryLabelFromASecondLoadedPolicyViaDeclaredTranslation() {
        var configuration = Fixtures.configurationWithSecondPolicy();
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/></securitylabel>");
        envelope.elements().get(0).add(drinkSpiffyLabel("10", "0"));
        assertSame(envelope, configuration.check(envelope));
    }

    @Test void rejectsALabelFromASecondLoadedPolicyWithoutADeclaredEquivalence() {
        var configuration = Fixtures.configurationWithSecondPolicy();
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/></securitylabel>");
        envelope.elements().get(0).add(drinkSpiffyLabel("11", null));
        assertThrows(RuntimeException.class, () -> configuration.check(envelope));
    }

    @Test void permitsAnEquivalentLabelFromASecondLoadedPolicyViaDeclaredTranslation() {
        var configuration = Fixtures.configurationWithSecondPolicy();
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/><equivalentlabel/></securitylabel>");
        envelope.elements().get(0).add(foodSpiffyLabel("52", "3"));
        envelope.elements().get(1).add(drinkSpiffyLabel("10", "0"));
        assertSame(envelope, configuration.check(envelope));
    }

    @Test void rejectsAFalseEquivalentLabelFromASecondLoadedPolicy() {
        var configuration = Fixtures.configurationWithSecondPolicy();
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/><equivalentlabel/></securitylabel>");
        Element primary = Fixtures.xml("<label xmlns='http://surevine.com/xmlns/spiffy'>" +
            "<policy id='1.2.826.0.1.6726289.0.0'>Food</policy><classification lacv='52'>Luxury</classification>" +
            "<tag type='permissive' id='1.2.826.0.1.6726289.0.0.1' lacv='3'>Sweet</tag>" +
            "<tag type='permissive' id='1.2.826.0.1.6726289.0.0.1' lacv='6'>Chocolate</tag></label>");
        envelope.elements().get(0).add(primary);
        envelope.elements().get(1).add(drinkSpiffyLabel("10", "0"));
        assertThrows(RuntimeException.class, () -> configuration.check(envelope));
    }

    @Test void catalogLabelFromASecondLoadedPolicyIsTranslatedAndValidated() {
        var configuration = Fixtures.configurationWithSecondPolicy();
        String payload = drinkSpiffyLabel("10", "0").asXML();
        assertDoesNotThrow(() -> configuration.encodeCatalogLabel(payload, LabelFormat.XML, false));
        String denied = drinkSpiffyLabel("11", null).asXML();
        assertThrows(IllegalArgumentException.class, () -> configuration.encodeCatalogLabel(denied, LabelFormat.XML, false));
    }

    @Test void encodeCatalogLabelOptionallyChecksThePeerClearance() {
        var permissive = Fixtures.configurationWithPeerClearance("food-clearance-all-okay");
        String payload = Fixtures.read("food-label-milk-chocolate");
        assertDoesNotThrow(() -> permissive.encodeCatalogLabel(payload, LabelFormat.XML, true));
        var restrictive = Fixtures.configurationWithPeerClearance("food-clearance-lactose-intolerant");
        assertDoesNotThrow(() -> restrictive.encodeCatalogLabel(payload, LabelFormat.XML, false));
        assertThrows(IllegalArgumentException.class, () -> restrictive.encodeCatalogLabel(payload, LabelFormat.XML, true));
    }

    @Test void loadedPoliciesListsEveryLoadedPolicyInLoadOrderWithItsOriginalDocument() {
        var configuration = Fixtures.configurationWithSecondPolicy();
        var loaded = configuration.loadedPolicies();
        assertEquals(2, loaded.size());
        assertEquals("1.2.826.0.1.6726289.0.0", loaded.get(0).id());
        assertEquals("Food", loaded.get(0).name());
        assertEquals(Fixtures.read("food-policy"), loaded.get(0).document());
        assertEquals("1.2.826.0.1.6726289.0.1", loaded.get(1).id());
        assertEquals("Drink", loaded.get(1).name());
        assertEquals(Fixtures.read("drink-policy"), loaded.get(1).document());
    }

    @Test void loadedPolicyByIdAndByNameLookup() {
        var configuration = Fixtures.configurationWithSecondPolicy();
        assertEquals("Food", configuration.loadedPolicyById("1.2.826.0.1.6726289.0.0").name());
        assertEquals("1.2.826.0.1.6726289.0.0", configuration.loadedPolicyByName("Food").id());
        assertEquals("Drink", configuration.loadedPolicyById("1.2.826.0.1.6726289.0.1").name());
        assertEquals("1.2.826.0.1.6726289.0.1", configuration.loadedPolicyByName("Drink").id());
        assertThrows(IllegalArgumentException.class, () -> configuration.loadedPolicyById("unknown"));
        assertThrows(IllegalArgumentException.class, () -> configuration.loadedPolicyByName("unknown"));
    }
}
