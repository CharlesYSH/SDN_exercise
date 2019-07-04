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
import org.onosproject.net.topology.TopologyService;


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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skeletal ONOS application component.
 */
@Component(immediate = true)
public class AppComponent {

    private static final int DEFAULT_TIMEOUT = 10;
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
  
    
    protected Map<DeviceId, Map<MacAddress, PortNumber>> macTables = new ConcurrentHashMap();
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
            // packet has been block or send
            if (context.isHandled()) {
                return;
            }
        
            InboundPacket pkt = context.inPacket();
            Ethernet ethPkt = pkt.parsed();
            macTables.putIfAbsent(pkt.receivedFrom().deviceId(), new ConcurrentHashMap());

            //not ethernet packet
            if (ethPkt == null) {
                return;
            }

            MacAddress macAddress = ethPkt.getSourceMAC();

            // Bail if this is deemed to be a control packet.
            if (isControlPacket(ethPkt)) {
                log.info("Get Control Packet!!");
                return;
            }

            HostId srcId = HostId.hostId(ethPkt.getSourceMAC());
            HostId dstId = HostId.hostId(ethPkt.getDestinationMAC());

            // Do not process LLDP MAC address in any way.
            if (dstId.mac().isLldp()) {
                log.info("Get LLDP MAC address!!");
                return;
            }            

            handler(context);
        }
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


    //handle packet
    private void handler(PacketContext context){

        InboundPacket pkt = context.inPacket();
        Ethernet ethPkt = pkt.parsed();
        ConnectPoint cpDevice = pkt.receivedFrom();

        Map<MacAddress, PortNumber> macTable_device = macTables.get(cpDevice.deviceId());
        MacAddress srcMac = ethPkt.getSourceMAC();
        MacAddress dstMac = ethPkt.getDestinationMAC();
        macTable_device.put(srcMac, cpDevice.port());
        PortNumber outPort = macTable_device.get(dstMac);
        //log.info("Device {} learn {} from port {}.",cpDevice.deviceId(),srcMac,cpDevice.port());

        if (outPort != null) {
            //log.info("Find output port {} on {} !!",outPort,cpDevice.deviceId());
            
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

            flowObjectiveService.forward(context.inPacket().receivedFrom().deviceId(),  forwardingObjective);

            context.treatmentBuilder().setOutput(outPort);
            context.send();
        }else{
            log.info("No record on {} !!",cpDevice.deviceId());
            flood(context);
        }
    }

    // Indicates whether this is a control packet, e.g. LLDP, BDDP
    private boolean isControlPacket(Ethernet eth) {
        short type = eth.getEtherType();
        return type == Ethernet.TYPE_LLDP || type == Ethernet.TYPE_BSN;
    }

}


