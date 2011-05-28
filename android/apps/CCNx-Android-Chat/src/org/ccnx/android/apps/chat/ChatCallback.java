/*
 * CCNx Android Chat
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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

package org.ccnx.android.apps.chat;


/**
 * This provides feedback from the ChatWorker to the ChatScreen.
 */
public interface ChatCallback {
	/**
	 * A chat message from the network to display on screen
	 */
	public void recv(String message);

	/**
	 * @param ok true -> startup of CCNx services succeeded, false -> network failure
	 */
	public void ccnxServices(boolean ok);
}
