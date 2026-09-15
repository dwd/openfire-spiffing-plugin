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
        assertThrows(IllegalArgumentException.class, () -> Settings.fromXml(xxe));
    }
}
