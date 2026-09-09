package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Format;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public enum LabelFormat {
    ESS(Format.DER), NATO(Format.NATO), XML(Format.XML);

    final Format format;

    LabelFormat(Format format) { this.format = format; }

    byte[] decode(String text) {
        SecureXml.bounded(text, SecureXml.MAX_LABEL);
        return this == ESS ? decodeBase64(text) : text.getBytes(StandardCharsets.UTF_8);
    }

    static byte[] decodeBase64(String text) {
        // XML Schema base64Binary permits XML whitespace, not arbitrary MIME characters.
        return Base64.getDecoder().decode(text.replaceAll("[ \t\r\n]", ""));
    }
}
