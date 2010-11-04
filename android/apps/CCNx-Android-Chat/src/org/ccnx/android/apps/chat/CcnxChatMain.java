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
		
		String devid = "Android";
		
		Log.i(TAG, "onCreate()");
		
		Button button = (Button) findViewById(R.id.btnConnect);
		if( null != button )
			button.setOnClickListener(this);
		else
			Log.e(TAG, "Could not find btnConect!");
		
		try {
			TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
			String s = tm.getLine1Number();
			if ( null != s && s.length() > 0 )
				devid = s;
			
		} catch(Exception e) {
			Log.e(TAG, "TelephoneManager error", e);
			e.printStackTrace();
		}
		
		EditText etHandle = (EditText) findViewById(R.id.etHandle);
		etHandle.setText(devid);		
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

	/**
	 * Start the ChatScreen passing our settings
	 */
	private void connect() {
		try {
			
			File ff = getDir("storage", Context.MODE_WORLD_READABLE);
			Log.i(TAG,"getDir = " + ff.getAbsolutePath());
			
			UserConfiguration.setUserConfigurationDirectory( ff.getAbsolutePath() );
			
			EditText etNamespace = (EditText) findViewById(R.id.etNamespace);
			EditText etHandle = (EditText) findViewById(R.id.etHandle);

			String handle = etHandle.getText().toString();
			UserConfiguration.setUserName( handle );

			Intent i = new Intent(this, ChatScreen.class);
			i.putExtra("namespace", etNamespace.getText().toString());
			i.putExtra("handle", handle);
			this.startActivity(i);

		} catch(Exception e) {
			Log.e(TAG, "Error with ContentName", e);
			return;
		}
		
	}
    
}
