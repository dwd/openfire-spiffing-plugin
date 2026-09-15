package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpiffingPluginTest {
    static final class Runtime implements SpiffingPlugin.Runtime {
        final List<SecurityLabelInterceptor> interceptors = new ArrayList<>();
        final List<CatalogIqHandler> iqHandlers = new ArrayList<>();
        final List<Message> replies = new ArrayList<>();
        final Set<String> features = new HashSet<>();
        public void addInterceptor(SecurityLabelInterceptor i) { interceptors.add(i); }
        public void removeInterceptor(SecurityLabelInterceptor i) { assertTrue(interceptors.remove(i)); }
        public void addFeature(String namespace) { features.add(namespace); }
        public void removeFeature(String namespace) { features.remove(namespace); }
        public void addIqHandler(CatalogIqHandler handler) { iqHandlers.add(handler); }
        public void removeIqHandler(CatalogIqHandler handler) { assertTrue(iqHandlers.remove(handler)); }
        public boolean isLocal(JID jid) { return true; }
        public void reply(Message error) { replies.add(error); }
    }

    static final class CatalogStoreFake implements CatalogStore {
        final List<CatalogEntry> entries = new ArrayList<>();
        public List<CatalogEntry> list() { return new ArrayList<>(entries); }
        public void add(CatalogEntry entry) { entries.add(entry); }
        public void remove(String id) { entries.removeIf(e -> e.id().equals(id)); }
        public void clearDefault() { entries.replaceAll(e -> new CatalogEntry(e.id(), e.name(), e.selector(), e.format(), e.payload(), false)); }
    }

    @Test void registersOnceUnregistersAndRestoresPersistedSettings() throws Exception {
        var runtime = new Runtime();
        var store = new ConfigurationServiceTest.Store();
        var configuration = new ConfigurationService(store);
        var plugin = new SpiffingPlugin(configuration, new CatalogService(new CatalogStoreFake(), configuration::current), runtime);
        plugin.initializePlugin(null, null);
        plugin.initializePlugin(null, null);
        assertEquals(1, runtime.interceptors.size());
        assertEquals(1, runtime.iqHandlers.size());
        assertTrue(runtime.features.contains(PolicyConfiguration.NAMESPACE));
        assertTrue(runtime.features.contains(CatalogService.NAMESPACE));
        assertFalse(plugin.isConfigured());
        var interceptor = runtime.interceptors.get(0);
        assertThrows(PacketRejectedException.class, () -> interceptor.interceptPacket(new Message(), null, true, false));
        plugin.save(Fixtures.settings());
        assertTrue(plugin.isConfigured());
        var message = new Message();
        interceptor.interceptPacket(message, null, true, false);
        assertNotNull(message.getElement().element(PolicyConfiguration.ENVELOPE));
        plugin.destroyPlugin();
        plugin.destroyPlugin();
        assertTrue(runtime.interceptors.isEmpty());
        assertTrue(runtime.iqHandlers.isEmpty());
        assertFalse(runtime.features.contains(PolicyConfiguration.NAMESPACE));
        assertFalse(runtime.features.contains(CatalogService.NAMESPACE));
        plugin.initializePlugin(null, null);
        assertEquals(Fixtures.settings(), plugin.getSettings());
        plugin.destroyPlugin();
    }

    @Test void corruptedStartupStillRegistersEnforcementAndAdminSaveRecovers() {
        var runtime = new Runtime();
        var store = new ConfigurationServiceTest.Store();
        store.value = "broken";
        var configuration = new ConfigurationService(store);
        var plugin = new SpiffingPlugin(configuration, new CatalogService(new CatalogStoreFake(), configuration::current), runtime);
        plugin.initializePlugin(null, null);
        assertFalse(plugin.isConfigured());
        assertEquals(1, runtime.interceptors.size());
        assertThrows(PacketRejectedException.class, () -> runtime.interceptors.get(0).interceptPacket(new Message(), null, true, false));
        plugin.save(Fixtures.settings());
        assertTrue(plugin.isConfigured());
        plugin.destroyPlugin();
    }

    @Test void addsListsAndRemovesCatalogEntriesThroughThePlugin() {
        var runtime = new Runtime();
        var store = new ConfigurationServiceTest.Store();
        var configuration = new ConfigurationService(store);
        var plugin = new SpiffingPlugin(configuration, new CatalogService(new CatalogStoreFake(), configuration::current), runtime);
        plugin.initializePlugin(null, null);
        plugin.save(Fixtures.settings());
        var entry = plugin.catalogService().add("Milk chocolate", "Food|Chocolate", LabelFormat.XML, Fixtures.read("food-label-milk-chocolate"), true);
        assertEquals(1, plugin.catalogService().entries().size());
        assertTrue(plugin.catalogService().entries().get(0).isDefault());
        plugin.catalogService().remove(entry.id());
        assertTrue(plugin.catalogService().entries().isEmpty());
        plugin.destroyPlugin();
    }
}
