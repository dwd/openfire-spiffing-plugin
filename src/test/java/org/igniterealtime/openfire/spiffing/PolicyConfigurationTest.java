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
        var configuration = new PolicyConfiguration(Fixtures.settings(format));
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
        var configuration = new PolicyConfiguration(Fixtures.settings());
        Element marking = configuration.defaultEnvelope().element(QName.get("displaymarking", PolicyConfiguration.NAMESPACE));
        assertEquals(marking.getTextTrim(), configuration.defaultDisplayMarking());
        assertFalse(configuration.defaultDisplayMarking().isEmpty());
    }

    @ParameterizedTest @EnumSource(LabelFormat.class)
    void permitsInboundLabelInEachFormat(LabelFormat format) {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        Element envelope = Fixtures.envelope("food-label-milk-chocolate", format);
        assertSame(envelope, configuration.check(envelope));
    }

    @ParameterizedTest @EnumSource(LabelFormat.class)
    void configurationAcceptsEachInputFormat(LabelFormat format) {
        Site site = new Site();
        site.load(Fixtures.read("food-policy"));
        byte[] label = site.label(Fixtures.read("food-label-milk-chocolate")).write(format.format);
        byte[] clearance = site.clearance(Fixtures.read("food-clearance-all-okay")).write(format.format);
        var settings = new Settings(Fixtures.read("food-policy"), text(clearance, format), format, text(label, format), format, LabelFormat.ESS);
        assertDoesNotThrow(() -> new PolicyConfiguration(settings));
    }

    private static String text(byte[] data, LabelFormat format) {
        return format == LabelFormat.ESS ? Base64.getEncoder().encodeToString(data) : new String(data, StandardCharsets.UTF_8);
    }

    @ParameterizedTest @ValueSource(strings={"food-label-cheap-milk-chocolate", "food-label-meaty-milk-chocolate", "food-label-bacon", "food-label-water"})
    void rejectsInvalidOrDeniedLabels(String fixture) {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        assertThrows(RuntimeException.class, () -> configuration.check(Fixtures.envelope(fixture, LabelFormat.XML)));
    }

    @Test void clearanceMatchIsInsufficientWithoutPolicyValidity() {
        Site site = new Site();
        var policy = site.load(Fixtures.read("food-policy"));
        var label = site.label(Fixtures.read("food-label-cheap-milk-chocolate"));
        assertTrue(policy.acdf(label, site.clearance(Fixtures.read("food-clearance-all-okay"))));
        assertFalse(policy.valid(label));
        Settings original = Fixtures.settings();
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(new Settings(original.policy(), original.clearance(), original.clearanceFormat(),
            Fixtures.read("food-label-cheap-milk-chocolate"), LabelFormat.XML, LabelFormat.ESS)));
    }

    @Test void restrictiveCategoryMissingFromClearanceDeniesDefault() {
        var s = Fixtures.settings();
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(new Settings(s.policy(),
            Fixtures.read("food-clearance-lactose-intolerant"), LabelFormat.XML, s.defaultLabel(), s.labelFormat(), s.outputFormat())));
    }

    @Test void classificationMembershipIsExplicitNotHierarchical() {
        var s = Fixtures.settings();
        String onlyHigher = s.clearance().replace("lacv='52'", "lacv='53'");
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(new Settings(s.policy(), onlyHigher,
            s.clearanceFormat(), s.defaultLabel(), s.labelFormat(), s.outputFormat())));
    }

    @Test void informativeCategoryDoesNotRequireClearancePrivilege() {
        var config = new PolicyConfiguration(Fixtures.settings());
        assertDoesNotThrow(() -> config.check(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML)));
        assertTrue(Fixtures.read("food-label-milk-chocolate").contains("informative"));
        assertFalse(Fixtures.read("food-clearance-all-okay").contains("informative"));
    }

    @Test void emptyPrimaryUsesDefaultAndReplacesMisleadingMarking() {
        var config = new PolicyConfiguration(Fixtures.settings());
        var empty = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><displaymarking>PUBLIC</displaymarking><label/></securitylabel>");
        assertEquals(config.defaultEnvelope().asXML(), config.check(empty).asXML());
    }

    @Test void displayMarkingCannotGrantAccess() {
        var config = new PolicyConfiguration(Fixtures.settings());
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
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'>" + children + "</securitylabel>");
        assertThrows(RuntimeException.class, () -> configuration.check(envelope));
    }

    @Test void permitsEquivalentEncodingOfSamePolicyLabel() {
        var config = new PolicyConfiguration(Fixtures.settings());
        var envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.ESS);
        var equivalent = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML).elements().get(0);
        equivalent.detach();
        equivalent.setQName(QName.get("equivalentlabel", PolicyConfiguration.NAMESPACE));
        envelope.add(equivalent);
        assertSame(envelope, config.check(envelope));
    }

    @Test void rejectsUnknownPolicyAndFalseEquivalence() {
        var config = new PolicyConfiguration(Fixtures.settings());
        var envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML);
        var equivalent = envelope.elements().get(0).createCopy();
        equivalent.setQName(QName.get("equivalentlabel", PolicyConfiguration.NAMESPACE));
        var equivalentCategories = equivalent.elements().get(0).elements();
        equivalentCategories.get(equivalentCategories.size() - 1).detach(); // remove permissive category; still a valid permitted label, but not equivalent
        envelope.add(equivalent);
        assertThrows(RuntimeException.class, () -> config.check(envelope));
        var foreign = Fixtures.xml(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML).asXML().replace("1.2.826.0.1.6726289.0.0\"", "1.2.3.4\""));
        assertThrows(RuntimeException.class, () -> config.check(foreign));
    }

    @Test void acceptsXmlWhitespaceButNotMimeGarbageInBase64() {
        var config = new PolicyConfiguration(Fixtures.settings());
        var envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.ESS);
        var payload = envelope.elements().get(0).elements().get(0);
        payload.setText(" \n\t" + payload.getText() + "\r\n");
        assertDoesNotThrow(() -> config.check(envelope));
        payload.setText(payload.getText() + "!");
        assertThrows(RuntimeException.class, () -> config.check(envelope));
    }

    @Test void rejectsOversizedAndDeepLabels() {
        var config = new PolicyConfiguration(Fixtures.settings());
        var envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.ESS);
        envelope.addAttribute("padding", "x".repeat(SecureXml.MAX_LABEL));
        assertThrows(RuntimeException.class, () -> config.check(envelope));
        var deep = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/></securitylabel>");
        var cursor = deep.elements().get(0);
        for (int i = 0; i < 40; i++) cursor = cursor.addElement("nested");
        assertThrows(RuntimeException.class, () -> config.check(deep));
    }

    @Test void rejectsExternalEntitiesInEveryAdministratorXmlDocument() {
        var s = Fixtures.settings();
        String xxe = "<!DOCTYPE x [<!ENTITY steal SYSTEM 'file:///etc/passwd'>]><x>&steal;</x>";
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(new Settings(xxe, s.clearance(), s.clearanceFormat(), s.defaultLabel(), s.labelFormat(), s.outputFormat())));
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(new Settings(s.policy(), xxe, LabelFormat.XML, s.defaultLabel(), s.labelFormat(), s.outputFormat())));
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(new Settings(s.policy(), s.clearance(), s.clearanceFormat(), xxe, LabelFormat.XML, s.outputFormat())));
    }

    @Test void noPeerClearanceConfiguredByDefault() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        assertFalse(configuration.hasPeerClearance());
    }

    @Test void peerClearanceCanBeConfigured() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-all-okay", EnforcementMode.ENFORCE));
        assertTrue(configuration.hasPeerClearance());
    }

    @Test void invalidPeerClearanceIsRejectedAtConfigurationTime() {
        var s = Fixtures.settings();
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(new Settings(s.policy(), s.clearance(), s.clearanceFormat(),
            s.defaultLabel(), s.labelFormat(), s.outputFormat(), EnforcementMode.ENFORCE, false, "not a valid clearance", LabelFormat.XML)));
    }

    @Test void checkPermitsLabelWhenPeerClearanceIsPermissive() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-all-okay", EnforcementMode.ENFORCE));
        Element envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML);
        assertSame(envelope, configuration.check(envelope, true));
    }

    @Test void checkRejectsLabelDeniedByPeerClearanceEvenWhenServerClearancePermitsIt() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-lactose-intolerant", EnforcementMode.ENFORCE));
        Element envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML);
        assertDoesNotThrow(() -> configuration.check(envelope)); // server clearance alone permits it
        assertDoesNotThrow(() -> configuration.check(envelope, false)); // peer check not requested
        assertThrows(RuntimeException.class, () -> configuration.check(envelope, true)); // peer clearance denies it
    }

    @Test void checkDefaultPeerClearanceRejectsWhenPeerClearanceDeniesTheDefault() {
        var permissive = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-all-okay", EnforcementMode.ENFORCE));
        assertDoesNotThrow(permissive::checkDefaultPeerClearance);
        var restrictive = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-lactose-intolerant", EnforcementMode.ENFORCE));
        assertThrows(RuntimeException.class, restrictive::checkDefaultPeerClearance);
    }

    @Test void checkDefaultPeerClearanceIsANoOpWithoutAConfiguredPeerClearance() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        assertDoesNotThrow(configuration::checkDefaultPeerClearance);
    }

    @Test void checkPeerClearanceOnEnvelopeIsANoOpWithoutAConfiguredPeerClearance() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        Element envelope = Fixtures.envelope("food-label-water", LabelFormat.XML); // denied by the server clearance
        assertDoesNotThrow(() -> configuration.checkPeerClearance(envelope));
    }

    @Test void checkPeerClearanceOnEnvelopeRejectsADeniedLabel() {
        var permissive = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-all-okay", EnforcementMode.ENFORCE));
        assertDoesNotThrow(() -> permissive.checkPeerClearance(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML)));
        var restrictive = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-lactose-intolerant", EnforcementMode.ENFORCE));
        assertThrows(RuntimeException.class, () -> restrictive.checkPeerClearance(Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML)));
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
        var configuration = new PolicyConfiguration(Fixtures.settingsWithSecondPolicy());
        // The primary (food) policy still checks exactly as before.
        Element envelope = Fixtures.envelope("food-label-milk-chocolate", LabelFormat.XML);
        assertSame(envelope, configuration.check(envelope));
    }

    @Test void permitsAPrimaryLabelFromASecondLoadedPolicyViaDeclaredTranslation() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithSecondPolicy());
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/></securitylabel>");
        envelope.elements().get(0).add(drinkSpiffyLabel("10", "0"));
        assertSame(envelope, configuration.check(envelope));
    }

    @Test void rejectsALabelFromASecondLoadedPolicyWithoutADeclaredEquivalence() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithSecondPolicy());
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/></securitylabel>");
        // "House Beer" (lacv 11) has no equivalentClassification back to the primary policy.
        envelope.elements().get(0).add(drinkSpiffyLabel("11", null));
        assertThrows(RuntimeException.class, () -> configuration.check(envelope));
    }

    @Test void permitsAnEquivalentLabelFromASecondLoadedPolicyViaDeclaredTranslation() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithSecondPolicy());
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/><equivalentlabel/></securitylabel>");
        envelope.elements().get(0).add(foodSpiffyLabel("52", "3"));
        envelope.elements().get(1).add(drinkSpiffyLabel("10", "0"));
        assertSame(envelope, configuration.check(envelope));
    }

    @Test void rejectsAFalseEquivalentLabelFromASecondLoadedPolicy() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithSecondPolicy());
        var envelope = Fixtures.xml("<securitylabel xmlns='urn:xmpp:sec-label:0'><label/><equivalentlabel/></securitylabel>");
        // A permitted food label with an extra category ("Sweet" and "Chocolate") does not equal the drink
        // equivalent, which only translates to "Sweet" alone; both are independently valid and permitted,
        // but their decoded category sets differ.
        Element primary = Fixtures.xml("<label xmlns='http://surevine.com/xmlns/spiffy'>" +
            "<policy id='1.2.826.0.1.6726289.0.0'>Food</policy><classification lacv='52'>Luxury</classification>" +
            "<tag type='permissive' id='1.2.826.0.1.6726289.0.0.1' lacv='3'>Sweet</tag>" +
            "<tag type='permissive' id='1.2.826.0.1.6726289.0.0.1' lacv='6'>Chocolate</tag></label>");
        envelope.elements().get(0).add(primary);
        envelope.elements().get(1).add(drinkSpiffyLabel("10", "0")); // translates to Luxury+Sweet only
        assertThrows(RuntimeException.class, () -> configuration.check(envelope));
    }

    @Test void catalogLabelFromASecondLoadedPolicyIsTranslatedAndValidated() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithSecondPolicy());
        String payload = drinkSpiffyLabel("10", "0").asXML();
        assertDoesNotThrow(() -> configuration.encodeCatalogLabel(payload, LabelFormat.XML));
        String denied = drinkSpiffyLabel("11", null).asXML();
        assertThrows(IllegalArgumentException.class, () -> configuration.encodeCatalogLabel(denied, LabelFormat.XML));
    }

    @Test void clearanceMustBelongToThePrimaryPolicy() {
        var s = Fixtures.settingsWithSecondPolicy();
        // Encode the clearance under the (loaded) secondary policy instead of the primary one.
        String drinkClearance = "<clearance xmlns='http://surevine.com/xmlns/spiffy'>" +
            "<policy id='1.2.826.0.1.6726289.0.1'>Drink</policy><classification lacv='10'>House Wine</classification></clearance>";
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(new Settings(s.policies(), drinkClearance, LabelFormat.XML,
            s.defaultLabel(), s.labelFormat(), s.outputFormat(), s.enforcementMode())));
    }

    @Test void rejectsDuplicatePolicies() {
        var s = Fixtures.settingsWithSecondPolicy();
        assertThrows(IllegalArgumentException.class, () -> new PolicyConfiguration(new Settings(
            List.of(s.policy(), s.policy()), s.clearance(), s.clearanceFormat(), s.defaultLabel(), s.labelFormat(), s.outputFormat())));
    }
}
