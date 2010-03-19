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

package org.ccnx.ccn.profiles;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.protocol.ContentName;

/**
 * The command marker prefix allows profiles to register a namespace
 * for commands, queries, or other special identifiers, beginning with 
 * the special prefix marker byte C1 (COMMAND_MARKER_BYTE).
 * 
 * Commands are separated into namespaces, which are UTF-8 strings,
 * followed by an operation, which is a UTF-8 string component following
 * the last "." in the namespace designation. The remainder of the
 * command name component is interpreted by the namespace owner in whatever
 * manner they choose, with an optional convention to separate arguments
 * and commands with the tilde character (~).
 * 
 * There is an additional "." separating the prefix marker and the namespace.
 * The namespace designation can contain "." (and so can be broken down by
 * reverse DNS name, as with Java), " ", and other legal UTF-8 characters. It
 * ends either with the last ., or at the end of the name component, whichever
 * comes last. 
 * Namespaces containing only capital letters are reserved for CCN itself,
 * and are listed here.
 * 
 * Examples:
 * 
 * The repository uses a command namespace of "R", and commands like:
 * start_write:
 * %C1.R.sw
 * (where R is the namespace marker, sw is the specific command, and it takes no arguments)
 * 
 * %C1.org.ccnx.frobnicate~1~37
 * would be a command in the namespace "org.ccnx", where the command is "frobnicate",
 * which takes two arguments, in this case 1 and 37
 * 
 * The nonce protocol has only one operation, generating a nonce, with an optional argument
 * of a nonce length.
 * %C1.N.n[~<length<]
 * 
 * For now put the built-in commands here as well, though as we get ones that take
 * arguments we should start to break them out to profile-specific locations. But
 * start simple.
 * 
 * @author rasmusse, smetters
 *
 */
public class CommandMarkers {
	
	/**
	 * Reserved bytes.
	 */
	public static byte[] CCN_reserved_markers = { (byte)0xC0, (byte)0xC1, (byte)0xF5, 
	(byte)0xF6, (byte)0xF7, (byte)0xF8, (byte)0xF9, (byte)0xFA, (byte)0xFB, (byte)0xFC, 
	(byte)0xFD, (byte)0xFE};

	public static final byte COMMAND_PREFIX_BYTE = (byte)0xC1;
	
	/**
	 * %C1.
	 */
	public static final byte [] COMMAND_PREFIX = {COMMAND_PREFIX_BYTE, (byte)0x2E};
	
	public static final String COMMAND_SEPARATOR = ".";
	public static final String ARGUMENT_SEPARATOR = "~";

	/**
	 * (Name) enumeration "marker"
	 */
	public static final String ENUMERATION_NAMESPACE = "E";
	/**
	 * Basic enumeration command, no arguments,
	 */
	public static final byte [] COMMAND_MARKER_BASIC_ENUMERATION = commandComponent(ENUMERATION_NAMESPACE, "be", null);

	/**
	 * Repository "marker"
	 */
	public static final String REPOSITORY_NAMESPACE = "R";
	/**
	 * Start write command.
	 */
	public static final byte [] COMMAND_MARKER_REPO_START_WRITE = commandComponent(REPOSITORY_NAMESPACE, "sw", null);
	
	/**
	 * Nonce marker
	 */
	public static final String NONCE_NAMESPACE = "N";
	public static final byte [] COMMAND_MARKER_NONCE = commandComponent(NONCE_NAMESPACE, "n", null);
	
	/**
	 * Marker for typed binary name components. These aren't general binary name components, but name
	 * components with defined semantics. Specific examples are defined in their own profiles, see
	 * KeyProfile and GuidProfile, as well as markers for access controls. The interpretation of these
	 * should be a) you shouldn't show them to a user unless you really have to, and b) the content of the
	 * marker tells you the type and interpretation of the value.
	 * 
	 * Save "B" for general binary name components if we need to go there;
	 * use M for marker. Start by trying to define them in their own profiles; might
	 * have to centralize here for reference.
	 */
	public static final String MARKER_NAMESPACE = "M";

	
	public static final byte [] commandComponent(String namespace, String command, String [] arguments) {
		StringBuffer sb = new StringBuffer(namespace);
		if ((null != namespace) && (namespace.length() > 0)) {
			// otherwise use leading . and empty namespace
			sb.append(COMMAND_SEPARATOR);
		}
		if ((null != command) && (command.length() > 0)) {
			sb.append(command);
		}
		
		if ((null != arguments) && (arguments.length > 0)) {
			for (int i=0; i < arguments.length; ++i) {
				sb.append(ARGUMENT_SEPARATOR);
				sb.append(arguments[i]);
			}
		}
		byte [] csb = ContentName.componentParseNative(sb.toString());
		byte [] bc = new byte[csb.length + COMMAND_PREFIX.length];
		System.arraycopy(COMMAND_PREFIX, 0, bc, 0, COMMAND_PREFIX.length);
		System.arraycopy(csb, 0, bc, COMMAND_PREFIX.length, csb.length);
		return bc;
	}
	
	public static String getNamespace(byte [] commandComponent) {
		String cs = extractCommandString(commandComponent);
		int lastDot = cs.lastIndexOf(COMMAND_SEPARATOR);
		if (lastDot < 0) {
			return null;
		}
		return cs.substring(0, lastDot);
	}

	public static String getCommand(byte [] commandComponent) {
		String cs = extractCommandString(commandComponent);
		int lastDot = cs.lastIndexOf(COMMAND_SEPARATOR);
		int firstTilde = cs.indexOf(ARGUMENT_SEPARATOR);
		// if lastDot not there, lastDot = 0 and namespace is null, 0 correct starting point.
		if (firstTilde < 0)
			return cs.substring(lastDot+1);
		return cs.substring(lastDot + 1, firstTilde);
	}
	
	public static String [] getArguments(byte [] commandComponent) {
		String cs = extractCommandString(commandComponent);
		int firstTilde = cs.indexOf(ARGUMENT_SEPARATOR);
		if (firstTilde < 0)
			return new String[0];
		String argString = cs.substring(firstTilde + 1);
		return argString.split(ARGUMENT_SEPARATOR);
	}
	
	public static boolean isCommandComponent(byte [] commandComponent) {
		return DataUtils.isBinaryPrefix(COMMAND_PREFIX, commandComponent);
	}
	
	protected static String extractCommandString(byte [] commandComponent) {
		if (!isCommandComponent(commandComponent)) {
			throw new IllegalArgumentException("Not a command sequence!");
		}
		return new String(commandComponent, COMMAND_PREFIX.length, commandComponent.length - COMMAND_PREFIX.length);
	}
}
