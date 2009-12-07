/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.namespace;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.profiles.nameenum.EnumeratedNameList;
import org.ccnx.ccn.profiles.security.access.AccessControlManager;
import org.ccnx.ccn.profiles.security.access.AccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.ACL;
import org.ccnx.ccn.profiles.security.access.group.GroupAccessControlProfile;
import org.ccnx.ccn.profiles.security.access.group.ACL.ACLObject;
import org.ccnx.ccn.protocol.ContentName;

/**
 *
 **/
public class NamespaceManager {

	protected static Set<AccessControlManager> _acmList = new HashSet<AccessControlManager>(); 

	/**
	 * Used to mark the top level in a namespace under access control
	 * This class currently holds no data - it will be extended to hold access control 
	 * configuration information for that namespace.
	 */
	public static class Root extends GenericXMLEncodable {

		public static class RootObject extends CCNEncodableObject<Root> {
			
			public RootObject(ContentName name, CCNHandle handle) throws IOException {
				super(Root.class, false, name, handle);
			}
			public RootObject(ContentName name, Root r, CCNHandle handle) throws IOException {
				super(Root.class, false, name, r, handle);
			}
			public ContentName namespace() {
				return _baseName.copy(_baseName.count()-2);
			}
		}

		/**
		 * Search up a path (towards the root) for an Access Control root marker
		 * @param name The path to search - search starts at this name.
		 * @param library TODO
		 * @return null if none found, or a RootObject if found.
		 * @throws IOException
		 * @throws ConfigurationException
		 */
		public static RootObject find(ContentName name, CCNHandle handle) throws IOException {
			// scan up the path
			ContentName nextName = name;
			for (;name.count() > 0; nextName = name.parent()) {
				name = nextName;
				// see if a root marker is present
				EnumeratedNameList nameList = EnumeratedNameList.exists(AccessControlProfile.rootName(name), name, handle);

				if (null != nameList) {
					// looks like it is - so fetch the root object
					ContentName rootName = new ContentName(GroupAccessControlProfile.aclName(name),
							nameList.getLatestVersionChildName().lastComponent());
					Log.info("Found latest version of ac ROOT for " + name + " at " + rootName);
					RootObject ro = new RootObject(rootName, handle);
					if (ro.isGone())
						continue;
					return ro;
				}
			}
			Log.info("No ac ROOT found");
			return null;
		}

		/**
		 * Set up a part of the namespace to be under access control.
		 * This method writes the root block and root ACL to a repository.
		 * @param name The top of the namespace to be under access control
		 * @param acl The access control list to be used for the root of the
		 * namespace under access control.
		 * @throws IOException 
		 * @throws ConfigurationException 
		 */
		public static void create(ContentName name, ACL acl, CCNHandle handle) throws IOException, ConfigurationException {
			Root r = new Root();
			RootObject ro = new RootObject(AccessControlProfile.accessRoot(name), r, handle);
			ro.save();
			ACLObject aclo = new ACLObject(GroupAccessControlProfile.aclName(name), acl, handle);
			aclo.save();
		}

		@Override
		public void decode(XMLDecoder decoder) {
		}

		@Override
		public void encode(XMLEncoder encoder) {
		}

		@Override
		public boolean validate() {
			return true;
		}

		@Override
		public String getElementLabel() {
			// TODO Auto-generated method stub
			return null;
		}
	}
	
	/**
	 * Find an ACM object that covers operations on a specific name. 
	 * If none exists in memory then this searches up the name tree 
	 * looking for the root of a namespace under access control,
	 * and if it finds one creates an ACM. If none is found null is returned.
	 * @param name
	 * @param handle
	 * @return null if namespace is not under access control, or an ACM to perform 
	 * 	operations on the name if it is.
	 * @throws IOException
	 * @throws ConfigurationException 
	 */
	public static AccessControlManager findACM(ContentName name, CCNHandle handle)  throws IOException, ConfigurationException {
		// See if we already have an AccessControlManager covering this namespace
		for (AccessControlManager acm : _acmList) {
			if (acm.inProtectedNamespace(name))
				return acm;
		}
		// No ACM exists for this name - now look to see if we can find an ACL down the path to create one...
		Root.RootObject ro = Root.find(name, handle);
		if (ro == null) {
			// No AC root was found, so return without ACM.
			return null;
		}

		AccessControlManager acm = AccessControlManager.createManager(ro, handle);
		_acmList.add(acm);
		return acm;
	}
}
