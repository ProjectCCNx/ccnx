package com.parc.ccn.network;

import java.io.IOException;
import java.util.ArrayList;

import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.ContentAuthenticator;

/**
 * Interface to the lowest CCN levels. Eventually will be
 * just the interface to ccnd, but right now also talks
 * directly to the repository, as we don't know how to
 * talk to the repository via ccnd yet. Most clients,
 * and the CCN library, will use this as the "CCN".
 * 
 * TODO DKS sort out interfaces -- CCNRepository is right now
 * the slight extension of CCNBase to add enumeration functionality.
 * Once we've figured out where that is to go, we can change what
 * this implements.
 * @author smetters
 *
 */
public class CCNNetworkManager implements CCNRepository {
	
	/**
	 * Static singleton.
	 */
	protected static CCNNetworkManager _networkManager = null;
	
		
	public static CCNNetworkManager getNetworkManager() { 
		if (null != _networkManager) 
			return _networkManager;
		
		return createNetworkManager();
	}
	
	protected static synchronized CCNNetworkManager 
				createNetworkManager() {
		if (null == _networkManager) {
			_networkManager = new CCNNetworkManager();
			// Might need to add a thread to handle reexpressing interests...
			//_networkManager.start();
		}
		return _networkManager;
	}
	
	protected CCNNetworkManager() {
	}
	
	/**
	 * Puts we put only to our local repository. Right now we deal with
	 * a repository manager, as we need a persistence protocol.
	 * TODO persistence protocol
	 */
	public CompleteName put(ContentName name, ContentAuthenticator authenticator, 
							byte [] signature, byte[] content) throws IOException {
		return CCNRepositoryManager.getRepositoryManager().put(name, authenticator, signature, content);
	}

	/**
	 * Gets we also currently forward to the repository.
	 */
	public ArrayList<ContentObject> get(ContentName name, 
									    ContentAuthenticator authenticator,
									    boolean isRecursive) throws IOException {
		return CCNRepositoryManager.getRepositoryManager().get(name, authenticator, isRecursive);
	}

	/**
	 * We express interests to both the repository and the ccnd. Eventually,
	 * we won't express interests to the repository anymore; we do this currently
	 * so that we get notified about what other local applications put into
	 * the repository (which don't go through the ccnd).
	 * The repository will handle notifying the listener for hits it sees;
	 * we need to notify the listener for hits coming across the network.
	 */
	public void expressInterest(
			Interest interest,
			CCNInterestListener callbackListener) throws IOException {
	
		CCNRepositoryManager.getRepositoryManager().expressInterest(interest, callbackListener);

		// Register the interest for repeated presentation to the network.
		registerInterest(interest, callbackListener);
	}
	
	/**
	 * Cancel this query with all the repositories we sent
	 * it to.
	 */
	public void cancelInterest(Interest interest, CCNInterestListener callbackListener) throws IOException {
		
		CCNRepositoryManager.getRepositoryManager().cancelInterest(interest, callbackListener);
	
		// Remove interest from repeated presentation to the network.
		unregisterInterest(interest, callbackListener);
	}

	public ArrayList<CompleteName> enumerate(Interest interest) throws IOException {
		ArrayList<CompleteName> results = 
			CCNRepositoryManager.getRepositoryManager().enumerate(interest);
		return results;
	}

	public ArrayList<CompleteName> getChildren(CompleteName name) throws IOException {
		ArrayList<CompleteName> results = 
			CCNRepositoryManager.getRepositoryManager().getChildren(name);
		return results;
	}
	
	/**
	 * Pass things on to the network stack.
	 */
	void registerInterest(Interest interest, CCNInterestListener callbackListener) {
		// TODO
		throw new UnsupportedOperationException("implement me!");
	}
	
	void unregisterInterest(Interest interest, CCNInterestListener callbackListener) {
		// TODO
		throw new UnsupportedOperationException("implement me!");
	}
}
