package com.parc.ccn.network.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;

import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.observation.Event;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;

import com.parc.ccn.Library;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;

/**
 * Wraps a CCNQueryListener and links it to Jackrabbit events.
 * One JEL per interest, otherwise can't cancel individual
 * interests -- can only remove query listeners, not queries.
 * @author smetters
 *
 */
class JackrabbitEventListener implements EventListener {

	protected JackrabbitCCNRepository _repository;
	protected CCNInterestListener _listener;
	protected int _events;
	protected Interest _interest;
	
	protected ArrayList<String> _nodesAlreadyFound = new ArrayList<String>();

	public JackrabbitEventListener(JackrabbitCCNRepository repository,
								   Interest interest,
								   CCNInterestListener l, 
								   int events) {
		if (null == repository) 
			throw new IllegalArgumentException("JackrabbitEventListener: repository cannot be null!");
		_repository = repository;
		if (null == l) {
			// There is no point to having a null listener here,
			// though there is at the network level.
			Library.logger().info("JackrabbitEventListener: listener is null! We can listen, but we can not tell anybody if we find anything.");
		}
		_listener = l;
		if (null == interest) {
			// We need to know the interest to be able to cancel it later.
			Library.logger().warning("JackrabbitEventListener: interest cannot be null.");
		}
		_interest = interest;
		_events = events;
	}
	
	JackrabbitCCNRepository repository() { return _repository; }
	
	public int events() { return _events; }
	public CCNInterestListener interestListener() { return _listener; }
	public Interest interest() { 
		return _interest;
	}
	
	public void onEvent(EventIterator events) {
		
		if (null == interestListener()) {
			Library.logger().info("JackrabbitEventListener: no CCNQueryListener. Nothing to do.");
			return;
		}

		ArrayList<ContentObject> nodesFound = new ArrayList<ContentObject>();

		while (events.hasNext()) {

			Event event = events.nextEvent();

			// Start by handling all events together
			switch(event.getType()) {
			case Event.NODE_ADDED:
				// there seems to be a bug in jackrabbit that causes the 
				// NODE_ADDED event not to be delivered reliably.
				// We _do_ seem to get, however, a notification that the
				// jcr:primaryType property is being set. This should only
				// happen once (at node creation time),
				// at least in our application, so we'll use that instead
			case Event.PROPERTY_ADDED:
			case Event.PROPERTY_CHANGED:
			case Event.PROPERTY_REMOVED:
				try {
					Node affectedNode;
					try {

						affectedNode = repository().getNode(event.getPath());

						if (!JackrabbitCCNRepository.isCCNNode(affectedNode)) {
							continue;
						}

						CompleteName cn = repository().getCompleteName(affectedNode);

						// Need to filter -- the eventing interface only selects
						// based on name; the listener might have other criteria.
						// This is where we check those.
						if ((null != _listener) &&
							 _listener.matchesInterest(cn)) {
							if (!_nodesAlreadyFound.contains(affectedNode.getPath())) {
							repository();
								//	Library.logger().info("Listener found new CCN Node: " + cn.name());
								ContentObject co = 
									new ContentObject(cn.name(), cn.authenticator(), 
											JackrabbitCCNRepository.getContent(affectedNode), cn.signature());
								nodesFound.add(co); // nodes found in response to this query
								_nodesAlreadyFound.add(affectedNode.getPath());
							}
						} else {
							//Library.logger().info("New node: " + cn.name() + " does not match listener query.");
						}

					} catch (PathNotFoundException e) {
						Library.logger().warning("Cannot find node corresponding to generated event at path: " + event.getPath());
						Library.logStackTrace(Level.WARNING, e);
						continue;
					} catch (RepositoryException e) {
						Library.logger().warning("Error retrieving node corresponding to generated event at path: " + event.getPath());
						Library.logStackTrace(Level.WARNING, e);
						continue;
					} catch (IOException e) {
						Library.logger().warning("Error retrieving content object information corresponding to generated event at path: " + event.getPath());
						Library.logStackTrace(Level.WARNING, e);
						continue;					
					}
				} catch (RepositoryException e) {
					Library.logger().warning("Error: can't even retrieve path associated with event " + event);
					Library.logStackTrace(Level.WARNING, e);
					continue;
				}
			}
		}
	
		if (null != _listener)
			_listener.handleContent(nodesFound);
	}
}
