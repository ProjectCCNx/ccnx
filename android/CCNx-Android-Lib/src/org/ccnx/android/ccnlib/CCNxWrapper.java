/*
 * CCNx Android Helper Library.
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
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

import java.util.List;
import java.util.Properties;
import java.util.Map.Entry;

import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

/**
 * Wrap up the AIDL calls to a CCNxService.  Hide all the ugly RPC from
 * the user of the services.
 */
public abstract class CCNxWrapper {
	public String TAG = "CCNxWrapper";
	
	private static final int DEFAULT_WAIT_FOR_READY = 60000;
	
	private Object _lock = new Object();
	
	// Timeout to wait for ready in ms
	private int waitForReadyTimeout = DEFAULT_WAIT_FOR_READY;
	
	protected String serviceClassName = "";
	protected String serviceName = "";
	protected String serviceType = TAG;
	
	protected ServiceConnection sConn;
	
	protected Object iServiceLock = new Object();
	protected ICCNxService iService = null;
	
	protected String OPTION_LOG_LEVEL = "0";
	
	protected Properties options = new Properties();
	
	CCNxServiceCallback _scb;

	SERVICE_STATUS status = SERVICE_STATUS.SERVICE_OFF;
	
	Context _ctx;
	
	private IStatusCallback _cb = new IStatusCallback.Stub() {
		public void status(int st){
			Log.d(TAG,"Received a status update.. " + status.name());
			synchronized(_lock) {
				setStatus(SERVICE_STATUS.fromOrdinal(st));
				if(SERVICE_STATUS.SERVICE_FINISHED.equals(status)){
					setStatus(SERVICE_STATUS.SERVICE_OFF);
				}
			}
			issueCallback();
		}
	};
	
	public CCNxWrapper(Context ctx){
		_ctx = ctx;
	}
	
	public void setCallback(CCNxServiceCallback scb){
		_scb = scb;
	}
	
	protected void issueCallback(){
		_scb.newCCNxStatus(status);
	}
	
	/**
	 * Start the service.  If the service is running we will not try to start it
	 * we will only try to bind to it
	 * @return true if we are bound, false if we are not, some error occurred
	 */
	public boolean startService(){
		Log.d(TAG,"startService()");
		if(!isRunning()){
			_ctx.startService(getStartIntent());
		} else {
			setStatus(SERVICE_STATUS.SERVICE_RUNNING);
			issueCallback();
		}
		bindService();
		return isBound();
	}
	
	public void bindIfRunning(){
		Log.d(TAG,"If Running, Bind");
		if(isRunning()){
			bindService();
		}
	}
	
	/**
	 * Check whether we are bound to the running service
	 * @return true if we are bound, false if we are not
	 */
	public boolean isBound(){
		synchronized(iServiceLock) {
			return(iService != null);
		}
	}
	
	protected void bindService(){
		sConn = new ServiceConnection(){
			public void onServiceConnected(ComponentName name, IBinder binder) {
				Log.d(TAG, " Service Connected");
				synchronized(iServiceLock) {
					iService = ICCNxService.Stub.asInterface(binder);
					try {
						iService.registerStatusCallback(_cb);
						SERVICE_STATUS st = SERVICE_STATUS.fromOrdinal(iService.getStatus());
						Log.i(TAG,"bindService sets status: " + st);
						setStatus(st);
					} catch (RemoteException e) {
						// Did the service crash?
						e.printStackTrace();
					}
				}
				issueCallback();
			}

			public void onServiceDisconnected(ComponentName name) {
				Log.i(TAG, " Service Disconnected");
				synchronized(iServiceLock) {
					iService = null;
				}
				
			}	
		};
		_ctx.bindService(getBindIntent(), sConn, Context.BIND_AUTO_CREATE);
	}
	
	protected void unbindService(){
		synchronized(iServiceLock) {
			if(isBound()){
				_ctx.unbindService(sConn);
			}
			iService = null;
		}
	}
	
	public void stopService(){
		synchronized(iServiceLock) {
			try {
				if( null != iService) 
					iService.stop();
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			unbindService();
		}
		_ctx.stopService(new Intent(serviceName));
	}
	
	public boolean isRunning(){
		ActivityManager am = (ActivityManager) _ctx.getSystemService(Activity.ACTIVITY_SERVICE);
		List<ActivityManager.RunningServiceInfo> services = am.getRunningServices(50);
		for(ActivityManager.RunningServiceInfo s : services){
			if(s.service.getClassName().equalsIgnoreCase(serviceClassName)){
				Log.d(TAG,"Found " + serviceClassName + " Service Running");
				return true;
			} 
		}
		Log.d(TAG,serviceClassName + " Not Running");
		return false;
	}
	
	/**
	 * Sets the default timeout to be used for the waitForReady() call.
	 * Set it to 0 to not use a timeout.
	 * 
	 * @param timeout timeout to be used in waitForReady()
	 */
	public void setWaitForReadyTimeout(int timeout){
		waitForReadyTimeout = timeout;
	}
	
	/**
	 * waits until this service is in state RUNNING. (blocking call)
	 * There is a timeout associated with this wait. It can be changed
	 * using the setWaitForReadyTimeout() function.  If set to 0 it will wait forever.
	 * 
	 * Alternatively, you can call waitForReady() and give it the timeout parameter as the argument.
	 * 
	 * @return true if we are ready, false if we timed out
	 */
	public boolean waitForReady(){
		return waitForReady(waitForReadyTimeout);
	}
	

	/**
	 * waits until the service is in state RUNNING. (blocking call)
	 * This function takes a timeout as a parameter. If the timeout is triggered it will
	 * return false. If timeout is set to 0 it won't be used and wait will block indefinitely.
	 * 
	 * This function does not use the default value ser by setWaitForReadyTimeout().
	 * 
	 * @param timeout timeout passed to the wait() call.
	 * @return true if we are ready, false otherwise
	 */
	public boolean waitForReady(int timeout){
		synchronized(_lock){
			while(!SERVICE_STATUS.SERVICE_RUNNING.equals(status)){
				try {
					if(timeout == 0){
						_lock.wait();
					} else {
						_lock.wait(waitForReadyTimeout);
					}
				} catch (InterruptedException e) {
					// Our timeout expired
					return false;
				}
			}
		}
		return true;
	}
	
	public boolean isReady(){
		return (SERVICE_STATUS.SERVICE_RUNNING.equals(status));
	}
	
	public void setOption(String key, String value) {
		options.setProperty(key, value);
	}
	
	public void clearOptions() {
		options.clear();
	}
	
	protected void fillIntentOptions(Intent i) {
		for(Entry<Object, Object> entry : options.entrySet()) {
			String key = (String) entry.getKey();
			String value = (String) entry.getValue();
			i.putExtra(key, value);	
			Log.i(TAG, "Adding option " + key + " = " + value);
		}
	}
	
	/**
	 * Create an Intent to be issued to bind to the Service. It will be used as the parameter to bindService()
	 * This function is useful for adding "extras" to the intent
	 * 
	 * @return Intent to start this service
	 */
	protected abstract Intent getBindIntent();
	
	/**
	 * Create an Intent to be issued to start the Service. It will be used as the parameter to startService()
	 * This function is useful for adding "extras" to the intent
	 * 
	 * @return Intent to start this service
	 */
	protected abstract Intent getStartIntent();
	
	public SERVICE_STATUS getStatus(){
		return status;
	}
	
	protected void setStatus(SERVICE_STATUS st) {
		Log.i(TAG,"setStatus = " + st);
		synchronized(_lock) {
			status = st;
			_lock.notifyAll();
		}
	}
}
