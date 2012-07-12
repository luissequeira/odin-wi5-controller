package net.floodlightcontroller.odinmaster;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.odinmaster.NotificationCallback;
import net.floodlightcontroller.odinmaster.OdinEventSubscription;
import net.floodlightcontroller.odinmaster.NotificationCallbackContext;
import net.floodlightcontroller.odinmaster.SubscriptionCallbackTuple;
import net.floodlightcontroller.odinmaster.IOdinAgent;
import net.floodlightcontroller.odinmaster.OdinClient;
import net.floodlightcontroller.packet.DHCP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.staticflowentry.IStaticFlowEntryPusherService;
import net.floodlightcontroller.util.MACAddress;
import net.floodlightcontroller.core.IListener;


/**
 * OdinMaster implementation. Exposes interfaces to OdinApplications,
 * and keeps track of agents and clients in the system.
 * 
 * @author Lalith Suresh <suresh.lalith@gmail.com>
 *
 */
public class OdinMaster implements IFloodlightModule, IOFSwitchListener, IOdinApplicationInterface, IOFMessageListener {
	protected static Logger log = LoggerFactory.getLogger(OdinMaster.class);

	private IFloodlightProviderService floodlightProvider;
	private final Executor executor = Executors.newFixedThreadPool(10);
	
	private final AgentManager agentManager;
	private final ClientManager clientManager;	
	private final ILvapManager lvapManager;
	
	private long subscriptionId = 0;
	private String subscriptionList = "";
	private final ConcurrentMap<Long, SubscriptionCallbackTuple> subscriptions = new ConcurrentHashMap<Long, SubscriptionCallbackTuple>();

	public OdinMaster(){
		clientManager = new ClientManager();
		agentManager = new AgentManager(clientManager);
		lvapManager = new LvapManagerImpl();
	}
	
	public OdinMaster(AgentManager agentManager, ClientManager clientManager, ILvapManager lvapManager){
		this.agentManager = agentManager;
		this.clientManager = clientManager;
		this.lvapManager = lvapManager;
	}
	
	
	/** Odin Agent->Master protocol handlers **/
	
	/**
	 * Handle a ping from an agent
	 * 
	 * @param InetAddress of the agent
	 */
	public synchronized void receivePing (final InetAddress odinAgentAddr) {
		if (agentManager.receivePing(odinAgentAddr)) {
			// if the above leads to a new agent being
			// tracked, push the current subscription list
			// to it.
			pushSubscriptionListToAgent(agentManager.getOdinAgents().get(odinAgentAddr));
		}
	}
	
	/**
	 * Handle a probe message from an agent, triggered
	 * by a particular client.
	 * 
	 * @param odinAgentAddress InetAddress of agent
	 * @param clientHwAddress MAC address of client that performed probe scan
	 */
	public synchronized void receiveProbe (final InetAddress odinAgentAddress, final MACAddress clientHwAddress) {
		
		if (odinAgentAddress != null
	    	&& clientHwAddress != null
	    	&& clientHwAddress.isBroadcast() == false
	    	&& clientHwAddress.isMulticast() == false
	    	&& agentManager.isTracked(odinAgentAddress) == true) {
			
			OdinClient oc = clientManager.getClient(clientHwAddress);
	    	
	    	// Hearing from this client for the first time
	    	if (oc == null) {
				oc = lvapManager.getLvapWithNullIp(clientHwAddress);
	    		clientManager.addClient(oc);
	    	}
	    	
			if (oc.getOdinAgent() == null) {
				// client is connecting for the
				// first time, had explicitly
				// disconnected, or knocked
				// out at as a result of an agent
				// failure.
				handoffClientToAp(clientHwAddress, odinAgentAddress);
			}
		}
	}
	
	/**
	 * Handle an event publication from an agent
	 * 
	 * @param clientHwAddress client which triggered the event
	 * @param odinAgentAddr agent at which the event was triggered
	 * @param subscriptionIds list of subscription Ids that the event matches
	 */
	public synchronized void receivePublish (final MACAddress clientHwAddress, final InetAddress odinAgentAddr, final Map<Long, Long> subscriptionIds) {

		// The check for null clientHwAddress might go away
		// in the future if we end up having events
		// that are not related to clients at all.
		if (clientHwAddress == null || odinAgentAddr == null || subscriptionIds == null)
			return;
		
		IOdinAgent oa = agentManager.getOdinAgents().get(odinAgentAddr);
		
		// This should never happen!
		if (oa == null)
			return;

		for (Entry<Long, Long> entry: subscriptionIds.entrySet()) {
			SubscriptionCallbackTuple tup = subscriptions.get(entry.getKey());
			
			/* This might occur as a race condition when the master
			 * has cleared all subscriptions, but hasn't notified
			 * the agent about it yet.
			 */
			if (tup == null)
				continue;


			NotificationCallbackContext cntx = new NotificationCallbackContext(clientHwAddress, oa, entry.getValue());
			
			tup.cb.exec(tup.oes, cntx);
		}
	}

	/** Odin methods to be used by applications (from IOdinApplicationInterface) **/
	
	/**
	 * VAP-Handoff a client to a new AP. This operation is idempotent.
	 * 
	 * @param newApIpAddr IPv4 address of new access point
	 * @param hwAddrSta Ethernet address of STA to be handed off
	 */
	public void handoffClientToAp (final MACAddress clientHwAddr, final InetAddress newApIpAddr){
		// As an optimisation, we probably need to get the accessing done first,
		// prime both nodes, and complete a handoff. 
		
		if (clientHwAddr == null || newApIpAddr == null) {
			log.error("null argument in handoffClientToAp(): clientHwAddr:" + clientHwAddr + " newApIpAddr:" + newApIpAddr);
			return;
		}
		
		IOdinAgent newAgent = agentManager.getOdinAgents().get(newApIpAddr);
		
		// If new agent doesn't exist, ignore request
		if (newAgent == null) {
			log.error("Handoff request ignored: OdinAgent " + newApIpAddr + " doesn't exist");
			return;
		}
		
		OdinClient client = clientManager.getClient(clientHwAddr);
		
		// Ignore request if we don't know the client
		if (client == null) {
			log.error("Handoff request ignored: OdinClient " + clientHwAddr + " doesn't exist");
			return;
		}
		
		// If the client is connecting for the first time, then it
		// doesn't have a VAP associated with it already
		if (client.getOdinAgent() == null) {
			log.info ("Client: " + clientHwAddr + " connecting for first time. Assigning to: " + newAgent.getIpAddress());

			// Push flow messages associated with the client
			try {
				newAgent.getSwitch().write(client.getOFMessageList(), null);
			} catch (IOException e) {
				log.error("Failed to update switch's flow tables " + newAgent.getSwitch());
			}

			newAgent.addLvap(client);
			client.setOdinAgent(newAgent);
			return;
		}
		
		// If the client is already associated with AP-newIpAddr, we ignore
		// the request.
		InetAddress currentApIpAddress = client.getOdinAgent().getIpAddress();
		if (currentApIpAddress.getHostAddress().equals(newApIpAddr.getHostAddress())) {
			log.info ("Client " + clientHwAddr + " is already associated with AP " + newApIpAddr);
			return;
		}

		// Push flow messages associated with the client
		try {
			newAgent.getSwitch().write(client.getOFMessageList(), null);
		} catch (IOException e) {
			log.error("Failed to update switch's flow tables " + newAgent.getSwitch());
		}
		
		// Client is with another AP. We remove the VAP from
		// the current AP of the client, and spawn it on the new one.
		// We split the add and remove VAP operations across two threads
		// to make it faster. Note that there is a temporary inconsistent 
		// state between setting the agent for the client and it actually 
		// being reflected in the network
		client.setOdinAgent(newAgent);
		executor.execute(new OdinAgentLvapAddRunnable(newAgent, client));
		executor.execute(new OdinAgentLvapRemoveRunnable(agentManager.getOdinAgents().get(currentApIpAddress), client));
	}
	
	
	/**
	 * Get a list of Odin agents from the agent tracker
	 * @return a map of OdinAgent objects keyed by Ipv4 addresses
	 */
	public ConcurrentMap<InetAddress, IOdinAgent> getOdinAgents (){
		return agentManager.getOdinAgents();
	}
	
	
	/**
	 * Add a subscription for a particular event defined by oes. cb is
	 * defines the application specified callback to be invoked during
	 * notification. If the application plans to delete the subscription,
	 * later, the onus is upon it to keep track of the subscription
	 * id for removal later.
	 * 
	 * @param oes the susbcription
	 * @param cb the callback
	 */
	public synchronized long registerSubscription (final OdinEventSubscription oes, final NotificationCallback cb) {
		assert (oes != null);
		assert (cb != null);
		SubscriptionCallbackTuple tup = new SubscriptionCallbackTuple();
		tup.oes = oes;
		tup.cb = cb;
		subscriptionId++;
		subscriptions.put(subscriptionId, tup);
		
		/**
		 * Update the subscription list, and push to all agents
		 * TODO: This is a common subsription string being
		 * sent to all agents. Replace this with per-agent
		 * subscriptions.
		 */
		subscriptionList = "";
		int count = 0;
		for (Entry<Long, SubscriptionCallbackTuple> entry: subscriptions.entrySet()) {
			count++;
			final String addr = entry.getValue().oes.getClient();
			subscriptionList = subscriptionList + 
								entry.getKey() + " " + 
								(addr.equals("*") ? MACAddress.valueOf("00:00:00:00:00:00") : addr)  + " " +
								entry.getValue().oes.getStatistic() + " " +
								entry.getValue().oes.getRelation().ordinal() + " " +
								entry.getValue().oes.getValue() + " ";
		}

		subscriptionList = String.valueOf(count) + " " + subscriptionList;

		/**
		 * Should probably have threads to do this
		 */
		for (Entry<InetAddress, IOdinAgent> entry : getOdinAgents().entrySet()) {
			pushSubscriptionListToAgent(entry.getValue());
		}
		
		return subscriptionId;
	}
	
	
	/**
	 * Remove a subscription from the list
	 * 
	 * @param id subscription id to remove
	 * @return
	 */
	public synchronized void unregisterSubscription (final long id) {
		subscriptions.remove(id);
		
		subscriptionList = "";
		int count = 0;
		for (Entry<Long, SubscriptionCallbackTuple> entry: subscriptions.entrySet()) {
			count++;
			final String addr = entry.getValue().oes.getClient();
			subscriptionList = subscriptionList + 
								entry.getKey() + " " + 
								(addr.equals("*") ? MACAddress.valueOf("00:00:00:00:00:00") : addr)  + " " +
								entry.getValue().oes.getStatistic() + " " +
								entry.getValue().oes.getRelation().ordinal() + " " +
								entry.getValue().oes.getValue() + " ";
		}

		subscriptionList = String.valueOf(count) + " " + subscriptionList;

		/**
		 * Should probably have threads to do this
		 */
		for (Entry<InetAddress, IOdinAgent> entry : getOdinAgents().entrySet()) {
			pushSubscriptionListToAgent(entry.getValue());
		}
	}
	
	/** IFloodlightModule methods **/
	
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
	        new ArrayList<Class<? extends IFloodlightService>>();
	    l.add(IFloodlightProviderService.class);
        l.add(IRestApiService.class);
        l.add(IStaticFlowEntryPusherService.class);
		return l;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		return null;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
	}

	@Override
	public void startUp(FloodlightModuleContext context) {		
		floodlightProvider.addOFSwitchListener(this);
		floodlightProvider.addOFMessageListener(OFType.PACKET_IN, this);
		agentManager.setFloodlightProvider (floodlightProvider);
		
		// read config options
        Map<String, String> configOptions = context.getConfigParams(this);
        
        String authFile = "odin_authorisation"; // default
        String authFileConfig = configOptions.get("authFile");
        
        if (authFileConfig != null) {
            authFile = authFileConfig;
        }
        
        try {
			BufferedReader br = new BufferedReader (new FileReader(authFile));
			
			String strLine;
			
			while ((strLine = br.readLine()) != null) {
				String [] fields = strLine.split(" ");
				
				MACAddress hwAddress = MACAddress.valueOf(fields[0]);
				InetAddress ipaddr = InetAddress.getByName(fields[1]);
				MACAddress bssid = MACAddress.valueOf(fields[2]);
				String essid = fields[3];
				log.info("Adding client: " + fields[0] + " " + fields[1] + " " +fields[2] + " " +fields[3]);
				clientManager.addClient(hwAddress, ipaddr, bssid, essid);
				clientManager.getClients().get(hwAddress).setOFMessageList(lvapManager.getDefaultOFModList(ipaddr));
			}
		} catch (FileNotFoundException e) {
			// skip
		} catch (IOException e) {
			e.printStackTrace();
		}
        
        
        int port = 2819; // default
        
        String portNum = configOptions.get("masterPort");
        if (portNum != null) {
            port = Integer.parseInt(portNum);
        }
        
		// Spawn threads
        executor.execute(new OdinAgentProtocolServer(this, port));
	}

	/** IOFSwitchListener methods **/
	
	@Override
	public void addedSwitch(IOFSwitch sw) {
		// inform-agent manager
	}

	@Override
	public String getName() {
		return "OdinMaster";
	}

	@Override
	public void removedSwitch(IOFSwitch sw) {
		// Not all OF switches are Odin agents. We should immediately remove
		// any associated Odin agent then.
		final InetAddress switchIpAddr = ((InetSocketAddress) sw.getChannel().getRemoteAddress()).getAddress();
		agentManager.getOdinAgents().remove(switchIpAddr);		
	}

	/**
	 * Push the subscription list to the agent
	 * 
	 * @param oa agent to push subscription list to
	 */
	private void pushSubscriptionListToAgent (final IOdinAgent oa) {
		oa.setSubscriptions(subscriptionList);
	}

	
	private class OdinAgentLvapAddRunnable implements Runnable {
		final IOdinAgent oa;
		final OdinClient oc;
		
		public OdinAgentLvapAddRunnable(IOdinAgent newAgent, OdinClient oc) {
			this.oa = newAgent;
			this.oc = oc;
		}
		@Override
		public void run() {
			oa.addLvap(oc);
		}
		
	}
	
	private class OdinAgentLvapRemoveRunnable implements Runnable {
		final IOdinAgent oa;
		final OdinClient oc;
		
		public OdinAgentLvapRemoveRunnable(IOdinAgent oa, OdinClient oc) {
			this.oa = oa;
			this.oc = oc;
		}
		@Override
		public void run() {
			oa.removeLvap(oc);
		}
		
	}

	@Override
	public Command receive(
			IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		
		// We use this to pick up DHCP response frames
		// and update a client's IP address details accordingly
		
		Ethernet frame = IFloodlightProviderService.bcStore.get(cntx, 
                IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		IPacket payload = frame.getPayload(); // IP
        if (payload == null)
        	return Command.CONTINUE;
        
        IPacket p2 = payload.getPayload(); // TCP or UDP
        
        if (p2 == null) 
        	return Command.CONTINUE;
        
        IPacket p3 = p2.getPayload(); // Application
        if ((p3 != null) && (p3 instanceof DHCP)) {
        	DHCP packet = (DHCP) p3;
        	try {

        		final MACAddress clientHwAddr = MACAddress.valueOf(packet.getClientHardwareAddress());
        		final OdinClient oc = clientManager.getClients().get(clientHwAddr);
        		
    			// Don't bother if we're not tracking the client
        		if (oc == null) {
        			return Command.CONTINUE;
        		}
        		
        		// Look for the Your-IP field in the DHCP packet
        		if (packet.getYourIPAddress() != 0) {
        			
        			// int -> byte array -> InetAddr
        			final byte[] arr = ByteBuffer.allocate(4).putInt(packet.getYourIPAddress()).array();
        			final InetAddress yourIp = InetAddress.getByAddress(arr);
        			
        			// No need to invoke agent update protocol if the node
        			// is assigned the same IP
        			if (yourIp.equals(oc.getIpAddress())) {
        				return Command.CONTINUE;
        			}
        			
        			log.info("Updating client: " + clientHwAddr + " with ipAddr: " + yourIp);
        			oc.setIpAddress(yourIp);
        			oc.setOFMessageList(lvapManager.getDefaultOFModList(yourIp));
        			
        			// Push flow messages associated with the client
        			try {
        				oc.getOdinAgent().getSwitch().write(oc.getOFMessageList(), null);
        			} catch (IOException e) {
        				log.error("Failed to update switch's flow tables " + oc.getOdinAgent().getSwitch());
        			}
        			oc.getOdinAgent().updateLvap(oc);
        		}
        		
			} catch (UnknownHostException e) {
				// Shouldn't ever happen
				e.printStackTrace();
			}
        }
			
		return Command.CONTINUE;
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return false;
	}
}