/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.security.access;

import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.namespace.NamespaceProfile;
import org.ccnx.ccn.protocol.Component;
import org.ccnx.ccn.protocol.ContentName;

/**
 * This is a Profile defining basic naming and data standards to apply access control on a particular
 * subtree and all the data contained below it. It is intended to be generic, and specialized by further
 * protocols (e.g. GroupAccessControlProfile), that would definte particular access control schemes.
 * It focuses primarily on the definition of names for namespace control data, policy data, and keys.
 * For descriptions of data, and how this access control system functions, see the separate CCNx Access
 * Control Specifications Document.
 * 
 * It is not clear that the division between "generic" and "specific" is yet correct; what we want to do
 * is define a small set of common features that need to be agreed on by all access control (by encryption)
 * schemes that want to participate in this framework, and put all and only those common elements here.
 * This is a first or 2nd cut; future iterations may move elements in or out.
 *
 */
public class AccessControlProfile implements CCNProfile {

	public static final CommandMarker ACCESS_CONTROL_MARKER = 
		CommandMarker.commandMarker(CommandMarker.MARKER_NAMESPACE, "ACCESS");
	public static final byte [] ACCESS_CONTROL_MARKER_BYTES = ACCESS_CONTROL_MARKER.getBytes();

	public static final String ROOT_NAME = "ROOT";
	public static final byte [] ROOT_NAME_BYTES = Component.parseNative(ROOT_NAME);
	public static final String DATA_KEY_NAME = "DK";
	public static final byte [] DATA_KEY_NAME_BYTES = Component.parseNative(DATA_KEY_NAME);
	public static final Component ACCESS_CONTROL_POLICY_NAME = new Component("AccessControl");

	protected static final ContentName ROOT_POSTFIX_NAME = new ContentName(ACCESS_CONTROL_MARKER_BYTES, ROOT_NAME_BYTES);

	/**
	 * Returns whether the specified name contains the access control marker
	 * @param name the name
	 * @return
	 */
	public static boolean isAccessName(ContentName name) {
		return name.contains(ACCESS_CONTROL_MARKER_BYTES);
	}

	/**
	 * Truncates the specified name at the access control marker
	 * @param name the name
	 * @return the truncated name
	 */
	public static ContentName accessRoot(ContentName name) {
		return name.cut(ACCESS_CONTROL_MARKER_BYTES);
	}

	/**
	 * Return the name of the root access control policy information object,
	 * if it is stored at node nodeName.
	 **/
	public static ContentName rootName(ContentName nodeName) {
		ContentName baseName = (isAccessName(nodeName) ? accessRoot(nodeName) : nodeName);
		ContentName aclRootName = baseName.append(rootPostfix());
		return aclRootName;
	}
	
	/**
	 * Return the set of name components to add to an access root to get the root name.
	 */
	public static ContentName rootPostfix() {
		return ROOT_POSTFIX_NAME;
	}

	/**
	 * Get the name of the data key for a given content node.
	 * This is nodeName/_access_/DK.
	 * @param nodeName the name of the content node
	 * @return the name of the corresponding data key
	 */
	public static ContentName dataKeyName(ContentName nodeName) {
		ContentName baseName = accessRoot(nodeName);
		ContentName dataKeyName = new ContentName(baseName, ACCESS_CONTROL_MARKER_BYTES, DATA_KEY_NAME_BYTES);
		return dataKeyName;
	}

	/**
	 * Returns whether the specified name is a data key name
	 * @param name the name
	 * @return
	 */
	public static boolean isDataKeyName(ContentName name) {
		if (!isAccessName(name) || VersioningProfile.hasTerminalVersion(name)) {
			return false;
		}
		int versionComponent = VersioningProfile.findLastVersionComponent(name);
		if (name.stringComponent(versionComponent - 1).equals(DATA_KEY_NAME)) {
			return true;
		}
		return false;
	}
	
	/**
	 * Return the name of the access control policy under a known policy prefix.
	 * @param policyPrefix
	 * @return
	 */
	public static final ContentName getAccessControlPolicyName(ContentName policyPrefix) {
		return new ContentName(policyPrefix, ACCESS_CONTROL_POLICY_NAME);
	}

	/**
	 * Returns whether the specified name contains the access control policy marker
	 * @param name the name
	 * @return
	 */
	public static boolean isAccessControlPolicyName(ContentName name) {
		return name.contains(ACCESS_CONTROL_POLICY_NAME);
	}
	
	/**
	 * Get the policy marker name for a given namespace to access control.
	 */
	public static ContentName getAccessControlPolicyMarkerName(ContentName accessControlNamespace) {
		ContentName policyPrefix = NamespaceProfile.policyNamespace(accessControlNamespace);
		ContentName policyMarkerName = AccessControlProfile.getAccessControlPolicyName(policyPrefix);
		return policyMarkerName;
	}
	
}
