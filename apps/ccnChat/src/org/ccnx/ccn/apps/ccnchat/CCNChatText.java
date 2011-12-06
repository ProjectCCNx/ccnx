/*
 * A CCNx chat program.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.apps.ccnchat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import org.ccnx.ccn.apps.ccnchat.CCNChatNet.CCNChatCallback;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * A text-based interface to CCNChat.
 * 
 * Because we don't want to depend on an external curses library,
 * the UI uses a 2-mode interface.  The normal mode is OUTPUT, which
 * means received chat messages are displayed immediately.  If the
 * user pressed ENTER, we switch to INPUT mode.  Received chat messages
 * are queued and only displayed when the user leaves INPUT mode
 * by pressing ENTER again.
 * 
 * System.in is buffered, so we can only get when the user presses ENTER.
 * We will save whatever what typed before and put it as the start of
 * the INPUT line.
 */
public class CCNChatText implements Runnable, CCNChatCallback {

	public static void usage() {
		System.err.println("usage: CCNChat <ccn URI>");
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length != 1) {
			usage();
			System.exit(-1);
		}
		CCNChatText client;
		try {
			client = new CCNChatText(args[0]);
			client.start();
		} catch (MalformedContentNameStringException e) {
			System.err.println("Not a valid ccn URI: " + args[0] + ": " + e.getMessage());
			e.printStackTrace();
		} catch (ConfigurationException e) {
			System.err.println("Configuration exception running ccnChat: " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.err.println("IOException handling chat messages: " + e.getMessage());
			e.printStackTrace();
		}
	}


	public void run() {
		BufferedReader br = new BufferedReader(new InputStreamReader(System.in));

		output("Mode = " + _mode + "\n");
		output("Press ENTER to switch to INPUT mode.\n");



		while(true) {
			String message = null;
			String moreMessage = null;

			// Check for a character input
			try {
			    if( br.ready() ) {
					_mode = Mode.MODE_INPUT;
				}
			} catch(Exception e) {

			}

			switch(_mode) {
			case MODE_INPUT:
				// Read in the current input and put it out there
				try {
				    if (null == (message = br.readLine())) {
					System.out.println();
					System.out.println("Bye");
					System.exit(0);
				    }
				} catch (IOException e1) {
					e1.printStackTrace();
				}
				
				System.out.print("INPUT: " + message);
				try {
				    if (null == (moreMessage = br.readLine())) {
					System.out.println();
					System.out.println("Bye");
					System.exit(0);
				    }
					_chat.sendMessage(message + moreMessage);
				} catch(Exception e) {
					e.printStackTrace();
				}

				_mode = Mode.MODE_OUTPUT;
				break;

			case MODE_OUTPUT:
				try {
					message = _queue.poll(50, TimeUnit.MILLISECONDS);
				} catch (InterruptedException e) {
				}

				if( null != message ) {
					output(message);
				}
				break;
			}
		}
	}

	// =========================================================
	// Internal methods
	private final CCNChatNet _chat;
	private final Thread _thd;
	private final LinkedBlockingQueue<String> _queue = new LinkedBlockingQueue<String>();
	private Mode _mode = Mode.MODE_OUTPUT;
	private enum Mode {
		MODE_INPUT,
		MODE_OUTPUT
	}

	/**
	 * Send a message to the console.  Message should include its
	 * own newline, if so desired.
	 * @param message
	 */
	protected void output(String message) {
		System.out.print(message);
	}

	protected CCNChatText(String namespace) throws MalformedContentNameStringException {
		_chat = new CCNChatNet(this, namespace);
		_thd = new Thread(this, "CCNChatText");
	}

	/**
	 * Called by window thread when when window closes
	 */
	protected void stop() throws IOException {
		_chat.shutdown();
	}

	/**
	 * This blocks until _chat.shutdown() called.
	 * 
	 * This is run in the main() thread.
	 * 
	 * @throws IOException 
	 * @throws MalformedContentNameStringException 
	 * @throws ConfigurationException 
	 */
	protected void start() throws ConfigurationException, MalformedContentNameStringException, IOException {
		_thd.start();
		_chat.setLogging(Level.WARNING);
		_chat.listen();
	}


	public void recvMessage(String message) {
		_queue.add(message);
	}

}
