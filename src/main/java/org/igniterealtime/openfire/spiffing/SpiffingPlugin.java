package org.igniterealtime.openfire.spiffing;

import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.util.JiveGlobals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.Message;

import java.io.File;

public final class SpiffingPlugin implements Plugin {
    private static final Logger LOG = LoggerFactory.getLogger(SpiffingPlugin.class);

    interface Runtime {
        void addInterceptor(SecurityLabelInterceptor interceptor);
        void removeInterceptor(SecurityLabelInterceptor interceptor);
        void addFeature();
        void removeFeature();
        void reply(Message error);
    }

    private ConfigurationService configuration;
    private final Runtime runtime;
    private SecurityLabelInterceptor interceptor;

    public SpiffingPlugin() {
        runtime = new Runtime() {
            public void addInterceptor(SecurityLabelInterceptor i) { InterceptorManager.getInstance().addInterceptor(i); }
            public void removeInterceptor(SecurityLabelInterceptor i) { InterceptorManager.getInstance().removeInterceptor(i); }
            public void addFeature() { XMPPServer.getInstance().getIQDiscoInfoHandler().addServerFeature(PolicyConfiguration.NAMESPACE); }
            public void removeFeature() { XMPPServer.getInstance().getIQDiscoInfoHandler().removeServerFeature(PolicyConfiguration.NAMESPACE); }
            public void reply(Message error) { XMPPServer.getInstance().getRoutingTable().routePacket(error.getTo(), error); }
        };
    }

    SpiffingPlugin(ConfigurationService configuration, Runtime runtime) {
        this.configuration = configuration;
        this.runtime = runtime;
    }

    @Override
    public synchronized void initializePlugin(PluginManager manager, File directory) {
        if (interceptor != null) return;
        if (configuration == null) {
            configuration = new ConfigurationService(new FileConfigurationStore(JiveGlobals.getHomePath().resolve("conf/spiffing.xml")));
        }
        try {
            configuration.reload();
        } catch (IllegalArgumentException e) {
            LOG.error("Stored Spiffing configuration is invalid; inbound messages are blocked. Reconfigure in the Admin Console.");
        }
        interceptor = new SecurityLabelInterceptor(configuration::current, runtime::reply);
        runtime.addInterceptor(interceptor);
        runtime.addFeature();
    }

    @Override
    public synchronized void destroyPlugin() {
        if (interceptor != null) {
            runtime.removeInterceptor(interceptor);
            runtime.removeFeature();
            interceptor = null;
        }
    }

    public Settings getSettings() {
        PolicyConfiguration current = configuration.current();
        return current == null ? null : current.settings();
    }

    public boolean isConfigured() { return configuration.current() != null; }

    public void save(Settings settings) { configuration.save(settings); }
}
