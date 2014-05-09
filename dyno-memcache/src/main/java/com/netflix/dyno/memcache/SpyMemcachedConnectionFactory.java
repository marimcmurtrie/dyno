package com.netflix.dyno.memcache;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.spy.memcached.DefaultConnectionFactory;
import net.spy.memcached.KetamaNodeLocator;
import net.spy.memcached.MemcachedNode;
import net.spy.memcached.NodeLocator;

import com.netflix.dyno.connectionpool.ConnectionPoolConfiguration;
import com.netflix.dyno.connectionpool.ConnectionPoolMonitor;
import com.netflix.dyno.connectionpool.Host;
import com.netflix.dyno.connectionpool.impl.CircularList;

/**
 * This class encapsulates a custom {@link SpyMemcachedRRLocator} for our custom local zone aware round robin load balancing
 * with RR lb over the remote zone for fallback cases. 
 * 
 * @author poberai
 */
public class SpyMemcachedConnectionFactory extends DefaultConnectionFactory {
	
	private final String localDC; 
	private final InnerState innerState;
	private final ConnectionPoolConfiguration cpConfig;
	private final ConnectionPoolMonitor cpMonitor;

	/**
	 * Constructor
	 * @param hosts
	 * @param config
	 * @param monitor
	 */
	public SpyMemcachedConnectionFactory(List<Host> hosts, ConnectionPoolConfiguration config, ConnectionPoolMonitor monitor) {
		super();
		this.localDC = System.getenv("EC2_AVAILABILITY_ZONE");
		this.innerState = new InnerState(hosts);
		this.cpConfig = config;
		this.cpMonitor = monitor;
	}

	/**
	 * returns a instance of {@link KetamaNodeLocator}.
	 */
	public NodeLocator createLocator(List<MemcachedNode> list) {
		innerState.updateMemcachedNodes(list);
		return new SpyMemcachedRRLocator();
	}

	/**
	 * Impl of {@link NodeLocator} for custom local zone aware RR load balancing
	 * @author poberai
	 *
	 */
	private class SpyMemcachedRRLocator implements NodeLocator {

		@Override
		public MemcachedNode getPrimary(String k) {
			
			MemcachedNode node = null; 
			try {
				node = innerState.localZoneMCNodes.getNextElement();
				return node;
			} finally {
			
				// record stats for this host
				if (node != null) {
					Host host = innerState.saToHostMap.get(node.getSocketAddress());
					if (host != null) {
						cpMonitor.incConnectionBorrowed(host, -1);
					}
				}
			}
		}

		@Override
		public Iterator<MemcachedNode> getSequence(String k) {
			
			System.out.println("\nGet sequence called! " + innerState.remoteZoneMCNodes.getEntireList() + "\n");
			StackTraceElement[] elements = Thread.currentThread().getStackTrace();
			for (StackTraceElement el : elements) {
				System.out.println(el.toString());
			}

			final CircularList<MemcachedNode> cList = innerState.remoteZoneMCNodes;
			final int size = cList.getEntireList().size();

			return new Iterator<MemcachedNode>() {

				int count = size;

				@Override
				public boolean hasNext() {
					return count > 0;
				}

				@Override
				public MemcachedNode next() {
					count--;
					MemcachedNode node = null;
					try {
						node = cList.getNextElement();
						return node;
						
					} finally {
						// record this for stats
						if (node != null) {
							Host host = innerState.saToHostMap.get(node.getSocketAddress());
							if (host != null) {
								cpMonitor.incFailover(host, null);
							}
						}
					}
				}

				@Override
				public void remove() {
					throw new RuntimeException("Not implemented");
				}
			};
		}

		@Override
		public Collection<MemcachedNode> getAll() {
			return innerState.saToMCNodeMap.values();
		}

		@Override
		public NodeLocator getReadonlyCopy() {
			return this;
		}

		@Override
		public void updateLocator(List<MemcachedNode> nodes) {
			// do nothing
		}

	}

	/**
	 * Inner state tracking the local zone nodes in a circular list for RR load balancing. 
	 * It also tracks the remote zone nodes to fall back to during problems. 
	 * It also maintains a mapping of {@link SocketAddress} for all the {@link MemcachedNode}s
	 * This helps us connect {@link MemcachedNode}s to {@link Host}s and we can then track metrics with the 
	 * {@link ConnectionPoolMonitor} for each {@link Host} when we route requests to each {@link MemcachedNode}
	 *
	 * @author poberai
	 *
	 */
	private class InnerState { 

		// Used to lookup the primary node for an operation
		private final CircularList<MemcachedNode> localZoneMCNodes = new CircularList<MemcachedNode>(null);
		// used to lookup the backup nodes
		private final CircularList<MemcachedNode> remoteZoneMCNodes = new CircularList<MemcachedNode>(null);

		// Maps to go from Host -> Node and vice versa
		private final ConcurrentHashMap<SocketAddress, MemcachedNode> saToMCNodeMap = new ConcurrentHashMap<SocketAddress, MemcachedNode>();
		private final ConcurrentHashMap<SocketAddress, Host> saToHostMap = new ConcurrentHashMap<SocketAddress, Host>();

		private InnerState() {
		}

		private InnerState(Collection<Host> hosts) {
			this.updateHosts(hosts);
		}

		private void updateHosts(Collection<Host> hosts) {
			for (Host host : hosts) {
				saToHostMap.put(host.getSocketAddress(), host);
			}
		}

		private void updateMemcachedNodes(Collection<MemcachedNode> nodes) {

			List<MemcachedNode> localMCNodes = new ArrayList<MemcachedNode>();
			List<MemcachedNode> remoteMCNodes = new ArrayList<MemcachedNode>();
			
			for (MemcachedNode node : nodes) {

				saToMCNodeMap.put(node.getSocketAddress(), node);

				Host host = saToHostMap.get(node.getSocketAddress());
				if (host != null) {
					if (cpConfig.localDcAffinity()) {
						if (host.getDC().equalsIgnoreCase(localDC)) {
							localMCNodes.add(node);
						} else {
							// This is a remote zone host
							remoteMCNodes.add(node);
						}
					} else { 
						localMCNodes.add(node);
						remoteMCNodes.add(node);
					}
 				}
			}

			// Update the circular iterator with remote nodes
			localZoneMCNodes.swapWithList(localMCNodes);
			remoteZoneMCNodes.swapWithList(remoteMCNodes);
		}
	}
}