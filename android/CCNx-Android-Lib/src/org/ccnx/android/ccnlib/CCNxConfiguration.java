/*
 * CCNx Android Services
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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

package org.ccnx.android.ccnlib;

import java.io.File;
import java.io.IOException;
import java.security.InvalidKeyException;

import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.security.keys.BasicKeyManager;

import android.content.Context;
import android.os.Environment;
import android.telephony.TelephonyManager;
import android.util.Log;

/**
 * Configure CCNx.  You should always run CCNxConfiguration.config(this) inside
 * your main application Activity before using any CCNx calls.
 */
public final class CCNxConfiguration {
	private final static String TAG = "CCNxConfiguration";

	/**
	 * Configure CCNx for Android with a shared user directory.
	 * @param ctx
	 */
	public static void config(Context ctx) {
		config(ctx, false);
	}
	
	/**
	 * Configure CCNx settings for Android.  The default is to use a shared
	 * user directory under /sdcard/ccnx/user.  If you want a private keystore
	 * then set privateUserDirectory to true
	 * 
	 * @param ctx
	 * @param privateUserDirectory true means keystore under /data/data/<pkg>/app_ccnx
	 */
	public static void config(Context ctx, boolean privateUserDirectory) {
		File root;
		
		// Is the SDCARD available?
		if( privateUserDirectory || !Environment.getExternalStorageState().equalsIgnoreCase("mounted") ) {
			// set permissions
			// Android will add the prefix "app_" to the name here.
			root = ctx.getDir("ccnx", Context.MODE_PRIVATE);
		} else {
			root = Environment.getExternalStorageDirectory();
		}

		Log.i(TAG,"root storage = " + root.getAbsolutePath());
		File user = new File(root, "/ccnx/user");
		
		user.mkdirs();
		UserConfiguration.setUserConfigurationDirectory(user.getAbsolutePath());
	}

	public static String getPhoneNumber(Context ctx) {
		try {
			TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
			String s = tm.getLine1Number();
			if ( null != s && s.length() > 0 )
				return s;

		} catch(Exception e) {
			Log.e(TAG, "TelephoneManager error", e);
			e.printStackTrace();
		}
		return null;
	}

	/* ************************************************************ */

	public static void createUserKeystore(File user_dir, String user_name, 
			String keystore_name, String keystore_password) 
	throws ConfigurationException, IOException, InvalidKeyException
	{

		//			public BasicKeyManager(
		//						String userName, 
		//						String keyStoreDirectory,
		//						String configurationFileName,
		//						String keyStoreFileName, 
		//			    		String keyStoreType, 
		//						String defaultAlias, 
		//						char [] password

		BasicKeyManager bkm = new BasicKeyManager(
				user_name, 					// userName
				user_dir.getAbsolutePath(),		// keystore dir
				null,						// configurationFilename
				keystore_name, 				// filename
				null, 						//type
				null, 						// alias
				null == keystore_password ? null : keystore_password.toCharArray()); // password

		Log.i(TAG,"Initializing BKM");
		bkm.initialize();
		Log.i(TAG,"Closing BKM");
		bkm.close();
	}

	public static void createUserKeystore(File user_dir) {

		File try_keystore = new File(user_dir, UserConfiguration.keystoreFileName());

		if( try_keystore.exists() ) {
			Log.i(TAG, "Keystore exists: " + try_keystore.getAbsolutePath());
			return;
		}

		try {
			createUserKeystore(
					user_dir, 							// dir
					UserConfiguration.defaultKeyAlias(),// user name
					null,								// keystore name
					null );								// keystore pass
		} catch(Exception e) {
			e.printStackTrace();
		}
	}

	public static void recursiveDelete(String dirname) {
		Log.i(TAG,"RecursiveDelete: " + dirname);
		File path = new File(dirname);
		recursiveDelete(path);
	}

	public static void recursiveDelete(File path) {

		File [] ff = path.listFiles();
		if( ff != null ) {
			for( File f : ff ) {
				if( f.isDirectory() )
					recursiveDelete(f);
				try {
					f.delete();
				} catch(Exception e) {
					e.printStackTrace();
				}
			}
		}

		try {
			path.delete();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}

}
