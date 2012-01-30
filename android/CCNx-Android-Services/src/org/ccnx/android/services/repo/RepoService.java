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
import java.util.regex.Pattern;
import java.util.Map.Entry;

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
	public final static String DEFAULT_REPO_DIR = "/ccnx/repo";
	public final static String DEFAULT_REPO_NAMESPACE = "/"; 
	public final static String DEFAULT_SYNC_ENABLE = "1";
	public final static String DEFAULT_REPO_PROTO = "unix";
	
	private String repo_dir = null;
	private String repo_debug = null;
	private String repo_local_name = null;
	private String repo_global_name = null;
	private String repo_namespace = null;
	/* We should version the impl 
	 * However we only provide versions bundled
	 * with the CCNx release, currently just v1 and v2.
	 * Does it make sense for support semver of our repo
	 */
	private String repo_version = "2.0.0"; // XXX Make this configurable via Android Menu
	
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
		
		if (Pattern.matches("1\\.0\\.0", repo_version)) {
			try {
				repo_dir = createRepoDir(repo_dir);
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
	
						Log.d(TAG,"Repo version 1 starting using Java-based repo");
						_server = new RepositoryServer(_repo);
						setStatus(SERVICE_STATUS.SERVICE_RUNNING);
						_server.start();
					}
				}
			} catch(Exception e) {
				e.printStackTrace();
				thd = null;
				return;
			} finally {
				thd = null;
			}
		} else if (Pattern.matches("2\\.0\\.0", repo_version)) {
			Log.d(TAG,"Repo version 2 starting using native C-based repo optimized for ARMv7");
			if ((repo_dir = createRepoDir(repo_dir)) == null) {
				//
				// If we can't create the directory 
				// reasons: no perms, external storage unavailable
				// then we cannot proceed
				Log.e(TAG,"Repo version 2 unable to start because cannot create repo_dir");
				setStatus(SERVICE_STATUS.SERVICE_ERROR);
				return;
			}
			
			/* String ccnd_port = options.get(CCNR_OPTIONS.CCN_LOCAL_PORT.name());
			if( ccnd_port == null ) {
				ccnd_port = OPTION_CCN_PORT_DEFAULT;
				options.put(CCND_OPTIONS.CCN_LOCAL_PORT.name(), ccnd_port);
			}
			Log.d(TAG,CCND_OPTIONS.CCN_LOCAL_PORT.name() + " = " + options.get(CCND_OPTIONS.CCN_LOCAL_PORT.name()));
			*/
			// String ccnd_keydir = options.get(CCND_OPTIONS.CCND_KEYSTORE_DIRECTORY.name());
			if(options.get(REPO_OPTIONS.CCNR_DIRECTORY.name()) == null) {
				options.put(REPO_OPTIONS.CCNR_DIRECTORY.name(), repo_dir);
			} else {
				Log.d(TAG,REPO_OPTIONS.CCNR_DIRECTORY.name() + " = " + options.get(REPO_OPTIONS.CCNR_DIRECTORY.name()));
			}
			
			if(options.get(REPO_OPTIONS.CCNR_DEBUG.name()) == null) {
				options.put(REPO_OPTIONS.CCNR_DEBUG.name(), DEFAULT_REPO_DEBUG);
			} else {
				Log.d(TAG,REPO_OPTIONS.CCNR_DEBUG.name() + " = " + options.get(REPO_OPTIONS.CCNR_DEBUG.name()));
			}
			
			if(options.get(REPO_OPTIONS.CCNR_GLOBAL_PREFIX.name()) == null) {
				options.put(REPO_OPTIONS.CCNR_GLOBAL_PREFIX.name(), DEFAULT_REPO_GLOBAL_NAME);
			} else {
				Log.d(TAG,REPO_OPTIONS.CCNR_GLOBAL_PREFIX.name() + " = " + options.get(REPO_OPTIONS.CCNR_GLOBAL_PREFIX.name()));
			}
			
			if(options.get(REPO_OPTIONS.CCNR_PROTO.name()) == null) {
				options.put(REPO_OPTIONS.CCNR_PROTO.name(), DEFAULT_REPO_PROTO);
			} else {
				Log.d(TAG,REPO_OPTIONS.CCNR_PROTO.name() + " = " + options.get(REPO_OPTIONS.CCNR_PROTO.name()));
			}
			
			if(options.get(REPO_OPTIONS.CCNR_SYNC_ENABLE.name()) == null) {
				options.put(REPO_OPTIONS.CCNR_SYNC_ENABLE.name(), DEFAULT_SYNC_ENABLE);
			} else {
				Log.d(TAG,REPO_OPTIONS.CCNR_SYNC_ENABLE.name() + " = " + options.get(REPO_OPTIONS.CCNR_SYNC_ENABLE.name()));
			}
			
			try {
				for( Entry<String,String> entry : options.entrySet() ) {
					Log.d(TAG, "options key setenv: " + entry.getKey());
					ccnrSetenv(entry.getKey(), entry.getValue(), 1);
				}
	
				ccnrCreate(repo_version);
				setStatus(SERVICE_STATUS.SERVICE_RUNNING);
				try {
					ccnrRun();
				} finally {
					ccnrDestroy();
				}
			} catch(Exception e) {
				e.printStackTrace();
				// returning will end the thread
			}
			serviceStopped();
		} else {
			Log.d(TAG,"Unknown Repo version " + repo_version + " specified, failed to start Repo.");
			setStatus(SERVICE_STATUS.SERVICE_ERROR);
		}
	}
	
	protected void stopService(){
		Log.i(TAG,"stopService() called");
		
		setStatus(SERVICE_STATUS.SERVICE_TEARING_DOWN);
		if (Pattern.matches("1\\.0\\.0", repo_version)) {
			if( _server != null ) {
				Log.i(TAG,"calling _server.shutDown()");
				_server.shutDown();
				_server = null;
			}
		} else if (Pattern.matches("2\\.0\\.0", repo_version)) {
			setStatus(SERVICE_STATUS.SERVICE_TEARING_DOWN);
        	ccnrKill();
		} else {
			Log.d(TAG,"Unknown Repo version " + repo_version + " specified, failed to stop Repo.");
			setStatus(SERVICE_STATUS.SERVICE_ERROR);
		}
		serviceStopped(); // XXX Is it really ok to assume we've stopped when we might get errors?
	}

	private String createRepoDir(String repodir) {
		File f;
		if(repodir != null) {
			f = new File(repo_dir);
			f.mkdirs();
		} else {
			File external_dir;
			// repo_dir is null, lets get a directory from the android system
			// in external storage.
			external_dir = Environment.getExternalStorageDirectory();
			f = new File(external_dir.getAbsolutePath() + DEFAULT_REPO_DIR);
			f.mkdirs();
			repodir = f.getAbsolutePath();
		}
		return repodir;
	}
	protected native int ccnrCreate(String version);
	protected native int ccnrRun();
    protected native int ccnrDestroy();
    protected native int ccnrKill();
    protected native void ccnrSetenv(String key, String value, int overwrite);
    
    static {
    	//
    	// load library
    	//
    	try {
    		System.loadLibrary("controller");
    		Log.e(CLASS_TAG, "loaded native library: controller");
    	} catch(UnsatisfiedLinkError ule) {
    		Log.e(CLASS_TAG, "Unable to load native library: controller");
    	}
    }
}