/*
 * Copyright 2019-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.onosproject.k8snode.impl;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Modified;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.onlab.util.Tools;
import org.onosproject.cfg.ComponentConfigService;
import org.onosproject.cluster.ClusterService;
import org.onosproject.cluster.LeadershipService;
import org.onosproject.cluster.NodeId;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.k8snode.api.K8sNode;
import org.onosproject.k8snode.api.K8sNodeAdminService;
import org.onosproject.k8snode.api.K8sNodeEvent;
import org.onosproject.k8snode.api.K8sNodeHandler;
import org.onosproject.k8snode.api.K8sNodeListener;
import org.onosproject.k8snode.api.K8sNodeService;
import org.onosproject.k8snode.api.K8sNodeState;
import org.onosproject.net.Device;
import org.onosproject.net.DeviceId;
import org.onosproject.net.Port;
import org.onosproject.net.behaviour.BridgeConfig;
import org.onosproject.net.behaviour.BridgeDescription;
import org.onosproject.net.behaviour.ControllerInfo;
import org.onosproject.net.behaviour.DefaultBridgeDescription;
import org.onosproject.net.behaviour.DefaultTunnelDescription;
import org.onosproject.net.behaviour.InterfaceConfig;
import org.onosproject.net.behaviour.TunnelDescription;
import org.onosproject.net.behaviour.TunnelEndPoints;
import org.onosproject.net.behaviour.TunnelKeys;
import org.onosproject.net.device.DeviceAdminService;
import org.onosproject.net.device.DeviceEvent;
import org.onosproject.net.device.DeviceListener;
import org.onosproject.net.device.DeviceService;
import org.onosproject.ovsdb.controller.OvsdbClientService;
import org.onosproject.ovsdb.controller.OvsdbController;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;

import java.util.Dictionary;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static java.util.concurrent.Executors.newSingleThreadExecutor;
import static org.onlab.packet.TpPort.tpPort;
import static org.onlab.util.Tools.groupedThreads;
import static org.onosproject.k8snode.api.Constants.GENEVE;
import static org.onosproject.k8snode.api.Constants.GENEVE_TUNNEL;
import static org.onosproject.k8snode.api.Constants.GRE;
import static org.onosproject.k8snode.api.Constants.GRE_TUNNEL;
import static org.onosproject.k8snode.api.Constants.INTEGRATION_BRIDGE;
import static org.onosproject.k8snode.api.Constants.VXLAN;
import static org.onosproject.k8snode.api.Constants.VXLAN_TUNNEL;
import static org.onosproject.k8snode.api.K8sNodeService.APP_ID;
import static org.onosproject.k8snode.api.K8sNodeState.COMPLETE;
import static org.onosproject.k8snode.api.K8sNodeState.DEVICE_CREATED;
import static org.onosproject.k8snode.api.K8sNodeState.INCOMPLETE;
import static org.onosproject.k8snode.util.K8sNodeUtil.getBooleanProperty;
import static org.onosproject.k8snode.util.K8sNodeUtil.getOvsdbClient;
import static org.onosproject.k8snode.util.K8sNodeUtil.isOvsdbConnected;
import static org.onosproject.net.AnnotationKeys.PORT_NAME;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Service bootstraps kubernetes node based on its type.
 */
@Component(immediate = true)
public class DefaultK8sNodeHandler implements K8sNodeHandler {

    private final Logger log = getLogger(getClass());

    private static final String OVSDB_PORT = "ovsdbPortNum";
    private static final String AUTO_RECOVERY = "autoRecovery";
    private static final String DEFAULT_OF_PROTO = "tcp";
    private static final int DEFAULT_OVSDB_PORT = 6640;
    private static final int DEFAULT_OFPORT = 6653;
    private static final boolean DEFAULT_AUTO_RECOVERY = true;
    private static final int DPID_BEGIN = 3;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected LeadershipService leadershipService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ClusterService clusterService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceAdminService deviceAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected OvsdbController ovsdbController;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNodeService k8sNodeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected K8sNodeAdminService k8sNodeAdminService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected ComponentConfigService componentConfigService;

    @Property(name = OVSDB_PORT, intValue = DEFAULT_OVSDB_PORT,
            label = "OVSDB server listen port")
    private int ovsdbPort = DEFAULT_OVSDB_PORT;

    @Property(name = AUTO_RECOVERY, boolValue = DEFAULT_AUTO_RECOVERY,
            label = "A flag which indicates whether auto-recover kubernetes " +
                    "node status at the receiving of switch reconnecting event.")
    private boolean autoRecovery = DEFAULT_AUTO_RECOVERY;

    private final ExecutorService eventExecutor = newSingleThreadExecutor(
            groupedThreads(this.getClass().getSimpleName(), "event-handler", log));

    private final DeviceListener ovsdbListener = new InternalOvsdbListener();
    private final DeviceListener bridgeListener = new InternalBridgeListener();
    private final K8sNodeListener k8sNodeListener = new InternalK8sNodeListener();

    private ApplicationId appId;
    private NodeId localNode;

    @Activate
    protected void activate() {
        appId = coreService.getAppId(APP_ID);
        localNode = clusterService.getLocalNode().id();

        componentConfigService.registerProperties(getClass());
        leadershipService.runForLeadership(appId.name());
        deviceService.addListener(ovsdbListener);
        deviceService.addListener(bridgeListener);
        k8sNodeService.addListener(k8sNodeListener);

        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        k8sNodeService.removeListener(k8sNodeListener);
        deviceService.removeListener(bridgeListener);
        deviceService.removeListener(ovsdbListener);
        componentConfigService.unregisterProperties(getClass(), false);
        leadershipService.withdraw(appId.name());
        eventExecutor.shutdown();

        log.info("Stopped");
    }

    @Modified
    protected void modified(ComponentContext context) {
        readComponentConfiguration(context);

        log.info("Modified");
    }

    @Override
    public void processInitState(K8sNode k8sNode) {
        if (!isOvsdbConnected(k8sNode, ovsdbPort, ovsdbController, deviceService)) {
            ovsdbController.connect(k8sNode.managementIp(), tpPort(ovsdbPort));
            return;
        }
        if (!deviceService.isAvailable(k8sNode.intgBridge())) {
            createBridge(k8sNode, INTEGRATION_BRIDGE, k8sNode.intgBridge());
        }
    }

    @Override
    public void processDeviceCreatedState(K8sNode k8sNode) {
        try {
            if (!isOvsdbConnected(k8sNode, ovsdbPort, ovsdbController, deviceService)) {
                ovsdbController.connect(k8sNode.managementIp(), tpPort(ovsdbPort));
                return;
            }

            if (k8sNode.dataIp() != null &&
                    !isIntfEnabled(k8sNode, VXLAN_TUNNEL)) {
                createVxlanTunnelInterface(k8sNode);
            }

            if (k8sNode.dataIp() != null &&
                    !isIntfEnabled(k8sNode, GRE_TUNNEL)) {
                createGreTunnelInterface(k8sNode);
            }

            if (k8sNode.dataIp() != null &&
                    !isIntfEnabled(k8sNode, GENEVE_TUNNEL)) {
                createGeneveTunnelInterface(k8sNode);
            }
        } catch (Exception e) {
            log.error("Exception occurred because of {}", e);
        }
    }

    @Override
    public void processCompleteState(K8sNode k8sNode) {
        // do something if needed
    }

    @Override
    public void processIncompleteState(K8sNode k8sNode) {
        // do something if needed
    }

    /**
     * Extracts properties from the component configuration context.
     *
     * @param context the component context
     */
    private void readComponentConfiguration(ComponentContext context) {
        Dictionary<?, ?> properties = context.getProperties();

        Integer ovsdbPortConfigured = Tools.getIntegerProperty(properties, OVSDB_PORT);
        if (ovsdbPortConfigured == null) {
            ovsdbPort = DEFAULT_OVSDB_PORT;
            log.info("OVSDB port is NOT configured, default value is {}", ovsdbPort);
        } else {
            ovsdbPort = ovsdbPortConfigured;
            log.info("Configured. OVSDB port is {}", ovsdbPort);
        }

        Boolean autoRecoveryConfigured =
                getBooleanProperty(properties, AUTO_RECOVERY);
        if (autoRecoveryConfigured == null) {
            autoRecovery = DEFAULT_AUTO_RECOVERY;
            log.info("Auto recovery flag is NOT " +
                    "configured, default value is {}", autoRecovery);
        } else {
            autoRecovery = autoRecoveryConfigured;
            log.info("Configured. Auto recovery flag is {}", autoRecovery);
        }
    }

    /**
     * Creates a bridge with a given name on a given kubernetes node.
     *
     * @param k8sNode       kubernetes node
     * @param bridgeName    bridge name
     * @param devId         device identifier
     */
    private void createBridge(K8sNode k8sNode, String bridgeName, DeviceId devId) {
        Device device = deviceService.getDevice(k8sNode.ovsdb());

        List<ControllerInfo> controllers = clusterService.getNodes().stream()
                .map(n -> new ControllerInfo(n.ip(), DEFAULT_OFPORT, DEFAULT_OF_PROTO))
                .collect(Collectors.toList());

        String dpid = devId.toString().substring(DPID_BEGIN);

        BridgeDescription.Builder builder = DefaultBridgeDescription.builder()
                .name(bridgeName)
                .failMode(BridgeDescription.FailMode.SECURE)
                .datapathId(dpid)
                .disableInBand()
                .controllers(controllers);

        BridgeConfig bridgeConfig = device.as(BridgeConfig.class);
        bridgeConfig.addBridge(builder.build());
    }

    /**
     * Creates a VXLAN tunnel interface in a given kubernetes node.
     *
     * @param k8sNode       kubernetes node
     */
    private void createVxlanTunnelInterface(K8sNode k8sNode) {
        createTunnelInterface(k8sNode, VXLAN, VXLAN_TUNNEL);
    }

    /**
     * Creates a GRE tunnel interface in a given kubernetes node.
     *
     * @param k8sNode       kubernetes node
     */
    private void createGreTunnelInterface(K8sNode k8sNode) {
        createTunnelInterface(k8sNode, GRE, GRE_TUNNEL);
    }

    /**
     * Creates a GENEVE tunnel interface in a given kubernetes node.
     *
     * @param k8sNode       kubernetes node
     */
    private void createGeneveTunnelInterface(K8sNode k8sNode) {
        createTunnelInterface(k8sNode, GENEVE, GENEVE_TUNNEL);
    }

    /**
     * Creates a tunnel interface in a given kubernetes node.
     *
     * @param k8sNode       kubernetes node
     */
    private void createTunnelInterface(K8sNode k8sNode,
                                       String type, String intfName) {
        if (isIntfEnabled(k8sNode, intfName)) {
            return;
        }

        Device device = deviceService.getDevice(k8sNode.ovsdb());
        if (device == null || !device.is(InterfaceConfig.class)) {
            log.error("Failed to create tunnel interface on {}", k8sNode.ovsdb());
            return;
        }

        TunnelDescription tunnelDesc = buildTunnelDesc(type, intfName);

        InterfaceConfig ifaceConfig = device.as(InterfaceConfig.class);
        ifaceConfig.addTunnelMode(intfName, tunnelDesc);
    }

    /**
     * Builds tunnel description according to the network type.
     *
     * @param type      network type
     * @return tunnel description
     */
    private TunnelDescription buildTunnelDesc(String type, String intfName) {
        if (VXLAN.equals(type) || GRE.equals(type) || GENEVE.equals(type)) {
            TunnelDescription.Builder tdBuilder =
                    DefaultTunnelDescription.builder()
                            .deviceId(INTEGRATION_BRIDGE)
                            .ifaceName(intfName)
                            .remote(TunnelEndPoints.flowTunnelEndpoint())
                            .key(TunnelKeys.flowTunnelKey());

            switch (type) {
                case VXLAN:
                    tdBuilder.type(TunnelDescription.Type.VXLAN);
                    break;
                case GRE:
                    tdBuilder.type(TunnelDescription.Type.GRE);
                    break;
                case GENEVE:
                    tdBuilder.type(TunnelDescription.Type.GENEVE);
                    break;
                default:
                    return null;
            }

            return tdBuilder.build();
        }
        return null;
    }

    /**
     * Checks whether a given network interface in a given kubernetes node
     * is enabled or not.
     *
     * @param k8sNode       kubernetes node
     * @param intf          network interface name
     * @return true if the given interface is enabled, false otherwise
     */
    private boolean isIntfEnabled(K8sNode k8sNode, String intf) {
        return deviceService.isAvailable(k8sNode.intgBridge()) &&
                deviceService.getPorts(k8sNode.intgBridge()).stream()
                        .anyMatch(port -> Objects.equals(
                                port.annotations().value(PORT_NAME), intf) &&
                                port.isEnabled());
    }

    /**
     * Checks whether all requirements for this state are fulfilled or not.
     *
     * @param k8sNode       kubernetes node
     * @return true if all requirements are fulfilled, false otherwise
     */
    private boolean isCurrentStateDone(K8sNode k8sNode) {
        switch (k8sNode.state()) {
            case INIT:
                if (!isOvsdbConnected(k8sNode, ovsdbPort,
                        ovsdbController, deviceService)) {
                    return false;
                }

                return deviceService.isAvailable(k8sNode.intgBridge());
            case DEVICE_CREATED:
                if (k8sNode.dataIp() != null &&
                        !isIntfEnabled(k8sNode, VXLAN_TUNNEL)) {
                    return false;
                }
                if (k8sNode.dataIp() != null &&
                        !isIntfEnabled(k8sNode, GRE_TUNNEL)) {
                    return false;
                }
                if (k8sNode.dataIp() != null &&
                        !isIntfEnabled(k8sNode, GENEVE_TUNNEL)) {
                    return false;
                }

                return true;
            case COMPLETE:
            case INCOMPLETE:
                // always return false
                // run init CLI to re-trigger node bootstrap
                return false;
            default:
                return true;
        }
    }

    /**
     * Configures the kubernetes node with new state.
     *
     * @param k8sNode       kubernetes node
     * @param newState      a new state
     */
    private void setState(K8sNode k8sNode, K8sNodeState newState) {
        if (k8sNode.state() == newState) {
            return;
        }
        K8sNode updated = k8sNode.updateState(newState);
        k8sNodeAdminService.updateNode(updated);
        log.info("Changed {} state: {}", k8sNode.hostname(), newState);
    }

    /**
     * Bootstraps a new kubernetes node.
     *
     * @param k8sNode kubernetes node
     */
    private void bootstrapNode(K8sNode k8sNode) {
        if (isCurrentStateDone(k8sNode)) {
            setState(k8sNode, k8sNode.state().nextState());
        } else {
            log.trace("Processing {} state for {}", k8sNode.state(),
                    k8sNode.hostname());
            k8sNode.state().process(this, k8sNode);
        }
    }

    private void processK8sNodeRemoved(K8sNode k8sNode) {
        OvsdbClientService client = getOvsdbClient(k8sNode, ovsdbPort, ovsdbController);
        if (client == null) {
            log.info("Failed to get ovsdb client");
            return;
        }

        // delete integration bridge from the node
        client.dropBridge(INTEGRATION_BRIDGE);

        // disconnect ovsdb
        client.disconnect();
    }

    /**
     * An internal OVSDB listener. This listener is used for listening the
     * network facing events from OVSDB device. If a new OVSDB device is detected,
     * ONOS tries to bootstrap the kubernetes node.
     */
    private class InternalOvsdbListener implements DeviceListener {

        @Override
        public boolean isRelevant(DeviceEvent event) {
            return event.subject().type() == Device.Type.CONTROLLER;
        }

        private boolean isRelevantHelper() {
            return Objects.equals(localNode, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(DeviceEvent event) {
            Device device = event.subject();

            switch (event.type()) {
                case DEVICE_AVAILABILITY_CHANGED:
                case DEVICE_ADDED:
                    eventExecutor.execute(() -> {

                        if (!isRelevantHelper()) {
                            return;
                        }

                        K8sNode k8sNode = k8sNodeService.node(device.id());

                        if (k8sNode == null) {
                            return;
                        }

                        if (deviceService.isAvailable(device.id())) {
                            log.debug("OVSDB {} detected", device.id());
                            bootstrapNode(k8sNode);
                        }
                    });
                    break;
                case PORT_ADDED:
                case PORT_REMOVED:
                case DEVICE_REMOVED:
                default:
                    // do nothing
                    break;
            }
        }
    }

    /**
     * An internal integration bridge listener. This listener is used for
     * listening the events from integration bridge. To listen the events from
     * other types of bridge such as provider bridge or tunnel bridge, we need
     * to augment K8sNodeService.node() method.
     */
    private class InternalBridgeListener implements DeviceListener {

        @Override
        public boolean isRelevant(DeviceEvent event) {
            return event.subject().type() == Device.Type.SWITCH;
        }

        private boolean isRelevantHelper() {
            return Objects.equals(localNode, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(DeviceEvent event) {
            Device device = event.subject();

            switch (event.type()) {
                case DEVICE_AVAILABILITY_CHANGED:
                case DEVICE_ADDED:
                    eventExecutor.execute(() -> {

                        if (!isRelevantHelper()) {
                            return;
                        }

                        K8sNode k8sNode = k8sNodeService.node(device.id());

                        if (k8sNode == null) {
                            return;
                        }

                        if (deviceService.isAvailable(device.id())) {
                            log.debug("Integration bridge created on {}", k8sNode.hostname());
                            bootstrapNode(k8sNode);
                        } else if (k8sNode.state() == COMPLETE) {
                            log.info("Device {} disconnected", device.id());
                            setState(k8sNode, INCOMPLETE);
                        }

                        if (autoRecovery) {
                            if (k8sNode.state() == INCOMPLETE ||
                                    k8sNode.state() == DEVICE_CREATED) {
                                log.info("Device {} is reconnected", device.id());
                                k8sNodeAdminService.updateNode(
                                        k8sNode.updateState(K8sNodeState.INIT));
                            }
                        }
                    });
                    break;
                case PORT_UPDATED:
                case PORT_ADDED:
                    eventExecutor.execute(() -> {

                        if (!isRelevantHelper()) {
                            return;
                        }

                        K8sNode k8sNode = k8sNodeService.node(device.id());

                        if (k8sNode == null) {
                            return;
                        }

                        Port port = event.port();
                        String portName = port.annotations().value(PORT_NAME);
                        if (k8sNode.state() == DEVICE_CREATED && (
                                Objects.equals(portName, VXLAN_TUNNEL) ||
                                        Objects.equals(portName, GRE_TUNNEL) ||
                                        Objects.equals(portName, GENEVE_TUNNEL))) {
                            log.info("Interface {} added or updated to {}",
                                    portName, device.id());
                            bootstrapNode(k8sNode);
                        }
                    });
                    break;
                case PORT_REMOVED:
                    eventExecutor.execute(() -> {

                        if (!isRelevantHelper()) {
                            return;
                        }

                        K8sNode k8sNode = k8sNodeService.node(device.id());

                        if (k8sNode == null) {
                            return;
                        }

                        Port port = event.port();
                        String portName = port.annotations().value(PORT_NAME);
                        if (k8sNode.state() == COMPLETE && (
                                Objects.equals(portName, VXLAN_TUNNEL) ||
                                        Objects.equals(portName, GRE_TUNNEL) ||
                                        Objects.equals(portName, GENEVE_TUNNEL))) {
                            log.warn("Interface {} removed from {}",
                                    portName, event.subject().id());
                            setState(k8sNode, INCOMPLETE);
                        }
                    });
                    break;
                case DEVICE_REMOVED:
                default:
                    // do nothing
                    break;
            }
        }
    }

    /**
     * An internal kubernetes node listener.
     * The notification is triggered by KubernetesNodeStore.
     */
    private class InternalK8sNodeListener implements K8sNodeListener {

        private boolean isRelevantHelper() {
            return Objects.equals(localNode, leadershipService.getLeader(appId.name()));
        }

        @Override
        public void event(K8sNodeEvent event) {
            switch (event.type()) {
                case K8S_NODE_CREATED:
                case K8S_NODE_UPDATED:
                    eventExecutor.execute(() -> {

                        if (!isRelevantHelper()) {
                            return;
                        }

                        bootstrapNode(event.subject());
                    });
                    break;
                case K8S_NODE_REMOVED:
                    eventExecutor.execute(() -> {

                        if (!isRelevantHelper()) {
                            return;
                        }

                        processK8sNodeRemoved(event.subject());
                    });
                    break;
                case K8S_NODE_INCOMPLETE:
                default:
                    break;
            }
        }
    }
}