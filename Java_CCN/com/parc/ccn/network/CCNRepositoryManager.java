package com.parc.ccn.network;

import java.io.IOException;
import java.util.ArrayList;

import javax.jmdns.ServiceInfo;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.security.ContentAuthenticator;
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
public class CCNRepositoryManager extends DiscoveryManager implements CCNBase, CCNDiscoveryListener {
	
	/**
	 * Static singleton.
	 */
	protected static CCNRepositoryManager _repositoryManager = null;
	
	/**
	 * Other local repositories we know about to talk to.
	 */
	protected ArrayList<CCNRepository> _repositories = new ArrayList<CCNRepository>();

	public static CCNRepositoryManager getRepositoryManager() { 
		if (null != _repositoryManager) 
			return _repositoryManager;
		
		return createRepositoryManager();
	}
	
	protected static synchronized CCNRepositoryManager createRepositoryManager() {
		if (null == _repositoryManager) {
			_repositoryManager = new CCNRepositoryManager();
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
	 * respond to their queries using our primary
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
	protected CCNRepository _primaryRepository = null;
	
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
	protected CCNRepositoryManager() {
		super(true, false);
		// Make/get our local repository. Start listening
		// for others.
		// TODO DKS -- eventually make this configurable
		_primaryRepository = JackrabbitCCNRepository.getLocalJackrabbitRepository();
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
	 * Gets we send to all the repositories we manage. 
	 * Once query descriptors
	 * contain identifiers to allow cancellation, we need
	 * a way of amalgamating all the identifier information
	 * into one query descriptor.
	 */
	public CCNQueryDescriptor get(ContentName name, ContentAuthenticator authenticator, CCNQueryListener listener, long TTL) throws IOException {
		// TODO DKS: Should check to see if we have this query alredy outstanding?
		// TODO DKS: Check to see how query descriptors line up with interests
		CCNQueryDescriptor initialDescriptor = _primaryRepository.get(name, authenticator, listener, TTL);
		
		ManagedCCNQueryDescriptor managedDescriptor = 
				new ManagedCCNQueryDescriptor(initialDescriptor, listener);

		// TODO DKS change to propagate by interest manager for remote
		for (CCNRepository repository : _repositories) {
			if (!_primaryRepository.equals(repository)) {
				CCNQueryDescriptor newDescriptor = 
					repository.expressInterest(managedDescriptor.name().name(), managedDescriptor.name().authenticator(), listener, managedDescriptor.TTL());
				managedDescriptor.addIdentifier(newDescriptor.queryIdentifier());
			}
		}
		_outstandingQueries.add(managedDescriptor);
		return managedDescriptor;
	}

	/**
	 * Puts we put only to our local repository. 
	 */
	public CompleteName put(ContentName name, ContentAuthenticator authenticator, byte[] content) throws IOException {
		return _primaryRepository.put(name, authenticator, content);
	}

	/**
	 * Are there differences between observer and query
	 * interfaces? Maybe one returns everything and one
	 * just tells you what names have changed...
	 * @throws IOException
	 */
	public void resubscribeAll() throws IOException {
		// TODO DKS do we want the RepositoryManager to remember queries? if so, 
		// how? what does resubscribeAll do?
		_primaryRepository.resubscribeAll();
		for (CCNRepository repository : _repositories) {
			if (!repository.equals(_primaryRepository))
				repository.resubscribeAll();
		}
	}

	protected void repositoryAdded(CCNRepository newRepository) {
		// Forward all our outstanding queries to it.
		for (ManagedCCNQueryDescriptor mqd : _outstandingQueries) {
			CCNQueryDescriptor newDescriptor;
			try {
				newDescriptor = newRepository.get(mqd.name(), mqd.authenticator(), mqd.listener(), mqd.TTL());
				mqd.addIdentifier(newDescriptor.queryIdentifier());
			} catch (IOException e) {
				Library.logger().info("Cannot forward query " + mqd + " to new repository: " + newRepository.info().getURL());
				// DKS -- do something more draconian?
				continue;
			}
		}		
	}
	
	protected void repositoryRemoved(ServiceInfo repositoryInfo) {
		if (_primaryRepository.equals(repositoryInfo)) {
			Library.logger().warning("Lost primary repository. Replacing.");
			_primaryRepository = JackrabbitCCNRepository.getLocalJackrabbitRepository();
		}		
	}

	public ArrayList<ContentObject> get(ContentName name, ContentAuthenticator authenticator, CCNQueryType type) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	public ArrayList<ContentObject> get(ContentName name, ContentAuthenticator authenticator) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}
}
