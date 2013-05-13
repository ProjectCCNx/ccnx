/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
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
import org.ccnx.ccn.protocol.Component;
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
 * the last "." in the namespace designation. (The "operation" can also
 * just be considered the last component of the namespace.) The remainder of the
 * command name component is interpreted by the namespace owner in whatever
 * manner they choose, with an optional convention to separate (optional) 
 * text arguments from the namespace and operation (and each other) with 
 * the tilde character (~), and a single binary argument
 * (which must come last) which must be prefixed with %00 for a raw binary
 * argument, or %C1 for an argument which is CCNB-encoded data. (Multiple
 * binary arguments can be provided using CCNB-encoding.)
 * 
  * The namespace designation can contain "." (and so can be broken down by
 * reverse DNS name, as with Java), " ", and other legal UTF-8 characters. It
 * ends either with the last ., or at the end of the name component, whichever
 * comes last. 
 * Namespaces containing only capital letters are reserved for CCN itself,
 * and are listed here.
 * 
 * We consider the initial marker byte/namespace/operation component as the "command
 * marker" and the remainder as arguments (the former refers to a fixed operation,
 * while the latter may vary across calls to that operation). Methods to parse a name
 * component encountered in processing either hand back a CommandMarker representing the
 * namespace/operation, or the arguments; we don't put the arguments into a CommandMarker
 * object.
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
 * The nonce protocol has only one operation, generating a nonce, with a
 * random binary argument.
 * %C1.N%00<binary argument>
 * 
 * A namespace org.ccnx.foo could have an operation bar, that took a single ccnb-encoded argument:
 * %C1.org.ccnx.foo.bar%C1<argument>
 * 
 * or 2 UTF-8 arguments and a binary argument
 * %C1.org.ccnx.foo.bar~arg1~arg1%00<binary argument>
 * 
 * For now put the built-in commands here as well, though as we get ones that take
 * arguments we should start to break them out to profile-specific locations. But
 * start simple.
 * 
 * @author rasmusse, smetters
 *
 */
public class CommandMarker implements ContentName.ComponentProvider {
	
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
	public static final byte COMMAND_SEPARATOR_BYTE = COMMAND_SEPARATOR.getBytes()[0];
	public static final String UTF8_ARGUMENT_SEPARATOR = "~";
	public static final byte UTF8_ARGUMENT_SEPARATOR_BYTE = UTF8_ARGUMENT_SEPARATOR.getBytes()[0];
	public static final byte BINARY_ARGUMENT_SEPARATOR = 0x00;
	public static final byte CCNB_ARGUMENT_SEPARATOR = (byte)0xC1;

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
	 * Sync marker
	 */
	public static final String SYNC_NAMESPACE = "S";
	
	/**
	 * Start write command.
	 */
	public static final CommandMarker COMMAND_MARKER_REPO_START_WRITE = 
		commandMarker(REPOSITORY_NAMESPACE, "sw");
	/**
	 * Checked Start Write: request storage of particular stream if not already held
	 * The name prefix before the command marker component is the base name
	 * The component after the command marker is a nonce
	 * The next component is the starting segment component
	 * The next and final component is the starting segment digest component explicitly
	 * The command marker is in the middle so that the response from repo is not a data
	 * object having another data object full name (digest included) as prefix of its name
	 */
	public static final CommandMarker COMMAND_MARKER_REPO_CHECKED_START_WRITE = 
		commandMarker(REPOSITORY_NAMESPACE, "sw-c");

	public static final CommandMarker COMMAND_MARKER_REPO_ADD_FILE = 
		commandMarker(REPOSITORY_NAMESPACE, "af");
	
	/**
	 * Some very simple markers that need no other support. See KeyProfile and
	 * MetadataProfile for related core markers that use the Marker namespace.
	 */
	
	/**
	 * Nonce marker
	 */
	public static final String NONCE_NAMESPACE = "N";
	public static final CommandMarker COMMAND_MARKER_NONCE = commandMarker(NONCE_NAMESPACE, null);
	
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
	 * GUID marker
	 */
	public static final CommandMarker COMMAND_MARKER_GUID = commandMarker(CommandMarker.MARKER_NAMESPACE, "G");
	
	/**
	 * Marker for a name component that is supposed to indicate a scope
	 */
	public static final CommandMarker COMMAND_MARKER_SCOPE = 
		CommandMarker.commandMarker(CommandMarker.MARKER_NAMESPACE, "S");


	/**
	 * This in practice might be only the prefix, with additional variable arguments added
	 * on the fly.
	 */
	protected byte [] _byteCommandMarker; 
	
	public static final CommandMarker commandMarker(String namespace, String command) {
		return new CommandMarker(namespace, command);
	}
	
	public static final CommandMarker commandMarker(CommandMarker namespace, String command) {
		return new CommandMarker(namespace, command);
	}

	protected CommandMarker(CommandMarker parent, String operation) {
		
		if (null == operation) {
			_byteCommandMarker = parent.getBytes();
		} else {
			byte [] prefix = parent.getBytes();

			StringBuffer sb = new StringBuffer(COMMAND_SEPARATOR);
			sb.append(operation);
			byte [] csb = Component.parseNative(sb.toString());
			byte [] bc = new byte[csb.length + prefix.length];
			System.arraycopy(prefix, 0, bc, 0, prefix.length);
			System.arraycopy(csb, 0, bc, prefix.length, csb.length);
			_byteCommandMarker = bc;
		}
	}
	
	protected CommandMarker(String namespace, String command) {
		StringBuffer sb = new StringBuffer(namespace);
		if ((null != command) && (command.length() > 0)) {
			if ((null != namespace) && (namespace.length() > 0)) {
				// otherwise use leading . and empty namespace
				sb.append(COMMAND_SEPARATOR);
			}			
                        sb.append(command);
		}
		byte [] csb = Component.parseNative(sb.toString());
		byte [] bc = new byte[csb.length + COMMAND_PREFIX.length];
		System.arraycopy(COMMAND_PREFIX, 0, bc, 0, COMMAND_PREFIX.length);
		System.arraycopy(csb, 0, bc, COMMAND_PREFIX.length, csb.length);
		_byteCommandMarker = bc;
	}
	
	protected CommandMarker(byte [] nameComponent) {
		if (!isCommandComponent(nameComponent)) {
			throw new IllegalArgumentException("Not a command marker!");
		}
		_byteCommandMarker = nameComponent;
	}
		
	protected CommandMarker() {}

	/**
	 * Return binary representation of command marker.
	 * @return
	 */
	public byte [] getBytes() { return _byteCommandMarker; }
	
	public int length() { return _byteCommandMarker.length; }
	
	/**
	 * Returns the initial name components after the first marker of this command marker, 
	 * up to the operation (if any). Terminating "." is stripped. If there is only one initial 
	 * string component, the operation is interpreted as empty, and that component is returned 
	 * as the namespace. We assume we don't have any arguments in our byte array.
	 * @return
	 */
	public String getNamespace() {
		int occurcount = DataUtils.occurcount(_byteCommandMarker, COMMAND_PREFIX.length, COMMAND_SEPARATOR_BYTE);
		if (occurcount == 0) {
			// only one component
			return new String(_byteCommandMarker, COMMAND_PREFIX.length, _byteCommandMarker.length-COMMAND_PREFIX.length);
		}
		int lastDot = DataUtils.byterindex(_byteCommandMarker, _byteCommandMarker.length-1, COMMAND_SEPARATOR_BYTE);
		return new String(_byteCommandMarker, COMMAND_PREFIX.length, lastDot-1-COMMAND_PREFIX.length);
	}
	
	/**
	 * Returns the final string component operation of the prefix of this command marker, 
	 * if any (see getNamespace).
	 */
	public String getOperation() {
		int occurcount = DataUtils.occurcount(_byteCommandMarker, COMMAND_PREFIX.length, COMMAND_SEPARATOR_BYTE);
		if (occurcount == 0) {
			// only one component
			return null;
		}
		int lastDot = DataUtils.byterindex(_byteCommandMarker, COMMAND_PREFIX.length, COMMAND_SEPARATOR_BYTE);
		return new String(_byteCommandMarker, lastDot+1, _byteCommandMarker.length - lastDot - 1);
	}
	
	/**
	 * Generate a name component that adds arguments and data to this command marker.
	 * See addArguments and addData if you only need to add one and not the other.
	 * @param arguments
	 * @param applicationData
	 * @return the name component to use containing the command marker and arguments
	 */
	protected byte [] addArgumentsAndData(String [] arguments, byte [] applicationData, byte dataMarker) {
		byte [] csb = null;
		if ((null != arguments) && (arguments.length > 0)) {
			StringBuffer sb = new StringBuffer();
			for (int i=0; i < arguments.length; ++i) {
				sb.append(CommandMarker.UTF8_ARGUMENT_SEPARATOR);
				sb.append(arguments[i]);
			}
			csb = Component.parseNative(sb.toString());
		}
		int csblen = ((null != csb) ? csb.length : 0);
		
		int addlComponentLength = csblen + ((applicationData != null) ? 
				applicationData.length + 1 : 0);
		
		int offset = 0;
		byte [] component = new byte[length() + addlComponentLength];
		System.arraycopy(getBytes(), 0, component, offset, length());
		offset += length();
		
		if (csblen > 0) {
			System.arraycopy(csb, 0, component, offset, csblen);
			offset += csblen;
		}
	
		if ((null != applicationData) && (applicationData.length > 0)) {
			component[length()] = dataMarker;
			offset += 1;
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
		return addArgumentsAndData(arguments,  null, (byte)0x00);
	}
	
	/**
	 * Helper method if you just need to add one argument
	 */
	public byte [] addArgument(String argument) {
		return addArguments(new String[]{argument});
	}
	
	/**
	 * Helper method if you just need to add data.
	 * @param applicationData -- raw binary
	 * @return
	 */
	public byte [] addBinaryData(byte [] applicationData) {
		return addArgumentsAndData(null, applicationData, BINARY_ARGUMENT_SEPARATOR);
	}
	
	/**
	 * Helper method if you just need to add data.
	 * @param applicationData -- ccnb encoded
	 * @return
	 */
	public byte [] addCCNBEncodedData(byte [] applicationData) {
		return addArgumentsAndData(null, applicationData, CCNB_ARGUMENT_SEPARATOR);
	}

	public static boolean isCommandComponent(byte [] commandComponent) {
		return DataUtils.isBinaryPrefix(COMMAND_PREFIX, commandComponent);
	}
	
	/**
	 * Does the prefix of this component match the command bytes of this marker?
	 * @return
	 */
	public boolean isMarker(byte [] nameComponent) {
		return (0 == DataUtils.bytencmp(getBytes(), nameComponent, length()));
	}
	
	/**
	 * Find component that contains the marker
	 * @param name
	 * @return
	 */
	public int findMarker(ContentName name) {
        int nameCount = name.count();
		for (int i = 0; i < nameCount; i++) {
			if (isMarker(name.component(i)))
				return i;
		}
		return -1;
	}
	
	public static CommandMarker getMarker(byte [] nameComponent) {
		int argumentStart = argumentStart(nameComponent);
		if (argumentStart < 0) {
			return new CommandMarker(nameComponent);
		}
		return new CommandMarker(DataUtils.subarray(nameComponent, 0, argumentStart));
	}

	public static int argumentStart(byte [] nameComponent) {
		// Find the point where arguments (text or binary) start after the namespace/op.
		// Start searching after the command component prefix.
		int idx = textArgumentStart(nameComponent);
		if (idx < 0) {
			idx = binaryArgumentStart(nameComponent);
		}
		return idx;
	}
	
	public static int textArgumentStart(byte [] nameComponent) {	
		int idx = DataUtils.byteindex(nameComponent, COMMAND_PREFIX.length, UTF8_ARGUMENT_SEPARATOR_BYTE);
		return idx;
	}

	public static int binaryArgumentStart(byte [] nameComponent) {
		int idx = DataUtils.byteindex(nameComponent, COMMAND_PREFIX.length, BINARY_ARGUMENT_SEPARATOR);			
		return idx;
	}
	
	/**
	 * Processing application data coming in over the wire according to generic
	 * conventions. Particular markers can instantiate subclasses of CommandMarker
	 * to do more specific processing, or can put that processing in their profiles.
	 * 
	 * Here we put generic argument processing and command marker parsing capabilities for 
	 * CMs that follow conventions.
	 */


	/**
	 * Extract any arguments associated with this prefix. 
	 * @param nameComponent
	 * @return null if no arguments, otherwise String [] of text arguments.
	 */
	public static String [] getArguments(byte [] nameComponent) {
		
		if (!isCommandComponent(nameComponent)) {
			throw new IllegalArgumentException("Not a command marker!");
		}

		int argumentStart = textArgumentStart(nameComponent);
		if (argumentStart < 0) {
			return null;
		}
		
		int argumentEnd = binaryArgumentStart(nameComponent);
		if (argumentEnd >= 0) {
			if (argumentEnd <= argumentStart+1)
				return null;	// no Argument or other malformed data
		} else
			argumentEnd = nameComponent.length;
		
		String argString = new String(nameComponent, argumentStart+1, argumentEnd-argumentStart-1);
		return argString.split(UTF8_ARGUMENT_SEPARATOR);
	}
	
	public static boolean isCCNBApplicationData(byte [] nameComponent) {
		if (!isCommandComponent(nameComponent)) {
			throw new IllegalArgumentException("Not a command marker!");
		}

		int argumentStart = binaryArgumentStart(nameComponent);
		if (argumentStart < 0) {
			return false;
		}		
		return nameComponent[argumentStart] == CCNB_ARGUMENT_SEPARATOR;
	}
	
	public static byte [] extractApplicationData(byte [] nameComponent) {
		if (!isCommandComponent(nameComponent)) {
			throw new IllegalArgumentException("Not a command marker!");
		}

		int argumentStart = binaryArgumentStart(nameComponent);
		if (argumentStart < 0) {
			return null;
		}
		
		return DataUtils.subarray(nameComponent, argumentStart+1, nameComponent.length - argumentStart - 1);
	}

	public byte[] getComponent() {
		return _byteCommandMarker;
	}
}
