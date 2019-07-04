# SDN_exercise
Practiced SDN with ONOS

### Lab 1 - ONOS + Mininet
* Write a custom Mininet Topology
* Add your own CLI command

### Lab 2 - OpenFlow + Flow Rule
* Install/Delete flow rules with REST

### Lab 3 - Writing Applications
* Write an application of learning bridge function
* Update MAC to port table when receiving packet-INs

### Lab 4 - Path Service
* Write a path service application which finds a path
* Proactive install flow rules on each devices in the path

### Lab 5 - GroupMeter Entries
* Use group entries in OVS
* Use meter entries in OVS

### Lab 6 - Unicast DHCP Applcation
* Dynamically set DHCP server’s connect point through REST API (configuration service)
* Compute path between DHCP client and DHCP server
* Install flow rules to forward DHCP transaction traffic

### Lab 7 - Proxy ARP
* If no mapping is found -> flood ARP request to all edge ports
* If mapping of requested IP to MAC address has already been learned -> Send Packet-Out of ARP Reply directly

### Lab 8 - VLAN-based Segment Routing
* All flow/group rules should be installed once controller receives configuration
* Forward packets with label switching and source routing mechanism
* If there are multiple paths with same hop count, use SELECT group to achieve load balancing
