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
 * the special prefix marker byte C1 (COMMAND_MARKER_BYTE). This class
 * contains operations for representing and storing command marker prefixes
 * (without arguments or application data). The CommandComponent class
 * handles full component markers containing arguments and data.
 * 
 * Commands are separated into namespaces, which are UTF-8 strings,
 * followed by an operation, which is a UTF-8 string component following
 * the last "." in the namespace designation. The remainder of the
 * command name component is interpreted by the namespace owner in whatever
 * manner they choose, with an optional convention to separate arguments
 * and commands with the tilde character (~).
 * 
 * Applications can append their own data to the command marker prefix, by
 * separating it from the arguments with an application postfix byte.
 * Therefore the convention is: prefix, namespace, command (separated by
 * "."s, followed by an optional ~-delimted set of UTF-8 arguments,
 * followed by an optional argument postfix delimiter and then application data.
 * The application data can be stored in an instance of this class, or can
 * be tacked on/parsed statically.
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
 * %C1.N.n[~<length>]~<nonce>
 * 
 * For now put the built-in commands here as well, though as we get ones that take
 * arguments we should start to break them out to profile-specific locations. But
 * start simple.
 * 
 * @author rasmusse, smetters
 *
 */
public class CommandMarker {
	
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
	public static final byte ARGUMENT_TERMINATOR = "!".getBytes()[0];
	public static final int ARGUMENT_TERMINATOR_LENGTH = 1;

	/**
	 * (Name) enumeration "marker"
	 */
	public static final String ENUMERATION_NAMESPACE = "E";
	/**
	 * Basic enumeration command, no arguments,
	 */
	public static final CommandMarker COMMAND_MARKER_BASIC_ENUMERATION = 
					commandMarker(ENUMERATION_NAMESPACE, "be");

	/**
	 * Repository "marker"
	 */
	public static final String REPOSITORY_NAMESPACE = "R";
	/**
	 * Start write command.
	 */
	public static final CommandMarker COMMAND_MARKER_REPO_START_WRITE = 
		commandMarker(REPOSITORY_NAMESPACE, "sw");
	
	/**
	 * Some very simple markers that need no other support. See KeyProfile and
	 * MetadataProfile for related core markers that use the Marker namespace.
	 */
	
	/**
	 * Nonce marker
	 */
	public static final String NONCE_NAMESPACE = "N";
	public static final CommandMarker COMMAND_MARKER_NONCE = commandMarker(NONCE_NAMESPACE, "n");
	
	/**
	 * GUID marker
	 */
	public static final CommandMarker COMMAND_MARKER_GUID = commandMarker(CommandMarker.MARKER_NAMESPACE, "G");
	
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

	/**
	 * This in practice might be only the prefix, with additional variable arguments added
	 * on the fly.
	 */
	protected byte [] _byteCommandMarker; 
	
	public static final CommandMarker commandMarker(String namespace, String command) {
		return new CommandMarker(namespace, command);
	}
	
	protected CommandMarker(String namespace, String command) {
		StringBuffer sb = new StringBuffer(namespace);
		if ((null != namespace) && (namespace.length() > 0)) {
			// otherwise use leading . and empty namespace
			sb.append(COMMAND_SEPARATOR);
		}
		if ((null != command) && (command.length() > 0)) {
			sb.append(command);
		}
		byte [] csb = ContentName.componentParseNative(sb.toString());
		byte [] bc = new byte[csb.length + COMMAND_PREFIX.length];
		System.arraycopy(COMMAND_PREFIX, 0, bc, 0, COMMAND_PREFIX.length);
		System.arraycopy(csb, 0, bc, COMMAND_PREFIX.length, csb.length);
		_byteCommandMarker = bc;
	}
		
	protected CommandMarker() {}

	public String getNamespace() {
		String cs = extractCommandString(_byteCommandMarker);
		int lastDot = cs.lastIndexOf(COMMAND_SEPARATOR);
		if (lastDot < 0) {
			return null;
		}
		return cs.substring(0, lastDot);
	}

	public String getCommand() {
		String cs = extractCommandString(_byteCommandMarker);
		int lastDot = cs.lastIndexOf(COMMAND_SEPARATOR);
		int firstTilde = cs.indexOf(ARGUMENT_SEPARATOR);
		// if lastDot not there, lastDot = 0 and namespace is null, 0 correct starting point.
		if (firstTilde < 0)
			return cs.substring(lastDot+1);
		return cs.substring(lastDot + 1, firstTilde);
	}

	public byte [] getBytes() { return _byteCommandMarker; }
	
	public int length() { return _byteCommandMarker.length; }
	
	/**
	 * Generate a name component that adds arguments and data to this command marker.
	 * See addArguments and addData if you only need to add one and not the other.
	 * @param arguments
	 * @param applicationData
	 * @return
	 */
	public byte [] addArgumentsAndData(String [] arguments, byte [] applicationData) {
		byte [] csb = null;
		if ((null != arguments) && (arguments.length > 0)) {
			StringBuffer sb = new StringBuffer();
			for (int i=0; i < arguments.length; ++i) {
				sb.append(CommandMarker.ARGUMENT_SEPARATOR);
				sb.append(arguments[i]);
			}
			csb = ContentName.componentParseNative(sb.toString());
		}
		int csblen = ((null != csb) ? csb.length : 0);
		
		int addlComponentLength = csblen + ((applicationData != null) ? 
				applicationData.length + CommandMarker.ARGUMENT_TERMINATOR_LENGTH : 0);
		
		int offset = 0;
		byte [] component = new byte[length() + addlComponentLength];
		System.arraycopy(getBytes(), 0, component, offset, length());
		offset += length();
		
		if (csblen > 0) {
			System.arraycopy(csb, 0, component, offset, csblen);
			offset += csblen;
		}
	
		if ((null != applicationData) && (applicationData.length > 0)) {
			component[length()] = CommandMarker.ARGUMENT_TERMINATOR;
			offset += CommandMarker.ARGUMENT_TERMINATOR_LENGTH;
			System.arraycopy(applicationData, 0, component, offset, applicationData.length);
		}
		return component;
	}	
	
	/**
	 * Helper method if you just need to add arguments.
	 * @param arguments
	 * @return
	 */
	public byte [] addArguments(String [] arguments) {
		return addArgumentsAndData(arguments,  null);
	}
	
	/**
	 * Helper method if you just need to add data.
	 * @param applicationData
	 * @return
	 */
	public byte [] addData(byte [] applicationData) {
		return addArgumentsAndData(null, applicationData);
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
	
	
	/**
	 * Processing application data coming in over the wire according to generic
	 * conventions. Particular markers can instantiate subclasses of CommandMarker
	 * to do more specific processing, or can put that processing in their profiles.
	 */
	
	
	/**
	 * Does the prefix of this component match the command bytes of this marker?
	 * @return
	 */
	public boolean isMarker(byte [] nameComponent) {
		return (0 == DataUtils.bytencmp(getBytes(), nameComponent, length()));
	}

	/**
	 * Extract any arguments associated with this prefix. 
	 * @param nameComponent
	 * @return
	 */
	public String [] getArguments(byte [] nameComponent) {
		String cs = extractCommandString(_byteCommandMarker);
		int firstTilde = cs.indexOf(ARGUMENT_SEPARATOR);
		if (firstTilde < 0)
			return new String[0];
		int argTerminator = cs.indexOf(ARGUMENT_TERMINATOR, firstTilde+1);
		String argString = cs.substring(firstTilde + 1, (argTerminator < 0) ? cs.length() : argTerminator + 1);
		return argString.split(ARGUMENT_SEPARATOR);
	}
	
	public byte [] extractApplicationData(byte [] nameComponent) {
		if (!isMarker(nameComponent)) {
			throw new IllegalArgumentException("Not a command marker of type " + toString() + "!");
		}
		// What's the most efficient way to do this? State machine says, start
		// hopping through .s, after each . look for a ~ or an argument terminator
		int idx = DataUtils.byteindex(nameComponent, ARGUMENT_TERMINATOR);
		return DataUtils.subarray(nameComponent, idx+ARGUMENT_TERMINATOR_LENGTH, nameComponent.length - idx - 1);
	}
}
