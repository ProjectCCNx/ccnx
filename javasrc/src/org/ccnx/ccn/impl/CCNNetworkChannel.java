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

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;

import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.protocol.WirePacket;

/**
 *  This guy manages all of the access to the network connection.
 *  It is capable of supporting both UDP and TCP transport protocols
 *  
 *  It also creates a stream interface for input to the decoders. It is necessary to
 *  create our own input stream for TCP because the stream that can be obtained via the
 *  socket interface is not markable. Originally the UDP code used to translate the UDP
 *  input data into a ByteArrayInputStream after reading it in, but we now an use the
 *  same input stream for both transports.
 */
public class CCNNetworkChannel extends InputStream {
	public static final int MAX_PAYLOAD = 8800; // number of bytes in UDP payload
	public static final int HEARTBEAT_PERIOD = 3500;
	public static final int SOCKET_TIMEOUT = 1000; // period to wait in ms.
	public static final int DOWN_DELAY = 100;	// Wait period for retry when ccnd is down

	protected String _ncHost;
	protected int _ncPort;
	protected NetworkProtocol _ncProto;
	protected int _ncLocalPort;
	protected DatagramChannel _ncDGrmChannel = null;
	protected SocketChannel _ncSockChannel = null;
	protected Selector _ncSelector = null;
	protected Boolean _ncConnected = new Boolean(false); // Actually asking the channel if its connected doesn't appear to be reliable
	protected boolean _ncInitialized = false;
	protected Timer _ncHeartBeatTimer = null;
	protected Boolean _ncStarted = false;
	protected FileOutputStream _ncTapStreamIn = null;
	
	// Allocate datagram buffer
	protected ByteBuffer _datagram = ByteBuffer.allocateDirect(MAX_PAYLOAD);
	//private byte[] buffer = new byte[MAX_PAYLOAD];
	//protected ByteBuffer _datagram = ByteBuffer.wrap(buffer);
	private int _mark = 0;
	private int _readLimit = 0;
	
	public CCNNetworkChannel(String host, int port, NetworkProtocol proto, FileOutputStream tapStreamIn) throws IOException {
		_ncHost = host;
		_ncPort = port;
		_ncProto = proto;
		_ncTapStreamIn = tapStreamIn;
		_ncSelector = Selector.open();
		Log.info("Starting up CCNNetworkChannel using {0}.", proto);
	}
	
	/**
	 * Open the channel to ccnd depending on the protocol, connect on the ccnd port and
	 * set up the selector
	 * 
	 * @throws IOException
	 */
	public void open() throws IOException {
		if (_ncProto == NetworkProtocol.UDP) {
			try {
				_ncDGrmChannel = DatagramChannel.open();
				_ncDGrmChannel.connect(new InetSocketAddress(_ncHost, _ncPort));
				_ncDGrmChannel.configureBlocking(false);
				
				// For some weird reason we seem to have to test writing twice when ccnd is down
				// before the channel actually notices. There might be some kind of timing/locking
				// problem responsible for this but I can't figure out what it is.
				ByteBuffer test = ByteBuffer.allocate(1);
				if (_ncInitialized)
					_ncDGrmChannel.write(test);
				wakeup();
				_ncDGrmChannel.register(_ncSelector, SelectionKey.OP_READ);
				_ncLocalPort = _ncDGrmChannel.socket().getLocalPort();
				if (_ncInitialized) {
					test.flip();
					_ncDGrmChannel.write(test);
				}
				if (_ncStarted)
					_ncHeartBeatTimer.schedule(new HeartBeatTimer(), HEARTBEAT_PERIOD);
			} catch (IOException ioe) {
				return;
			}
		} else if (_ncProto == NetworkProtocol.TCP) {
			_ncSockChannel = SocketChannel.open();
			_ncSockChannel.connect(new InetSocketAddress(_ncHost, _ncPort));
			_ncSockChannel.configureBlocking(false);
			_ncSockChannel.register(_ncSelector, SelectionKey.OP_READ);			
		} else {
			throw new IOException("NetworkChannel: invalid protocol specified");
		}
		String connecting = (_ncInitialized ? "Reconnecting to" : "Contacting");
		Log.info(connecting + " CCN agent at " + _ncHost + ":" + _ncPort + " on local port " + _ncLocalPort);
		clearSelectedKeys();
		initStream();
		_ncInitialized = true;
		_ncConnected = true;
	}
	
	/**
	 * Get the next packet from the network. It could be either an interest or data. If ccnd is
	 * down this is where we do a sleep to avoid a busy wait.  We go ahead and try to read in
	 * the initial data here also because if there isn't any we want to find out here, not in the middle
	 * of thinking we might be able to decode something. Also since this is supposed to happen
	 * on packet boundaries, we reset the data buffer to its start during the initial read. We only do 
	 * the initial read if there's nothing already in the buffer though because in TCP we could have 
	 * read in some or all of a proceeding packet
	 * during the last reading. 
	 * 
	 * @return a ContentObject, an Interest, or null if there's no data waiting
	 * @throws IOException
	 */
	public XMLEncodable getPacket() throws IOException {
		if (isConnected()) {
			_mark = 0;
			_readLimit = 0;
			if (! _datagram.hasRemaining()) {
				int ret = doReadIn(0);
				if (ret <= 0 || !isConnected())
					return null;
			} 
			WirePacket packet = new WirePacket();
			packet.decode(this);
			return packet.getPacket();
		} else {
			try {
				Thread.sleep(DOWN_DELAY);
			} catch (InterruptedException e) {}
		}
		return null;
	}
	
	/**
	 * Close the channel depending on the protocol
	 * @throws IOException
	 */
	public void close() throws IOException {
		_ncConnected = false;
		if (_ncProto == NetworkProtocol.UDP) {
			wakeup();
			_ncDGrmChannel.close();
		} else if (_ncProto == NetworkProtocol.TCP) {
			_ncSockChannel.close();
		} else {
			throw new IOException("NetworkChannel: invalid protocol specified");
		}
	}
	
	/**
	 * Check whether the channel is currently connected.  This is really a test
	 * to see whether ccnd is running. If it isn't the channel is not connected.
	 * @return true if connected
	 */
	public boolean isConnected() {
		return _ncConnected;
	}
	
	/**
	 * Write to ccnd using methods based on the protocol type
	 * @param src - ByteBuffer to write
	 * @return - number of bytes written
	 * @throws IOException
	 */
	public int write(ByteBuffer src) throws IOException {
		if (! _ncConnected)
			return -1;
		if (Log.isLoggable(Level.FINEST))
			Log.finest("NetworkChannel.write() on port " + _ncLocalPort);
		try {
			if (_ncProto == NetworkProtocol.UDP) {
				return (_ncDGrmChannel.write(src));
			} else if (_ncProto == NetworkProtocol.TCP) {
				return (_ncSockChannel.write(src));
			} else {
				throw new IOException("NetworkChannel: invalid protocol specified");
			}
		} catch (PortUnreachableException pue) {}
		  catch (ClosedChannelException cce) {}
		close();
		return -1;
	}
	
	/**
	 * Need to do this after a successful select to allow the next select to happen
	 */
	private void clearSelectedKeys() {
		_ncSelector.selectedKeys().clear();
	}

	/**
	 * Perform a select based on incoming ccnd data
	 * @param timeout in ms
	 * @return number of channels selected - in practice this will always be 0 or 1
	 * @throws IOException
	 */
	public int select(long timeout) throws IOException {
		int selectVal = (_ncSelector.select(timeout));
		return selectVal;
	}
	
	/**
	 * Force wakeup from a select
	 * @return the selector
	 */
	public Selector wakeup() {
		return (_ncSelector.wakeup());
	}
	
	/**
	 * Initialize the channel at the point when we are actually ready to create faces
	 * with ccnd
	 * @throws IOException 
	 */
	public void init() throws IOException {
		if (_ncProto == NetworkProtocol.UDP) {
			if (! _ncStarted) {
				if (heartbeat()) {
					_ncHeartBeatTimer = new Timer(true);
					_ncHeartBeatTimer.schedule(new HeartBeatTimer(), HEARTBEAT_PERIOD);
				}
				_ncStarted = true;
			}
		}
	}
	
	private void initStream() {
		_datagram.clear();
		_datagram.limit(0);
	}
		
	@Override
	public int read() throws IOException {
		while (true) {
			try {
				if (_datagram.hasRemaining()) {
					int ret = (int)_datagram.get();
					return ret & 0xff;
				}
			} catch (BufferUnderflowException bfe) {}
			int ret = fill();
			if (ret < 0) {
				return ret;
			}
		}
	}
	
	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}
	
	@Override
	public int read(byte[] b, int off, int len) throws IOException {
		int ret = 0;
		if (len > b.length - off) {
			throw new IndexOutOfBoundsException();
		}
		if (! _datagram.hasRemaining()) {
			int tmpRet = fill();
			if (tmpRet <= 0) {
				return tmpRet;
			}
		}
		ret = _datagram.remaining() > len ? len : _datagram.remaining();
		_datagram.get(b, off, ret);
		return ret;
	}
	
	@Override
	public boolean markSupported() {
		return true;
	}
	
	@Override
	public void mark(int readlimit) {
		_readLimit = readlimit;
		_mark = _datagram.position();
	}
	
	@Override
	public void reset() throws IOException {
		_datagram.position(_mark);
	}
	
	/**
	 * Refill the buffer. We don't reset the start of it unless necessary (i.e. we have
	 * reached the end of the buffer). If the start is reset and a mark has been set within
	 * "readLimit" bytes of the end, we need to copy the end of the previous buffer out
	 * to the start so that a reset is possible.
	 * 
	 * @return
	 * @throws IOException
	 */
	private int fill() throws IOException {
		int position = _datagram.position();
		if (position >= _datagram.capacity()) {
			byte[] b = null;
			boolean doCopy = false;
			int checkPosition = position - 1;
			doCopy = _mark + _readLimit >= checkPosition && _mark <= checkPosition;
			if (doCopy) {
				b = new byte[checkPosition - (_mark - 1)];
				_datagram.position(_mark);
				_datagram.get(b);
			}
			_datagram.clear();
			if (doCopy) {
				_datagram.put(b);
			}
			_mark = 0;
			position = _datagram.position();
		}
		return doReadIn(position);
	}
	
	/**
	 * Read in data to the buffer starting at the specified position.
	 * @param position
	 * @return
	 * @throws IOException
	 */
	private int doReadIn(int position) throws IOException {
		int ret = 0;
		clearSelectedKeys();
		if (select(SOCKET_TIMEOUT) != 0) {
			// Note that we must set limit first before setting position because setting
			// position larger than limit causes an exception.
			_datagram.limit(_datagram.capacity());
			_datagram.position(position);
			if (_ncProto == NetworkProtocol.UDP) {
				ret = _ncDGrmChannel.read(_datagram);
			} else {
				ret = _ncSockChannel.read(_datagram);
			}
			if (ret >= 0) {
				// The following is the equivalent of doing a flip except we don't
				// want to reset the position to 0 as flip would do (because we
				// potentially want to preserve a mark). But the read positions
				// the buffer to end of the read and we want to position to the start
				// of the data just read in.
				_datagram.limit(position + ret);
				_datagram.position(position);
				if (null != _ncTapStreamIn) {
					byte [] b = new byte[ret];
					_datagram.get(b);
					_ncTapStreamIn.write(b);
					// Got the data so we have to redo the "hand flip" to read from the
					// correct position.
					_datagram.limit(position + ret);
					_datagram.position(position);
				}
			} else
				if (ret < 0)
					close();
		}
		return ret;
	}
	
	/**
	 * @return true if heartbeat sent
	 */
	private boolean heartbeat() {
		try {
			ByteBuffer heartbeat = ByteBuffer.allocate(1);
			_ncDGrmChannel.write(heartbeat);
			return true;
		} catch (IOException io) {
			// We do not see errors on send typically even if 
			// agent is gone, so log each but do not track
			Log.warning("Error sending heartbeat packet: {0}", io.getMessage());
			try {
				close();
			} catch (IOException e) {}
		}
		return false;
	}
			
	/**
	 * Do scheduled writes of heartbeats on UDP connections.
	 */
	private class HeartBeatTimer extends TimerTask {
		public void run() {
			if (heartbeat())
			_ncHeartBeatTimer.schedule(new HeartBeatTimer(), HEARTBEAT_PERIOD);

		} /* run() */	
	} /* private class HeartBeatTimer extends TimerTask */
} /* NetworkChannel */