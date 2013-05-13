/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2012, 2013 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.ccnx.ccn.config.SystemConfiguration;

/**
 * Asynchronously process data from a ContentHandler in cases in which there may be a
 * processing delay in order to allow the netmanager thread to continue to fetch data.
 * A new processing thread is started only when necessary.
 */
public abstract class QueuedContentHandler<E> implements Runnable {
	protected Queue<E> _queue = new ConcurrentLinkedQueue<E>();
	protected boolean _isRunning = false;

	/**
	 * Add a content object to the queue for processing. If we aren't running a processing
	 * thread right now, start one.
	 *
	 * @param ci encapsulated data from a content handler
	 */
	public void add(E e) {
		_queue.add(e);
		if (!_isRunning) {
			_isRunning = true;
			SystemConfiguration._systemThreadpool.execute(this);
		}
	}

	/**
	 * Asynchronously dequeue and process data from a ContentHandler
	 */
	public void run() {
		while (!checkShutdown()) {
			E e = null;
			e = _queue.poll();
			if (null == e) {
				_isRunning = false;
				return;
			}
			process(e);
		}
	}

	/**
	 * Override for different behavior
	 * @return
	 */
	protected boolean checkShutdown() {
		return false;
	}

	/**
	 * Process the data from a ContentHandler asynchronously
	 *
	 * @param co  - the ContentObject
	 * @param interest - the Interest
	 */
	protected abstract void process(E e);
}
