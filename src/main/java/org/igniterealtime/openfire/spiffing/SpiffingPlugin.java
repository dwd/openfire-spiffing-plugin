package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.io.File;

public final class SpiffingPlugin implements Plugin {
    private static final Logger LOG = LoggerFactory.getLogger(SpiffingPlugin.class);

    interface Runtime {
        void addInterceptor(SecurityLabelInterceptor interceptor);
        void removeInterceptor(SecurityLabelInterceptor interceptor);
        void addFeature(String namespace);
        void removeFeature(String namespace);
        void addIqHandler(CatalogIqHandler handler);
        void removeIqHandler(CatalogIqHandler handler);
        boolean isLocal(JID jid);
        void reply(Message error);
    }

    private ConfigurationService configuration;
    private CatalogService catalog;
    private final Runtime runtime;
    private SecurityLabelInterceptor interceptor;
    private CatalogIqHandler catalogIqHandler;

    public SpiffingPlugin() {
        runtime = new Runtime() {
            public void addInterceptor(SecurityLabelInterceptor i) { InterceptorManager.getInstance().addInterceptor(i); }
            public void removeInterceptor(SecurityLabelInterceptor i) { InterceptorManager.getInstance().removeInterceptor(i); }
            public void addFeature(String namespace) { XMPPServer.getInstance().getIQDiscoInfoHandler().addServerFeature(namespace); }
            public void removeFeature(String namespace) { XMPPServer.getInstance().getIQDiscoInfoHandler().removeServerFeature(namespace); }
            public void addIqHandler(CatalogIqHandler handler) { XMPPServer.getInstance().getIQRouter().addHandler(handler); }
            public void removeIqHandler(CatalogIqHandler handler) { XMPPServer.getInstance().getIQRouter().removeHandler(handler); }
            public boolean isLocal(JID jid) { return XMPPServer.getInstance().isLocal(jid); }
            public void reply(Message error) { XMPPServer.getInstance().getRoutingTable().routePacket(error.getTo(), error); }
        };
    }

    SpiffingPlugin(ConfigurationService configuration, CatalogService catalog, Runtime runtime) {
        this.configuration = configuration;
        this.catalog = catalog;
        this.runtime = runtime;
    }

    @Override
    public synchronized void initializePlugin(PluginManager manager, File directory) {
        if (interceptor != null) return;
        if (configuration == null) {
            configuration = new ConfigurationService(new JiveGlobalsConfigurationStore());
        }
        if (catalog == null) {
            catalog = new CatalogService(new DatabaseCatalogStore(), configuration::current);
        }
        try {
            configuration.reload();
        } catch (IllegalArgumentException e) {
            LOG.error("Stored Spiffing configuration is invalid; inbound messages are blocked. Reconfigure in the Admin Console.");
        }
        interceptor = new SecurityLabelInterceptor(configuration::current, runtime::reply);
        runtime.addInterceptor(interceptor);
        runtime.addFeature(PolicyConfiguration.NAMESPACE);
        catalogIqHandler = new CatalogIqHandler(catalog, runtime::isLocal);
        runtime.addIqHandler(catalogIqHandler);
        runtime.addFeature(CatalogService.NAMESPACE);
    }

    @Override
    public synchronized void destroyPlugin() {
        if (interceptor != null) {
            runtime.removeInterceptor(interceptor);
            runtime.removeFeature(PolicyConfiguration.NAMESPACE);
            interceptor = null;
        }
        if (catalogIqHandler != null) {
            runtime.removeIqHandler(catalogIqHandler);
            runtime.removeFeature(CatalogService.NAMESPACE);
            catalogIqHandler = null;
        }
    }

    public Settings getSettings() {
        PolicyConfiguration current = configuration.current();
        return current == null ? null : current.settings();
    }

    public boolean isConfigured() { return configuration.current() != null; }

    public void save(Settings settings) { configuration.save(settings); }

    public CatalogService catalogService() { return catalog; }
}
