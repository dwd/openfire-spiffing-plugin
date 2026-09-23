package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Site;
import org.dom4j.Element;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class Fixtures {
    static String read(String name) {
        try (var stream = Fixtures.class.getResourceAsStream("/fixtures/" + name + ".xml")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    static Settings settings() { return settings(LabelFormat.ESS); }

    // Tests default to "enforce" so pre-existing rejection assertions keep testing rejection.
    static Settings settings(LabelFormat output) { return settings(output, EnforcementMode.ENFORCE); }

    static Settings settings(LabelFormat output, EnforcementMode enforcementMode) {
        return settings(output, enforcementMode, false);
    }

    static Settings settings(LabelFormat output, EnforcementMode enforcementMode, boolean stripDefaultLabelForFederation) {
        return new Settings(read("food-policy"), read("food-clearance-all-okay"), LabelFormat.XML,
            read("food-label-milk-chocolate"), LabelFormat.XML, output, enforcementMode, stripDefaultLabelForFederation);
    }

    /** A configuration with a configured peer clearance, built from an existing clearance fixture. */
    static Settings settingsWithPeerClearance(String peerClearanceFixture, EnforcementMode enforcementMode) {
        return new Settings(read("food-policy"), read("food-clearance-all-okay"), LabelFormat.XML,
            read("food-label-milk-chocolate"), LabelFormat.XML, LabelFormat.ESS, enforcementMode, false,
            read(peerClearanceFixture), LabelFormat.XML);
    }

    static Element envelope(String fixture, LabelFormat format) {
        Site site = new Site();
        site.load(read("food-policy"));
        var label = site.label(read(fixture));
        byte[] data = label.write(format.format);
        String payload = format == LabelFormat.ESS
            ? "<esssecuritylabel xmlns='" + PolicyConfiguration.ESS_NAMESPACE + "'>" + Base64.getEncoder().encodeToString(data) + "</esssecuritylabel>"
            : new String(data, StandardCharsets.UTF_8);
        return xml("<securitylabel xmlns='" + PolicyConfiguration.NAMESPACE + "'><label>" + payload + "</label></securitylabel>");
    }

    static Element xml(String xml) { return SecureXml.parse(xml, SecureXml.MAX_DOCUMENT); }
}
