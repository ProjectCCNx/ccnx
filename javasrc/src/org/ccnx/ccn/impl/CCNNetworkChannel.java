/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.impl;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.impl.support.Log;

/**
 *  This guy manages all of the access to the network connection.
 *  We do this as a separate class so we can support both TCP and UDP
 *  transports.
 */
public class CCNNetworkChannel {
	public static final int MAX_PAYLOAD = 8800; // number of bytes in UDP payload
	public static final int HEARTBEAT_PERIOD = 3500;

	protected String _ncHost;
	protected int _ncPort;
	protected NetworkProtocol _ncProto;
	protected int _ncLocalPort;
	protected DatagramChannel _ncDGrmChannel = null; // for use by run thread only!
	protected SocketChannel _ncSockChannel = null;
	protected Selector _ncSelector = null;
	protected Boolean _ncConnected = new Boolean(false);
	protected boolean _ncInitialized = false;
	protected Timer _ncHeartBeatTimer = null;
	protected Boolean _ncStarted = false;
	protected TCPInputStream _ncStream = null;	//TCP only
	
	// Allocate datagram buffer: want to wrap array to ensure backed by
	// array to permit decoding
	ByteBuffer _datagram1 = ByteBuffer.allocateDirect(MAX_PAYLOAD);
	ByteBuffer _datagram2 = ByteBuffer.allocateDirect(MAX_PAYLOAD);
	ByteBuffer _currentDgram = _datagram1;

	public CCNNetworkChannel(String host, int port, NetworkProtocol proto) throws IOException {
		_ncHost = host;
		_ncPort = port;
		_ncProto = proto;
		_ncSelector = Selector.open();
		if (_ncProto == NetworkProtocol.TCP) {
			_ncStream = new TCPInputStream();
		}
	}
	
	public void open() throws IOException {
		if (_ncProto == NetworkProtocol.UDP) {
			try {
				_ncDGrmChannel = DatagramChannel.open();
				_ncDGrmChannel.connect(new InetSocketAddress(_ncHost, _ncPort));
				_ncDGrmChannel.configureBlocking(false);
				ByteBuffer test = ByteBuffer.allocate(1);
				int ret = _ncDGrmChannel.write(test);
				if (ret < 1)
					return;
				wakeup();
				_ncDGrmChannel.register(_ncSelector, SelectionKey.OP_READ);
				_ncLocalPort = _ncDGrmChannel.socket().getLocalPort();
				if (_ncStarted)
					_ncHeartBeatTimer.schedule(new HeartBeatTimer(), 0);
				_ncConnected = true;
			} catch (IOException ioe) {
				return;
			}
		} else if (_ncProto == NetworkProtocol.TCP) {
			_ncSockChannel = SocketChannel.open();
			_ncSockChannel.connect(new InetSocketAddress(_ncHost, _ncPort));
			_ncSockChannel.configureBlocking(false);
			_ncSockChannel.register(_ncSelector, SelectionKey.OP_READ);
			_ncLocalPort = _ncSockChannel.socket().getLocalPort();
		} else {
			throw new IOException("NetworkChannel: invalid protocol specified");
		}
		String connecting = (_ncInitialized ? "Reconnecting to" : "Contacting");
		Log.info(connecting + " CCN agent at " + _ncHost + ":" + _ncPort + " on local port " + _ncLocalPort);
		_ncInitialized = true;
	}
	
	public void close() throws IOException {
		if (_ncProto == NetworkProtocol.UDP) {
			_ncConnected = false;
			wakeup();
			_ncDGrmChannel.close();
		} else if (_ncProto == NetworkProtocol.TCP) {
			_ncSockChannel.close();
		} else {
			throw new IOException("NetworkChannel: invalid protocol specified");
		}
	}
	
	public boolean isConnected() {
		if (_ncProto == NetworkProtocol.UDP) {
			return _ncConnected;
		} else if (_ncProto == NetworkProtocol.TCP) {
			return (_ncSockChannel.isConnected());
		} else {
			Log.severe("NetworkChannel: invalid protocol specified");
			return false;
		}
	}
	
	public InputStream getInputStream(FileOutputStream tapStreamIn) throws IOException {
		clearSelectedKeys();
		if (_ncProto == NetworkProtocol.UDP) {
			_currentDgram.clear(); // make ready for new read
			synchronized (this) {
				_ncDGrmChannel.read(_currentDgram); // queue readers and writers
			}
			if( Log.isLoggable(Level.FINEST) )
				Log.finest("Read datagram (" + _currentDgram.position() + " bytes) for port: " + _ncPort);
			_currentDgram.flip(); // make ready to decode
			if (null != tapStreamIn) {
				byte [] b = new byte[_currentDgram.limit()];
				_currentDgram.get(b);
				tapStreamIn.write(b);
				_currentDgram.rewind();
			}
			byte[] array = _currentDgram.array();
			
			if (Log.isLoggable(Level.FINEST)) {
				byte[] tmp = new byte[8];
				System.arraycopy(array, _currentDgram.position(), tmp, 0, (_currentDgram.remaining() > tmp.length) ? tmp.length : _currentDgram.remaining());
				BigInteger tmpBuf = new BigInteger(1,tmp);
				Log.finest("decode (buf.pos: " + _currentDgram.position() + " remaining: " + _currentDgram.remaining() + ") start: " + tmpBuf.toString(16));
			}
			
			ByteArrayInputStream bais = new ByteArrayInputStream(array, _currentDgram.position(), _currentDgram.remaining());
			return bais;
		} else if (_ncProto == NetworkProtocol.TCP) {
			_ncStream.fill();
			return _ncStream;
		} else {
			Log.severe("CCNNetworkChannel: invalid protocol specified");
			return null;
		}
	}
	
	public int read(ByteBuffer dst) throws IOException {
		Log.finest("NetworkChannel.read() on port " + _ncLocalPort);
		if (_ncProto == NetworkProtocol.UDP) {
			return (_ncDGrmChannel.read(dst));
		} else if (_ncProto == NetworkProtocol.TCP) {
			return (_ncSockChannel.read(dst));
		} else {
			throw new IOException("NetworkChannel: invalid protocol specified");
		}
	}
    
	public int write(ByteBuffer src) throws IOException {
		Log.finest("NetworkChannel.write() on port " + _ncLocalPort);
		if (_ncProto == NetworkProtocol.UDP) {
			return (_ncDGrmChannel.write(src));
		} else if (_ncProto == NetworkProtocol.TCP) {
			return (_ncSockChannel.write(src));
		} else {
			throw new IOException("NetworkChannel: invalid protocol specified");
		}
	}
	
	
	private void clearSelectedKeys() {
		_ncSelector.selectedKeys().clear();
	}

	public int select(long timeout) throws IOException {
		int selectVal = (_ncSelector.select(timeout));
		return selectVal;
	}
	
	public Selector wakeup() {
		return (_ncSelector.wakeup());
	}
	
	public void startup() {
		if (_ncProto == NetworkProtocol.UDP) {
			if (! _ncStarted) {
				_ncHeartBeatTimer = new Timer(true);
				_ncHeartBeatTimer.schedule(new HeartBeatTimer(), 0L);
				_ncStarted = true;
			}
		}
	}
	
	private class TCPInputStream extends InputStream {
		
		private ByteBuffer _markDgram = _currentDgram;
		private boolean _previouslyRead = false;

		@Override
		public int read() throws IOException {
			while (true) {
				try {
					if (_currentDgram.hasRemaining()) {
						int ret = (int)_currentDgram.get();
						return ret & 0xff;
					}
				} catch (BufferUnderflowException bfe) {}
				int ret = fill();
				if (ret <= 0)
					return ret;
			}
		}
		
		public boolean markSupported() {
			return true;
		}
		
		public void mark(int readlimit) {
			_markDgram = _currentDgram;
			_currentDgram.mark();
		}
		
		public void reset() throws IOException {
			if (_markDgram != _currentDgram) {
				_previouslyRead = true;
				_currentDgram = _markDgram;
			}
			_currentDgram.reset();
		}
		
		private int fill() throws IOException {
			_currentDgram = _currentDgram == _datagram1 ? _datagram2 : _datagram1;
			int ret;
			if (!_previouslyRead) {
				_currentDgram.clear();
				synchronized (this) {
					ret = _ncSockChannel.read(_currentDgram);
				}
				Log.finest("fill - did a read of " + ret + " bytes.");
				_currentDgram.flip();
			} else {
				_currentDgram.rewind();
				ret = _currentDgram.remaining();
			}
			_previouslyRead = false;
			return ret;
		}
	}
	
			
	/**
	 * Do scheduled writes of heartbeats on UDP connections.
	 */
	private class HeartBeatTimer extends TimerTask {
		public void run() {
			if (_ncConnected) {
				try {
					ByteBuffer heartbeat = ByteBuffer.allocate(1);
					_ncDGrmChannel.write(heartbeat);
					_ncHeartBeatTimer.schedule(new HeartBeatTimer(), HEARTBEAT_PERIOD);
				} catch (IOException io) {
					// We do not see errors on send typically even if 
					// agent is gone, so log each but do not track
					Log.warning("Error sending heartbeat packet: {0}", io.getMessage());
					try {
						close();
					} catch (IOException e) {}
				}
			}
		} /* run() */	
	} /* private class HeartBeatTimer extends TimerTask */
} /* NetworkChannel */