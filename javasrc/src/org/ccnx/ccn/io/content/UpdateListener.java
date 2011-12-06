/*
 * Part of the CCNx Java Library.
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
package org.ccnx.ccn.io.content;

/**
 * Interface that allows external listeners to register to be notified when a network
 * object receives an update.
 */
public interface UpdateListener {
	
	/**
	 * Notification when a new version is available of a given object (the object's data and
	 * version information will already have been updated to reflect the new version).
	 * @param newVersion The newly updated object.
	 * @param wasSave If true, someone called save() on this particular object, if false,
	 *   the object received new data from the network.
	 */
	public void newVersionAvailable(CCNNetworkObject<?> newVersion, boolean wasSave);

}
