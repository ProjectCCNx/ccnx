/*
 * CCNx Android Chat
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
package org.ccnx.android.apps.chat;

import java.io.File;

import org.ccnx.ccn.config.UserConfiguration;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;

/**
 * Initial screen to make configuration choices.  No CCNx code here.
 * After user presses Connect button, we startup the ChatScreen UI and
 * exit this screen.
 * 
 * The default "handle" (CCNX user name) is the device's phone number.
 * If we cannot get a phone number, we use the name "Android".
 */
public final class CcnxChatMain extends Activity implements OnClickListener{
	protected final static String TAG = "ccnchat.StartScreen";
	
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.ccnchat_settings);
		
		Log.i(TAG, "onCreate()");
		
		Button button = (Button) findViewById(R.id.btnConnect);
		if( null != button )
			button.setOnClickListener(this);
		else
			Log.e(TAG, "Could not find btnConect!");

		_etNamespace = (EditText) findViewById(R.id.etNamespace);
		_etHandle = (EditText) findViewById(R.id.etHandle);
		_etRemoteHost = (EditText) findViewById(R.id.etRemoteHost);
		_etRemotePort = (EditText) findViewById(R.id.etRemotePort);
		


		try {
			TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
			String s = tm.getLine1Number();
			if ( null != s && s.length() > 0 )
				DEFAULT_HANDLE = s;
			
		} catch(Exception e) {
			Log.e(TAG, "TelephoneManager error", e);
			e.printStackTrace();
		}

		
		restorePreferences();
	}
	
	@Override
	public void onStop() {
		super.onStop();
		savePreferences();
	}

	public void onClick(View v) {
		// do something when the button is clicked

		Log.d(TAG, "OnClickListener " + String.valueOf(v.getId()));

		switch (v.getId()) {
		case R.id.btnConnect:
			// Start ChatScreen then exit this screen
			connect();
			finish();
			break;

		default:
			break;
		}
	}
	
	
	// ==========================================================
	// Internal stuff
	private static final String PREFS_NAME="ccnChatPrefs";
	
	private static final String DEFAULT_NAMESPACE="/ccnchat";
	private String DEFAULT_HANDLE="Android";
	private static final String DEFAULT_REMOTEHOST="";
	private static final String DEFAULT_REMOTEPORT="9695";
	
	protected static final String PREF_NAMESPACE="namespace";
	protected static final String PREF_HANDLE="handle";
	protected static final String PREF_REMOTEHOST="remotehost";
	protected static final String PREF_REMOTEPORT="remoteport";
	
	private EditText _etNamespace;
	private EditText _etHandle;
	private EditText _etRemoteHost;
	private EditText _etRemotePort;

	/**
	 * Start the ChatScreen passing our settings
	 */
	private void connect() {
		try {
			File ff = getDir("storage", Context.MODE_WORLD_READABLE);
			Log.i(TAG,"getDir = " + ff.getAbsolutePath());
			
			UserConfiguration.setUserConfigurationDirectory( ff.getAbsolutePath() );
			
			String handle = _etHandle.getText().toString();
			UserConfiguration.setUserName( handle );

			Intent i = new Intent(this, ChatScreen.class);
			i.putExtra(PREF_NAMESPACE, _etNamespace.getText().toString());
			i.putExtra(PREF_HANDLE, handle);
			i.putExtra(PREF_REMOTEHOST, _etRemoteHost.getText().toString());
			i.putExtra(PREF_REMOTEPORT, _etRemotePort.getText().toString());
			this.startActivity(i);

		} catch(Exception e) {
			Log.e(TAG, "Error with ContentName", e);
			return;
		}
		
	}
	
	private void restorePreferences() {
		SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
		String namespace = settings.getString(PREF_NAMESPACE, DEFAULT_NAMESPACE);
		String handle = settings.getString(PREF_HANDLE, DEFAULT_HANDLE);
		String remotehost = settings.getString(PREF_REMOTEHOST, DEFAULT_REMOTEHOST);
		String remoteport = settings.getString(PREF_REMOTEPORT, DEFAULT_REMOTEPORT);
		
		_etHandle.setText(handle);
		_etNamespace.setText(namespace);
		_etRemoteHost.setText(remotehost);
		_etRemotePort.setText(remoteport);
		
	}
    
	private void savePreferences() {
		SharedPreferences settings = getSharedPreferences(PREFS_NAME, 0);
		SharedPreferences.Editor editor = settings.edit();
		
		editor.putString(PREF_NAMESPACE, _etNamespace.getText().toString());
		editor.putString(PREF_HANDLE, _etHandle.getText().toString());
		editor.putString(PREF_REMOTEHOST, _etRemoteHost.getText().toString());
		editor.putString(PREF_REMOTEPORT, _etRemotePort.getText().toString());
		editor.commit();
	}
}
