package org.igniterealtime.openfire.spiffing;

import io.cridland.spiffing.Classification;
import io.cridland.spiffing.Clearance;
import io.cridland.spiffing.Label;
import io.cridland.spiffing.Site;
import io.cridland.spiffing.Spif;
import org.jivesoftware.openfire.XMPPServer;
import org.jivesoftware.openfire.container.Plugin;
import org.jivesoftware.openfire.container.PluginManager;
import org.jivesoftware.openfire.handler.IQHandler;
import org.jivesoftware.openfire.interceptor.InterceptorManager;
import org.jivesoftware.openfire.interceptor.PacketInterceptor;
import org.jivesoftware.openfire.muc.MUCEventDelegate;
import org.jivesoftware.openfire.muc.MultiUserChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xmpp.packet.JID;
import org.xmpp.packet.Message;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

public final class SpiffingPlugin implements Plugin {
    private static final Logger LOG = LoggerFactory.getLogger(SpiffingPlugin.class);

    interface Runtime {
        void addInterceptor(PacketInterceptor interceptor);
        void removeInterceptor(PacketInterceptor interceptor);
        void addFeature(String namespace);
        void removeFeature(String namespace);
        void addIqHandler(IQHandler handler);
        void removeIqHandler(IQHandler handler);
        boolean isLocal(JID jid);
        void reply(Message error);

        /** Whether {@code jid}'s bare form names an existing room on a locally hosted MUC service. Defaults to never a room, so tests that don't exercise room features need no changes. */
        default boolean isMucRoom(JID jid) { return false; }

        /** Wraps and installs a join-gating delegate (built from each service's current delegate, if any) on every currently loaded MUC service. Default: a no-op, for tests that don't exercise room features. */
        default void installRoomJoinGate(UnaryOperator<MUCEventDelegate> gateFactory) {}

        /** Restores each MUC service's original delegate, undoing {@link #installRoomJoinGate}. Default: a no-op. */
        default void removeRoomJoinGate() {}
    }

    private ConfigurationService configuration;
    private CatalogService catalog;
    private UserClearanceService userClearances;
    private RoomClearanceService roomClearances;
    private RoomLabelService roomLabels;
    private final Runtime runtime;
    private SecurityLabelInterceptor interceptor;
    private RoomConfigFormInterceptor roomConfigFormInterceptor;
    private CatalogIqHandler catalogIqHandler;
    private PolicyIqHandler policyIqHandler;
    private boolean roomJoinGateInstalled;

    public SpiffingPlugin() {
        runtime = new Runtime() {
            private final Map<MultiUserChatService, MUCEventDelegate> originalDelegates = new HashMap<>();
            public void addInterceptor(PacketInterceptor i) { InterceptorManager.getInstance().addInterceptor(i); }
            public void removeInterceptor(PacketInterceptor i) { InterceptorManager.getInstance().removeInterceptor(i); }
            public void addFeature(String namespace) { XMPPServer.getInstance().getIQDiscoInfoHandler().addServerFeature(namespace); }
            public void removeFeature(String namespace) { XMPPServer.getInstance().getIQDiscoInfoHandler().removeServerFeature(namespace); }
            public void addIqHandler(IQHandler handler) { XMPPServer.getInstance().getIQRouter().addHandler(handler); }
            public void removeIqHandler(IQHandler handler) { XMPPServer.getInstance().getIQRouter().removeHandler(handler); }
            public boolean isLocal(JID jid) { return XMPPServer.getInstance().isLocal(jid); }
            public void reply(Message error) { XMPPServer.getInstance().getRoutingTable().routePacket(error.getTo(), error); }
            public boolean isMucRoom(JID jid) {
                MultiUserChatService service = XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatService(jid);
                return service != null && jid.getNode() != null && service.hasChatRoom(jid.getNode());
            }
            public void installRoomJoinGate(UnaryOperator<MUCEventDelegate> gateFactory) {
                for (MultiUserChatService service : XMPPServer.getInstance().getMultiUserChatManager().getMultiUserChatServices()) {
                    originalDelegates.put(service, service.getMUCDelegate());
                    service.setMUCDelegate(gateFactory.apply(service.getMUCDelegate()));
                }
            }
            public void removeRoomJoinGate() {
                originalDelegates.forEach(MultiUserChatService::setMUCDelegate);
                originalDelegates.clear();
            }
        };
    }

    SpiffingPlugin(ConfigurationService configuration, CatalogService catalog, Runtime runtime) {
        this(configuration, catalog, null, runtime);
    }

    SpiffingPlugin(ConfigurationService configuration, CatalogService catalog, UserClearanceService userClearances, Runtime runtime) {
        this(configuration, catalog, userClearances, null, null, runtime);
    }

    SpiffingPlugin(
        ConfigurationService configuration,
        CatalogService catalog,
        UserClearanceService userClearances,
        RoomClearanceService roomClearances,
        RoomLabelService roomLabels,
        Runtime runtime
    ) {
        this.configuration = configuration;
        this.catalog = catalog;
        this.userClearances = userClearances;
        this.roomClearances = roomClearances;
        this.roomLabels = roomLabels;
        this.runtime = runtime;
    }

    @Override
    public synchronized void initializePlugin(PluginManager manager, File directory) {
        if (interceptor != null) return;
        if (configuration == null) {
            configuration = new ConfigurationService(new DatabasePolicyStore());
        }
        if (catalog == null) {
            catalog = new CatalogService(new DatabaseCatalogStore(), configuration::current);
        }
        if (userClearances == null) {
            userClearances = new UserClearanceService(new UserPropertyClearanceStore());
        }
        if (roomClearances == null) {
            roomClearances = new RoomClearanceService(new DatabaseRoomClearanceStore());
        }
        if (roomLabels == null) {
            roomLabels = new RoomLabelService(new DatabaseRoomLabelStore());
        }
        try {
            configuration.reload();
        } catch (IllegalArgumentException e) {
            LOG.error("Stored Spiffing configuration is invalid; inbound messages are blocked. Reconfigure in the Admin Console.");
        }
        bootstrapPolicyIfConfigured();
        interceptor = new SecurityLabelInterceptor(
            configuration::current, configuration::isCorrupted, userClearances::list,
            runtime::isMucRoom, roomClearances::list, roomLabels::get, runtime::reply
        );
        runtime.addInterceptor(interceptor);
        runtime.addFeature(PolicyConfiguration.NAMESPACE);
        roomConfigFormInterceptor = new RoomConfigFormInterceptor(() -> configuration, runtime::isMucRoom, roomClearances, roomLabels);
        runtime.addInterceptor(roomConfigFormInterceptor);
        catalogIqHandler = new CatalogIqHandler(catalog, runtime::isLocal);
        runtime.addIqHandler(catalogIqHandler);
        runtime.addFeature(CatalogService.NAMESPACE);
        policyIqHandler = new PolicyIqHandler(configuration::current, runtime::isLocal);
        runtime.addIqHandler(policyIqHandler);
        runtime.addFeature(PolicyIqHandler.NAMESPACE);
        RoomClearanceService finalRoomClearances = roomClearances;
        RoomLabelService finalRoomLabels = roomLabels;
        UserClearanceService finalUserClearances = userClearances;
        runtime.installRoomJoinGate(existing -> new RoomJoinGate(
            configuration::current, finalRoomLabels::get, finalUserClearances::list, runtime::isLocal, existing
        ));
        roomJoinGateInstalled = true;
    }

    @Override
    public synchronized void destroyPlugin() {
        if (interceptor != null) {
            runtime.removeInterceptor(interceptor);
            runtime.removeFeature(PolicyConfiguration.NAMESPACE);
            interceptor = null;
        }
        if (roomConfigFormInterceptor != null) {
            runtime.removeInterceptor(roomConfigFormInterceptor);
            roomConfigFormInterceptor = null;
        }
        if (catalogIqHandler != null) {
            runtime.removeIqHandler(catalogIqHandler);
            runtime.removeFeature(CatalogService.NAMESPACE);
            catalogIqHandler = null;
        }
        if (policyIqHandler != null) {
            runtime.removeIqHandler(policyIqHandler);
            runtime.removeFeature(PolicyIqHandler.NAMESPACE);
            policyIqHandler = null;
        }
        if (roomJoinGateInstalled) {
            runtime.removeRoomJoinGate();
            roomJoinGateInstalled = false;
        }
    }

    /**
     * If {@link SpiffingProperties#getBootstrapPolicyFile()} points at a SPIF document and no
     * policy has been configured yet (i.e. the store is empty, typically a fresh install), loads
     * that document and saves it as the server's default policy, with a server clearance and
     * default outgoing message label both set to the policy's lowest (least restrictive)
     * classification. This exists so test/dev deployments (see
     * {@link SpiffingProperties#BOOTSTRAP_POLICY_FILE}'s documentation) end up with a working
     * policy without needing to drive the Admin Console UI by hand; it is a no-op whenever the
     * property isn't set, or a policy already exists, so it never overrides an administrator's
     * own configuration.
     */
    private void bootstrapPolicyIfConfigured() {
        String path = SpiffingProperties.getBootstrapPolicyFile();
        if (path == null || path.isBlank()) return;
        if (!configuration.policies().isEmpty()) return;
        try {
            String spifXml = Files.readString(Path.of(path), StandardCharsets.UTF_8);
            Spif spif = new Site().load(spifXml);
            Classification lowest = spif.defaultClassification();
            Clearance clearance = SettingsForm.buildClearance(spif, List.of(lowest.lacv().toString()), List.of());
            Label defaultLabel = new Label(spif, lowest.lacv());
            LabelFormat format = LabelFormat.XML;
            SecurityPolicy policy = new SecurityPolicy(
                spif.policyId(), spif.name(), spifXml,
                format.encode(clearance), format,
                format.encode(defaultLabel), format,
                true
            );
            configuration.savePolicy(policy);
            LOG.info("Bootstrapped Spiffing policy '{}' from {}", spif.name(), path);
        } catch (Exception e) {
            LOG.error("Failed to bootstrap Spiffing policy from {}: {}", path, e.getMessage(), e);
        }
    }

    public ConfigurationService configurationService() { return configuration; }

    public UserClearanceService userClearanceService() { return userClearances; }

    public RoomClearanceService roomClearanceService() { return roomClearances; }

    public RoomLabelService roomLabelService() { return roomLabels; }

    public boolean isConfigured() {
        PolicyConfiguration current = configuration.current();
        return current != null && current.hasPolicies();
    }

    public boolean isCorrupted() { return configuration.isCorrupted(); }

    public CatalogService catalogService() { return catalog; }
}
