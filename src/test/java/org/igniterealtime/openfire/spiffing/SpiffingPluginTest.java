package org.igniterealtime.openfire.spiffing;

import org.junit.jupiter.api.Test;
import org.jivesoftware.openfire.interceptor.PacketRejectedException;
import org.xmpp.packet.Message;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SpiffingPluginTest {
    static final class Runtime implements SpiffingPlugin.Runtime {
        final List<SecurityLabelInterceptor> interceptors = new ArrayList<>();
        final List<Message> replies = new ArrayList<>();
        boolean feature;
        public void addInterceptor(SecurityLabelInterceptor i) { interceptors.add(i); }
        public void removeInterceptor(SecurityLabelInterceptor i) { assertTrue(interceptors.remove(i)); }
        public void addFeature() { feature = true; }
        public void removeFeature() { feature = false; }
        public void reply(Message error) { replies.add(error); }
    }

    @Test void registersOnceUnregistersAndRestoresPersistedSettings() throws Exception {
        var runtime = new Runtime();
        var store = new ConfigurationServiceTest.Store();
        var plugin = new SpiffingPlugin(new ConfigurationService(store), runtime);
        plugin.initializePlugin(null, null);
        plugin.initializePlugin(null, null);
        assertEquals(1, runtime.interceptors.size());
        assertTrue(runtime.feature);
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
        assertFalse(runtime.feature);
        plugin.initializePlugin(null, null);
        assertEquals(Fixtures.settings(), plugin.getSettings());
        plugin.destroyPlugin();
    }

    @Test void corruptedStartupStillRegistersEnforcementAndAdminSaveRecovers() {
        var runtime = new Runtime();
        var store = new ConfigurationServiceTest.Store();
        store.value = "broken";
        var plugin = new SpiffingPlugin(new ConfigurationService(store), runtime);
        plugin.initializePlugin(null, null);
        assertFalse(plugin.isConfigured());
        assertEquals(1, runtime.interceptors.size());
        assertThrows(PacketRejectedException.class, () -> runtime.interceptors.get(0).interceptPacket(new Message(), null, true, false));
        plugin.save(Fixtures.settings());
        assertTrue(plugin.isConfigured());
        plugin.destroyPlugin();
    }
}
