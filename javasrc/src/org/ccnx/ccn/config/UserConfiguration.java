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

package org.ccnx.ccn.config;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * A class encapsulating user-specific configuration information and default variable values.
 * Eventually will be handled more sensibly by a user configuration file. This is likely
 * to change extensively as user model evolves.
 */
public class UserConfiguration {
	
	/**
	 * Our eventual configuration file location.
	 */
	protected static final String USER_CONFIG_FILE = "ccn_config.xml";

	/**
	 * Directory (subdirectory of User.home) where all user metadata is kept.
	 */
	protected static final String CCN_DIR_NAME = ".ccn";
	
	protected static final String KEYSTORE_FILE_NAME = ".ccn_keystore";
	protected static final String KEY_DIRECTORY = "keyCache";
	protected static final String ADDRESSBOOK_FILE_NAME = "ccn_addressbook.xml";

	protected static final String DEFAULT_CCN_NAMESPACE_STRING = "/ccnx.org";
	protected static final String DEFAULT_USER_NAMESPACE_AREA = DEFAULT_CCN_NAMESPACE_STRING + "/home";
	protected static final String DEFAULT_USER_KEY_NAME = "Key";
	protected static ContentName DEFAULT_CCN_NAMESPACE;
	protected static ContentName DEFAULT_USER_NAMESPACE;
	
	// TODO: DKS improve handling
	protected static final String KEYSTORE_PASSWORD = "Th1s1sn0t8g00dp8ssw0rd.";
	protected static final int DEFAULT_KEY_LENGTH = 1024;
	protected static final String DEFAULT_KEY_ALG = "RSA";
	protected static final String DEFAULT_KEY_ALIAS = "CCNUser";
	protected static final String DEFAULT_KEYSTORE_TYPE = "PKCS12"; // "JCEKS"; // want JCEKS, but don't want to force regen yet
	
	protected static String CCN_DIR;
	protected static String USER_DIR;
	protected static String USER_NAME;
	protected static String FILE_SEP;
	
	static {
		try {
			USER_DIR = System.getProperty("user.home");
			USER_NAME = System.getProperty("user.name");
			FILE_SEP = System.getProperty("file.separator");
			DEFAULT_CCN_NAMESPACE = 
				ContentName.fromNative(DEFAULT_CCN_NAMESPACE_STRING);
			DEFAULT_USER_NAMESPACE = 
				ContentName.fromNative(DEFAULT_USER_NAMESPACE_AREA + 
								ContentName.SEPARATOR +
								USER_NAME);
			
			CCN_DIR = USER_DIR + FILE_SEP + CCN_DIR_NAME;
		} catch (MalformedContentNameStringException e) {
			Log.warning("This should not happen. MalformedContentNameStringException in system-generated name: " + e.getMessage());
			Log.warningStackTrace(e);
		}
	}
	
	public static String ccnDirectory() { return CCN_DIR; }
	
	public static String userConfigFile() { 
		return CCN_DIR + FILE_SEP + USER_CONFIG_FILE; }
	
	public static String keystoreFileName() { 
		return CCN_DIR + FILE_SEP + KEYSTORE_FILE_NAME; }
	
	public static String keystorePassword() { 
		return KEYSTORE_PASSWORD; }
	
	public static String keyRepositoryDirectory() {
		return CCN_DIR + FILE_SEP + KEY_DIRECTORY; }
	
	public static String addressBookFileName() { 
		return CCN_DIR + FILE_SEP + ADDRESSBOOK_FILE_NAME; }
	
	public static String defaultKeyAlgorithm() { return DEFAULT_KEY_ALG; }
	
	public static String defaultKeyAlias() { return DEFAULT_KEY_ALIAS; }
	
	public static String defaultKeystoreType() { return DEFAULT_KEYSTORE_TYPE; }
	
	public static int defaultKeyLength() { return DEFAULT_KEY_LENGTH; }

	public static String userName() { return USER_NAME; }
	
	public static String defaultKeyName() { return DEFAULT_USER_KEY_NAME; }
	public static ContentName defaultNamespace() { return DEFAULT_CCN_NAMESPACE; }
	public static ContentName defaultUserNamespace() { return DEFAULT_USER_NAMESPACE; }
}
