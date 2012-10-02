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

import java.util.HashMap;

import org.ccnx.android.ccnlib.ICCNxService;
import org.ccnx.android.ccnlib.IStatusCallback;
import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Log;
import android.content.SharedPreferences;
import android.app.NotificationManager;
import android.app.Notification;
import android.app.PendingIntent;

/**
 * Generic service wrapper for Android.  Provides the basic control
 * structure and service abstraction.
 */
public abstract class CCNxService extends Service implements Runnable {
	protected String TAG = "CCNxService";
	protected static final int NOTIFICATION = 2008;
	// The status of the current service
	private SERVICE_STATUS status;

	// Keep a record of all the callbacks we need to issue
	final RemoteCallbackList<IStatusCallback> mCallbacks = new RemoteCallbackList<IStatusCallback>();


	// Where to map all the options that we need for the service
	protected HashMap<String, String> options = new HashMap<String, String>();

	// Thread to run the actual service code
	protected Thread thd = null;

	// Is the service thread running
	public boolean running;  


	protected NotificationManager mNM;

	protected SharedPreferences mCCNxServicePrefs;

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

		// Init Preferences
		mCCNxServicePrefs = this.getSharedPreferences("ccnxserviceprefs", MODE_WORLD_READABLE);
		setStatus(SERVICE_STATUS.SERVICE_SETUP);
		mNM = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
		showNotification();
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
		// If service failed to start for any reason, should we keep trying to start?
		// We want this service to be sticky to ensure execution for arbitrary duration
		// however, problems in startup usually don't go away, creating repeated user alerts
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
		// If there is a service error, don't clear the status to finished
		// Otherwise we'll never see that the service is in an error state
		//
		if (status != SERVICE_STATUS.SERVICE_ERROR) {
			setStatus(SERVICE_STATUS.SERVICE_FINISHED);
		}
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
					// Do we really want to bury RemoteException?
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

	private void showNotification() {
        // In this sample, we'll use the same text for the ticker and the expanded notification
        CharSequence text = getText(R.string.service_started1_msg);

        // Set the icon, scrolling text and timestamp
        Notification notification = new Notification(R.drawable.ccnxlogo48px, text, System.currentTimeMillis());

        // The PendingIntent to launch our activity if the user selects this notification
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, new Intent(this, Controller.class), 0);

        // Set the info for the views that show in the notification panel.
        notification.setLatestEventInfo(this, getText(R.string.app_name), text, contentIntent);

        // Send the notification.
        mNM.notify(NOTIFICATION, notification);
    }
	protected abstract void onStartService(Intent i);
	protected abstract void runService();
	protected abstract void stopService();
}
