/*
 * CCNx Android Services
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.android.services.ccnd;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map.Entry;

import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;
import org.ccnx.android.ccnlib.CcndWrapper.CCND_OPTIONS;
import org.ccnx.android.services.CCNxService;
import org.ccnx.ccn.impl.security.keys.BasicKeyManager;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * CCNxService specialization for ccnd.
 * 
 * The CCND keystore directory is created MODE_PRIVATE.  This only
 * works if it is on the internal storage, not an sdcard.
 * 
 * The ccnd unix domain socket is created in the keystore directory, so
 * obviously that cannot be used with MODE_PRIVATE.  At the present,
 * nothing using the unix domain socket on Android. 
 */
public final class CcndService extends CCNxService {
	public static final String CLASS_TAG = "CCNxCCNdService";
	
	private String KEYSTORE_NAME = ".ccnd_keystore_";
	private final static char [] KEYSTORE_PASS = "\010\043\103\375\327\237\152\351\155".toCharArray();
	
	private final static String OPTION_CCND_CAP_DEFAULT = "500";
	private final static String OPTION_CCN_PORT_DEFAULT = "9695";
	
	protected static final String [] libs = { "controller" };
	
	public CcndService(){
		TAG=CLASS_TAG;
		
		// make sure libraries are loaded
		try {
			for( int i = 0; i < libs.length; i++ ) {
				System.loadLibrary(libs[i]);
			}
		} catch(Throwable e) { e.printStackTrace(); }
	}

	protected void onStartService(Intent intent) {
		Log.d(TAG, "Starting");
		
		// Get all the CCND options from the intent 
		// If no option is found on intent, look in System properties
		for( CCND_OPTIONS opt : CCND_OPTIONS.values() ) {
			if(! intent.hasExtra(opt.name())){
				continue;
			}
			String s = intent.getStringExtra( opt.name() );
			if( null == s ) 
				s = System.getProperty(opt.name());
				Log.d(TAG,"setting option " + opt.name() + " = " + s);
			if( s != null ) {
				options.put(opt.name(), s);
			}
		}

		Load();	
	}
	
	public void runService(){
		setStatus(SERVICE_STATUS.SERVICE_INITIALIZING);
		
		String ccnd_port = options.get(CCND_OPTIONS.CCN_LOCAL_PORT.name());
		if( ccnd_port == null ) {
			ccnd_port = OPTION_CCN_PORT_DEFAULT;
			options.put(CCND_OPTIONS.CCN_LOCAL_PORT.name(), ccnd_port);
		}
		Log.d(TAG,CCND_OPTIONS.CCN_LOCAL_PORT.name() + " = " + options.get(CCND_OPTIONS.CCN_LOCAL_PORT.name()));
		
		String ccnd_keydir = options.get(CCND_OPTIONS.CCND_KEYSTORE_DIRECTORY.name());
		if( ccnd_keydir == null ) {
			File f = getDir("ccnd", Context.MODE_PRIVATE );
			ccnd_keydir = f.getAbsolutePath();
			options.put(CCND_OPTIONS.CCND_KEYSTORE_DIRECTORY.name(), ccnd_keydir);
		}
		
		if(options.get(CCND_OPTIONS.CCND_CAP.name()) == null) {
			options.put(CCND_OPTIONS.CCND_CAP.name(), OPTION_CCND_CAP_DEFAULT);
		}
		
		if(options.get(CCND_OPTIONS.CCN_LOCAL_SOCKNAME.name()) == null) {
			options.put(CCND_OPTIONS.CCN_LOCAL_SOCKNAME.name(), ccnd_keydir + "/ccnd.sock");
		}
		
		dumpOptions();
		
		createKeystore(ccnd_keydir, KEYSTORE_NAME + ccnd_port);

		
		try {
			for( Entry<String,String> entry : options.entrySet() ) {
				setenv(entry.getKey(), entry.getValue(), 1);
			}
			
			ccndCreate();
			setStatus(SERVICE_STATUS.SERVICE_RUNNING);
			try {
				ccndRun();
			} finally {
				ccndDestroy();
			}
		} catch(Exception e) {
			e.printStackTrace();
			// returning will end the thread
		}
		serviceStopped();
	}
	
	protected void createKeystore(String dir_name, String keystore_name) {
		File dir = new File(dir_name);

		// This is to get a keystore file
		// Does dir/.ccnd_keystore_xxx exist?
		File try_keystore = new File(dir, keystore_name);

		if( try_keystore.exists() ) {
			Log.d(TAG, "Keystore Exists! " + try_keystore.getAbsolutePath());
			return;
		}
		
		Log.d(TAG,"Creating Keystore @ " + try_keystore.getAbsolutePath());

		try {			   
			FileOutputStream stream = new FileOutputStream(try_keystore);
			BasicKeyManager.createKeyStore(stream, null, "ccnd", KEYSTORE_PASS, "CCND");
			stream.close();
		} catch(Exception e) {
			e.printStackTrace();
		}

	}

	protected void stopService(){
		setStatus(SERVICE_STATUS.SERVICE_TEARING_DOWN);
		kill();
	}
	
	/* ************************************************************* */

	protected native void ccndCreate();
	protected native void ccndRun();
	protected native void ccndDestroy();
	protected native void kill();
	protected native void setenv(String key, String value, int overwrite);
}
