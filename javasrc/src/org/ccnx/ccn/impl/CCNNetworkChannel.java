/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010-2013 Palo Alto Research Center, Inc.
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.CCNNetworkManager.NetworkProtocol;
import org.ccnx.ccn.impl.encoding.BinaryXMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.support.Log;

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
	public static final int HEARTBEAT_PERIOD = 3500;
	public static final int SOCKET_TIMEOUT = SystemConfiguration.MEDIUM_TIMEOUT; // period to wait in ms.
//	public static final int DOWN_DELAY = SystemConfiguration.MEDIUM_TIMEOUT;	// Wait period for retry when ccnd is down
	public static final int LINGER_TIME = 10;	// In seconds

	// This is to make log messages intelligible
	protected final static AtomicInteger _channelIdCounter = new AtomicInteger(0);
	protected final int _channelId;

	// These are set in the constructor
	protected final String _ncHost;
	protected final int _ncPort;
	protected final NetworkProtocol _ncProto;
	protected final FileOutputStream _ncTapStreamIn;

	protected int _ncLocalPort;
	protected DatagramChannel _ncDGrmChannel = null;
	protected SocketChannel _ncSockChannel = null;

	// This lock provides exclusion between calls to open() and close()
	protected Object _opencloseLock = new Object();
	protected Selector _ncReadSelector = null;
	protected Selector _ncWriteSelector = null;			 // Not needed for UDP
	protected int _downDelay = 250;

	// This lock (maybe unnecessary now?), if used with _openCloseLock, should be contained inside it.
	protected Object _ncConnectedLock = new Object();
	protected boolean _ncConnected = false; // Actually asking the channel if its connected doesn't appear to be reliable
	protected boolean _retry = true; // Attempt to reconnect

	protected boolean _ncInitialized = false;
	protected Boolean _ncStarted = false;

	protected BinaryXMLDecoder _decoder = null;

	// Allocate datagram buffer
	protected ByteBuffer _datagram = ByteBuffer.allocateDirect(CCNNetworkManager.MAX_PAYLOAD);
	// The following lines can be uncommented to help with debugging (i.e. you can't easily look at
	// what's in the buffer when an allocateDirect is done).
	// TODO - this should be under the control of a debugging flag instead
	//private byte[] buffer = new byte[CCNNetworkManager.MAX_PAYLOAD];
	//protected ByteBuffer _datagram = ByteBuffer.wrap(buffer);
	private int _mark = -1;
	private int _readLimit = 0;
	private int _lastMark = 0;

	public CCNNetworkChannel(String host, int port, NetworkProtocol proto, FileOutputStream tapStreamIn) throws IOException {
		_ncHost = host;
		_ncPort = port;
		_ncProto = proto;
		_ncTapStreamIn = tapStreamIn;
		_channelId = _channelIdCounter.incrementAndGet();
		_decoder = new BinaryXMLDecoder();
		_decoder.setResyncable(true);

		if (Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO))
			Log.info(Log.FAC_NETMANAGER, "NetworkChannel {0}: Starting up CCNNetworkChannel using {1}.",  _channelId, proto.toString());
	}

	/**
	 * Open the channel to ccnd depending on the protocol, connect on the ccnd port and
	 * set up the selector
	 *
	 * @throws IOException
	 */
	public void open() throws IOException {
		synchronized(_opencloseLock) {
			if (Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO))
				Log.info(Log.FAC_NETMANAGER, "NetworkChannel {0}: open()",  _channelId);

			if( _ncConnected ) {
				Log.severe(Log.FAC_NETMANAGER, "NetworkChannel {0}: Calling open on an already connected channel!", _channelId);
				throw new IOException("NetworkChannel " + _channelId + ": channel already connected");
			}

			_ncReadSelector = Selector.open();

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
					_ncDGrmChannel.register(_ncReadSelector, SelectionKey.OP_READ);
					_ncLocalPort = _ncDGrmChannel.socket().getLocalPort();
					if (_ncInitialized) {
						test.flip();
						_ncDGrmChannel.write(test);
					}
				} catch (NullPointerException npe) {
					Log.warning(Log.FAC_NETMANAGER, "NetworkChannel {0}: UDP open exception {1}",  _channelId, npe.getMessage());
					npe.printStackTrace();
					return;
				} catch (IOException ioe) {
					Log.warning(Log.FAC_NETMANAGER, "NetworkChannel {0}: UDP open exception {1}",  _channelId, ioe.getMessage());
					ioe.printStackTrace();
					return;
				}
			} else if (_ncProto == NetworkProtocol.TCP) {
				_ncSockChannel = SocketChannel.open();
				try {
					_ncSockChannel.connect(new InetSocketAddress(_ncHost, _ncPort));
				} catch (IOException ioe) {
					if (!_ncInitialized) {
						Log.warning(Log.FAC_NETMANAGER, "NetworkChannel {0}: TCP open exception {1}",  _channelId, ioe.getMessage());
						throw ioe;
					}
					Log.info(Log.FAC_NETMANAGER, "NetworkChannel {0}: TCP (re)open exception {1}",  _channelId, ioe.getMessage());
					return;
				}
				_ncSockChannel.configureBlocking(false);
				_ncSockChannel.register(_ncReadSelector, SelectionKey.OP_READ);
				_ncWriteSelector = Selector.open();
				_ncSockChannel.register(_ncWriteSelector, SelectionKey.OP_WRITE);
				_ncLocalPort = _ncSockChannel.socket().getLocalPort();
				//_ncSockChannel.socket().setSoLinger(true, LINGER_TIME);
			} else {
				throw new IOException("NetworkChannel " + _channelId + ": invalid protocol specified");
			}

			if (Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO)) {
				String connecting = (_ncInitialized ? "Reconnecting to" : "Contacting");
				Log.info(Log.FAC_NETMANAGER, "NetworkChannel {0}: {1} CCN agent at {2}:{3} on local port {4}",
						_channelId,
						connecting,
						_ncHost,
						_ncPort,
						_ncLocalPort
						);
			}
			initStream();
			_ncInitialized = true;
			_downDelay = _ncPort * 17 % 199 + 101; // randomize a bit on backoff times
			synchronized (_ncConnectedLock) {
				_ncConnected = true;
			}
		}
	}

	/**
	 * Get the next packet from the network. It could be either an interest or data. If ccnd is
	 * down this is where we do a sleep to avoid a busy wait.  We go ahead and try to read in
	 * the initial data here also because if there isn't any we want to find out here, not in the middle
	 * of thinking we might be able to decode something. Also since this is supposed to happen
	 * on packet boundaries, we reset the data buffer to its start during the initial read. We only do
	 * the initial read if there's nothing already in the buffer though because in TCP we could have
	 * read in some or all of a preceding packet during the last reading.
	 *
	 * Also it should be noted that we are relying on ccnd to guarantee that all packets sent
	 * to us are complete ccn packets. This code does not have the ability to recover from
	 * receiving a partial ccn packet followed by correctly formed ones.
	 *
	 * @return a ContentObject, an Interest, or null if there's no data waiting
	 * @throws IOException
	 */
	public XMLEncodable getPacket() throws IOException {
		if (isConnected()) {
			_mark = -1;
			_readLimit = 0;
			if (! _datagram.hasRemaining()) {
				int ret = doReadIn(0);
				if (ret <= 0 || !isConnected())
					return null;
			}
			_decoder.beginDecoding(this);
			return _decoder.getPacket();
		}
		try {
			if (_retry) {
				synchronized (_opencloseLock) {
					_opencloseLock.wait(_downDelay);
					if (! _ncConnected) {
						if (_downDelay < HEARTBEAT_PERIOD)
							_downDelay = _downDelay * 2 + 1;
						open();
					}
				}
			} else {
				// We do not want to spin without a delay
				Thread.sleep(_downDelay);
			}
		} catch (InterruptedException e) {
			Log.info(Log.FAC_NETMANAGER, "NetworkChannel {0}: interrupted",  _channelId);
		}
		return null;
	}

	/**
	 * Close the channel depending on the protocol
	 * @throws IOException
	 */
	@Override
	public void close() throws IOException {
		close(false);
	}

	private void close(boolean retry) throws IOException {
		synchronized(_opencloseLock) {
			if (Log.isLoggable(Log.FAC_NETMANAGER, Level.INFO))
				Log.info(Log.FAC_NETMANAGER, "NetworkChannel {0}: close({1})",  _channelId, retry);
			_retry &= retry;

			synchronized (_ncConnectedLock) {
				_ncConnected = false;
			}

			_ncReadSelector.close();
			if (_ncWriteSelector != null)
				_ncWriteSelector.close();

			if (_ncDGrmChannel != null) {
				_ncDGrmChannel.close();
			}
			if (_ncSockChannel != null) {
				_ncSockChannel.close();
			}
		}
	}

	/**
	 * Check whether the channel is currently connected.  This is really a test
	 * to see whether ccnd is running. If it isn't the channel is not connected.
	 * @return true if connected
	 */
	public boolean isConnected() {
		synchronized (_ncConnectedLock) {
			return _ncConnected;
		}
	}

	/**
	 * Write to ccnd using methods based on the protocol type
	 * @param src - ByteBuffer to write
	 * @return - number of bytes written
	 * @throws IOException
	 */
	public int write(ByteBuffer src) throws IOException {
		if (! isConnected())
			return -1; // XXX - is this documented?
		if (Log.isLoggable(Log.FAC_NETMANAGER, Level.FINEST))
			Log.finest(Log.FAC_NETMANAGER,
					"NetworkChannel {0}: write() on port {1}", _channelId, _ncLocalPort);

		try {
			if (_ncDGrmChannel != null) {
				return (_ncDGrmChannel.write(src));
			} else {
				// XXX -this depends on synchronization in caller, which is less than ideal.
				// Need to handle partial writes
				int written = 0;
				while (src.hasRemaining()) {
					if (! isConnected())
						return -1;
					int b = _ncSockChannel.write(src);
					if (b > 0) {
						written += b;
					} else {
						_ncWriteSelector.selectedKeys().clear();
						_ncWriteSelector.select();
					}
				}
				return written;
			}
		} catch (PortUnreachableException pue) {}
		  catch (ClosedChannelException cce) {}
		Log.info(Log.FAC_NETMANAGER, "NetworkChannel {0}: closing due to error on write", _channelId);
		close(true);
		return -1;
	}

	/**
	 * Force wakeup from a select
	 * @return the selector
	 */
	public Selector wakeup() {
		return (_ncReadSelector.wakeup());
	}

	/**
	 * Initialize the channel at the point when we are actually ready to create faces
	 * with ccnd
	 * @throws IOException
	 */
	public void init() throws IOException {
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
					int ret = _datagram.get();
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
		if (_mark < 0)
			throw new IOException("Reset called with no mark set - readlimit: " + _readLimit + " lastMark: " + _lastMark);
		if ((_datagram.position() - _mark) > _readLimit) {
			throw new IOException("Invalid reset called past readlimit");
		}
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
			doCopy = _mark >= 0 && _mark + _readLimit >= checkPosition;
			if (doCopy) {
				b = new byte[checkPosition - (_mark - 1)];
				_datagram.position(_mark);
				_datagram.get(b);
			}
			_datagram.clear();
			if (doCopy) {
				_datagram.put(b);
				_mark = 0;
			} else {
				_lastMark = _mark;
				_mark = -1;
			}
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
		_ncReadSelector.selectedKeys().clear();
		if (_ncReadSelector.select() != 0) {
			if (! isConnected())
				return -1;
			// Note that we must set limit first before setting position because setting
			// position larger than limit causes an exception.
			_datagram.limit(_datagram.capacity());
			_datagram.position(position);
			if (_ncDGrmChannel != null) {
				ret = _ncDGrmChannel.read(_datagram);
			} else {
				ret = _ncSockChannel.read(_datagram);
			}
			if (ret >= 0) {
				// The following is the equivalent to doing a flip except we don't
				// want to reset the position to 0 as flip would do (because we
				// potentially want to preserve a mark). But the read positions
				// the buffer to end of the read and we want to reposition to the start
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
				close(true);
		}
		return ret;
	}

	/**
	 * @return true if heartbeat sent
	 */
	public boolean heartbeat() {
		try {
			ByteBuffer heartbeat = ByteBuffer.allocate(1);
			_ncDGrmChannel.write(heartbeat);
			return true;
		} catch (IOException io) {
			// We do not see errors on send typically even if
			// agent is gone, so log each but do not track
			Log.warning(Log.FAC_NETMANAGER,
					"NetworkChannel {0}: Error sending heartbeat packet: {1}", _channelId, io.getMessage());
			try {
				close(true);
			} catch (IOException e) {}
		}
		return false;
	}

} /* NetworkChannel */
