package org.igniterealtime.openfire.spiffing;

import org.dom4j.QName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogServiceTest {
    static final class Store implements CatalogStore {
        final List<CatalogEntry> entries = new ArrayList<>();
        int clearDefaultCalls;
        public List<CatalogEntry> list() { return new ArrayList<>(entries); }
        public void add(CatalogEntry entry) { entries.add(entry); }
        public void remove(String id) { entries.removeIf(e -> e.id().equals(id)); }
        public void clearDefault() {
            clearDefaultCalls++;
            entries.replaceAll(e -> new CatalogEntry(e.id(), e.name(), e.selector(), e.format(), e.payload(), false));
        }
    }

    @Test void rejectsAddWithoutAnActiveConfiguration() {
        var service = new CatalogService(new Store(), () -> null);
        assertThrows(IllegalArgumentException.class, () -> service.add("Public", null, LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), false));
    }

    @Test void addValidatesAgainstTheActivePolicyAndClearance() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var service = new CatalogService(new Store(), () -> configuration);
        assertThrows(IllegalArgumentException.class,
            () -> service.add("Bacon", null, LabelFormat.XML, Fixtures.read("food-label-bacon"), false));
        assertTrue(service.entries().isEmpty());
        var entry = service.add("Milk chocolate", "Food|Chocolate", LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), false);
        assertEquals(List.of(entry), service.entries());
    }

    @Test void onlyOneEntryCanBeTheDefault() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var store = new Store();
        var service = new CatalogService(store, () -> configuration);
        var first = service.add("First", null, LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), true);
        assertTrue(first.isDefault());
        var second = service.add("Second", null, LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), true);
        assertTrue(second.isDefault());
        assertEquals(2, store.clearDefaultCalls);
        assertEquals(2, service.entries().size());
        long defaults = service.entries().stream().filter(CatalogEntry::isDefault).count();
        assertEquals(1, defaults);
    }

    @Test void removeDeletesTheEntry() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var service = new CatalogService(new Store(), () -> configuration);
        var entry = service.add("Milk chocolate", null, LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), false);
        service.remove(entry.id());
        assertTrue(service.entries().isEmpty());
    }

    @Test void buildCatalogFailsClosedWithoutAnActiveConfiguration() {
        var service = new CatalogService(new Store(), () -> null);
        assertThrows(IllegalStateException.class, () -> service.buildCatalog(false));
    }

    @Test void buildCatalogEncodesEachEntryWithSelectorAndDefaultAttributes() {
        var configuration = new PolicyConfiguration(Fixtures.settings());
        var service = new CatalogService(new Store(), () -> configuration);
        service.add("Milk chocolate", "Food|Chocolate", LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), true);
        var catalog = service.buildCatalog(false);
        assertEquals(CatalogService.NAMESPACE, catalog.getNamespaceURI());
        assertEquals("false", catalog.attributeValue("restrict"));
        var item = catalog.element(QName.get("item", CatalogService.NAMESPACE));
        assertNotNull(item);
        assertEquals("Food|Chocolate", item.attributeValue("selector"));
        assertEquals("true", item.attributeValue("default"));
        assertNotNull(item.element(QName.get("securitylabel", PolicyConfiguration.NAMESPACE)));
    }

    @Test void entriesThatNoLongerValidateAreOmittedFromTheCatalog() {
        var settings = Fixtures.settings();
        var configuration = new PolicyConfiguration(settings);
        var store = new Store();
        var service = new CatalogService(store, () -> configuration);
        service.add("Milk chocolate", null, LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), false);
        // Simulate a policy/clearance change that no longer authorizes a previously valid entry.
        store.entries.set(0, new CatalogEntry(store.entries.get(0).id(), "Bacon", null, LabelFormat.XML, Fixtures.read("food-label-bacon"), false));
        var catalog = service.buildCatalog(false);
        assertTrue(catalog.elements(QName.get("item", CatalogService.NAMESPACE)).isEmpty());
    }

    @Test void buildCatalogOmitsAnEntryDeniedByThePeerClearanceWhenRequested() {
        var configuration = new PolicyConfiguration(Fixtures.settingsWithPeerClearance("food-clearance-lactose-intolerant", EnforcementMode.ENFORCE));
        var service = new CatalogService(new Store(), () -> configuration);
        // Permitted by the server clearance (food-clearance-all-okay), but the peer clearance denies milk chocolate.
        service.add("Milk chocolate", null, LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), false);
        var withoutPeerCheck = service.buildCatalog(false);
        assertFalse(withoutPeerCheck.elements(QName.get("item", CatalogService.NAMESPACE)).isEmpty());
        var withPeerCheck = service.buildCatalog(true);
        assertTrue(withPeerCheck.elements(QName.get("item", CatalogService.NAMESPACE)).isEmpty());
    }
}
