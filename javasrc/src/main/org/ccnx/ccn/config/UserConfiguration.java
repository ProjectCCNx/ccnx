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

package org.ccnx.ccn.config;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.Component;
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
	protected static final String DEFAULT_CONFIGURATION_FILE_NAME = "ccnx_config.bin";

	
	protected static final String DEFAULT_KEYSTORE_FILE_NAME = ".ccnx_keystore";
	protected static final String DEFAULT_KEY_CACHE_FILE_NAME = "secure_key_cache.bin";
	protected static final String KEY_DIRECTORY = "keyCache";
	protected static final String ADDRESSBOOK_FILE_NAME = "ccnx_addressbook.xml";

	protected static final String CCNX_DEFAULT_NAMESPACE = "/ccnx.org";
	
	protected static final Component DEFAULT_USER_NAMESPACE_MARKER = new Component("Users");
	protected static final Component DEFAULT_KEY_NAMESPACE_MARKER = new Component("Keys");
	
	/**
	 * Currently very cheezy keystore handling. Will improve when we can actually use
	 * java 1.6-only features.
	 */
	protected static final String DEFAULT_KEYSTORE_PASSWORD = "Th1s1sn0t8g00dp8ssw0rd.";
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
	protected static final String CCNX_DEFAULT_NAMESPACE_PROPERTY = 
		"org.ccnx.config.CCNxNamespace";
	protected static final String CCNX_DEFAULT_NAMESPACE_ENVIRONMENT_VARIABLE = "CCNX_NAMESPACE";

	/**
	 * Default value of user configuration directory name -- this is not
	 * the full path, merely the directory name itself; by default we interpret
	 * the directory name as <user_home>/.ccnx.
	 * @return
	 */
	protected static final String CCNX_DEFAULT_USER_CONFIG_DIR_NAME = ".ccnx";
	
	/**
	 * Directory (subdirectory of User.home) where all user metadata is kept.
	 * Property/environment variable to set the user configuration directory (full path).
	 */
	protected static final String CCNX_USER_CONFIG_DIR_PROPERTY = 
		"org.ccnx.config.CCNxDir";
	protected static final String CCNX_USER_CONFIG_DIR_ENVIRONMENT_VARIABLE = "CCNX_DIR";

	/**
	 * User friendly name, by default user.name Java property
	 */
	protected static final String CCNX_USER_NAME_PROPERTY = 
		"org.ccnx.config.UserName";
	protected static final String CCNX_USER_NAME_ENVIRONMENT_VARIABLE = "CCNX_USER_NAME";

	/**
	 * User namespace, by default ccnxNamespace()/<DEFAULT_USER_NAMESPACE_MARKER>/userName();
	 * the user namespace prefix will be set to the value given here -- so the user
	 * namespace will be userNamespacePrefix()/<DEFAULT_USER_NAMESPACE_MARKER>/<user name>
	 * for either a user name we are given or our default user name.
	 */
	protected static final String CCNX_USER_NAMESPACE_PREFIX_PROPERTY = 
		"org.ccnx.config.UserNamespacePrefix";
	protected static final String CCNX_USER_NAMESPACE_PREFIX_ENVIRONMENT_VARIABLE = "CCNX_USER_NAMESPACE_PREFIX";

	/**
	 * User namespace, by default ccnxNamespace()/<DEFAULT_USER_NAMESPACE_MARKER>/userName();
	 * the user namespace will be set to the value given here -- we don't add the
	 * user namespace marker or userName(). 
	 */
	protected static final String CCNX_USER_NAMESPACE_PROPERTY = 
		"org.ccnx.config.UserNamespace";
	protected static final String CCNX_USER_NAMESPACE_ENVIRONMENT_VARIABLE = "CCNX_USER_NAMESPACE";

	/**
	 * Property and variable to set the keystore file name to something other than the default .ccnx_keystore
	 * (the directory is handled separately, as the CCNX_USER_CONFIG_DIRECTORY...)
	 */
	protected static final String CCNX_KEYSTORE_FILENAME_PROPERTY = 
		"org.ccnx.config.KeystoreFilename";
	protected static final String CCNX_KEYSTORE_FILENAME_ENVIRONMENT_VARIABLE = "CCNX_KEYSTORE_FILENAME";

	/**
	 * Property and variable to set the keystore password to something other than the default;
	 *  can also be overridden in calls to the key manager constructor.
	 */
	protected static final String CCNX_KEYSTORE_PASSWORD_PROPERTY = 
		"org.ccnx.config.KeystorePassword";
	protected static final String CCNX_KEYSTORE_PASSWORD_ENVIRONMENT_VARIABLE = "CCNX_KEYSTORE_PASSWORD";

	/**
	 * Property and variable to set the keystore file name to something other than the default ccnx_user.conf
	 * (the directory is handled separately, as the CCNX_USER_CONFIG_DIRECTORY...)
	 */
	protected static final String CCNX_CONFIGURATION_FILENAME_PROPERTY = 
		"org.ccnx.config.ConfigurationFilename";
	protected static final String CCNX_CONFIGURATION_FILENAME_ENVIRONMENT_VARIABLE = "CCNX_CONFIG_FILENAME";

	/**
	 * Property and variable to set the key locator to use for the default key. Need something
	 * more complicated, probably read from a configuration file. But this will get us started.
	 * Parse this as "key locator for the default key", not "the default value for the key locator".
	 */
	protected static final String CCNX_DEFAULT_KEY_LOCATOR_PROPERTY = 
		"org.ccnx.config.DefaultKeyLocator";
	protected static final String CCNX_DEFAULT_KEY_LOCATOR_ENVIRONMENT_VARIABLE = "CCNX_DEFAULT_KEY_LOCATOR";

	/**
	 * Property and variable to control whether we publish keys or not.
	 */
	protected static final String CCNX_PUBLISH_KEYS_PROPERTY = 
		"org.ccnx.config.PublishKeys";
	protected static final String CCNX_PUBLISH_KEYS_ENVIRONMENT_VARIABLE = "CCNX_PUBLISH_KEYS";
	
	/**
	 * Property and variable to control whether we load/can set user's key-related configuration
	 * (key locators, key cache, etc). Key cache saving and loading is additionally handled
	 * below -- both this variable and that one need to be set to true to automatically save
	 * and load the key cache; if CCNX_SAVE_KEY_CACHE_CONFIGURATION_PROPERTY is true but this
	 * CCNX_USE_KEY_CONFIGURATION_PROPERTY is false, then users can manually save and load the
	 * key cache, but it will not be handled automatically on startup.
	 * 
	 */
	protected static final String CCNX_USE_KEY_CONFIGURATION_PROPERTY = "org.ccnx.config.UseKeyConfiguration";
	protected static final String CCNX_USE_KEY_CONFIGURATION_ENVIRONMENT_VARIABLE = "CCNX_USE_KEY_CONFIGURATION";

	/**
	 * Variable to control whether key cache is saved on request and reloaded on startup.
	 * See CCNX_USE_KEY_CONFIGURATION_PROPERTY.
	 */
	protected static final String CCNX_SAVE_KEY_CACHE_PROPERTY = "org.ccnx.config.SaveKeyCache";
	protected static final String CCNX_SAVE_KEY_CACHE_ENVIRONMENT_VARIABLE = "CCNX_SAVE_KEY_CACHE";
	protected static final String DEFAULT_SAVE_KEY_CACHE_SETTING = SystemConfiguration.STRING_FALSE; // default to off for now.

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
	protected static ContentName _defaultNamespace;

	/**
	 * User prefix (e.g. for keys). By default, the user namespace prefix together with user information.
	 */
	protected static ContentName _userNamespace;
	
	/**
	 * User namespace prefix (e.g. for keys). By default, the CCNX prefix
	 */
	protected static ContentName _userNamespacePrefix;

	/**
	 * Keystore file name. This is the name of the actual file, without the directory.
	 */
	protected static String _keystoreFileName;
	
	/**
	 * Keystore password, if not default. Yes we know this is bad; it's 
	 * on our list of things to improve.
	 */
	protected static String _keystorePassword;

	/**
	 * Configuration file name. This is the name of the actual file, without the directory.
	 */
	protected static String _configurationFileName;
	
	/**
	 * Do we publish keys by default?
	 */
	protected static Boolean _publishKeys;
	
	/**
	 * Do we load stored state about cached secret keys, key locators (credentials) to
	 * use, and so on? Setting this to false can prevent interactions between unit tests
	 * and the user's internal configuration data. If false, we also prevent writing
	 * to configuration state.
	 */
	protected static Boolean _useKeyConfiguration;
	
	/**
	 * Do we automatically save and load the key cache as part of the configuration data?
	 * (Automatic loading of key cache happens only if _useKeyConfiguration is also true.)
	 */
	protected static Boolean _saveAndLoadKeyCache;
	
	protected static final String USER_DIR = System.getProperty("user.home");
	
	public static String FILE_SEP = System.getProperty("file.separator");
	
	public static void setUserName(String name) {
		_userName = name;
	}
	
	public static String userName() { 
		if (null == _userName) {
			_userName = SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_USER_NAME_PROPERTY, CCNX_USER_NAME_ENVIRONMENT_VARIABLE,
															 System.getProperty("user.name"));
		}
		return _userName; 
	}
	
	public static void setUserConfigurationDirectory(String path) {
		_userConfigurationDir = path;
	}
	
	public static String userConfigurationDirectory() { 
		if (null == _userConfigurationDir) {
			_userConfigurationDir = SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_USER_CONFIG_DIR_PROPERTY, 
																		 CCNX_USER_CONFIG_DIR_ENVIRONMENT_VARIABLE,
																		 USER_DIR + FILE_SEP + CCNX_DEFAULT_USER_CONFIG_DIR_NAME);
			if (null == _userConfigurationDir)
				_userConfigurationDir = USER_DIR + FILE_SEP + CCNX_DEFAULT_USER_CONFIG_DIR_NAME;
		}
		return _userConfigurationDir; 
	}
	
	public static void setDefaultNamespacePrefix(String defaultNamespacePrefix) throws MalformedContentNameStringException {
		_defaultNamespace = (null == defaultNamespacePrefix) ? null : ContentName.fromNative(defaultNamespacePrefix);
	}
	
	public static ContentName defaultNamespace() { 
		if (null == _defaultNamespace) {
			String defaultNamespaceString = 
				SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_DEFAULT_NAMESPACE_PROPERTY, CCNX_DEFAULT_NAMESPACE_ENVIRONMENT_VARIABLE, 
													CCNX_DEFAULT_NAMESPACE);
			try {
				_defaultNamespace = ContentName.fromNative(defaultNamespaceString);
			} catch (MalformedContentNameStringException e) {
				Log.severe("Attempt to configure invalid default CCNx namespace: {0}!", defaultNamespaceString);
				throw new RuntimeException("Attempt to configure invalid default CCNx namespace: " + defaultNamespaceString + "!");
			}
		}
		return _defaultNamespace; 
	}
	
	public static void setUserNamespace(String userNamespace) throws MalformedContentNameStringException {
		_userNamespace = (null == userNamespace) ? null : ContentName.fromNative(userNamespace);
	}
	
	public static ContentName userNamespace() { 
		if (null == _userNamespace) {
			String userNamespaceString = SystemConfiguration.retrievePropertyOrEnvironmentVariable(
					CCNX_USER_NAMESPACE_PROPERTY, CCNX_USER_NAMESPACE_ENVIRONMENT_VARIABLE, null);
			if (null != userNamespaceString) {
				try {
					_userNamespace = ContentName.fromNative(userNamespaceString);
				} catch (MalformedContentNameStringException e) {
					Log.severe("Attempt to configure invalid default user namespace: {0}!", userNamespaceString);
					throw new RuntimeException("Attempt to configure invalid default user namespace: " + userNamespaceString + "!");
				}
			} else {
				_userNamespace = userNamespace(userName());
			}
		}
		return _userNamespace; 
	}
	
	/**
	 * User the userNamespacePrefix() to generate a namespace for a particular user
	 * @param userName
	 * @return
	 */
	public static ContentName userNamespace(String userName) {
		if (null == userName) {
			userName = userName();
		}
		return new ContentName(userNamespacePrefix(), userName);
	}
	
	public static void setUserNamespacePrefix(String userNamespacePrefix) throws MalformedContentNameStringException {
		_userNamespacePrefix = (null == userNamespacePrefix) ? null : ContentName.fromNative(userNamespacePrefix);
	}
	
	public static ContentName userNamespacePrefix() { 
		if (null == _userNamespacePrefix) {
			String userNamespacePrefixString = SystemConfiguration.retrievePropertyOrEnvironmentVariable(
					CCNX_USER_NAMESPACE_PREFIX_PROPERTY, CCNX_USER_NAMESPACE_PREFIX_ENVIRONMENT_VARIABLE, null);
			if (null != userNamespacePrefixString) {
				try {
					_userNamespacePrefix = ContentName.fromNative(userNamespacePrefixString);
				} catch (MalformedContentNameStringException e) {
					Log.severe("Attempt to configure invalid default user namespace prefix: {0}!", userNamespacePrefixString);
					throw new RuntimeException("Attempt to configure invalid default user namespace prefix: " + userNamespacePrefixString + "!");
				}
			} else {
				_userNamespacePrefix = new ContentName(defaultNamespace(), DEFAULT_USER_NAMESPACE_MARKER);
			}
		}
		return _userNamespacePrefix; 
	}

	public static void setKeystoreFileName(String fileName) {
		_keystoreFileName = fileName;
	}
	
	public static String keystoreFileName() { 
		if (null == _keystoreFileName) {
			_keystoreFileName = SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_KEYSTORE_FILENAME_PROPERTY, 
																		 CCNX_KEYSTORE_FILENAME_ENVIRONMENT_VARIABLE,
															DEFAULT_KEYSTORE_FILE_NAME);
		}
		return _keystoreFileName; 
	}
	
	public static String configurationFileName() { 
		if (null == _configurationFileName) {
			_configurationFileName = SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_CONFIGURATION_FILENAME_PROPERTY, 
																		 CCNX_CONFIGURATION_FILENAME_ENVIRONMENT_VARIABLE,
															DEFAULT_CONFIGURATION_FILE_NAME);
		}
		return _configurationFileName; 
	}
	
	public static String keyCacheFileName() {
		return DEFAULT_KEY_CACHE_FILE_NAME;
	}
	
	public static void setKeystorePassword(String password) {
		_keystorePassword = password;
	}
	
	public static String keystorePassword() { 
		if (null == _keystorePassword) {
			_keystorePassword = SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_KEYSTORE_PASSWORD_PROPERTY, 
																		 CCNX_KEYSTORE_PASSWORD_ENVIRONMENT_VARIABLE,
																		 DEFAULT_KEYSTORE_PASSWORD);
		}
		return _keystorePassword; 
	}

	/**
	 * Don't provide a mechanism to set this here; this is actually configured on the KeyManagers.
	 * Just provide a means for them to pull in property/environment/configuration file parameters.
	 * @return
	 */
	public static String defaultKeyLocator() { 
		return SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_DEFAULT_KEY_LOCATOR_PROPERTY, 
																		 CCNX_DEFAULT_KEY_LOCATOR_ENVIRONMENT_VARIABLE,
																		 null);
	}
	
	public static boolean useKeyConfiguration() {
		if (null == _useKeyConfiguration) {

			String strPublish =  
				SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_USE_KEY_CONFIGURATION_PROPERTY, 
						CCNX_USE_KEY_CONFIGURATION_ENVIRONMENT_VARIABLE,
						SystemConfiguration.STRING_TRUE);
			_useKeyConfiguration = strPublish.equalsIgnoreCase(SystemConfiguration.STRING_TRUE);
		}
		return _useKeyConfiguration;
	}

	/**
	 * Do we save the key cache when asked, and retrieve it on startup?
	 * @return
	 */
	public static boolean saveAndLoadKeyCache() {
		if (null == _saveAndLoadKeyCache) {
			// Set default to be false, until we have turned on key cache encryption	
			String strPublish =  
				SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_SAVE_KEY_CACHE_PROPERTY, 
						CCNX_SAVE_KEY_CACHE_ENVIRONMENT_VARIABLE,
						DEFAULT_SAVE_KEY_CACHE_SETTING);
			_saveAndLoadKeyCache = strPublish.equalsIgnoreCase(SystemConfiguration.STRING_TRUE);
		}
		return _saveAndLoadKeyCache;
	}
	
	public static void setSaveAndLoadKeyCache(boolean saveKeyCache) {
		_saveAndLoadKeyCache = saveKeyCache;
	}

	public static boolean publishKeys() { 
		if (null == _publishKeys) {

			String strPublish =  
				SystemConfiguration.retrievePropertyOrEnvironmentVariable(CCNX_PUBLISH_KEYS_PROPERTY, 
						CCNX_PUBLISH_KEYS_ENVIRONMENT_VARIABLE,
						SystemConfiguration.STRING_TRUE);
			_publishKeys = strPublish.equalsIgnoreCase(SystemConfiguration.STRING_TRUE);
		}
		return _publishKeys;
	}
	
	public static void setPublishKeys(boolean publish) {
		_publishKeys = publish;
	}

	public static String keyRepositoryDirectory() {
		return userConfigurationDirectory() + FILE_SEP + KEY_DIRECTORY; }
	
	public static String addressBookFileName() { 
		return userConfigurationDirectory() + FILE_SEP + ADDRESSBOOK_FILE_NAME; }
	
	public static String defaultKeyAlgorithm() { return DEFAULT_KEY_ALG; }
	
	public static String defaultKeyAlias() { return DEFAULT_KEY_ALIAS; }
	
	public static String defaultKeystoreType() { return DEFAULT_KEYSTORE_TYPE; }
	
	public static int defaultKeyLength() { return DEFAULT_KEY_LENGTH; }

	public static Component defaultKeyNamespaceMarker() { return DEFAULT_KEY_NAMESPACE_MARKER; }
}
