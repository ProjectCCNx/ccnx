/*
 * A CCNx chat program.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;

import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.io.content.CCNStringObject;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

/**
 * Based on a client/server chat example in Robert Sedgewick's Algorithms
 * in Java.
 * @author smetters
 *
 */
public class CCNChat extends JFrame implements ActionListener {

	private static final long serialVersionUID = -8779269133035264361L;

	protected ContentName _namespace;
	// Separate read and write libraries so we will read our own updates,
	// and don't have to treat our inputs differently than others.
	protected CCNStringObject _readString;
	protected CCNStringObject _writeString;
	protected Timestamp _lastUpdate;
	boolean _finished = false;
	
	protected static final long CYCLE_TIME = 1000;
	protected static final String SYSTEM = "System";
	protected static SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("HH:mm");

    // Chat window
    protected JTextArea  _messagePane = new JTextArea(10, 32);
    private JTextField _typedText   = new JTextField(32);

    public CCNChat(String namespace) throws MalformedContentNameStringException {

    	this._namespace = ContentName.fromURI(namespace);

        // close output stream  - this will cause listen() to stop and exit
        addWindowListener(
            new WindowAdapter() {
                public void windowClosing(WindowEvent e) {
                    try {
						shutdown();
					} catch (IOException e1) {
						System.out.println("IOException shutting down listener: " + e1);
						e1.printStackTrace();
					}
                }
            }
        );
        
        
        // Make window
        _messagePane.setEditable(false);
        _messagePane.setBackground(Color.LIGHT_GRAY);
        _messagePane.setLineWrap(true);
        _typedText.addActionListener(this);

        Container content = getContentPane();
        content.add(new JScrollPane(_messagePane), BorderLayout.CENTER);
        content.add(_typedText, BorderLayout.SOUTH);
        
        // display the window, with focus on typing box
        setTitle("CCNChat 1.0: [" + _namespace + "]");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        pack();
        _typedText.requestFocusInWindow();
        setVisible(true);
    }

    /**
     * Turn off everything.
     * @throws IOException 
     */
	public void shutdown() throws IOException {
		_finished = true;
		if (null != _readString) {
			_readString.cancelInterest();
			showMessage(SYSTEM, now(), "Shutting down " + _namespace + "...");
		}
	}
	
	/**
	 * Process input to TextField after user hits enter.
	 * (non-Javadoc)
	 * @see java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
	 */
	public void actionPerformed(ActionEvent e) {
		try {
			String newText = _typedText.getText();
			if ((null != newText) && (newText.length() > 0)) {
				_writeString.save(newText);
			}

		} catch (Exception e1) {
			System.err.println("Exception saving our input: " + e1.getClass().getName() + ": " + e1.getMessage());
			e1.printStackTrace();
			showMessage("System", now(), "Exception saving our input: " + e1.getClass().getName() + ": " + e1.getMessage());
		}
        _typedText.setText("");
        _typedText.requestFocusInWindow();
	}
	
	public void listen() throws ConfigurationException, IOException {
		_readString = new CCNStringObject(_namespace, (String)null, SaveType.RAW, CCNHandle.open());
		_readString.updateInBackground(true);
		
		String introduction = UserConfiguration.userName() + " has entered " + _namespace;
		_writeString = new CCNStringObject(_namespace, introduction, SaveType.RAW, CCNHandle.open());
		_writeString.save();
		
		// Need to do synchronization for updates that come in while we're processing last one.
		while (!_finished) {
			try {
				synchronized(_readString) {
					_readString.wait(CYCLE_TIME);
				}
			} catch (InterruptedException e) {
			}
			if (_readString.isSaved()) {
				Timestamp thisUpdate = _readString.getVersion();
				if ((null == _lastUpdate) || thisUpdate.after(_lastUpdate)) {
					System.out.println("Got an update: " + _readString.getVersion());
					_lastUpdate = thisUpdate;
					showMessage(_readString.getContentPublisher(), _readString.getPublisherKeyLocator(), thisUpdate, _readString.string());
				} else {
				}
			}
		}
	}
	
	/**
	 * Add a message to the output.
	 * @param message
	 */
	protected void showMessage(String sender, Timestamp time, String message) {
		_messagePane.insert("[" + sender + " " + DATE_FORMAT.format(time) + "]: " + message + "\n", _messagePane.getText().length());
        _messagePane.setCaretPosition(_messagePane.getText().length());
	}
	
	protected void showMessage(PublisherPublicKeyDigest publisher, KeyLocator keyLocator, Timestamp time, String message) {
		// Start with key fingerprints. Move up to user names.
		showMessage(publisher.shortFingerprint().substring(0, 8), time, message);
	}
	
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
		CCNChat client;
		try {
			client = new CCNChat(args[0]);
			client.listen();
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
	
	public static Timestamp now() { return new Timestamp(System.currentTimeMillis()); }
}
