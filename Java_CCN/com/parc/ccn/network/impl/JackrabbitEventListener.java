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
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;

/**
 * Wraps a CCNQueryListener and links it to Jackrabbit events.
 * @author smetters
 *
 */
class JackrabbitEventListener implements EventListener {

	protected JackrabbitCCNRepository _repository;
	protected CCNQueryListener _listener;
	protected int _events;

	public JackrabbitEventListener(JackrabbitCCNRepository repository,
								   CCNQueryListener l, int events) {
		if (null == repository) 
			throw new IllegalArgumentException("JackrabbitEventListener: repository cannot be null!");
		_repository = repository;
		if (null == l) 
			throw new IllegalArgumentException("JackrabbitEventListener: listener cannot be null!");
		_listener = l;
		_events = events;
	}
	
	JackrabbitCCNRepository repository() { return _repository; }
	
	public int events() { return _events; }
	public CCNQueryListener queryListener() { return _listener; }
	public CCNQueryDescriptor [] queryDescriptors() { return _listener.getQueries(); }
	
	public void onEvent(EventIterator events) {
		
		ArrayList<CompleteName> nodesFound = new ArrayList<CompleteName>();
		
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
							CompleteName cn = repository().getCompleteName(affectedNode);
							
							// Need to filter -- the eventing interface only selects
							// based on name; the listener might have other criteria.
							// This is where we check those.
							if (_listener.matchesQuery(cn)) {
								nodesFound.add(cn);
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
		_listener.handleResults(nodesFound);
	}
}
