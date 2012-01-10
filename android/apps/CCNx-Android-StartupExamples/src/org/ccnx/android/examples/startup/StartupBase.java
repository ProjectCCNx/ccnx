package org.ccnx.android.examples.startup;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.security.keys.BasicKeyManager;
import org.ccnx.ccn.profiles.ccnd.CCNDaemonException;
import org.ccnx.ccn.profiles.ccnd.SimpleFaceControl;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ScrollView;
import android.widget.TextView;

public abstract class StartupBase extends Activity {
	protected String TAG="StartupBase";
	protected final static String LOG_LEVEL="WARNING";

	// ===========================================================================
	// Process control Methods

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.starter);

		tv = (TextView) findViewById(R.id.tvOutput);
		sv = (ScrollView) findViewById(R.id.svOutput);

		ScreenOutput("Starting the CCNx thread\n");

	}

	@Override
	public void onStart() {
		super.onStart();	
		tv.setText("");
		tv.invalidate();
		
		_lastScreenOutput = System.currentTimeMillis();
		_firstScreenOutput = _lastScreenOutput;
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
		if( _handle != null ) {
			// close the default handle, which was used by SimpleFaceControl
			_handle.close();
			KeyManager.closeDefaultKeyManager();
			_handle = null;
		}
		super.onDestroy();
	}

	// ===========================================================================
	// UI Methods

	private final static int EXIT_MENU = 1;
	private final static int SHUTDOWN_MENU = 2;

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		boolean result = super.onCreateOptionsMenu(menu);
		menu.add(0, EXIT_MENU, 1, "Exit");
		menu.add(0, SHUTDOWN_MENU, 1, "Exit & Shutdown");
		return result;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case EXIT_MENU:
			doExit();
			closeCcn();
			finish();
			return true;

		case SHUTDOWN_MENU:
			doShutdown();
			closeCcn();
			finish();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	abstract void doExit();
	abstract void doShutdown();

	// ====================================================================
	// Internal implementation

	protected TextView tv = null;
	protected ScrollView sv = null;
	protected SimpleDateFormat _dateformat = new SimpleDateFormat("HH:mm:ss.S");
	protected long _lastScreenOutput = -1;
	protected long _firstScreenOutput = -1;
	protected Object _outputLock = new Object();
	
	protected CCNHandle _handle = null;
	protected KeyManager _km = null;

	protected void openCcn() throws ConfigurationException, IOException, InvalidKeyException {
		org.ccnx.ccn.impl.support.Log.setLevel(org.ccnx.ccn.impl.support.Log.FAC_ALL, Level.parse(LOG_LEVEL));
		
		_km = new BasicKeyManager();
		_km.initialize();
		KeyManager.setDefaultKeyManager(_km);
		_handle = CCNHandle.open(_km);	
	}
	
	protected void closeCcn() {
		if( _handle != null ) {
			_handle.close();
			_handle = null;
		}
		
		if( _km != null ) {
			KeyManager.closeDefaultKeyManager();
			_km = null;
		}
	}
	
	protected void setupFace() throws CCNDaemonException {
		postToUI("Calling SimpleFaceControl");
		SimpleFaceControl.getInstance(_handle).openMulicastInterface();
		postToUI("Finished SimpleFaceControl");
	}
	
	/**
	 * In the UI thread, post a message to the screen
	 */
	protected void ScreenOutput(String s) {
		synchronized(_outputLock) {
			if( _lastScreenOutput < 0 )
				_lastScreenOutput = System.currentTimeMillis();
			if( _firstScreenOutput < 0 )
				_firstScreenOutput = _lastScreenOutput;
			
			Date now = new Date();
			long delta = now.getTime() - _lastScreenOutput;
			double sec = delta / 1000.0;
			
			delta = now.getTime() - _firstScreenOutput;
			double totalsec = delta / 1000.0;
			
			_lastScreenOutput = now.getTime();
			
			String text = String.format("%s (delta %.3f, total %.3f sec)\n%s\n\n",
					_dateformat.format(now),
					sec,
					totalsec,
					s);
			
			tv.append(text);
			tv.invalidate();

			Log.d(TAG,"ScreenOutput: " + text);

			// Now scroll to the bottom
			sv.post(new Runnable() {
				public void run() {
					sv.fullScroll(ScrollView.FOCUS_DOWN);
				}

			});
		}
	}

	/**
	 * this will be called when we receive a chat line.
	 * It is executed in the UI thread.
	 */
	private Handler _handler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			String text = (String) msg.obj;
			ScreenOutput(text);
			msg.obj = null;
			msg = null;
		}
	};

	/**
	 * From ChatCallback, when we get a message from CCN
	 */
	public void postToUI(String message) {
		Message msg = Message.obtain();
		msg.obj = message;
		_handler.sendMessage(msg);		
	}


}
