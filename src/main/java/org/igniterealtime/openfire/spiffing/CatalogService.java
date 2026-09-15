package org.igniterealtime.openfire.spiffing;

import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.QName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Validates catalogue entries against the active {@link PolicyConfiguration} and builds XEP-0258
 * {@code <catalog/>} responses. Entries are persisted independently of the message-stamping default
 * label, so a policy/clearance change can leave a previously valid entry unable to authorize; such
 * entries are omitted (and logged) rather than failing the whole catalogue.
 */
public final class CatalogService {
    public static final String NAMESPACE = "urn:xmpp:sec-label:catalog:2";
    private static final Logger LOG = LoggerFactory.getLogger(CatalogService.class);

    private final CatalogStore store;
    private final Supplier<PolicyConfiguration> configuration;

    public CatalogService(CatalogStore store, Supplier<PolicyConfiguration> configuration) {
        this.store = store;
        this.configuration = configuration;
    }

    public List<CatalogEntry> entries() { return store.list(); }

    /**
     * Validates the label against the active policy/clearance, then persists the entry. A new default
     * entry replaces any previously configured default. Rejects the request if no configuration is active.
     */
    public synchronized CatalogEntry add(String name, String selector, LabelFormat format, String payload, boolean isDefault) {
        PolicyConfiguration current = configuration.get();
        if (current == null) {
            throw new IllegalArgumentException("Save a valid policy and clearance before adding catalogue entries.");
        }
        CatalogEntry entry = new CatalogEntry(UUID.randomUUID().toString(), name, selector, format, payload, isDefault);
        try {
            current.encodeCatalogLabel(entry.payload(), entry.format());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The label is invalid, exceeds the server clearance, or cannot be represented in the selected format.");
        }
        if (isDefault) store.clearDefault();
        store.add(entry);
        return entry;
    }

    public synchronized void remove(String id) { store.remove(id); }

    /**
     * Builds the catalogue element for the active configuration.
     *
     * @throws IllegalStateException if no configuration is currently active.
     */
    public Element buildCatalog() {
        PolicyConfiguration current = configuration.get();
        if (current == null) {
            throw new IllegalStateException("No active Spiffing configuration; the catalogue is unavailable.");
        }
        Element catalog = DocumentHelper.createElement(QName.get("catalog", NAMESPACE));
        catalog.addAttribute("restrict", "false");
        for (CatalogEntry entry : store.list()) {
            Element item = catalog.addElement(QName.get("item", NAMESPACE));
            if (entry.selector() != null) item.addAttribute("selector", entry.selector());
            if (entry.isDefault()) item.addAttribute("default", "true");
            try {
                item.add(current.encodeCatalogLabel(entry.payload(), entry.format()));
            } catch (RuntimeException e) {
                item.detach();
                LOG.warn("Catalogue entry '{}' no longer validates against the active policy; omitted from the published catalogue.", entry.name());
            }
        }
        return catalog;
    }
}
