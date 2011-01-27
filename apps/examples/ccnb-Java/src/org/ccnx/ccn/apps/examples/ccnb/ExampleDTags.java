/*
 * A CCNx example of extending ccnb encoding/decoding.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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


package org.ccnx.ccn.apps.examples.ccnb;

/**
 * This class defines the protocol tags and strings for XML used in the example.
 */
public class ExampleDTags {
	
	/**
	 * If you add to this list, also add to the reverse map below.
	 */
	public static final int EXAMPLE_OFFSET = 5678001;	// This needs to not conflict with any TAG used elsewhere.
	public static final int Example2Integers = 5678001;
	public static final int Integer1 = 5678002;
	public static final int Integer2 = 5678003;
	
	public static final int CustodianEndpointEntry = 3551004;
	public static final int DescriptorType = 3551005;
	public static final int CustodianEndpoint = 3551006;
	public static final int EndpointData = 3551007;
	public static final int SIPData = 3551008;
	public static final int SIPType = 3551009;
	public static final int Proxy = 3551010;
	public static final int User = 3551011;
	public static final int Password = 3551012;
	public static final int StunServer = 3551013;
	public static final int DirectData = 3551014;
	public static final int InterfaceType = 3551015;
	public static final int EndpointAttribute = 3551016;
	public static final int RoutePrefix = 3551017;
	public static final int MembershipRestriction = 3551018;
	public static final int Preference = 3551019;
	public static final int IsHub = 3551020;
	public static final int VCAPolicy = 3551021;
	public static final int WiFiData = 3551022;
	public static final int SSID = 3551023;
	public static final int BSSID = 3551024;
	public static final int LANData = 3551025;
	public static final int DefaultRouterMAC = 3551026;
	public static final int IsHubOf = 3551027;

	protected static final String [] _tagToStringMap = new String[]{
		"Example2Integers", "Integer1", "Integer2", "CustodianEndpoint", 
		"DescriptorType", "CustodianEndpointEntry", "EndpointData", "SIPData", "SIPType",
		"Proxy", "User", "Password", "StunServer", "DirectData", "InterfaceType",
		"EndpointAttribute", "RoutePrefix", "MembershipRestriction", "Preference", "IsHub",
		"VCAPolicy", "WiFiData", "SSID", "BSSID", "LANData", "DefaultRouterMAC", "IsHubOf"};
	protected static final int TAG_MAP_LENGTH = _tagToStringMap.length;

	/**
	 * This is the slow search -- find a tag based on an index. Only 
	 * used in cases where we need to print based on a binary tag value; 
	 * this is only used in text encoding of usually binary objects... For
	 * now, as it's rare, do a scan, rather than taking the up front hit
	 * to build a hash table.
	 * @param tagVal
	 * @return
	 */
	public static String tagToString(long tagVal) {
		if ((tagVal >= EXAMPLE_OFFSET) && tagVal < (TAG_MAP_LENGTH + EXAMPLE_OFFSET)) {
			return _tagToStringMap[(int)tagVal - EXAMPLE_OFFSET];
		} 
		return null;
	}
	
	/**
	 * This is the slow search, and does the reverse of tagToString().
	 * @param tagName
	 * @return
	 */
	public static Long stringToTag(String tagName) {
		// the slow way, but right now we don't care.... want a static lookup for the forward direction
		for (int i=0; i < _tagToStringMap.length; ++i) {
			if ((null != _tagToStringMap[i]) && (_tagToStringMap[i].equals(tagName))) {
				return (long)i + EXAMPLE_OFFSET;
			}
		}
		return null;
	}

}
