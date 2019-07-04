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
package nctu.st.unicastdhcp;

import com.google.common.collect.ImmutableSet;

import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Service;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;

import org.onosproject.net.config.ConfigFactory;
import org.onosproject.net.config.NetworkConfigEvent;
import org.onosproject.net.config.NetworkConfigListener;
import org.onosproject.net.config.NetworkConfigRegistry;
import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.InboundPacket;
import org.onosproject.net.packet.DefaultOutboundPacket;

import org.onosproject.net.host.HostService;
import org.onosproject.net.edge.EdgePortService;
import org.onosproject.net.Link;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.Host;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;

import org.onosproject.net.flowobjective.FlowObjectiveService;
import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.ForwardingObjective;

import org.onosproject.net.flow.FlowRuleService;
import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;

import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.topology.TopologyGraph;
import org.onosproject.net.topology.TopologyVertex;
import org.onosproject.net.topology.TopologyEdge;

import org.onlab.packet.Ethernet;
import org.onlab.packet.ARP;
import org.onlab.packet.IPv4; 
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ip4Address;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.LinkedList;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;

import nctu.st.unicastdhcp.MyConfig;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected EdgePortService edgeService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry cfgService;

    private ApplicationId appId;
    private ReactivePacketProcessor processor = new ReactivePacketProcessor();
    private final InternalConfigListener cfgListener = new InternalConfigListener();

    private static final int DEFAULT_TIMEOUT = 60;
    private static final int DEFAULT_PRIORITY = 4000;
    private static String dhcpMac = "FF:FF:FF:FF:FF:FF";
    private static String dhcpCPoint = "of:0000000000000000/0";
    
    private final Set<ConfigFactory> factories = ImmutableSet.of(
        new ConfigFactory<ApplicationId, MyConfig>(
            APP_SUBJECT_FACTORY, MyConfig.class, "myconfig"){
                @Override
                public MyConfig createConfig(){
                    return new MyConfig();
                }
            }
    );

    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.st.unicastdhcp");

        cfgService.addListener(cfgListener);
        factories.forEach(cfgService::registerConfigFactory);
        cfgListener.reconfigureNetwork(cfgService.getConfig(appId, MyConfig.class));

        packetService.addProcessor(processor, PacketProcessor.director(2));
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        log.info("Started");
    }

    @Deactivate
    protected void deactivate() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4)
                .matchIPProtocol(IPv4.PROTOCOL_UDP)
                .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
        packetService.removeProcessor(processor);
        processor = null;

        cfgService.removeListener(cfgListener);
        factories.forEach(cfgService::unregisterConfigFactory);

        log.info("Stopped");
    }

    private class InternalConfigListener implements NetworkConfigListener {
        @Override
        public void event(NetworkConfigEvent event) {
            if ((event.type() == NetworkConfigEvent.Type.CONFIG_ADDED ||
                 event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED) &&
                 event.configClass().equals(MyConfig.class)) {
                    MyConfig cfg = cfgService.getConfig(appId, MyConfig.class);
                    reconfigureNetwork(cfg);
                    log.info("[Reconfigured] mac: {}, dhcpCPoint: {} ", dhcpMac, dhcpCPoint);
                 }
        }

        // update the variable
        private void reconfigureNetwork(MyConfig cfg) {
            if (cfg == null) {
                return;
            }
            if (cfg.getCPoint() != null) {
                dhcpCPoint = cfg.getCPoint();
            }
            if (cfg.getMAC() != null) {
                dhcpMac = cfg.getMAC();
            }
        }
    }

    private class ReactivePacketProcessor implements PacketProcessor {
        @Override
        public void process(PacketContext context) {
            // Stop processing if the packet has been handled
            if (context.isHandled()) {
                return;
            }

            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            // Not ethernet packet
            if (ethPkt == null) {
                return;
            }

            // DHCP packet
            if(ethPkt.getEtherType() == ethPkt.TYPE_IPV4){
                IPv4 ip4Payload = (IPv4) ethPkt.getPayload();
                if(ip4Payload.getProtocol() == IPv4.PROTOCOL_UDP ){
                    UDP udpPayload = (UDP) ip4Payload.getPayload();
                    if(udpPayload.getSourcePort() == UDP.DHCP_CLIENT_PORT ){
                        dhcpHandler(context);
                        return;
                    }
                }
            }

            // IPV4 packet
            //ipv4Handler(context);
        }
    }

    private void ipv4Handler(PacketContext context){
        InboundPacket pkt = context.inPacket();
        Ethernet ethPkt = pkt.parsed();
        MacAddress srcmac = ethPkt.getSourceMAC();
        MacAddress dstmac = ethPkt.getDestinationMAC();

        Map<DeviceId, List> myGraph = getGraph();
        
        //get source device
        Set<Host> findHosts = hostService.getHostsByMac(srcmac);
        if( findHosts.size()==0 ){
            log.info("[WARN] HostService not find {}",srcmac);
            return;
        }
        Host firstHost = findHosts.iterator().next();
        DeviceId sourceDeviceId = firstHost.location().deviceId();

        Map<DeviceId, Link> parentLink = getAllPath(myGraph, sourceDeviceId);

        //find path
        findHosts = hostService.getHostsByMac(dstmac);
        firstHost = findHosts.iterator().next();
        ConnectPoint dstCP = firstHost.location();
        installRule(srcmac, dstmac, dstCP.port(), dstCP.deviceId());
        log.info("[IPV4] Install: {} {} {}", srcmac, dstmac, dstCP.port(), dstCP.deviceId() );
        while(dstCP.deviceId() != sourceDeviceId){
            //log.info( "Path:  "+dstCP.deviceId().toString() );
            Link linkPath = parentLink.get(dstCP.deviceId());
            if(linkPath==null) break;
            installRule(srcmac, dstmac, linkPath.src().port(), linkPath.src().deviceId());
            log.info("[IPV4] Install: {} {} {}", srcmac, dstmac, linkPath.src().port(), linkPath.src().deviceId() );
            dstCP = linkPath.src();
        }

    }

    private void dhcpHandler(PacketContext context){
        InboundPacket pkt = context.inPacket();
        ConnectPoint toHostCP = pkt.receivedFrom();
        Ethernet ethPkt = pkt.parsed();
        MacAddress srcmac = ethPkt.getSourceMAC();
        MacAddress dstmac = MacAddress.valueOf(dhcpMac);

        Map<DeviceId, List> myGraph = getGraph();

        //get source device
        Set<Host> findHosts = hostService.getHostsByMac(srcmac);
        findHosts = hostService.getHostsByMac(srcmac);
        if( findHosts.size()==0 ){
          log.info("[WARN] HostService not find {}",srcmac);
          return;
        }
        Host firstHost = findHosts.iterator().next();
        DeviceId sourceDeviceId = firstHost.location().deviceId();

        Map<DeviceId, Link> parentLink = getAllPath(myGraph, sourceDeviceId);

        //find path
        if( sourceDeviceId.equals(DeviceId.deviceId(dhcpCPoint.split("/")[0])) ){
            installDHCPRule(srcmac, PortNumber.portNumber(dhcpCPoint.split("/")[1]), sourceDeviceId);
            log.info("[DHCP] Install: {} {} {}", srcmac, PortNumber.portNumber(dhcpCPoint.split("/")[1]), sourceDeviceId );
        }else{
            Link firstlink = parentLink.get(DeviceId.deviceId(dhcpCPoint.split("/")[0]));
            ConnectPoint dstCP = firstlink.dst();
            installDHCPRule(srcmac, PortNumber.portNumber(dhcpCPoint.split("/")[1]), dstCP.deviceId());
            log.info("[DHCP] Install: {} {} {}", srcmac, PortNumber.portNumber(dhcpCPoint.split("/")[1]), dstCP.deviceId() );
            while(dstCP.deviceId() != sourceDeviceId){
              Link linkPath = parentLink.get(dstCP.deviceId());
              if(linkPath==null) break;
              //log.info("Path on "+linkPath.src().deviceId().toString() );
              installDHCPRule(srcmac, linkPath.src().port(), linkPath.src().deviceId());
              log.info("[DHCP] Install: {} {} {}", srcmac, linkPath.src().port(), linkPath.src().deviceId() );
              dstCP = linkPath.src();
          }
        }

        //inverse
        srcmac = MacAddress.valueOf(dhcpMac);
        dstmac = ethPkt.getSourceMAC();

        //get source device
        sourceDeviceId = DeviceId.deviceId(dhcpCPoint.split("/")[0]);
        parentLink = getAllPath(myGraph, sourceDeviceId);

        //find path
        findHosts = hostService.getHostsByMac(dstmac);
        firstHost = findHosts.iterator().next();
        ConnectPoint dstCP = firstHost.location();
        installRule(srcmac, dstmac, dstCP.port(), dstCP.deviceId());
        log.info("[DHCP] Install: {} {} {}", srcmac, dstmac, dstCP.port(), dstCP.deviceId() );
        while(dstCP.deviceId() != sourceDeviceId){
            //log.info( "Path:  "+dstCP.deviceId().toString() );
            Link linkPath = parentLink.get(dstCP.deviceId());
            if(linkPath==null) break;
            installRule(srcmac, dstmac, linkPath.src().port(), linkPath.src().deviceId());
            log.info("[DHCP] Install: {} {} {}", srcmac, dstmac, linkPath.src().port(), linkPath.src().deviceId() );
            dstCP = linkPath.src();
        }
    }


    private Map<DeviceId, List> getGraph(){
        Map<DeviceId, List> myGraph = new HashMap();
        TopologyGraph currentGraph = topologyService.getGraph(topologyService.currentTopology());
        Set<TopologyVertex> vertexes = new HashSet<TopologyVertex>(currentGraph.getVertexes());
        for(TopologyVertex i : vertexes){
            //log.info("Device:  "+i.deviceId().toString());
            List vtxlist = new LinkedList<Link>();
            Set<TopologyEdge> to_edges = currentGraph.getEdgesFrom(i);
            for(TopologyEdge j : to_edges){
                vtxlist.add(j.link());
            }
            myGraph.put(i.deviceId(), vtxlist);
        }
        return myGraph;
    }

    private Map<DeviceId, Link> getAllPath(Map<DeviceId, List> myGraph, DeviceId sourceDeviceId){
        Map<DeviceId, Integer> distance = new HashMap<DeviceId, Integer>();  //dist
        Map<DeviceId, Link> parentLink = new HashMap<DeviceId, Link>();      //parent
        Set<DeviceId> vertexes = myGraph.keySet();
        for(DeviceId i : vertexes){
            distance.put(i,Integer.MAX_VALUE);
            parentLink.put(i,null);
        }
        distance.put(sourceDeviceId,0);

        while(!vertexes.isEmpty()){
            DeviceId minVertex = null;
            Integer minDistance = Integer.MAX_VALUE;
            for(DeviceId i : vertexes){
                if( distance.get(i) <= minDistance ){
                    minVertex = i;
                    minDistance = distance.get(i);
                }
            }

            List<Link> to_edges = myGraph.get(minVertex);
            for(Link i : to_edges ){
                if( minDistance.intValue()+1 < distance.get(i.dst().deviceId()).intValue() ){
                    distance.put(i.dst().deviceId(), new Integer(minDistance.intValue()+1) );
                    parentLink.put(i.dst().deviceId(),i);
                }
            }
            vertexes.remove(minVertex);
        }

        return parentLink;
    }

    /** Sends DHCP flow modify to device */
    private void installDHCPRule(MacAddress srcMac, 
                             PortNumber outPort, DeviceId configDeviceId){
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();

        selectorBuilder.matchEthType(Ethernet.TYPE_IPV4)
                       .matchIPProtocol(IPv4.PROTOCOL_UDP)
                       .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                       .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .setOutput(outPort)
                    .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                    .withSelector(selectorBuilder.build())
                    .withTreatment(treatment)
                    .withPriority(DEFAULT_PRIORITY)
                    .withFlag(ForwardingObjective.Flag.VERSATILE)
                    .fromApp(appId)
                    .makeTemporary(DEFAULT_TIMEOUT)
                    .add();

        flowObjectiveService.forward(configDeviceId,  forwardingObjective);
    }

    /** Sends flow modify to device */
    private void installRule(MacAddress srcMac, MacAddress dstMac,
                             PortNumber outPort, DeviceId configDeviceId){
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();

        selectorBuilder.matchEthType(Ethernet.TYPE_IPV4)
                       .matchIPProtocol(IPv4.PROTOCOL_UDP)
                       .matchUdpSrc(TpPort.tpPort(UDP.DHCP_SERVER_PORT))
                       .matchUdpDst(TpPort.tpPort(UDP.DHCP_CLIENT_PORT));
                    //.matchEthSrc(srcMac)
                    //.matchEthDst(dstMac);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                    .setOutput(outPort)
                    .build();

        ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                    .withSelector(selectorBuilder.build())
                    .withTreatment(treatment)
                    .withPriority(DEFAULT_PRIORITY)
                    .withFlag(ForwardingObjective.Flag.VERSATILE)
                    .fromApp(appId)
                    .makeTemporary(DEFAULT_TIMEOUT)
                    .add();

        flowObjectiveService.forward(configDeviceId,  forwardingObjective);
    }

}
