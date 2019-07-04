from mininet.net import Mininet
from mininet.link import Intf
from mininet.topo import Topo
from mininet.cli import CLI
from mininet.log import info, output,error

class Project1_Topo(Topo):
	def __init__(self):
		Topo.__init__(self)

		h1 = self.addHost("h1")
		h2 = self.addHost("h2")
		h3 = self.addHost("h3")
		h4 = self.addHost("h4")

		s1 = self.addSwitch("s1")
		s2 = self.addSwitch("s2")
		s3 = self.addSwitch("s3")
		s4 = self.addSwitch("s4")

		self.addLink(h1,s1)
		self.addLink(h2,s1)
		self.addLink(h3,s2)
		self.addLink(h4,s2)
		self.addLink(s1,s3)
		self.addLink(s1,s4)
		self.addLink(s2,s3)
		self.addLink(s2,s4)

def mycmd( self, line):
	"mycmd is an example command to extend the Mininet CLI"
	net = self.mn
	output( 'mycmd invoked for', net, 'with line', line, '\n' )

def addHostToSwitch( self, line ):
	"to add a host to specific switch"
	net = self.mn
	newNumber = len(net.hosts)+1
	newHost = net.addHost( "h%d"%newNumber )
	getSwitch = net.getNodeByName( line )
	#output(getSwitch.intfs, '\n')
	link = net.addLink(getSwitch,newHost)
	output(getSwitch.intfs, '\n')
	output(newHost.intfs, '\n')
	#output( getSwitch.intfs[len(getSwitch.intfs)-1] )
	newHost.setIP( ip='10.0.0.%d'%newNumber )
	getSwitch.attach( getSwitch.intfs[len(getSwitch.intfs)-1] )

topos = {"topology": Project1_Topo }
CLI.do_mycmd = mycmd
CLI.do_addHostToSwitch = addHostToSwitch
