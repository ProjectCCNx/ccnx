package com.parc.ccn.network;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.logging.Level;

import javax.jmdns.ServiceInfo;

import com.parc.ccn.Library;
import com.parc.ccn.data.CCNBase;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener.CCNQueryType;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.network.discovery.CCNDiscovery;
import com.parc.ccn.network.discovery.CCNDiscoveryListener;
import com.parc.ccn.network.impl.JackrabbitCCNRepository;

/**
 * Keep track of all the repositories we know about,
 * route queries and handle synchronization. Have
 * one primary repository for puts. Most clients,
 * and the CCN library, will use this as the "CCN".
 * @author smetters
 *
 */
public class RepositoryManager implements CCNBase, CCNDiscoveryListener {
	
	/**
	 * Static singleton.
	 */
	protected static RepositoryManager _repositoryManager = null;
	
	public static RepositoryManager getCCNRepositoryManager() { 
		if (null != _repositoryManager) 
			return _repositoryManager;
		
		return createRepositoryManager();
	}
	
	protected static synchronized RepositoryManager createRepositoryManager() {
		if (null == _repositoryManager) {
			_repositoryManager = new RepositoryManager();
			// Might need to add a thread to handle discovery responses...
			_repositoryManager.start();
		}
		return _repositoryManager;
	}

	/**
	 * We have one local repository that we create/open
	 * and put data to, also put synchronization data into.
	 * We have many other repositories that we forward
	 * queries to and handle responses from. Right now
	 * we only do one-hop forwarding -- we ask repositories
	 * we discover the queries coming from our user, and
	 * respond to their queries using our local primary
	 * repository. We don't currently query our other
	 * local repositories because of legacy security issues
	 * (e.g. they could be our raw filesystem or email), 
	 * or the repositories we've discovered, as that has
	 * routing issues we don't want to get into yet (but
	 * it would be easy to go there to play with routing).
	 * Still have interesting local security issues as
	 * we mirror stuff from local read-only repositories
	 * to the rw repository.
	 */
	protected CCNRepository _localRepository = null;
	protected ArrayList<CCNRepository> _repositories = new ArrayList<CCNRepository>();
	
	/**
	 * Outstanding queries. If we find a new repository, give
	 * them the outstanding queries too.
	 */
	protected ArrayList<ManagedCCNQueryDescriptor> _outstandingQueries = new ArrayList<ManagedCCNQueryDescriptor>();
	
	/**
	 * Default constructor to make static singleton.
	 * Start with fixed configuration, then worry about
	 * getting fancy...
	 */
	RepositoryManager() {
		// Make our local repository. Start listening
		// for others.
		_localRepository = new JackrabbitCCNRepository();
	}
	
	void start() {
		// Find other repositories in the area.
		CCNDiscovery.findServers(this);
	}
	
	/**
	 * Handle requests from clients.
	 */
	public void cancel(CCNQueryDescriptor query) throws IOException {
		// This is either one of our managed query descriptors,
		// or a sub-descriptor that maps back to one.
		// If not, there is nothing we can do.
		
	}

	/**
	 * Gets we send to everybody. Once query descriptors
	 * contain identifiers to allow cancellation, we need
	 * a way of amalgamating all the identifier information
	 * into one query descriptor.
	 */
	public CCNQueryDescriptor get(ContentName name, ContentAuthenticator authenticator, CCNQueryType type, CCNQueryListener listener, long TTL) throws IOException {
		// Should check to see if we have this query alredy outstanding?
		
		CCNQueryDescriptor initialDescriptor = _localRepository.get(name, authenticator, type, listener, TTL);
		
		ManagedCCNQueryDescriptor managedDescriptor = 
				new ManagedCCNQueryDescriptor(initialDescriptor, listener);

		for (CCNRepository repository : _repositories) {
			if (!_localRepository.equals(repository)) {
				CCNQueryDescriptor newDescriptor = 
					repository.get(managedDescriptor.name(), managedDescriptor.authenticator(), managedDescriptor.type(), listener, managedDescriptor.TTL());
				managedDescriptor.addIdentifier(newDescriptor.queryIdentifier());
			}
		}
		_outstandingQueries.add(managedDescriptor);
		return managedDescriptor;
	}

	/**
	 * Puts we put only to our local repository. 
	 */
	public void put(ContentName name, ContentAuthenticator authenticator, byte[] content) throws IOException {
		_localRepository.put(name, authenticator, content);
	}

	/**
	 * Are there differences between observer and query
	 * interfaces? Maybe one returns everything and one
	 * just tells you what names have changed...
	 * @throws IOException
	 */
	public void resubscribeAll() throws IOException {
		_localRepository.resubscribeAll();
		for (CCNRepository repository : _repositories) {
			if (!repository.equals(_localRepository))
				repository.resubscribeAll();
		}
	}
	
	/**
	 * Handle new repositories appearing and old ones
	 * going away.
	 */
	public void serviceRemoved(ServiceInfo info, boolean isLocal) {
		// If a repository has disappeared, just remove it
		// from our list. We can assume that it's no longer 
		// available for us to cancel queries to it.
	}

	public void serviceAdded(ServiceInfo info, boolean isLocal) {
		// We've found a new repository. Does it match
		// any of the ones we currently have? If not,
		// remember it, and forward to it all our outstanding
		// queries.
		
		// Should we skip making a backend if this is local?
		
		// Want to find out before we make a repository 
		// for this one.
		for (CCNRepository repository : _repositories) {
			if (repository.equals(info)) {
				Library.logger().info("Found a repository we alreday know about: " + info.getURL());
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
}
