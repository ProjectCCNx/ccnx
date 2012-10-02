/*
 * CCNx Android Services
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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
import java.util.HashMap;

import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;
import org.ccnx.android.ccnlib.RepoWrapper.REPO_OPTIONS;
import org.ccnx.android.ccnlib.RepoWrapper.CCNR_OPTIONS;
import org.ccnx.android.ccnlib.RepoWrapper.CCNS_OPTIONS;
import org.ccnx.android.services.CCNxService;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.repo.LogStructRepoStore;
import org.ccnx.ccn.impl.repo.RepositoryServer;
import org.ccnx.ccn.impl.repo.RepositoryStore;

import android.content.Intent;
import android.content.SharedPreferences;
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
	public final static String DEFAULT_SYNC_DEBUG = "WARNING";
	public final static String DEFAULT_REPO_PROTO = "unix";
	
	private String repo_dir = null;
	private String repo_debug = null;
	private String repo_local_name = null;
	private String repo_global_name = null;
	private String repo_namespace = null;
	/* We should version the impl 
	 * However we only provide versions bundled
	 * with the CCNx release, currently just v1 and v2.
	 * Does it make sense to use semver for our repo?
	 * Presumably we'll keep legacy versions around for backwards compatibility. 
	 * But in addition to api versions, we'll potentially have pluggable repos, so 
	 * will need to be able to load the version of repo at runtime.  So may be that 
	 * will use reflection to load the repo instead of an ugly if-else/switch statement that 
	 * we have at the moment.
	 */
	private String repo_version = "2.0.0"; // XXX Make this configurable via Android Menu
	
	// used for startup & shutdown
	protected Object _lock = new Object();

	public RepoService(){
		TAG=CLASS_TAG;
	}

	protected void onStartService(Intent intent) {
		Log.d(TAG, "onStartService - Starting");
		boolean isPrefSet = false;

		// Get all the CCND options from the intent 
		// If no option is found on intent, look in System properties
		// If no system property is set, fallback to preferences
		// And while settings OPTIONS, set preferences
		// We will only attempt a recovery if we are running REPO 2.0.0
		SharedPreferences.Editor prefsEditor = mCCNxServicePrefs.edit();

		if (intent != null) {
			if (Pattern.matches("1\\.0\\.0", repo_version)) {
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
			} else if (Pattern.matches("2\\.0\\.0", repo_version)) {
				for( CCNR_OPTIONS opt : CCNR_OPTIONS.values() ) {
					if(!intent.hasExtra(opt.name())){
						continue;
					}
					String s = intent.getStringExtra( opt.name() );
					if( null == s ) 
						s = System.getProperty(opt.name());
						Log.d(TAG,"setting option " + opt.name() + " = " + s);
					if( s != null ) {
						options.put(opt.name(), s);
						isPrefSet = true;
						prefsEditor.putString(opt.name(), s);
					}
				}
				for( CCNS_OPTIONS opt : CCNS_OPTIONS.values() ) {
					if(!intent.hasExtra(opt.name())){
						continue;
					}
					String s = intent.getStringExtra( opt.name() );
					if( null == s ) 
						s = System.getProperty(opt.name());
						Log.d(TAG,"setting option " + opt.name() + " = " + s);
					if( s != null ) {
						options.put(opt.name(), s);
						isPrefSet = true;
						prefsEditor.putString(opt.name(), s);
					}
				}
				if (isPrefSet) {
					prefsEditor.commit();
				}
			} else {
				Log.d(TAG,"Unknown Repo version " + repo_version + " specified, failed to start Repo.");
				setStatus(SERVICE_STATUS.SERVICE_ERROR);
			}
		} else {
			// We must load options from prefs
			options = new HashMap<String, String>((HashMap<String, String>)mCCNxServicePrefs.getAll());
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
				Log.d(TAG, "Exception while invoking runService().  Reason: " + e.getMessage());
				setStatus(SERVICE_STATUS.SERVICE_ERROR);
			} finally {
				thd = null;
			}
		} else if (Pattern.matches("2\\.0\\.0", repo_version)) {
			Log.d(TAG,"Repo version 2 starting using native C-based repo optimized for ARMv7");
			
			/* Only set defaults when the CCNR/SYNC defaults are not appropriate for Android */
			if(options.get(CCNR_OPTIONS.CCNR_DEBUG.name()) == null) {
				options.put(CCNR_OPTIONS.CCNR_DEBUG.name(), DEFAULT_REPO_DEBUG);
			} else {
				Log.d(TAG,CCNR_OPTIONS.CCNR_DEBUG.name() + " = " + options.get(CCNR_OPTIONS.CCNR_DEBUG.name()));
			}
			
			/* Make sure this directory is on the external storage */
			if(options.get(CCNR_OPTIONS.CCNR_DIRECTORY.name()) == null){
				repo_dir = DEFAULT_REPO_DIR;
				options.put(CCNR_OPTIONS.CCNR_DIRECTORY.name(), repo_dir);
			} else {
				repo_dir = options.get(CCNR_OPTIONS.CCNR_DIRECTORY.name());
				if (!repo_dir.startsWith(Environment.getExternalStorageDirectory().getAbsolutePath())) {
					repo_dir = Environment.getExternalStorageDirectory().getAbsolutePath() + repo_dir;
					options.put(CCNR_OPTIONS.CCNR_DIRECTORY.name(), repo_dir);
					Log.d(TAG, "CCNR_DIRECTORY remapped to contain external storage path = " + repo_dir);
				}
				Log.d(TAG,CCNR_OPTIONS.CCNR_DIRECTORY.name() + " = " + repo_dir);
			}
			
			if(options.get(CCNR_OPTIONS.CCNR_GLOBAL_PREFIX.name()) == null) {
				options.put(CCNR_OPTIONS.CCNR_GLOBAL_PREFIX.name(), DEFAULT_REPO_GLOBAL_NAME);
			} else {
				Log.d(TAG,CCNR_OPTIONS.CCNR_GLOBAL_PREFIX.name() + " = " + options.get(CCNR_OPTIONS.CCNR_GLOBAL_PREFIX.name()));
			}
			
			/* Take CCNR_BTREE_* defaults */
			/* Take CCNR_CONTENT_CACHE defaults */
			/* Take CCNR_MIN_SEND_BUFSIZE */
			
			/* If we decide to split the CCND and CCNR services into separate APKs
			 * We'll need to toggle this to use tcp */
			if(options.get(CCNR_OPTIONS.CCNR_PROTO.name()) == null) {
				options.put(CCNR_OPTIONS.CCNR_PROTO.name(), DEFAULT_REPO_PROTO);
			} else {
				Log.d(TAG,CCNR_OPTIONS.CCNR_PROTO.name() + " = " + CCNR_OPTIONS.CCNR_PROTO.name());
			}
			
			/* Take CCNR_LISTEN_ON defaults */
			/* Take CCNR_STATUS_PORT defaults */
			/* Take all CCNS_* defaults */
			/* Don't bother with undocumented CCNS_* variables */
			
			if ((repo_dir = createRepoDir(repo_dir)) == null) {
				//
				// If we can't create the directory 
				// reasons: no perms, external storage unavailable
				// then we cannot proceed
				Log.e(TAG,"Repo version 2 unable to start because cannot create repo_dir");
				setStatus(SERVICE_STATUS.SERVICE_ERROR);
				return;
			}
			try {
				for( Entry<String,String> entry : options.entrySet() ) {
					Log.d(TAG, "options key setenv: " + entry.getKey());
					ccnrSetenv(entry.getKey(), entry.getValue(), 1);
				}
	
				if (ccnrCreate(repo_version) == 0) {
					setStatus(SERVICE_STATUS.SERVICE_RUNNING);
					try {
						ccnrRun();
					} finally {
						ccnrDestroy();
					}
				} else {
					// If we have problems initially creating the CCNR handle, we should shutdown with error
					Log.d(TAG,"ccnrCreate failure, failed to start Repo.");
					setStatus(SERVICE_STATUS.SERVICE_ERROR);
				}
			} catch(Exception e) {
				e.printStackTrace();
				Log.d(TAG, "Exception caught while starting up/shutting down.  Reason: " + e.getMessage()); 
				setStatus(SERVICE_STATUS.SERVICE_ERROR);
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
		setStatus(SERVICE_STATUS.SERVICE_FINISHED); // XXX Is it really ok to assume we've stopped when we might get errors?
	}

	private String createRepoDir(String repodir) {
		File f;
		File external_dir = Environment.getExternalStorageDirectory();
		
		if(repodir != null) {
			// Check if repodir contains the external storage path
			if (!repodir.startsWith(external_dir.getAbsolutePath())) {
				repodir = external_dir.getAbsolutePath() + repodir;
			}
			
			f = new File(repodir); 
			if (f.mkdirs()) {
				Log.d(TAG,"Created repodir = " + repodir);
			} else {
				Log.d(TAG,"Unable to create repodir = " + repodir + ", already exists");
			}
			
		} else {
			// repo_dir is null, lets get a directory from the android system
			// in external storage.
			f = new File(external_dir.getAbsolutePath() + DEFAULT_REPO_DIR);
			if (f.mkdirs()) {
				Log.d(TAG,"Created default repodir = " + external_dir.getAbsolutePath() + DEFAULT_REPO_DIR);
			} else {
				Log.d(TAG,"Unable to create default repodir = " +  external_dir.getAbsolutePath() + DEFAULT_REPO_DIR + ", already exists");
			}
			repodir = f.getAbsolutePath();
		}
		return repodir;
	}
	
	private boolean deleteRepoDir() {
		File f;
		if(repo_dir != null) {
			f = new File(repo_dir);
			try {
				return (f.delete());
			} catch(SecurityException se) {
				Log.e(TAG, "deleteRepoDir SecurityException: " + se.getMessage());
				return false;
			}
		} else {
			return false;
		}
	}
	
	protected native int ccnrCreate(String version);
	protected native int ccnrRun();
    protected native int ccnrDestroy();
    protected native int ccnrKill();
    protected native void ccnrSetenv(String key, String value, int overwrite);
    
    static {
    	//
    	// load library
    	try {
    		System.loadLibrary("controller");
    		Log.e(CLASS_TAG, "loaded native library: controller");
    	} catch(UnsatisfiedLinkError ule) {
    		Log.e(CLASS_TAG, "Unable to load native library: controller");
    	}
    }
}
