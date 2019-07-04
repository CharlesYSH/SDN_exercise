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
package nctu.st.proxyarp;

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
    private Map<Ip4Address, MacAddress> arpTable = new ConcurrentHashMap();


    @Activate
    protected void activate() {
        appId = coreService.registerApplication("nctu.st.proxyarp");

        packetService.addProcessor(processor, PacketProcessor.director(2));
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.requestPackets(selector.build(), PacketPriority.REACTIVE, appId);
        log.info("Started");
    }


    @Deactivate
    protected void deactivate() {
        TrafficSelector.Builder selector = DefaultTrafficSelector.builder();
        selector.matchEthType(Ethernet.TYPE_ARP);
        packetService.cancelPackets(selector.build(), PacketPriority.REACTIVE, appId);
        packetService.removeProcessor(processor);
        processor = null;

        log.info("Stopped");
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

            // ARP packet
            if(ethPkt.getEtherType() == ethPkt.TYPE_ARP) {
                //log.info("[ARP] Packet in !!");
                ARP arpPayload = (ARP) ethPkt.getPayload();
                byte[] senderMac = arpPayload.getSenderHardwareAddress();
                byte[] senderIP = arpPayload.getSenderProtocolAddress();
                arpTable.putIfAbsent( Ip4Address.valueOf(senderIP), 
                                      MacAddress.valueOf(senderMac) );
                arpHandler(context);
                return;
            }
        }
    }


    private void arpHandler(PacketContext context){
        InboundPacket pkt = context.inPacket();
        ConnectPoint srccp = pkt.receivedFrom();
        Ethernet ethPkt = pkt.parsed();
        ARP arpPayload = (ARP) ethPkt.getPayload();

        // ARP REQUEST
        if( arpPayload.getOpCode() == ARP.OP_REQUEST ){
            Ip4Address targetIP
                       = Ip4Address.valueOf(arpPayload.getTargetProtocolAddress());
            MacAddress targetMac = arpTable.get( targetIP );

            if( targetMac == null ){
                log.info("[ARP] REQUEST!!");
                for ( ConnectPoint cp : edgeService.getEdgePoints() ){
                    if( cp.deviceId().equals( srccp.deviceId() ) && 
                        cp.port().equals( srccp.port() ) ){
                            continue;
                    }
                    packetOut(cp.deviceId(), cp.port(),
                              ByteBuffer.wrap(ethPkt.serialize()) );
                }
                return;
            }else{
                log.info( "[ARP] FIND in TABLE!!" );
                Ethernet arpRe = ARP.buildArpReply(targetIP, targetMac, ethPkt);
                packetOut(srccp.deviceId(), srccp.port(),
                          ByteBuffer.wrap(arpRe.serialize()) );
            }
        }

        // ARP REPLY
        if( arpPayload.getOpCode() == ARP.OP_REPLY ){
            log.info("[ARP] REPLY!!");
            MacAddress targetMac
                       = MacAddress.valueOf(arpPayload.getTargetHardwareAddress());
            Set<Host> findHosts = hostService.getHostsByMac(targetMac);
            if( findHosts.size()==0 ){
                log.info( "[WARN] HostService not find {}", targetMac );
                return;
            }
            Host firstHost = findHosts.iterator().next();
            ConnectPoint sourceCP = firstHost.location();
            packetOut(sourceCP.deviceId(), sourceCP.port(),
                      ByteBuffer.wrap(ethPkt.serialize()) );
        }

    }


    private void packetOut(DeviceId device, PortNumber outPort, ByteBuffer data){
        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                                     .setOutput(outPort).build();
        DefaultOutboundPacket oPacket = 
                              new DefaultOutboundPacket(device, treatment, data);
        packetService.emit(oPacket);
    }
}
