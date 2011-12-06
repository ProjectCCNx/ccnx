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

import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;
import org.ccnx.android.ccnlib.CcndWrapper.CCND_OPTIONS;
import org.ccnx.android.ccnlib.RepoWrapper.REPO_OPTIONS;

import android.content.Context;
import android.util.Log;

/**
 * This is a helper class to access the ccnd and repo services. It provides
 * abstractions so that the programs can start and stop the services as well
 * as interact with them for configuration and monitoring.
 */
public final class CCNxServiceControl {
	private final static String TAG = "CCNxServiceControl";
	
	CcndWrapper ccndInterface;
	RepoWrapper repoInterface;
	Context _ctx;
	
	CCNxServiceCallback _cb = null;
	
	SERVICE_STATUS ccndStatus = SERVICE_STATUS.SERVICE_OFF;
	SERVICE_STATUS repoStatus = SERVICE_STATUS.SERVICE_OFF;
	
	CCNxServiceCallback ccndCallback = new CCNxServiceCallback(){
		@Override
		public void newCCNxStatus(SERVICE_STATUS st) {
			Log.i(TAG,"ccndCallback ccndStatus = " + st.toString());
			ccndStatus = st;
			switch(ccndStatus){
			case SERVICE_OFF:
				newCCNxAPIStatus(SERVICE_STATUS.CCND_OFF);
				break;
			case SERVICE_INITIALIZING:
				newCCNxAPIStatus(SERVICE_STATUS.CCND_INITIALIZING);
				break;
			case SERVICE_TEARING_DOWN:
				newCCNxAPIStatus(SERVICE_STATUS.CCND_TEARING_DOWN);
				break;
			case SERVICE_RUNNING:
				newCCNxAPIStatus(SERVICE_STATUS.CCND_RUNNING);
				break;
			}
		}
	};
	
	CCNxServiceCallback repoCallback = new CCNxServiceCallback(){
		@Override
		public void newCCNxStatus(SERVICE_STATUS st) {
			Log.i(TAG,"repoCallback repoStatus = " + st.toString());
			repoStatus = st;	
			switch(repoStatus){
			case SERVICE_OFF:
				newCCNxAPIStatus(SERVICE_STATUS.REPO_OFF);
				break;
			case SERVICE_INITIALIZING:
				newCCNxAPIStatus(SERVICE_STATUS.REPO_INITIALIZING);
				break;
			case SERVICE_TEARING_DOWN:
				newCCNxAPIStatus(SERVICE_STATUS.REPO_TEARING_DOWN);
				break;
			case SERVICE_RUNNING:
				newCCNxAPIStatus(SERVICE_STATUS.REPO_RUNNING);
				break;
			}
		}
	};
	
	public CCNxServiceControl(Context ctx) {
		_ctx = ctx;
		ccndInterface = new CcndWrapper(_ctx);
		ccndInterface.setCallback(ccndCallback);
		ccndStatus = ccndInterface.getStatus();
		repoInterface = new RepoWrapper(_ctx);
		repoInterface.setCallback(repoCallback);
		repoStatus = repoInterface.getStatus();
		
	}
	
	public void registerCallback(CCNxServiceCallback cb){
		_cb = cb;
	}
	
	public void unregisterCallback(){
		_cb = null;
	}
	
	/**
	 * Start the CCN daemon and Repo 
	 * If configuration parameters have been set these will be used
	 * This is a BLOCKING call
	 * 
	 * @return true if everything started correctly, false otherwise
	 */
	public boolean startAll(){
		newCCNxAPIStatus(SERVICE_STATUS.START_ALL_INITIALIZING);
		Log.i(TAG,"startAll waitng for startService");
		ccndInterface.startService();
		Log.i(TAG,"startAll waitng for waitForReady");
		ccndInterface.waitForReady();
		newCCNxAPIStatus(SERVICE_STATUS.START_ALL_CCND_DONE);
		if(!ccndInterface.isReady()){
			newCCNxAPIStatus(SERVICE_STATUS.START_ALL_ERROR);
			return false;
		}
		repoInterface.startService();
		repoInterface.waitForReady();
		if(!repoInterface.isReady()){
			newCCNxAPIStatus(SERVICE_STATUS.START_ALL_ERROR);
			return false;
		} 
		newCCNxAPIStatus(SERVICE_STATUS.START_ALL_DONE);
		return true;
	}
	
	/**
	 * Start the CCN daemon and Repo 
	 * If configuration parameters have been set these will be used
	 * This is a non-blocking call.  If you want to be notified when everything
	 * has started then you should register a callback before issuing this call.
	 */
	public void startAllInBackground(){
		Runnable r = new Runnable(){
			public void run() {
				startAll();
			}
		};
		Thread thd = new Thread(r);
		thd.start();
	}
	
	public void connect(){
		ccndInterface.bindIfRunning();
		repoInterface.bindIfRunning();
	}
	
	/**
	 * Disconnect from the services.  This is needed for a clean exit from an application. It leaves the services running.
	 */
	public void disconnect(){
		ccndInterface.unbindService();
		repoInterface.unbindService();
	}
	
	/**
	 * Stop the CCN daemon and Repo 
	 * This call will unbind from the service and stop it. There is no need to issue a disconnect().
	 */
	public void stoptAll(){
		repoInterface.stopService();
		ccndInterface.stopService();
	}
	
	public boolean isCcndRunning(){
		return ccndInterface.isRunning();
	}
	
	public boolean isRepoRunning(){
		return repoInterface.isRunning();
	}
	
	public void startCcnd(){
		ccndInterface.startService();
	}
	
	public void stopCcnd(){
		ccndInterface.stopService();
	}
	
	public void startRepo(){
		repoInterface.startService();
	}
	
	public void stopRepo(){
		repoInterface.stopService();
	}
	
	public void newCCNxAPIStatus(SERVICE_STATUS s){
		Log.d(TAG,"newCCNxAPIStatus sending " + s.toString());
		try {
			if(_cb != null) {
				_cb.newCCNxStatus(s);
			}
		} catch(Exception e){
			// Did the callback just throw an exception??
			// We're going to ignore it, it's not our problem (right?)
			Log.e(TAG,"The client callback has thrown an exception");
			e.printStackTrace();
		}
	}

	public void setCcndOption(CCND_OPTIONS option, String value) {
		ccndInterface.setOption(option, value);
	}
	
	public void setRepoOption(REPO_OPTIONS option, String value) {
		repoInterface.setOption(option, value);
	}
	
	/**
	 * Are ccnd and the repo running and ready?
	 * @return true if BOTH ccnd and the repo are in state Running
	 */
	public boolean allReady(){
		return(SERVICE_STATUS.SERVICE_RUNNING.equals(ccndStatus) &&
			   SERVICE_STATUS.SERVICE_RUNNING.equals(repoStatus));
	}
	
	public SERVICE_STATUS getCcndStatus(){
		return ccndStatus;
	}
	
	public SERVICE_STATUS getRepoStatus(){
		return repoStatus;
	}
}