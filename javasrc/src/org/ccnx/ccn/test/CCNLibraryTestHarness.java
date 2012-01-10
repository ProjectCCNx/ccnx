/*
 * A CCNx library test.
 *
 * Copyright (C) 2008, 2009, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation. 
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */

package org.ccnx.ccn.test;

import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.InterestTable;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

/**
 * An enhanced CCNHandle used for logging/tracking during tests.
 */
public class CCNLibraryTestHarness extends CCNHandle {
	
	private ConcurrentLinkedQueue<ContentObject> _outputQueue = new ConcurrentLinkedQueue<ContentObject>();
	private InterestTable<CCNInterestHandler> _handlers = new InterestTable<CCNInterestHandler>();

	public CCNLibraryTestHarness() throws ConfigurationException,
			IOException {
		super(false);
	}
	
	public void reset() {
		_outputQueue.clear();
		_handlers.clear();
	}
	
	public Queue<ContentObject> getOutputQueue() {
		return _outputQueue;
	}
	
	@Override
	public ContentObject put(ContentObject co) throws IOException {
		_outputQueue.add(co);
		return co;
	}
	
	@Override
	public void registerFilter(ContentName filter,
			CCNInterestHandler handler) {
		_handlers.add(new Interest(filter), handler);
	}
	
	@Override
	public void unregisterFilter(ContentName filter,
			CCNInterestHandler handler) {
		_handlers.remove(new Interest(filter), handler);		
	}
	
	@Override
	public ContentObject get(Interest interest, long timeout) throws IOException {
		for (CCNInterestHandler handler : _handlers.getValues(interest.name())) {
			handler.handleInterest(interest);
		}
		return _outputQueue.remove();
	}
	
	@Override
	public ContentObject get(ContentName name, long timeout) throws IOException {
		Interest interest = new Interest(name);
		return get(interest, timeout);
	}
}
