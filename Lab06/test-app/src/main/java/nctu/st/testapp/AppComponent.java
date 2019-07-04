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
package nctu.st.testapp;

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

import org.onosproject.net.flowobjective.DefaultForwardingObjective;
import org.onosproject.net.flowobjective.ForwardingObjective;
import org.onosproject.net.flowobjective.FlowObjectiveService;

import org.onosproject.net.packet.PacketPriority;
import org.onosproject.net.packet.PacketService;
import org.onosproject.net.packet.PacketProcessor;
import org.onosproject.net.packet.PacketContext;
import org.onosproject.net.packet.InboundPacket;

import org.onosproject.net.Path;
import org.onosproject.net.Host;
import org.onosproject.net.HostId;
import org.onosproject.net.DeviceId;
import org.onosproject.net.ConnectPoint;
import org.onosproject.net.PortNumber;
import org.onosproject.net.host.HostService;
import org.onosproject.net.Link;
import org.onosproject.net.device.DeviceService;

import org.onosproject.net.topology.TopologyService;
import org.onosproject.net.topology.TopologyGraph;
import org.onosproject.net.topology.TopologyVertex;
import org.onosproject.net.topology.TopologyEdge;

import org.onosproject.net.flow.TrafficSelector;
import org.onosproject.net.flow.DefaultTrafficSelector;
import org.onosproject.net.flow.TrafficTreatment;
import org.onosproject.net.flow.DefaultTrafficTreatment;
import org.onosproject.net.flow.FlowRule;
import org.onosproject.net.flow.DefaultFlowRule;
import org.onosproject.net.flow.FlowRuleService;

import org.onlab.packet.Ethernet;
import org.onlab.packet.MacAddress;
import org.onlab.packet.Ip4Address;
import org.onlab.packet.IpPrefix;
import org.onlab.packet.TpPort;
import org.onlab.packet.UDP;
import org.onlab.packet.IPv4;

import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import static org.onosproject.net.config.basics.SubjectFactories.APP_SUBJECT_FACTORY;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    /** Initial variable */
    private final Logger log = LoggerFactory.getLogger(getClass());
    private ApplicationId appId;
    private final InternalConfigListener cfgListener = new InternalConfigListener();
    private ReactivePacketProcessor processor = new ReactivePacketProcessor();
    

    private static final MacAddress BoardcastMac = MacAddress.valueOf("ff:ff:ff:ff:ff:ff");
    private static final Ip4Address srcDHCPip = Ip4Address.valueOf("0.0.0.0");
    private static final Ip4Address dstDHCPip = Ip4Address.valueOf("255.255.255.255");
    private static final int DEFAULT_TIMEOUT = 300;
    private static final int DEFAULT_PRIORITY = 4000;
    private static String dhcpMac = "FF:FF:FF:FF:FF:FF";
    private static String dhcpCPoint = "of:0000000000000000/0";

    /** Config factory */
    private final Set<ConfigFactory> factories = ImmutableSet.of(
        new ConfigFactory<ApplicationId, nctu.st.testapp.MyConfig>(APP_SUBJECT_FACTORY,
        nctu.st.testapp.MyConfig.class, "myconfig"){
            @Override
            public nctu.st.testapp.MyConfig createConfig(){
                return new nctu.st.testapp.MyConfig();
            }
        }
    );

    /** ONOS service */
    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected NetworkConfigRegistry cfgService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected TopologyService topologyService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected PacketService packetService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected HostService hostService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected FlowObjectiveService flowObjectiveService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected CoreService coreService;

    @Reference(cardinality = ReferenceCardinality.MANDATORY_UNARY)
    protected DeviceService deviceService;

    /** Start App */
    @Activate
    protected void activate() {

        appId = coreService.registerApplication("EchoConfig");
        cfgService.addListener(cfgListener);
        factories.forEach(cfgService::registerConfigFactory);
        cfgListener.reconfigureNetwork(cfgService.getConfig(appId, nctu.st.testapp.MyConfig.class));

        packetService.addProcessor(processor, PacketProcessor.director(2));
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);

        //initialDHCPflow();
        log.info("Started");
    }

    /** Stop App */
    @Deactivate
    protected void deactivate() {

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4); 
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);

        packetService.removeProcessor(processor);
        processor = null;

        cfgService.removeListener(cfgListener);
        factories.forEach(cfgService::unregisterConfigFactory);
        log.info("Stopped");
    }

    /** Config Listener */
    private class InternalConfigListener implements NetworkConfigListener {

        // update the variable
        private void reconfigureNetwork(nctu.st.testapp.MyConfig cfg) {
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

        @Override
        public void event(NetworkConfigEvent event) {
            // While configuration is uploaded
            if ((event.type() == NetworkConfigEvent.Type.CONFIG_ADDED ||
                 event.type() == NetworkConfigEvent.Type.CONFIG_UPDATED) &&
                 event.configClass().equals(nctu.st.testapp.MyConfig.class)) {

                nctu.st.testapp.MyConfig cfg 
                                         = cfgService.getConfig(appId, nctu.st.testapp.MyConfig.class);
                // handle config
                reconfigureNetwork(cfg);
                log.info("[Reconfigured] mac: {}, dhcpCPoint: {} ", dhcpMac, dhcpCPoint);
            }
        }

    }

    /** Packet Processor */
    private class ReactivePacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {

            // Stop processing if the packet has been handled, since we can't do any more to it.
            if (context.isHandled()) {
                return;
            }
        
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            // not ethernet packet
            if (ethPkt == null) {
                return;
            }

            // Bail if this is deemed to be a control packet.
            if (isControlPacket(ethPkt)) {
                log.info("Get Control Packet!!");
                return;
            }          

            // check dhcp packet
            if ( ethPkt.isBroadcast() ){
                IPv4 ipv4IpPayload = (IPv4) ethPkt.getPayload();
                Ip4Address ipSrcAddr = Ip4Address.valueOf(ipv4IpPayload.getSourceAddress());
                Ip4Address ipDstAddr = Ip4Address.valueOf(ipv4IpPayload.getDestinationAddress());
                if ( ipSrcAddr.equals(srcDHCPip) && ipDstAddr.equals(dstDHCPip) ){
                    log.info("Get DHCP!!");
                    dhcp_handler(context);
                }
                return;
            }

            log.info("Get packet!!");
            handler(context);
        }
    }


    /** handle dhcp broadcast */
    private void dhcp_handler(PacketContext context){
        InboundPacket pkt = context.inPacket();
        ConnectPoint toHostCP = pkt.receivedFrom();
        Ethernet ethPkt = pkt.parsed();
        MacAddress srcmac = ethPkt.getSourceMAC();
        MacAddress dstmac = MacAddress.valueOf(dhcpMac);
        //log.info("path to DHCP: "+dstmac.toString());
        //log.info("From: "+toHostCP.deviceId().toString()+" "+toHostCP.port().toString());

        //build graph
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

        //get source device
        Set<Host> findHosts = hostService.getHostsByMac(srcmac);
        findHosts = hostService.getHostsByMac(srcmac);
        if( findHosts.size()==0 ){
          log.info("hostService not find {}",srcmac);
          return;
        }
        Host firstHost = findHosts.iterator().next();
        DeviceId sourceDeviceId = firstHost.location().deviceId();
        //log.info("SrcDevice:  "+sourceDeviceId.toString());
        
        //calculate path with Dijkstra's algorithm
        Map<DeviceId, Integer> distance = new HashMap<DeviceId, Integer>();  //dist
        Map<DeviceId, Link> parentLink = new HashMap<DeviceId, Link>();      //parent
        for(TopologyVertex i : vertexes){
            distance.put(i.deviceId(),Integer.MAX_VALUE);
            parentLink.put(i.deviceId(),null);
        }
        distance.put(sourceDeviceId,0);

        while(!vertexes.isEmpty()){
            TopologyVertex minVertex = null;
            Integer minDistance = Integer.MAX_VALUE;
            
            for(TopologyVertex i : vertexes){
                if( distance.get(i.deviceId()) <= minDistance ){
                    minVertex = i;
                    minDistance = distance.get(i.deviceId());
                }
            }

            List<Link> to_edges = myGraph.get(minVertex.deviceId());
            for(Link i : to_edges ){
                if( minDistance.intValue()+1 < distance.get(i.dst().deviceId()).intValue() ){
                    distance.put(i.dst().deviceId(), new Integer(minDistance.intValue()+1) );
                    parentLink.put(i.dst().deviceId(),i);
                }
            }

            vertexes.remove(minVertex);
        }

        //find path
	if( sourceDeviceId.equals(DeviceId.deviceId(dhcpCPoint.split("/")[0])) ){
            installDHCPRule(srcmac, PortNumber.portNumber(dhcpCPoint.split("/")[1]), sourceDeviceId);
            log.info("[Install] dhcp: {} {} {}", srcmac, PortNumber.portNumber(dhcpCPoint.split("/")[1]), sourceDeviceId );
        }else{
          Link firstlink = parentLink.get(DeviceId.deviceId(dhcpCPoint.split("/")[0]));
          ConnectPoint dstCP = firstlink.dst();
          installDHCPRule(srcmac, PortNumber.portNumber(dhcpCPoint.split("/")[1]), dstCP.deviceId());
          log.info("[Install] dhcp: {} {} {}", srcmac, PortNumber.portNumber(dhcpCPoint.split("/")[1]), dstCP.deviceId() );
          while(dstCP.deviceId() != sourceDeviceId){
              Link linkPath = parentLink.get(dstCP.deviceId());
              if(linkPath==null) break;
              //log.info("Path on "+linkPath.src().deviceId().toString() );
              installDHCPRule(srcmac, linkPath.src().port(), linkPath.src().deviceId());
              log.info("[Install] dhcp: {} {} {}", srcmac, linkPath.src().port(), linkPath.src().deviceId() );
              dstCP = linkPath.src();
          }
        }

        //build graph
        myGraph = new HashMap();
        currentGraph = topologyService.getGraph(topologyService.currentTopology());
        vertexes = new HashSet<TopologyVertex>(currentGraph.getVertexes());
        for(TopologyVertex i : vertexes){
            //log.info("Device:  "+i.deviceId().toString());
            List vtxlist = new LinkedList<Link>();
            Set<TopologyEdge> to_edges = currentGraph.getEdgesFrom(i);
            for(TopologyEdge j : to_edges){
                vtxlist.add(j.link());
            }
            myGraph.put(i.deviceId(), vtxlist);
        }


        //inverse
        srcmac = MacAddress.valueOf(dhcpMac);
        dstmac = ethPkt.getSourceMAC();

        //get source device
        sourceDeviceId = DeviceId.deviceId(dhcpCPoint.split("/")[0]);
        
        //calculate path with Dijkstra's algorithm
        distance = new HashMap<DeviceId, Integer>();
        parentLink = new HashMap<DeviceId, Link>();
        for(TopologyVertex i : vertexes){
            distance.put(i.deviceId(),Integer.MAX_VALUE);
            parentLink.put(i.deviceId(),null);
        }
        distance.put(sourceDeviceId,0);
        while(!vertexes.isEmpty()){
            TopologyVertex minVertex = null;
            Integer minDistance = Integer.MAX_VALUE;
            
            for(TopologyVertex i : vertexes){
                if( distance.get(i.deviceId()) <= minDistance ){
                    minVertex = i;
                    minDistance = distance.get(i.deviceId());
                }
            }

            List<Link> to_edges = myGraph.get(minVertex.deviceId());
            for(Link i : to_edges ){
                if( minDistance.intValue()+1 < distance.get(i.dst().deviceId()).intValue() ){
                    distance.put(i.dst().deviceId(), new Integer(minDistance.intValue()+1) );
                    parentLink.put(i.dst().deviceId(),i);
                }
            }

            vertexes.remove(minVertex);
        }

        //find path
        findHosts = hostService.getHostsByMac(dstmac);
        firstHost = findHosts.iterator().next();
        ConnectPoint dstCP = firstHost.location();
        installRule(srcmac, dstmac, dstCP.port(), dstCP.deviceId());
        log.info("[Install] : {} {} {}", srcmac, dstmac, dstCP.port(), dstCP.deviceId() );
        while(dstCP.deviceId() != sourceDeviceId){
            log.info( "Path:  "+dstCP.deviceId().toString() );
            Link linkPath = parentLink.get(dstCP.deviceId());
            if(linkPath==null) break;
            installRule(srcmac, dstmac, linkPath.src().port(), linkPath.src().deviceId());
            log.info("[Install] : {} {} {}", srcmac, dstmac, linkPath.src().port(), linkPath.src().deviceId() );
            dstCP = linkPath.src();
        }

    }
    
    /** handle packet */
    private void handler(PacketContext context){

        InboundPacket pkt = context.inPacket();
        Ethernet ethPkt = pkt.parsed();

        MacAddress srcmac = ethPkt.getSourceMAC();
        MacAddress dstmac = ethPkt.getDestinationMAC();

        //build graph
        Map<DeviceId, List> myGraph = new ConcurrentHashMap();
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

        //get source device
        Set<Host> findHosts = hostService.getHostsByMac(srcmac);
        Host firstHost = findHosts.iterator().next();
        DeviceId sourceDeviceId = firstHost.location().deviceId();
        
        //calculate path with Dijkstra's algorithm
        Map<DeviceId, Integer> distance = new HashMap<DeviceId, Integer>();
        Map<DeviceId, Link> parentLink = new HashMap<DeviceId, Link>();
        for(TopologyVertex i : vertexes){
            distance.put(i.deviceId(),Integer.MAX_VALUE);
            parentLink.put(i.deviceId(),null);
        }
        distance.put(sourceDeviceId,0);

        while(!vertexes.isEmpty()){
            TopologyVertex minVertex = null;
            Integer minDistance = Integer.MAX_VALUE;
            
            for(TopologyVertex i : vertexes){
                if( distance.get(i.deviceId()) <= minDistance ){
                    minVertex = i;
                    minDistance = distance.get(i.deviceId());
                }
            }
            //log.info( "Bag size:  "  + String.valueOf(vertexes.size()) );
            //log.info( "MIN distance:  "  + minDistance.toString() );
            //log.info( "MIN ID:  "  + minVertex.deviceId().toString() );

            List<Link> to_edges = myGraph.get(minVertex.deviceId());
            for(Link i : to_edges ){
                if( minDistance.intValue()+1 < distance.get(i.dst().deviceId()).intValue() ){
                    distance.put(i.dst().deviceId(), new Integer(minDistance.intValue()+1) );
                    parentLink.put(i.dst().deviceId(),i);
                }
            }

            vertexes.remove(minVertex);
        }

        //find path
        findHosts = hostService.getHostsByMac(dstmac);
        firstHost = findHosts.iterator().next();
        ConnectPoint dstCP = firstHost.location();
        installRule(srcmac, dstmac, dstCP.port(), dstCP.deviceId());
        while(dstCP.deviceId() != sourceDeviceId){
            log.info( "Path:  "+dstCP.deviceId().toString() );
            Link linkPath = parentLink.get(dstCP.deviceId());
            if(linkPath==null) break;
            installRule(srcmac, dstmac, linkPath.src().port(), linkPath.src().deviceId());
            dstCP = linkPath.src();
        }

    }


    /** Sends flow modify to device */
    private void installRule(MacAddress srcMac, MacAddress dstMac,
                             PortNumber outPort, DeviceId configDeviceId){
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();

        selectorBuilder.matchEthSrc(srcMac)
                    .matchEthDst(dstMac);

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
    private void installDHCPRule(MacAddress srcMac, 
                             PortNumber outPort, DeviceId configDeviceId){
        TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();

        selectorBuilder.matchEthSrc(srcMac).matchEthDst(BoardcastMac);
                    /*.matchEthType(Ethernet.TYPE_IPV4)
                    .matchIPProtocol(IPv4.PROTOCOL_UDP)
                    .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                    .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));*/

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

    /** Default DHCP flow */
    private void initialDHCPflow(){ 

        TopologyGraph currentGraph = topologyService.getGraph(topologyService.currentTopology());
        for(TopologyVertex i : currentGraph.getVertexes()){
            TrafficSelector.Builder selectorBuilder = DefaultTrafficSelector.builder();

            selectorBuilder.matchEthType(Ethernet.TYPE_IPV4)
                        .matchIPProtocol(IPv4.PROTOCOL_UDP)
                        .matchUdpSrc(TpPort.tpPort(UDP.DHCP_CLIENT_PORT))
                        .matchUdpDst(TpPort.tpPort(UDP.DHCP_SERVER_PORT));

            TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                        .setOutput(PortNumber.CONTROLLER).build();

            ForwardingObjective forwardingObjective = DefaultForwardingObjective.builder()
                        .withSelector(selectorBuilder.build())
                        .withTreatment(treatment)
                        .withPriority(40001)
                        .withFlag(ForwardingObjective.Flag.VERSATILE)
                        .fromApp(appId)
                        .makeTemporary(DEFAULT_TIMEOUT)
                        .add();

            flowObjectiveService.forward(i.deviceId(),  forwardingObjective);
        }
    }

    /** Indicates whether this is a control packet, e.g. LLDP, BDDP */
    private boolean isControlPacket(Ethernet eth) {
        short type = eth.getEtherType();
        return type == Ethernet.TYPE_LLDP || type == Ethernet.TYPE_BSN;
    }
}




