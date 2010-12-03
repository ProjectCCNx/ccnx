/*
 * CCNx Android Chat
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

package org.ccnx.android.apps.chat;

/**
 * This module is purely UI for chat.  It has an EditText to enter your
 * message and a TextView to display received messages.  The TextView
 * uses a ScrollView so we can keep scrolling the screen to the bottom
 * so the most recent message is always at the bottom.
 * 
 * This UI is very basic and has these limitations:
 *   - No screen orientation change support
 *   - No pause/sleep support
 *   - Does not check the system setting to see if we should not use the network
 * 
 * CCN pieces are in the ChatWorker.
 * 
 * We disable the EditText until we get a ready() call from
 * ChatWorker, indicating that the network is ready for our input.
 * 
 */
import org.ccnx.ccn.config.UserConfiguration;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnKeyListener;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

public class ChatScreen extends Activity implements OnKeyListener, ChatCallback {
	protected final static String TAG="ChatScreen";

	// ===========================================================================
	// Process control Methods

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.ccnchat_chat);

		EditText etPost = (EditText) findViewById(R.id.etPost);
		etPost.setEnabled(false);
		etPost.setOnKeyListener(this);

		tv = (TextView) findViewById(R.id.tvOutput);
		//tv.setMovementMethod(new ScrollingMovementMethod()); 
		tv.setText("");
		sv = (ScrollView) findViewById(R.id.svOutput);

		ScreenOutput("Starting the CCN listen thread\n");

		Intent i = this.getIntent();
		String namespace = i.getStringExtra(CcnxChatMain.PREF_NAMESPACE);
		String username = i.getStringExtra(CcnxChatMain.PREF_HANDLE);
		String remotehost = i.getStringExtra(CcnxChatMain.PREF_REMOTEHOST);
		String remoteport = i.getStringExtra(CcnxChatMain.PREF_REMOTEPORT);

		_worker = new ChatWorker(this, this);
		_worker.start(username, namespace, remotehost, remoteport);

		// Do these CCNx operations after we created ChatWorker
		ScreenOutput("User name = " + UserConfiguration.userName() + "\n");
		ScreenOutput("ccnDir    = " + UserConfiguration.userConfigurationDirectory() + "\n");
		ScreenOutput("Waiting for CCN Services to become ready\n");
	}

	@Override
	public void onStart() {
		super.onStart();	
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
			finish();
			return true;

		case SHUTDOWN_MENU:
			_worker.shutdown();
			finish();
			return true;
		}

		return super.onOptionsItemSelected(item);
	}

	@Override
	public boolean onKey(View v, int keyCode, KeyEvent event) {
		// send text when enter pressed

		Log.d(TAG, "KeyEvent = " + keyCode);
		Log.d(TAG, "Event = " + event.toString());

		if( keyCode == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_UP ) {
			EditText etPost = null;
			etPost = (EditText) findViewById(R.id.etPost);
			UiToCcn(etPost.getText().toString());
			etPost.setText("");	
			return true;
		}

		return false;
	}

	// ====================================================================
	// Internal implementation

	protected ChatWorker _worker;
	protected TextView tv = null;
	protected ScrollView sv = null;

	/**
	 * In the UI thread, post a message to the screen
	 */
	protected void ScreenOutput(String s) {
		tv.append(s);
		tv.invalidate();

		// Now scroll to the bottom
		sv.post(new Runnable() {
			public void run() {
				sv.fullScroll(ScrollView.FOCUS_DOWN);
			}

		});
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
	 * Send a message to the network
	 */
	protected void UiToCcn(String s) {
		_worker.send(s);
	}

	/**
	 * From ChatCallback, when we get a message from CCN
	 */
	public void recv(String message) {
		Message msg = Message.obtain();
		msg.obj = message;
		_handler.sendMessage(msg);		
	}

	/**
	 * Called by ChatWorker based on CCNx service state
	 */
	@Override
	public void ccnxServices(boolean ok) {
		if ( ok ) {
			recv("CCN Services now ready -- let's chat!\n");
			
			// activate the edit text by sending a Runnable to
			// the UI handler.
			_handler.post(
					new Runnable() {
						@Override
						public void run() {
							EditText etPost = (EditText) findViewById(R.id.etPost);
							etPost.setEnabled(true);
						}
					}
			);
		} else {
			recv("CCN Service error, cannot chat!\n");
		}
	}
}
