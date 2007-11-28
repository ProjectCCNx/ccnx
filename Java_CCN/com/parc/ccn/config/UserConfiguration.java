package com.parc.ccn.config;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;

public class UserConfiguration {
	
	/**
	 * TODO Eventually move some options into config file.
	 */
	protected static final String USER_CONFIG_FILE = "ccn_config.xml";

	/**
	 * Where all user metadata is kept.
	 */
	protected static final String CCN_DIR_NAME = ".ccn";
	protected static final String KEYSTORE_FILE_NAME = ".ccn_keystore";
	protected static final String ADDRESSBOOK_FILE_NAME = "ccn_addressbook.xml";

	protected static final String DEFAULT_CCN_NAMESPACE_STRING = "/parc.com";
	protected static final String DEFAULT_USER_NAMESPACE_AREA = DEFAULT_CCN_NAMESPACE_STRING + "/home";
	protected static final String DEFAULT_USER_KEY_NAME = "Key";
	protected static ContentName DEFAULT_CCN_NAMESPACE;
	protected static ContentName DEFAULT_USER_NAMESPACE;
	
	// TODO: DKS improve handling
	protected static final String KEYSTORE_PASSWORD = "Th1s1sn0t8g00dp8ssw0rd.";
	protected static final int DEFAULT_KEY_LENGTH = 1024;
	protected static final String DEFAULT_KEY_ALG = "RSA";
	protected static final String DEFAULT_KEY_ALIAS = "CCNUser";
	
	protected static String CCN_DIR;
	protected static String USER_DIR;
	protected static String USER_NAME;
	protected static String FILE_SEP;
	
	static {
		try {
			USER_DIR = System.getProperty("user.dir");
			USER_NAME = System.getProperty("user.name");
			FILE_SEP = System.getProperty("file.separator");
			DEFAULT_CCN_NAMESPACE = new ContentName(DEFAULT_CCN_NAMESPACE_STRING);
			DEFAULT_USER_NAMESPACE = 
				new ContentName(DEFAULT_USER_NAMESPACE_AREA + 
								ContentName.SEPARATOR +
								USER_NAME);
			
			CCN_DIR = USER_DIR + FILE_SEP + CCN_DIR_NAME;
		} catch (MalformedContentNameStringException e) {
			Library.logger().warning("This should not happen. MalformedContentNameStringException in system-generated name: " + e.getMessage());
			Library.warningStackTrace(e);
		}
	}
	
	public static String ccnDirectory() { return CCN_DIR; }
	
	public static String userConfigFile() { 
		return CCN_DIR + FILE_SEP + USER_CONFIG_FILE; }
	
	public static String keystoreFileName() { 
		return CCN_DIR + FILE_SEP + KEYSTORE_FILE_NAME; }
	
	public static String keystorePassword() { 
		return CCN_DIR + FILE_SEP + KEYSTORE_PASSWORD; }
	
	public static String addressBookFileName() { 
		return CCN_DIR + FILE_SEP + ADDRESSBOOK_FILE_NAME; }
	
	public static String defaultKeyAlgorithm() { return DEFAULT_KEY_ALG; }
	
	public static String defaultKeyAlias() { return DEFAULT_KEY_ALIAS; }
	
	public static int defaultKeyLength() { return DEFAULT_KEY_LENGTH; }

	public static String userName() { return USER_NAME; }
	
	public static String defaultKeyName() { return DEFAULT_USER_KEY_NAME; }
	public static ContentName defaultNamespace() { return DEFAULT_CCN_NAMESPACE; }
	public static ContentName defaultUserNamespace() { return DEFAULT_USER_NAMESPACE; }
}
