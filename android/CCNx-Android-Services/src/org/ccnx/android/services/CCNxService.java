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

package org.ccnx.android.services;

import java.util.TreeMap;

import org.ccnx.android.ccnlib.ICCNxService;
import org.ccnx.android.ccnlib.IStatusCallback;
import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;

/**
 * Generic service wrapper for Android.  Provides the basic control
 * structure and service abstraction.
 */
public abstract class CCNxService extends Service implements Runnable {
	protected String TAG = "CCNxService";

	// The status of the current service
	private SERVICE_STATUS status;

	// Keep a record of all the callbacks we need to issue
	final RemoteCallbackList<IStatusCallback> mCallbacks = new RemoteCallbackList<IStatusCallback>();


	// Where to map all the options that we need for the service
	protected TreeMap<String, String> options = new TreeMap<String, String>();

	// Thread to run the actual service code
	protected Thread thd = null;

	// Is the service thread running
	boolean running;  


	private final ICCNxService.Stub serviceBinder = new ICCNxService.Stub() {
		public int getStatus() {
			return status.ordinal();
		}

		public void stop() {
			stopService();
		}

		public void registerStatusCallback(IStatusCallback cb){
			if (cb != null) {
				Log.d(TAG,String.format("Registering callback %08X", cb.hashCode()));
				mCallbacks.register(cb);
			}
		}

		public void unregisterStatusCallback(IStatusCallback cb){
			if (cb != null) {
				Log.d(TAG,String.format("Unregistering callback %08X", cb.hashCode()));
				mCallbacks.unregister(cb);
			}

		}
	};

	@Override
	public void onCreate(){
		Log.d(TAG, "Creating");

		// Some emulators have problems with ipv6
		System.setProperty("java.net.preferIPv6Addresses", "false");

		setStatus(SERVICE_STATUS.SERVICE_SETUP);
	}

	@Override
	public void onDestroy(){
		Log.d(TAG, "Destroying");
		super.onDestroy();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d(TAG, "Starting:  " + startId + ": " + intent); 

		// Get all the options from the intent

		onStartService(intent);
		return START_STICKY;
	}

	@Override
	public IBinder onBind(Intent arg0) {
		Log.d(TAG, "Binding");
		return serviceBinder;
	}

	@Override
	public boolean onUnbind(Intent i){
		Log.d(TAG, "Unbinding");
		return false;
	}

	public void Load(){
		if (! running){
			Log.d(TAG,"Starting new thread");
			running = true;
			thd = new Thread(this,TAG);
			thd.start();
		}
		Log.d(TAG,"Thread started");
	}

	public void run(){
		runService();
	}

	protected void serviceStopped(){
		setStatus(SERVICE_STATUS.SERVICE_FINISHED);
		running = false;
	}

	public void setStatus(SERVICE_STATUS st) {	
		status = st;
		// Broadcast to all clients the new value.
		Log.d(TAG, "Starting broacast");
		final int N = mCallbacks.beginBroadcast();
		try {
			for (int i=0; i<N; i++) {
				try {
					Log.d(TAG, "Broadcasting to client " + i);
					mCallbacks.getBroadcastItem(i).status(status.ordinal());
				} catch (RemoteException e) {
					// The RemoteCallbackList will take care of removing
					// the dead object for us.
					e.printStackTrace();
				}
			}
		} finally {
			mCallbacks.finishBroadcast();
		}
		Log.d(TAG, "Finished broacast");
	}

	protected void dumpOptions(){
		for(String opt : options.keySet()){
			Log.d(TAG,opt + " = " + options.get(opt));
		}
	}

	protected abstract void onStartService(Intent i);
	protected abstract void runService();
	protected abstract void stopService();
}
