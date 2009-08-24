package com.parc.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.InterestTable;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

import com.parc.ccn.config.ConfigurationException;

public class CCNLibraryTestHarness extends CCNHandle {
	
	private ConcurrentLinkedQueue<ContentObject> _outputQueue = new ConcurrentLinkedQueue<ContentObject>();
	private InterestTable<CCNFilterListener> _listeners = new InterestTable<CCNFilterListener>();

	public CCNLibraryTestHarness() throws ConfigurationException,
			IOException {
		super(false);
	}
	
	public void reset() {
		_outputQueue.clear();
		_listeners.clear();
	}
	
	public Queue<ContentObject> getOutputQueue() {
		return _outputQueue;
	}
	
	public ContentObject put(ContentObject co) throws IOException {
		_outputQueue.add(co);
		return co;
	}
	
	public void registerFilter(ContentName filter,
			CCNFilterListener callbackListener) {
		_listeners.add(new Interest(filter), callbackListener);
	}
	
	public void unregisterFilter(ContentName filter,
			CCNFilterListener callbackListener) {
		_listeners.remove(new Interest(filter), callbackListener);		
	}
	
	public ContentObject get(Interest interest, long timeout) throws IOException {
		for (CCNFilterListener listener : _listeners.getValues(interest.name())) {
			ArrayList<Interest> al = new ArrayList<Interest>();
			al.add(interest);
			listener.handleInterests(al);
		}
		return _outputQueue.remove();
	}
}
