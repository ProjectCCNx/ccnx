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
 * Eventually this will be handled more sensibly by a user configuration file. This is likely
 * to change extensively as the user model evolves.
 */
public class UserConfiguration {
	
	/**
	 * Our eventual configuration file location.
	 */
	protected static final String USER_CONFIG_FILE = "ccnx_config.xml";

	
	protected static final String KEYSTORE_FILE_NAME = ".ccnx_keystore";
	protected static final String KEY_DIRECTORY = "keyCache";
	protected static final String ADDRESSBOOK_FILE_NAME = "ccnx_addressbook.xml";

	protected static final String DEFAULT_CCN_NAMESPACE_STRING = "/ccnx.org";
	
	protected static final String DEFAULT_USER_NAMESPACE_MARKER = "Users";
	protected static final String DEFAULT_USER_KEY_NAMESPACE_MARKER = "Keys";
	
	/**
	 * Currently very cheezy keystore handling. Will improve when we can actually use
	 * java 1.6-only features.
	 */
	protected static final String KEYSTORE_PASSWORD = "Th1s1sn0t8g00dp8ssw0rd.";
	protected static final int DEFAULT_KEY_LENGTH = 1024;
	protected static final String DEFAULT_KEY_ALG = "RSA";
	/**
	 * Change to all lower case. Most OSes turn out to be non case-sensitive
	 * for this, but not all.
	 */
	protected static final String DEFAULT_KEY_ALIAS = "ccnxuser";
	protected static final String DEFAULT_KEYSTORE_TYPE = "PKCS12"; // "JCEKS"; // want JCEKS, but don't want to force keystore regeneration yet
	
	/**
	 * Default prefix to use, e.g. for user information if not overridden by local stuff.
	 */
	/**
	 * Command-line property to set default user data directory
	 * @return
	 */
	protected static final String CCNX_NAMESPACE_PROPERTY = 
		"org.ccnx.config.CCNxNamespace";
	
	/**
	 * Environament variable to set default user data directory; 
	 * overridden by command-line property.
	 * @return
	 */
	protected static final String CCNX_NAMESPACE_ENVIRONMENT_VARIABLE = "CCNX_NAMESPACE";

	/**
	 * Directory (subdirectory of User.home) where all user metadata is kept.
	 */
	/**
	 * Default value of user configuration directory name. 
	 * @return
	 */
	protected static final String CCNX_USER_CONFIG_DIR_NAME = ".ccnx";
	/**
	 * Command-line property to set default user data directory
	 * @return
	 */
	protected static final String CCNX_USER_CONFIG_DIR_PROPERTY = 
		"org.ccnx.config.CCNxDir";
	
	/**
	 * Environament variable to set default user data directory; 
	 * overridden by command-line property.
	 * @return
	 */
	protected static final String CCNX_USER_CONFIG_DIR_ENVIRONMENT_VARIABLE = "CCNX_DIR";

	/**
	 * User friendly name, by default user.name Java property
	 */
	/**
	 * Command-line property to set default user data directory
	 * @return
	 */
	protected static final String CCNX_USER_NAME_PROPERTY = 
		"org.ccnx.config.UserName";
	
	/**
	 * Environament variable to set default user data directory; 
	 * overridden by command-line property.
	 * @return
	 */
	protected static final String CCNX_USER_NAME_ENVIRONMENT_VARIABLE = "CCNX_USER_NAME";

	/**
	 * User namespace, by default ccnxNamespace()/<DEFAULT_USER_NAMESPACE_MARKER>/userName();
	 * the user namespace will be set to the value given here -- we don't add the
	 * user namespace marker or userName(). 
	 */
	/**
	 * Command-line property to set default user data directory
	 * @return
	 */
	protected static final String CCNX_USER_NAMESPACE_PROPERTY = 
		"org.ccnx.config.UserNamespace";
	
	/**
	 * Environament variable to set default user data directory; 
	 * overridden by command-line property.
	 * @return
	 */
	protected static final String CCNX_USER_NAMESPACE_ENVIRONMENT_VARIABLE = "CCNX_USER_NAMESPACE";

	/**
	 * Value of CCN directory.
	 */
	protected static String _userConfigurationDir;
	
	/**
	 * User name. By default value of user.name property.
	 */
	protected static String _userName;
	
	/**
	 * CCNx (control) prefix. 
	 */
	protected static ContentName _ccnxNamespace;

	/**
	 * User prefix (e.g. for keys). By default, the CCNX prefix together with user information.
	 */
	protected static ContentName _userNamespace;
	
	/**
	 * Keystore file name. This is the name of the actual file, without the directory.
	 */
	protected static String _keystoreFileName;

	protected static final String USER_DIR = System.getProperty("user.home");
	protected static String FILE_SEP = System.getProperty("file.separator");
	
	public static void setUserName(String name) {
		_userName = name;
	}
	
	public static String userName() { 
		if (null == _userName) {
			_userName = retrievePropertyOrEvironmentVariable(CCNX_USER_NAME_PROPERTY, CCNX_USER_NAME_ENVIRONMENT_VARIABLE,
															 System.getProperty("user.name"));
		}
		return _userName; 
	}
	
	public static void setUserConfigurationDirectory(String path) {
		_userConfigurationDir = path;
	}
	
	public static String userConfigurationDirectory() { 
		if (null == _userConfigurationDir) {
			_userConfigurationDir = retrievePropertyOrEvironmentVariable(CCNX_USER_CONFIG_DIR_PROPERTY, 
																		 CCNX_USER_CONFIG_DIR_ENVIRONMENT_VARIABLE,
															USER_DIR + FILE_SEP + CCNX_USER_CONFIG_DIR_NAME);
		}
		return _userConfigurationDir; 
	}
	
	public static void setccnxNamespacePrefix(String ccnxNamespacePrefix) throws MalformedContentNameStringException {
		_ccnxNamespace = (null == ccnxNamespacePrefix) ? null : ContentName.fromNative(ccnxNamespacePrefix);
	}
	
	public static ContentName ccnxNamespace() { 
		if (null == _ccnxNamespace) {
			String ccnxNamespaceString = 
				retrievePropertyOrEvironmentVariable(CCNX_NAMESPACE_PROPERTY, CCNX_NAMESPACE_ENVIRONMENT_VARIABLE, DEFAULT_CCN_NAMESPACE_STRING);
			try {
				_ccnxNamespace = ContentName.fromNative(ccnxNamespaceString);
			} catch (MalformedContentNameStringException e) {
				Log.severe("Attempt to configure invalid default CCNx namespace: {0}!", ccnxNamespaceString);
				throw new RuntimeException("Attempt to configure invalid default CCNx namespace: " + ccnxNamespaceString + "!");
			}
		}
		return _ccnxNamespace; 
	}
	
	public static void setUserNamespace(String userNamespace) throws MalformedContentNameStringException {
		_userNamespace = (null == userNamespace) ? null : ContentName.fromNative(userNamespace);
	}
	
	public static ContentName userNamespace() { 
		if (null == _userNamespace) {
			String userNamespaceString = retrievePropertyOrEvironmentVariable(
					CCNX_USER_NAMESPACE_PROPERTY, CCNX_USER_NAMESPACE_ENVIRONMENT_VARIABLE, null);
			if (null != userNamespaceString) {
				try {
					_userNamespace = ContentName.fromNative(userNamespaceString);
				} catch (MalformedContentNameStringException e) {
					Log.severe("Attempt to configure invalid default CCNx namespace: {0}!", userNamespaceString);
					throw new RuntimeException("Attempt to configure invalid default CCNx namespace: " + userNamespaceString + "!");
				}
			} else {
				_userNamespace = ContentName.fromNative(ccnxNamespace(), DEFAULT_USER_NAMESPACE_MARKER, userName());
			}
		}
		return _userNamespace; 
	}


	public static String userConfigFile() { 
		return userConfigurationDirectory() + FILE_SEP + USER_CONFIG_FILE; }
	
	public static String defaultKeystoreFileName() { 
		return KEYSTORE_FILE_NAME; }
	
	public static String defaultKeystorePassword() { 
		return KEYSTORE_PASSWORD; }
	
	public static String keyRepositoryDirectory() {
		return userConfigurationDirectory() + FILE_SEP + KEY_DIRECTORY; }
	
	public static String addressBookFileName() { 
		return userConfigurationDirectory() + FILE_SEP + ADDRESSBOOK_FILE_NAME; }
	
	public static String defaultKeyAlgorithm() { return DEFAULT_KEY_ALG; }
	
	public static String defaultKeyAlias() { return DEFAULT_KEY_ALIAS; }
	
	public static String defaultKeystoreType() { return DEFAULT_KEYSTORE_TYPE; }
	
	public static int defaultKeyLength() { return DEFAULT_KEY_LENGTH; }

	public static String defaultKeyName() { return DEFAULT_USER_KEY_NAME; }
	public static ContentName defaultNamespace() { return DEFAULT_CCN_NAMESPACE; }
	public static ContentName defaultUserNamespace() { return DEFAULT_USER_NAMESPACE; }
	
	/**
	 * Retrieve a string that might be stored as an environment variable, or
	 * overridden on the command line. If the command line variable is set, return
	 * its (String) value; if not, return the environment variable value if available;
	 * if neither is set return the default value. Caller should synchronize as appropriate.
	 * @return The value in force for this variable, or null if unset.
	 */
	public static String retrievePropertyOrEvironmentVariable(String javaPropertyName, String environmentVariableName, String defaultValue) { 
		// First try the command line property.
		String value = null;
		if (null != javaPropertyName) {
			value = System.getProperty(javaPropertyName);
		}
		if ((null == value) && (null != environmentVariableName)) {
			// Try for an environment variable.
			value = System.getenv(environmentVariableName);
		}
		if (null == value) {
			return defaultValue;
		}
		return value;
	}
}
