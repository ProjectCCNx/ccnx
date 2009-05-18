package com.parc.ccn.library.profiles;

import java.sql.Timestamp;
import java.util.ArrayList;

import com.parc.ccn.data.ContentName;
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
	
	// These may eventually want to move somewhere more general
	public static final String GROUP_PREFIX = "Groups";
	public static final String USER_PREFIX = "Users";
	
	public static final String GROUP_PUBLIC_KEY_NAME = "KEY";
	public static final String MEMBER_KEY_BLOCK_NAME = "MemberKeys";
	public static final String PREVIOUS_KEY_NAME = "PreviousKey";
	public static final String ACL_NAME = "ACL";
	public static final String NODE_KEY_NAME = "NK";
	public static final String DATA_KEY_NAME = "DK";

	public static final String SUPERSEDED_MARKER = "Superseded";
	
	
	public static boolean isAccessName(ContentName name) {
		return name.contains(ACCESS_CONTROL_MARKER);
	}
	
	public static ContentName accessRoot(ContentName name) {
		return name.cut(ACCESS_CONTROL_MARKER);
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
	
	public static ContentName nodeKeyName(ContentName nodeName) {
		if (!isAccessName(nodeName)) {
		}
	}
	
	public static ContentName aclName(ContentName nodeName) {
		ContentName baseName = accessRoot(nodeName);
		ContentName aclName = ContentName.fromNative(baseName, ACCESS_CONTROL_MARKER, ACL_NAME);
		return aclName;
	}
	
	public static ContentName dataKeyName(ContentName dataNodeName) {
		
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
		ArrayList<byte []> nameComponents = new ArrayList<byte[]>();
		if (!isAccessName(namespace)) {
			nameComponents.add(ContentName.componentParseNative(ACCESS_CONTROL_MARKER));
		}
		if (!namespace.contains(USER_PREFIX)) {
			nameComponents.add(ContentName.componentParseNative(USER_PREFIX));
		}
		byte [][] components = new byte[nameComponents.size()][];
		return new ContentName(namespace, nameComponents.toArray(components));
	}

	public static ContentName groupNamespaceName(ContentName namespace) {
		ArrayList<byte []> nameComponents = new ArrayList<byte[]>();
		if (!namespace.contains(ACCESS_CONTROL_MARKER)) {
			nameComponents.add(ContentName.componentParseNative(ACCESS_CONTROL_MARKER));
		}
		if (!namespace.contains(GROUP_PREFIX)) {
			nameComponents.add(ContentName.componentParseNative(GROUP_PREFIX));
		}
		byte [][] components = new byte[nameComponents.size()][];
		return new ContentName(namespace, nameComponents.toArray(components));
	}
	
	public static ContentName groupName(ContentName namespace, String groupFriendlyName) {
		return ContentName.fromNative(groupNamespaceName(namespace), groupFriendlyName);
	}
	
	public static ContentName groupPublicKeyName(ContentName namespace, String groupFriendlyName) {
		return ContentName.fromNative(groupName(namespace,groupFriendlyName),  GROUP_PUBLIC_KEY_NAME);
	}

	public static String groupNodeKeyToGroupName(ContentName wnk) {
		// TODO Auto-generated method stub
		return null;
	}

	public static boolean isPrincipalNameComponent(byte [] wnkNameComponent) {
		// TODO Auto-generated method stub
		return false;
	}

	public static boolean isWrappedKeyNameComponent(byte [] wnkNameComponent) {
		// TODO Auto-generated method stub
		return false;
	}

	public static String getPrincipalNameFromNameComponent(
			byte[] wnkChildName) {
		// TODO Auto-generated method stub
		return null;
	}

	public static byte[] getTargetKeyIDFromNameComponent(byte[] wnkChildName) {
		// TODO Auto-generated method stub
		return null;
	}

	public static byte[] targetKeyIDToNameComponent(byte[] keyID) {
		// TODO Auto-generated method stub
		return null;
	}

	public static Pair<String, Timestamp> parsePrincipalInfoFromNameComponent(
			byte[] wkChildName) {
		// TODO Auto-generated method stub
		return null;
	}

	public static byte[] principalInfoToNameComponent(String principalName,
			Timestamp timestamp) {
		// TODO Auto-generated method stub
		return null;
	}
}
