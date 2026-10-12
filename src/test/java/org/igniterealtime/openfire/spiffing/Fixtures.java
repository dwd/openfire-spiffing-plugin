package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Site;
import org.dom4j.Element;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

final class Fixtures {
    static String read(String name) {
        try (var stream = Fixtures.class.getResourceAsStream("/fixtures/" + name + ".xml")) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new AssertionError(e); }
    }

    static SecurityPolicy foodPolicy() {
        return foodPolicy(read("food-clearance-all-okay"), read("food-label-milk-chocolate"));
    }

    static SecurityPolicy foodPolicy(String clearance, String defaultLabel) {
        return new SecurityPolicy(
            "1.2.826.0.1.6726289.0.0",
            "Food",
            read("food-policy"),
            clearance,
            clearance != null ? LabelFormat.XML : null,
            defaultLabel,
            defaultLabel != null ? LabelFormat.XML : null,
            true
        );
    }

    static SecurityPolicy drinkPolicy() {
        return new SecurityPolicy(
            "1.2.826.0.1.6726289.0.1",
            "Drink",
            read("drink-policy"),
            null,
            null,
            null,
            null,
            false
        );
    }

    static PolicyConfiguration configuration() {
        return configuration(LabelFormat.ESS);
    }

    static PolicyConfiguration configuration(LabelFormat output) {
        return new PolicyConfiguration(List.of(foodPolicy()), List.of(), output);
    }

    static PolicyConfiguration configurationWithPeerClearance(String peerClearanceFixture) {
        PeerClearance pc = new PeerClearance("pc1", "", "1.2.826.0.1.6726289.0.0", read(peerClearanceFixture), LabelFormat.XML);
        return new PolicyConfiguration(List.of(foodPolicy()), List.of(pc), LabelFormat.ESS);
    }

    static PolicyConfiguration configurationWithSecondPolicy() {
        return new PolicyConfiguration(List.of(foodPolicy(), drinkPolicy()), List.of(), LabelFormat.ESS);
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
