package com.parc.ccn.library.profiles;

import java.io.IOException;
import java.sql.Timestamp;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.util.DataUtils;
import com.sun.tools.javac.util.Pair;

public class AccessControlProfile implements CCNProfile {
	
	/**
	 * We need to specify how a number of things are named:
	 * - users, and their keys
	 * - groups, and their keys
	 * - access control lists
	 * - node keys, and their encryption under ACL member keys
	 * - if used, markers indicating where to find ACLs/node keys
	 */
	
	// Is it better to use .access or _access_? The former might be used by "real" data more often...
	public static final String ACCESS_CONTROL_MARKER = CCNProfile.MARKER + "access" + CCNProfile.MARKER;
	public static final byte [] ACCESS_CONTROL_MARKER_BYTES = ContentName.componentParseNative(ACCESS_CONTROL_MARKER);
	
	// These may eventually want to move somewhere more general
	public static final String GROUP_PREFIX = "Groups";
	public static final byte [] GROUP_PREFIX_BYTES = ContentName.componentParseNative(GROUP_PREFIX);
	public static final String USER_PREFIX = "Users";
	public static final byte [] USER_PREFIX_BYTES = ContentName.componentParseNative(USER_PREFIX);
	
	public static final String GROUP_PUBLIC_KEY_NAME = "Key";
	public static final String GROUP_PRIVATE_KEY_NAME = "PrivateKey";
	public static final String GROUP_MEMBERSHIP_LIST_NAME = "MembershipList";
	public static final String PREVIOUS_KEY_NAME = "PreviousKey";
	public static final String ACL_NAME = "ACL";
	public static final byte [] ACL_NAME_BYTES = ContentName.componentParseNative(ACL_NAME);
	public static final String NODE_KEY_NAME = "NK";
	public static final byte [] NODE_KEY_NAME_BYTES = ContentName.componentParseNative(NODE_KEY_NAME);	
	public static final String DATA_KEY_NAME = "DK";
	public static final byte [] DATA_KEY_NAME_BYTES = ContentName.componentParseNative(DATA_KEY_NAME);

	// Needs to be something not in base64 charset.
	public static final String COMPONENT_SEPARATOR_STRING = ":";
	public static final byte [] COMPONENT_SEPARATOR = ContentName.componentParseNative(COMPONENT_SEPARATOR_STRING);
	public static final byte [] WRAPPING_KEY_PREFIX = ContentName.componentParseNative("keyid" + COMPONENT_SEPARATOR_STRING);
	public static final byte [] PRINCIPAL_PREFIX = ContentName.componentParseNative("p" + COMPONENT_SEPARATOR_STRING);

	public static final String SUPERSEDED_MARKER = "Superseded";
	
	
	public static boolean isAccessName(ContentName name) {
		return name.contains(ACCESS_CONTROL_MARKER_BYTES);
	}
	
	public static ContentName accessRoot(ContentName name) {
		return name.cut(ACCESS_CONTROL_MARKER_BYTES);
	}
	
	public static boolean isNodeKeyName(ContentName name) {
		if (!isAccessName(name) || VersioningProfile.isVersioned(name)) {
			return false;
		}
		ContentName nkName = VersioningProfile.versionRoot(name);
		if (nkName.stringComponent(nkName.count()-1).equals(NODE_KEY_NAME)) {
			return true;
		}
		return false;
	}
	
	/**
	 * Get the name of the node key for a given content node, if there is one.
	 * This is <nodeName>/_access_/NK, with a version then added for a specific node key.
	 * @param nodeName
	 * @return
	 */
	public static ContentName nodeKeyName(ContentName nodeName) {
		ContentName rootName = accessRoot(nodeName);
		ContentName nodeKeyName = new ContentName(rootName, ACCESS_CONTROL_MARKER_BYTES, NODE_KEY_NAME_BYTES);
		return nodeKeyName;
	}
	
	public static ContentName aclName(ContentName nodeName) {
		ContentName baseName = accessRoot(nodeName);
		ContentName aclName = new ContentName(baseName, ACCESS_CONTROL_MARKER_BYTES, ACL_NAME_BYTES);
		return aclName;
	}
	
	public static ContentName dataKeyName(ContentName nodeName) {
		ContentName baseName = accessRoot(nodeName);
		ContentName dataKeyName = new ContentName(baseName, ACCESS_CONTROL_MARKER_BYTES, DATA_KEY_NAME_BYTES);
		return dataKeyName;
	}
	
	public static boolean isDataKeyName(ContentName name) {
		if (!isAccessName(name) || VersioningProfile.isVersioned(name)) {
			return false;
		}
		ContentName dkName = VersioningProfile.versionRoot(name);
		if (dkName.stringComponent(dkName.count()-1).equals(DATA_KEY_NAME)) {
			return true;
		}
		return false;
	}
	
	/**
	 * Assumes a top-level namespace, where the group information is stored in 
	 * <namespace>/_access_/Groups and <namespace>/_access_/Users..
	 * @param namespace
	 * @param groupFriendlyName
	 * @return
	 */
	public static ContentName userNamespaceName(ContentName namespace) {
		return new ContentName(accessRoot(namespace), ACCESS_CONTROL_MARKER_BYTES, USER_PREFIX_BYTES);
	}

	public static ContentName groupNamespaceName(ContentName namespace) {
		return new ContentName(accessRoot(namespace), ACCESS_CONTROL_MARKER_BYTES, GROUP_PREFIX_BYTES);
	}
	
	public static ContentName groupName(ContentName namespace, String groupFriendlyName) {
		return ContentName.fromNative(groupNamespaceName(namespace), groupFriendlyName);
	}
	
	/**
	 * This is the unversioned root. The actual public key is stored at the latest version of
	 * this name. The private key and decoding blocks are stored under that version, with
	 * the segments of the group public key.
	 * @param namespace
	 * @param groupFriendlyName
	 * @return
	 */
	public static ContentName groupPublicKeyName(ContentName namespace, String groupFriendlyName) {
		return ContentName.fromNative(groupName(namespace, groupFriendlyName),  GROUP_PUBLIC_KEY_NAME);
	}
	public static ContentName groupMembershipListName(ContentName namespace, String groupFriendlyName) {
		return ContentName.fromNative(groupName(namespace, groupFriendlyName),  GROUP_MEMBERSHIP_LIST_NAME);
	}

	public static String groupNameToFriendlyName(ContentName groupName) {
		return ContentName.componentPrintNative(groupName.lastComponent());
	}

	public static ContentName groupPrivateKeyDirectory(
			ContentName groupPublicKeyNameAndVersion) {
		// We hang the wrapped private key directly off the public key version.
		return groupPublicKeyNameAndVersion;
	}

	public static boolean isPrincipalNameComponent(byte [] nameComponent) {
		return DataUtils.isBinaryPrefix(PRINCIPAL_PREFIX, nameComponent);
	}

	public static boolean isWrappedKeyNameComponent(byte [] wnkNameComponent) {
		return DataUtils.isBinaryPrefix(WRAPPING_KEY_PREFIX, wnkNameComponent);
	}

	/**
	 * Wrapped key blocks are stored under a name whose last (pre content digest) component
	 * identifies the key used to wrap them, as 
	 * WRAPPING_KEY_PREFIX COMPONENT_SEPARATOR base64Encode(keyID)
	 * or 
	 * keyid:<base 64 encoding of binary key id>
	 * The reason for the prefix is to allow unique separation from the principal name
	 * links, the reason for the base 64 encoding is to allow unique separation from the
	 * prefix.
	 * @param childName
	 * @return
	 * @throws IOException
	 */
	public static byte[] getTargetKeyIDFromNameComponent(byte[] childName) throws IOException {
		if (!isWrappedKeyNameComponent(childName))
			return null;
		byte [] base64keyid = new byte[childName.length - WRAPPING_KEY_PREFIX.length];
		System.arraycopy(childName, WRAPPING_KEY_PREFIX.length, base64keyid, 0, base64keyid.length);
		byte [] keyid = DataUtils.base64Decode(base64keyid);
		return keyid;
	}

	public static byte[] targetKeyIDToNameComponent(byte[] keyID) {
		if (null == keyID)
			return null;
		String encodedKeyID = DataUtils.base6Encode(keyID);
		byte [] encodedKeyIDBytes = ContentName.componentParseNative(encodedKeyID);
		byte [] output = new byte[WRAPPING_KEY_PREFIX.length + encodedKeyIDBytes.length];
		System.arraycopy(WRAPPING_KEY_PREFIX, 0, output, 0, WRAPPING_KEY_PREFIX.length);
		System.arraycopy(encodedKeyIDBytes, 0, output, WRAPPING_KEY_PREFIX.length, encodedKeyIDBytes.length);
		return output;
	}

	public static Pair<String, Timestamp> parsePrincipalInfoFromNameComponent(
			byte[] childName) {
		if (!isPrincipalNameComponent(childName) || (childName.length <= PRINCIPAL_PREFIX.length))
			return null;
		
		// First time we see COMPONENT_SEPARATOR is the separation point.
		// Could jump back based on fixed width of timestamp.
		int sepIndex = -1;
		for (sepIndex = PRINCIPAL_PREFIX.length; sepIndex < childName.length; sepIndex++) {
			if (childName[sepIndex] == COMPONENT_SEPARATOR[0])
				break;
		}
		if (sepIndex == childName.length) {
			Library.logger().warning("Unexpected principal name format - no separator: " + 
					ContentName.componentPrintURI(childName, PRINCIPAL_PREFIX.length, childName.length-PRINCIPAL_PREFIX.length));
			return null;
		}
		byte [] principal = new byte[sepIndex - PRINCIPAL_PREFIX.length];
		byte [] timestamp = new byte[childName.length - sepIndex];
		System.arraycopy(childName, PRINCIPAL_PREFIX.length, principal, 0, principal.length);
		System.arraycopy(childName, sepIndex+1, timestamp, 0, timestamp.length);
		
		String strPrincipal = ContentName.componentPrintNative(principal);
		// Represent as version or just the timestamp part?
		Timestamp version = DataUtils.binaryTime12ToTimestamp(timestamp);
		return new Pair<String, Timestamp>(strPrincipal, version);	
	}

	/**
	 * Principal names for links to wrapped key blocks take the form:
	 * PRINCIPAL_PREFIX COMPONENT_SEPARATOR principalName COMPONENT_SEPARATOR timestamp as 12-bit binary
	 * This allows a single enumeration of a wrapped key directory to determine
	 * not only which principals the keys are wrapped for, but also what versions of their
	 * private keys the keys are wrapped under (also determinable from the contents of the
	 * wrapped key blocks, but to do that you have to pull the wrapped key block).
	 * These serve as the name of a link to the actual wrapped key block.
	 * @param principalName
	 * @param timestamp
	 * @return
	 */
	public static byte[] principalInfoToNameComponent(String principalName,
													  Timestamp timestamp) {
		byte [] bytePrincipal = ContentName.componentParseNative(principalName);
		byte [] byteTime = DataUtils.timestampToBinaryTime12(timestamp);
		byte [] component = new byte[PRINCIPAL_PREFIX.length + bytePrincipal.length + COMPONENT_SEPARATOR.length + byteTime.length];
		// java 1.6 has much better functions for array copying
		System.arraycopy(PRINCIPAL_PREFIX, 0, component, 0, PRINCIPAL_PREFIX.length);
		System.arraycopy(bytePrincipal, 0, component, PRINCIPAL_PREFIX.length, bytePrincipal.length);
		System.arraycopy(COMPONENT_SEPARATOR, 0, component, PRINCIPAL_PREFIX.length+bytePrincipal.length, COMPONENT_SEPARATOR.length);
		System.arraycopy(byteTime, 0, component, PRINCIPAL_PREFIX.length+bytePrincipal.length+COMPONENT_SEPARATOR.length, 
							byteTime.length);
		
		return component;
	}
}
