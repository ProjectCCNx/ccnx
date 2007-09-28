package com.parc.ccn.network;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.logging.Level;

import javax.jmdns.ServiceInfo;

import com.parc.ccn.Library;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.network.discovery.CCNDiscovery;
import com.parc.ccn.network.discovery.CCNDiscoveryListener;

public class DiscoveryManager implements CCNDiscoveryListener {

	/**
	 * Other repositories we know about to talk to.
	 */
	protected ArrayList<CCNRepository> _repositories = new ArrayList<CCNRepository>();
	protected boolean _wantLocal;
	protected boolean _wantRemote;
	
	/**
	 * Do we want local repositories, remote repositories or both?
	 * @param wantLocal
	 */
	protected DiscoveryManager(boolean wantLocal, boolean wantRemote) {
		super();
		_wantLocal = wantLocal;
		_wantRemote = wantRemote;
	}

	/**
	 * Handle new repositories appearing and old ones
	 * going away.
	 */
	public void serviceRemoved(ServiceInfo info, boolean isLocal) {
		// If a repository has disappeared, just remove it
		// from our list. We can assume that it's no longer 
		// available for us to cancel queries to it.
		for (CCNRepository repository : _repositories) {
			if (repository.equals(info)) {
				Library.logger().info("Removing a repository we know about: " + info.getURL());
				// we know this one.
				_repositories.remove(info);
				return;
			}
		}
	}

	public void serviceAdded(ServiceInfo info, boolean isLocal) {
		// We've found a new repository. Does it match
		// any of the ones we currently have? If not,
		// remember it, and forward to it all our outstanding
		// queries.
		
		// Should we skip making a backend if this is local?
		// yes.
		if (!_wantLocal && isLocal)
			return;
		
		// Should we skip if it is remote?
		if (!_wantRemote && !isLocal)
			return;
		
		// Want to find out before we make a repository 
		// for this one.
		for (CCNRepository repository : _repositories) {
			if (repository.equals(info)) {
				Library.logger().info("Found a repository we already know about: " + info.getURL());
				// we know this one.
				return;
			}
		}
		Library.logger().info("Found a new repository: " + info.getURL());
		try {
			CCNRepository newRepository = RepositoryFactory.connect(info);
	
			// Add this repository to our list.
			_repositories.add(newRepository); // DKS -- synchronize?
			
			// Forward all our outstanding queries to it.
			for (ManagedCCNQueryDescriptor mqd : _outstandingQueries) {
				CCNQueryDescriptor newDescriptor;
				try {
					newDescriptor = newRepository.get(mqd.name(), mqd.authenticator(), mqd.type(), mqd.listener(), mqd.TTL());
					mqd.addIdentifier(newDescriptor.queryIdentifier());
				} catch (IOException e) {
					Library.logger().info("Cannot forward query " + mqd + " to new repository: " + info.getURL());
					// DKS -- do something more draconian?
					continue;
				}
			}
		
		} catch (MalformedURLException e) {
			Library.logger().warning("Cannot instantiate connection to that repository!");
			Library.logStackTrace(Level.WARNING, e);
		} 
	}

	/**
	 * We want to know about remote CCN repositories we
	 * can sync to. This could be replaced by simply
	 * testing that a node connection agent exists and
	 * is around.
	 *
	 */
	protected void start() {
		// Find other repositories in the area.
		CCNDiscovery.findServers(this);
	}
}