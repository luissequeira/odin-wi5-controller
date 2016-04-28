package net.floodlightcontroller.odin.master;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.floodlightcontroller.odin.master.OdinClient;
import net.floodlightcontroller.util.MACAddress;

class ClientManager {
	private final Map<MACAddress, OdinClient> odinClientMap = new ConcurrentHashMap<MACAddress, OdinClient> ();
	private final Map<MACAddress, OdinClient> odinClientMap4G = new ConcurrentHashMap<MACAddress, OdinClient> ();
	
	/**
	 * Add a client to the client tracker
	 * 
	 * @param hwAddress Client's hw address
	 * @param ipv4Address Client's IPv4 address
	 * @param vapBssid Client specific VAP bssid
	 * @param vapEssid Client specific VAP essid
	 */
	protected void addClient (final MACAddress clientHwAddress, final InetAddress ipv4Address, final Lvap lvap) {
		odinClientMap.put(clientHwAddress, new OdinClient (clientHwAddress, ipv4Address, lvap));
	}
	
	
	/**
	 * Add a client to the client tracker
	 * 
	 * @param hwAddress Client's hw address
	 * @param ipv4Address Client's IPv4 address
	 * @param vapBssid Client specific VAP bssid
	 * @param vapEssid Client specific VAP essid
	 */
	protected void addClient (final OdinClient oc) {
		odinClientMap.put(oc.getMacAddress(), oc);
	}
	
	
	/**
	 * Removes a client from the tracker
	 * 
	 * @param hwAddress Client's hw address
	 */
	protected void removeClient (final MACAddress clientHwAddress) {
		odinClientMap.remove(clientHwAddress);
	}
	
	
	/**
	 * Get a client by hw address
	 */
	protected OdinClient getClient (final MACAddress clientHwAddress) {
		return odinClientMap.get(clientHwAddress);
	}
	
	
	/**
	 * Get the client Map from the manager
	 * @return client map
	 */
	protected Map<MACAddress, OdinClient> getClients () {
		return odinClientMap;
	}
	
	/**
	 * Add a client to the client tracker 4G (they will not have much info?).
	 * 
	 * @param OdinClient client that is moved to 4G
	 */
	protected void addClient4G (final OdinClient oc) {
		odinClientMap4G.put(oc.getMacAddress(), oc);
	}
	
	/**
	 * Removes a client from the tracker
	 * 
	 * @param hwAddress Client's hw address
	 */
	protected void removeClient4G (final MACAddress clientHwAddress) {
		odinClientMap4G.remove(clientHwAddress);
	}
	
	
	/**
	 * Get a client by hw address
	 */
	protected OdinClient getClient4G (final MACAddress clientHwAddress) {
		return odinClientMap4G.get(clientHwAddress);
	}
	
	
	/**
	 * Get the client Map from the manager
	 * @return client map
	 */
	protected Map<MACAddress, OdinClient> getClients4G () {
		return odinClientMap4G;
	}
}
