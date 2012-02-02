/*
 * CCNx Android Services
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

package org.ccnx.android.services;

import org.ccnx.android.ccnlib.CCNxServiceControl;
import org.ccnx.android.ccnlib.CCNxServiceCallback;
import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;
import org.ccnx.android.ccnlib.CcndWrapper.CCND_OPTIONS;
import org.ccnx.android.ccnlib.RepoWrapper.CCNR_OPTIONS;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.Spinner;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * Android UI for controlling CCNx services.
 */
public final class Controller extends Activity implements OnClickListener {
	public final static String TAG = "CCNx Service Controller";
	
	Button allBtn;
	
	ProgressDialog pd;
	
	Context _ctx;
	
	TextView tvCcndStatus;
	TextView tvRepoStatus;
	TextView deviceIPAddress;
	
	CCNxServiceControl control;
	
	// Create a handler to receive status updates
	private final Handler _handler = new Handler() {
		public void handleMessage(Message msg){
			SERVICE_STATUS st = SERVICE_STATUS.fromOrdinal(msg.what);
			Log.d(TAG,"New status from CCNx Services: " + st.name());
			// This is very very lazy.  Instead of checking what we got, we'll just
			// update the state and let that get our new status
			updateState();
		}
	};
	
	CCNxServiceCallback cb = new CCNxServiceCallback(){
		public void newCCNxStatus(SERVICE_STATUS st) {
			_handler.sendEmptyMessage(st.ordinal());
		}
	};
	
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.controllermain);   
        
        Log.d(TAG,"Creating Service Controller");
        
        _ctx = this.getApplicationContext();
        
        allBtn = (Button)findViewById(R.id.allStartButton);
        allBtn.setOnClickListener(this);
        tvCcndStatus = (TextView)findViewById(R.id.tvCcndStatus);
        tvRepoStatus = (TextView)findViewById(R.id.tvRepoStatus);
        deviceIPAddress = (TextView)findViewById(R.id.deviceIPAddress);
        String ipaddr = getIPAddress();
        
        if (ipaddr != null) {
        	deviceIPAddress.setText(ipaddr);
        } else {
        	deviceIPAddress.setText("Unable to determine IP Address");
        }
        init();
    }
    
    @Override
    public void onDestroy() {
    	control.disconnect();
    	super.onDestroy();
    }
    
    @Override
    public void onResume() {
    	super.onResume();
    	String ipaddr = getIPAddress();
        
        if (ipaddr != null) {
        	deviceIPAddress.setText(ipaddr);
        } else {
        	deviceIPAddress.setText("Unable to determine IP Address");
        }
    }
    
    private void init(){
    	control = new CCNxServiceControl(this);
    	control.registerCallback(cb);
    	control.connect();
    	updateState();
    }

	public void onClick(View v) {
		switch( v.getId() ) {
		case R.id.allStartButton:
			allButton();
			break;
		default:
			Log.e(TAG, "");
		}
	}

	private void updateState(){
		if(control.isAllRunning()){
			allBtn.setText(R.string.allStopButton);
		} else {
			allBtn.setText(R.string.allStartButton);
		}
		tvCcndStatus.setText(control.getCcndStatus().name());
		tvRepoStatus.setText(control.getRepoStatus().name());
	}
	
	/**
	 * Start all services in the background
	 */
	private void allButton(){
		if(control.isAllRunning()){
			// Everything is ready, we must stop
			control.stoptAll();
		} else { /* Note, this doesn't take into account partially running state */
			// Not all running... attempt to start them
			// but first, get the user settings
			// Consider these to be our defaults
			// We don't really check validity of the data in terms of constraints
			// so we should shore this up to be more robust
			final EditText ccnrDir = (EditText) findViewById(R.id.key_ccnr_directory);  
			String val = ccnrDir.getText().toString();  
			if (isValid(val)) {
				control.setCcnrOption(CCNR_OPTIONS.CCNR_DIRECTORY, val);
			} else {
				// Toast it, and return (so the user fixes the bum field)
				Toast.makeText(this, "CCNR_DIRECTORY field is not valid.  Please set and then start.", 10).show();
				return;
			}
			
			final EditText ccnrGlobalPrefix= (EditText) findViewById(R.id.key_ccnr_global_prefix);  
			val = ccnrGlobalPrefix.getText().toString();  
			if (isValid(val)) {
				control.setCcnrOption(CCNR_OPTIONS.CCNR_GLOBAL_PREFIX, val);
			} else {
				// Toast it, and return (so the user fixes the bum field)
				Toast.makeText(this, "CCNR_GLOBAL_PREFIX field is not valid.  Please set and then start.", 10).show();
				return;
			}
			
			final Spinner ccnrDebugSpinner = (Spinner) findViewById(R.id.key_ccnr_debug);  
			val = ccnrDebugSpinner.getSelectedItem().toString();  
			if (isValid(val)) {
				control.setCcnrOption(CCNR_OPTIONS.CCNR_DEBUG, val);
			} else {
				// Toast it, and return (so the user fixes the bum field)
				// XXX I Don't think this will ever happen
				Toast.makeText(this, "CCNR_DEBUG field is not valid.  Please set and then start.", 10).show();
				return;
			}
			control.startAllInBackground();
		}
		updateState();
	}
	
	private String getIPAddress() {
		try {
			for (Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces(); e.hasMoreElements();) {
				NetworkInterface nic = e.nextElement();
				for (Enumeration<InetAddress> enumIpAddr = nic.getInetAddresses(); enumIpAddr.hasMoreElements();) {
					InetAddress addr = enumIpAddr.nextElement();
					if (!addr.isLoopbackAddress()) {
						return addr.getHostAddress().toString();
					}
				}
			}
		} catch (SocketException ex) {
			Toast.makeText(this, "Error obtaining IP Address.  Reason: " + ex.getMessage(), 10).show();
		}
		return null;
	}
	private boolean isValid(String val) {
		// Normally we'd do real field validation to make sure input matches type of input
		return (!((val == null) || (val.length() == 0)));
	}
}
