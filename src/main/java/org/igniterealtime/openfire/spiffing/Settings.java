package org.igniterealtime.openfire.spiffing;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import java.util.Objects;

/** All settings are persisted together, so readers never see a partial policy update. */
public record Settings(String policy, String clearance, LabelFormat clearanceFormat,
                       String defaultLabel, LabelFormat labelFormat, LabelFormat outputFormat) {
    public Settings {
        SecureXml.bounded(policy, SecureXml.MAX_DOCUMENT);
        SecureXml.bounded(clearance, SecureXml.MAX_LABEL);
        SecureXml.bounded(defaultLabel, SecureXml.MAX_LABEL);
        Objects.requireNonNull(clearanceFormat);
        Objects.requireNonNull(labelFormat);
        Objects.requireNonNull(outputFormat);
    }

    public String toXml() {
        Element root = DocumentHelper.createElement("spiffing-settings");
        root.addAttribute("version", "1");
        root.addElement("policy").setText(policy);
        root.addElement("clearance").addAttribute("format", clearanceFormat.name()).setText(clearance);
        root.addElement("default-label").addAttribute("format", labelFormat.name())
            .addAttribute("output", outputFormat.name()).setText(defaultLabel);
        return root.asXML();
    }

    public static Settings fromXml(String xml) {
        Element root = SecureXml.parse(xml, SecureXml.MAX_DOCUMENT * 6);
        if (!root.getName().equals("spiffing-settings") || !root.getNamespaceURI().isEmpty()
            || !"1".equals(root.attributeValue("version")) || root.elements().size() != 3) {
            throw new IllegalArgumentException("Unsupported settings document.");
        }
        Element policy = single(root, "policy"), clearance = single(root, "clearance"), label = single(root, "default-label");
        return new Settings(policy.getText(), clearance.getText(), LabelFormat.valueOf(clearance.attributeValue("format")),
            label.getText(), LabelFormat.valueOf(label.attributeValue("format")), LabelFormat.valueOf(label.attributeValue("output")));
    }

    private static Element single(Element root, String name) {
        var matches = root.elements(org.dom4j.QName.get(name));
        if (matches.size() != 1 || !matches.get(0).elements().isEmpty()) {
            throw new IllegalArgumentException("Invalid settings field.");
        }
        return matches.get(0);
    }
}
