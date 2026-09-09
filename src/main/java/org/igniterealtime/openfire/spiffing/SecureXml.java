package org.igniterealtime.openfire.spiffing;

import org.dom4j.Element;
import org.dom4j.io.SAXReader;
import java.io.StringReader;

final class SecureXml {
    static final int MAX_DOCUMENT = 1_048_576;
    static final int MAX_LABEL = 65_536;

    private SecureXml() {}

    static String bounded(String text, int limit) {
        if (text == null || text.isBlank() || text.length() > limit) {
            throw new IllegalArgumentException("Document is missing or exceeds the size limit.");
        }
        return text;
    }

    static Element parse(String xml, int limit) {
        bounded(xml, limit);
        try {
            SAXReader reader = new SAXReader();
            reader.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            reader.setFeature("http://xml.org/sax/features/external-general-entities", false);
            reader.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            reader.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            reader.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
            reader.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("External entities are disabled"); });
            return reader.read(new StringReader(xml)).getRootElement();
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid XML document.");
        }
    }
}
