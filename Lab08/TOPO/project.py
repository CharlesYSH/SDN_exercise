from mininet.topo import Topo
from mininet.net import Mininet
from mininet.cli import CLI
from mininet.node import RemoteController, Host, OVSSwitch
from mininet.link import TCLink
from mininet.log import setLogLevel, info, warn, error
import os

class Logger():

    def __init__(self):
        setLogLevel('info')

    def info(self, log):
        info('[INFO] %s\n' % log)

    def warn(self, log):
        warn('[WARN] %s\n' % log)

    def error(self, log):
        error('[ERROR] %s\n' % log)

logger = Logger()

class MyTopo(Topo):

    def __init__(self):

        logger.info('Creating Mininet topology...')
        
        Topo.__init__(self)

        s1 = self.addSwitch('s1', dpid='0000000000000001')
        s2 = self.addSwitch('s2', dpid='0000000000000002')
        s3 = self.addSwitch('s3', dpid='0000000000000003')

        h1 = self.addHost('h1', mac='00:00:00:00:00:01', ip='10.0.2.1/16')
        h2 = self.addHost('h2', mac='00:00:00:00:00:02', ip='10.0.2.2/16')
        h3 = self.addHost('h3', mac='00:00:00:00:00:03', ip='10.0.3.1/16')
        h4 = self.addHost('h4', mac='00:00:00:00:00:04', ip='10.0.3.2/16')
        h5 = self.addHost('h5', mac='00:00:00:00:00:05', ip='0.0.0.0')

        self.addLink(s1, s2)
        self.addLink(s1, s3)

        self.addLink(s2, h1)
        self.addLink(s2, h2)
        self.addLink(s3, h3)
        self.addLink(s3, h4)
        self.addLink(s2, h5)
        
    def addLinkBtwSwitch(self, line):
        switches = line.split(" ")
        logger.info('Adding device link between %s and %s...' % (switches[0], switches[1]))

        net = self.mn
        s1 = net.get(switches[0])
        s2 = net.get(switches[1])

        net.addLink(s1, s2)

        s1IntfList = s1.intfList()
        s2IntfList = s2.intfList()

        s1.attach(s1IntfList[-1])
        s2.attach(s2IntfList[-1])

    def delLinkBtwSwitch(self, line):
        switches = line.split(" ")
        logger.info('Deleting device link between %s and %s...' % (switches[0], switches[1]))

        net = self.mn
        s1 = net.get(switches[0])
        s2 = net.get(switches[1])

        net.delLinkBetween(s1, s2)

    CLI.do_addLinkBtwSwitch = addLinkBtwSwitch
    CLI.do_delLinkBtwSwitch = delLinkBtwSwitch

topos = {'mytopo' : MyTopo}

if __name__ == '__main__':
    topo = MyTopo()
    net = Mininet(topo=topo, link=TCLink, controller=None)
    net.addController('c0', switch=OVSSwitch, controller=RemoteController, ip='127.0.0.1')

    net.start()

    logger.info('Run DHCP server...')
    dhcp = net.getNodeByName('h2')
    dhcp.cmdPrint('/usr/sbin/dhcpd -4 -pf /run/dhcp-server.pid -cf ./dhcpd.conf %s' % dhcp.defaultIntf())

    CLI(net)

    logger.info('Killing DHCP server...')
    dhcp = net.getNodeByName('h2')
    dhcp.cmdPrint("kill -9 `ps aux | grep h2-eth0 | grep dhcpd | awk '{print $2}'`")

    net.stop()
    os.system("mn -c")
