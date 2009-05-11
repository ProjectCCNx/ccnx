package com.parc.ccn.security.access;

import java.security.PrivateKey;
import java.util.HashMap;
import java.util.HashSet;

/**
 * A cache for decrypted symmetric keys for access control.
 * @author smetters
 *
 */
public class KeyCache {
	
	private HashMap<byte [], NodeKey> _nodeKeyMap = new HashMap<byte [], NodeKey>();
	private HashMap<byte [], PrivateKey> _myKeyMap = new HashMap<byte [], PrivateKey>();
	private HashMap<byte [], PrivateKey> _groupKeyMap = new HashMap<byte [], PrivateKey>();
	private HashMap<byte [], Group> _groupMap = new HashMap<byte [], Group>();
	private HashMap<String, Group> _myGroups = new HashMap<String, Group>();
}
