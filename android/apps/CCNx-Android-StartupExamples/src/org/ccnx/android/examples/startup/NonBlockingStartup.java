package org.ccnx.android.examples.startup;

import java.io.File;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.concurrent.CountDownLatch;

import org.ccnx.android.ccnlib.CCNxConfiguration;
import org.ccnx.android.ccnlib.CCNxServiceCallback;
import org.ccnx.android.ccnlib.CCNxServiceControl;
import org.ccnx.android.ccnlib.CCNxServiceStatus.SERVICE_STATUS;
import org.ccnx.android.ccnlib.CcndWrapper.CCND_OPTIONS;
import org.ccnx.android.ccnlib.RepoWrapper.REPO_OPTIONS;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.profiles.ccnd.CCNDaemonException;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class NonBlockingStartup extends StartupBase {
	protected String TAG="NonBlockingStartup";
	
	// ===========================================================================
	// Process control Methods

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		TextView title = (TextView) findViewById(R.id.tvTitle);
		title.setText("NonBlockingStartup");
	}

	@Override
	public void onStart() {
		super.onStart();	
		Log.i(TAG,"onStart");
		_worker = new NonBlockingWorker();
		_thd = new Thread(_worker);
		_thd.start();
	}

	@Override
	public void onResume() {
		super.onResume();
	}

	@Override
	public void onPause() {
		super.onPause();
	}

	@Override
	public void onDestroy() {
		Log.i(TAG, "onDestroy()");

		_worker.stop();

		super.onDestroy();
	}

	// ===========================================================================
	// UI Methods

	@Override
	void doExit() {
		
	}
	
	@Override
	void doShutdown() {
		_worker.shutdown();
	}

	// ====================================================================
	// Internal implementation

	protected NonBlockingWorker _worker = null;
	protected Thread _thd = null;

	// ===============================================
	protected class NonBlockingWorker implements Runnable, CCNxServiceCallback {
		protected final static String TAG="NonBlockingWorker";

		/**
		 * Create a worker thread to handle all the CCNx calls.
		 */
		public NonBlockingWorker() {
			_context = NonBlockingStartup.this.getBaseContext();

			postToUI("Setting CCNxConfiguration");
			
			// Use a shared key directory
			CCNxConfiguration.config(_context, false);

			File ff = getDir("storage", Context.MODE_WORLD_READABLE);
			postToUI("Setting setUserConfigurationDirectory: " + ff.getAbsolutePath());
			
			Log.i(TAG,"getDir = " + ff.getAbsolutePath());
			UserConfiguration.setUserConfigurationDirectory( ff.getAbsolutePath() );
			
			// Do these CCNx operations after we created ChatWorker
			ScreenOutput("User name = " + UserConfiguration.userName());
			ScreenOutput("ccnDir    = " + UserConfiguration.userConfigurationDirectory());
			ScreenOutput("Waiting for CCN Services to become ready");
		}

		/**
		 * Exit the worker thread, but keep services running
		 */
		public synchronized void stop() {
			// this is called form onDestroy too, so only do something
			// if the user didn't select a menu option to exit or shutdown.
			if( _latch.getCount() > 0 ) {
				_latch.countDown();
				_ccnxService.disconnect();
			}
		}

		/**
		 * Exit the worker thread and shutdown services
		 */
		public synchronized void shutdown() {
			_latch.countDown();
			try {
				_ccnxService.stopAll();
			} catch(Exception e) {
				e.printStackTrace();
			}
		}

		/**
		 * Runnable method
		 */
		@Override
		public void run() {
			// Startup CCNx in a blocking call
			postToUI("Starting CCNx in non-blocking mode");
			initializeNonBlockingCCNx();
			

			// wait for shutdown
			postToUI("Worker thread now blocking until exit");

			while( _latch.getCount() > 0 ) {
				try {
					_latch.await();
				} catch (InterruptedException e) {
				}
			}
			
			Log.i(TAG, "run() exits");		
		}

		// ==============================================================================
		// Internal implementation
		protected final CountDownLatch _latch = new CountDownLatch(1);
		protected final Context _context;
		protected CCNxServiceControl _ccnxService;

		/*********************************************/
		// These are all run in the CCN thread

		private void initializeNonBlockingCCNx() {
			_ccnxService = new CCNxServiceControl(_context);
			_ccnxService.registerCallback(this);
			_ccnxService.setCcndOption(CCND_OPTIONS.CCND_DEBUG, "1");
			_ccnxService.setRepoOption(REPO_OPTIONS.REPO_DEBUG, LOG_LEVEL);
			postToUI("calling startAllInBackground");
			_ccnxService.startAllInBackground();
		}

		/**
		 * Called from CCNxServiceControl
		 */
		@Override
		public void newCCNxStatus(SERVICE_STATUS st) {
			postToUI("CCNxStatus: " + st.toString());
			
			switch(st) {

			case START_ALL_DONE:
				try {
					postToUI("Opening CCN key manager/handle");
					openCcn();
					
					setupFace();
					postToUI("Finished CCNx Initialization");

				} catch (CCNDaemonException e) {
					e.printStackTrace();
					postToUI("SimpleFaceControl error: " + e.getMessage());
				} catch (ConfigurationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (InvalidKeyException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}

				break;
			case START_ALL_ERROR:
				postToUI("CCNxStatus ERROR");
				break;
			}
		}
	}
}
