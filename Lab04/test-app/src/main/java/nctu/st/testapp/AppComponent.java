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

import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.LinkedList;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private static final int DEFAULT_TIMEOUT = 300;
    private static final int DEFAULT_PRIORITY = 40001;

    private final Logger log = LoggerFactory.getLogger(getClass());

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
  

    private ApplicationId appId;
    private ReactivePacketProcessor processor = new ReactivePacketProcessor();


    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.st.testapp");
        
        packetService.addProcessor(processor, PacketProcessor.director(2));

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4); //.matchEthType(Ethernet.TYPE_ARP)
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);

        log.info("Started", appId.id());
    }


    @Deactivate
    protected void deactivate() {

        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_IPV4); 
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);

        packetService.removeProcessor(processor);
        processor = null;
        log.info("Stopped");
    }


    private class ReactivePacketProcessor implements PacketProcessor {

        @Override
        public void process(PacketContext context) {

            // Stop processing if the packet has been handled, since we can't do any more to it.
            if (context.isHandled()) {
                return;
            }
        
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();

            //not ethernet packet
            if (ethPkt == null) {
                return;
            }

            // Bail if this is deemed to be a control packet.
            if (isControlPacket(ethPkt)) {
                log.info("Get Control Packet!!");
                return;
            }          

            handler(context);
        }
    }


    //handle packet
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


    // Sends flow modify to device
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


    // Indicates whether this is a control packet, e.g. LLDP, BDDP
    private boolean isControlPacket(Ethernet eth) {
        short type = eth.getEtherType();
        return type == Ethernet.TYPE_LLDP || type == Ethernet.TYPE_BSN;
    }


    // Floods the specified packet if permissible.
    private void flood(PacketContext context) {
        if (topologyService.isBroadcastPoint(topologyService.currentTopology(),
                                             context.inPacket().receivedFrom())) {
            //log.info("flood");
            packetOut(context, PortNumber.FLOOD);
        } else {
            context.block();
        }
    }


    // Sends a packet out the specified port.
    private void packetOut(PacketContext context, PortNumber portNumber) {
        context.treatmentBuilder().setOutput(portNumber);
        context.send();
    }

}


