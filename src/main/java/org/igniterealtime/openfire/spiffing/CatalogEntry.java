package org.igniterealtime.openfire.spiffing;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One administrator-defined, named security label offered to clients via XEP-0258 label catalogue
 * discovery ({@code urn:xmpp:sec-label:catalog:2}). Independent from the message-stamping default
 * label configured in {@link Settings}; at most one entry may be flagged as the catalogue default.
 */
public record CatalogEntry(String id, String name, String selector, LabelFormat format, String payload, boolean isDefault) {
    static final int MAX_NAME = 256;
    static final int MAX_SELECTOR = 256;
    // XEP-0258 selector-value = <item> *( "|" <item> ), where <item> excludes "|". Segments may not be blank.
    private static final Pattern SELECTOR = Pattern.compile("[^|]+(\\|[^|]+)*");

    public CatalogEntry {
        Objects.requireNonNull(id);
        SecureXml.bounded(name, MAX_NAME);
        if (selector != null && selector.isEmpty()) selector = null;
        if (selector != null) {
            SecureXml.bounded(selector, MAX_SELECTOR);
            if (!SELECTOR.matcher(selector).matches()) {
                throw new IllegalArgumentException("Selector must be a non-empty '|'-separated path, e.g. Classified|SECRET.");
            }
        }
        Objects.requireNonNull(format);
        SecureXml.bounded(payload, SecureXml.MAX_LABEL);
    }
}
