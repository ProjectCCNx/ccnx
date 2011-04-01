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

package org.ccnx.android.services.repo;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Properties;
import java.util.logging.Level;

import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;
import org.ccnx.android.ccnlib.RepoWrapper.REPO_OPTIONS;
import org.ccnx.android.services.CCNxService;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.repo.LogStructRepoStore;
import org.ccnx.ccn.impl.repo.RepositoryServer;
import org.ccnx.ccn.impl.repo.RepositoryStore;

import android.content.Intent;
import android.os.Environment;
import android.util.Log;

/**
 * CCNxService specialization for the Repository.
 */
public final class RepoService extends CCNxService {
	public static final String CLASS_TAG = "CCNxRepoService"; 
	
	private RepositoryServer _server=null;
	private RepositoryStore _repo=null;
	
	public final static String DEFAULT_REPO_DEBUG = "WARNING";
	public final static String DEFAULT_REPO_LOCAL_NAME = "/local";
	public final static String DEFAULT_REPO_GLOBAL_NAME = "/ccnx/repos";
	public final static String DEFAULT_REPO_NAMESPACE = "/";
	
	private String repo_dir = null;
	private String repo_debug = null;
	private String repo_local_name = null;
	private String repo_global_name = null;
	private String repo_namespace = null;
	
	// used for startup & shutdown
	protected Object _lock = new Object();

	public RepoService(){
		TAG=CLASS_TAG;
	}

	protected void onStartService(Intent intent) {
		Log.d(TAG, "Starting");

		try {
			Properties props = new Properties();
			byte [] opts = intent.getByteArrayExtra("vm_options");
			if( null != opts ) {
				ByteArrayInputStream bais = new ByteArrayInputStream(opts);
				props.loadFromXML(bais);

				System.getProperties().putAll(props);
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
		
		Log.d(TAG, "CCN_DIR      = " + UserConfiguration.userConfigurationDirectory());
		Log.d(TAG, "DEF_ALIS     = " + UserConfiguration.defaultKeyAlias());
		Log.d(TAG, "KEY_DIR      = " + UserConfiguration.keyRepositoryDirectory());
		Log.d(TAG, "KEY_FILE     = " + UserConfiguration.keystoreFileName());
		Log.d(TAG, "USER_NAME    = " + UserConfiguration.userName());
		
		if(intent.hasExtra(REPO_OPTIONS.REPO_DIRECTORY.name())){
			repo_dir = intent.getStringExtra(REPO_OPTIONS.REPO_DIRECTORY.name());
		}
		
		if(intent.hasExtra(REPO_OPTIONS.REPO_DEBUG.name())){
			repo_debug = intent.getStringExtra(REPO_OPTIONS.REPO_DEBUG.name());
		} else {
			repo_debug = DEFAULT_REPO_DEBUG;
		}
		
		if(intent.hasExtra(REPO_OPTIONS.REPO_LOCAL.name())){
			repo_local_name = intent.getStringExtra(REPO_OPTIONS.REPO_LOCAL.name());
		} else {
			repo_local_name = DEFAULT_REPO_LOCAL_NAME;
		}
		
		if(intent.hasExtra(REPO_OPTIONS.REPO_GLOBAL.name())){
			repo_global_name = intent.getStringExtra(REPO_OPTIONS.REPO_GLOBAL.name());
		} else {
			repo_global_name = DEFAULT_REPO_GLOBAL_NAME;
		}
		
		if(intent.hasExtra(REPO_OPTIONS.REPO_NAMESPACE.name())){
			repo_namespace = intent.getStringExtra(REPO_OPTIONS.REPO_NAMESPACE.name());
		} else {
			repo_namespace = DEFAULT_REPO_NAMESPACE;
		}
		
		Load();	
	}

	@Override
	protected void runService() {
		setStatus(SERVICE_STATUS.SERVICE_INITIALIZING);
		
		try {
			File f;
			if(repo_dir != null) {
				f = new File(repo_dir);
				f.mkdirs();
			} else {
				File external_dir;
				// repo_dir is null, lets get a directory from the android system
				// in external storage.
				external_dir = Environment.getExternalStorageDirectory();
				f = new File(external_dir.getAbsolutePath() + "/ccnx/repo/");
				f.mkdirs();
				repo_dir = f.getAbsolutePath();
			}
			Log.d(TAG,"Using repo directory " + repo_dir);
			Log.d(TAG,"Using repo debug     " + repo_debug);

			// Set the log level for the Repo
			Log.d(TAG, "Setting CCNx Logging FAC_ALL to " + repo_debug);
			org.ccnx.ccn.impl.support.Log.setLevel(org.ccnx.ccn.impl.support.Log.FAC_ALL, Level.parse(repo_debug));
			
			synchronized(_lock) {
				if( null == _repo ) {
					_repo = new LogStructRepoStore();

					int count = 0;
					while( count < 3 ) {
						try {
							count++;
							_repo.initialize(repo_dir, null, repo_local_name, repo_global_name, repo_namespace, null);
							break;
						} catch(Exception e) {
							if( count >= 3 )
								throw e;
							try {
								Log.d(TAG,"Experiencing problems starting REPO, try again...");
								Thread.sleep(1000);
							} catch(InterruptedException ie) {

							}
						}
					}
					_server = new RepositoryServer(_repo);
					setStatus(SERVICE_STATUS.SERVICE_RUNNING);
					_server.start();
				}
				//ready = true;
			}

		} catch(Exception e) {
			e.printStackTrace();
			thd = null;
			return;
		} finally {
			thd = null;
		}
	}
	
	protected void stopService(){
		Log.i(TAG,"stopService() called");
		
		setStatus(SERVICE_STATUS.SERVICE_TEARING_DOWN);
		if( _server != null ) {
			Log.i(TAG,"calling _server.shutDown()");
			_server.shutDown();
			_server = null;
		}
		serviceStopped();
	}
}
