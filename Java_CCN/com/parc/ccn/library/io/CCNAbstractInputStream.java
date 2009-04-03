package com.parc.ccn.library.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.SegmentationProfile;

public abstract class CCNAbstractInputStream extends InputStream {

	protected static final int MAX_TIMEOUT = 2000;

	protected CCNLibrary _library;

	protected ContentObject _currentBlock = null;
	protected int _blockOffset = 0;
	protected int _blockIndex = 0;
	
	/**
	 * This is the name we are querying against, prior to each
	 * fragment/segment number.
	 */
	protected ContentName _baseName = null;
	protected PublisherKeyID _publisher = null;
	protected Integer _startingBlockIndex = null;
	protected int _timeout = MAX_TIMEOUT;
	
	/**
	 * If this content uses Merkle Hash Trees or other structures to amortize
	 * signature cost, we can amortize verification cost as well by caching verification
	 * data. Store the currently-verified root signature, so we don't have to re-verify;
	 * and the verified root hash. For each piece of incoming content, see if it aggregates
	 * to the same root, if so don't reverify signature. If not, assume it's part of
	 * a new tree and change the root.
	 */
	protected byte [] _verifiedRootSignature = null;
	protected byte [] _verifiedProxy = null;

	public CCNAbstractInputStream(ContentName baseName, Integer startingBlockIndex, 
			PublisherKeyID publisher, CCNLibrary library) throws XMLStreamException, IOException {
		super();
		
		if (null == baseName)
			throw new IllegalArgumentException("baseName cannot be null!");
			
			_library = library; 
			if (null == _library) {
				_library = CCNLibrary.getLibrary();
		}
		_publisher = publisher;	
		
		// So, we assume the name we get in is up to but not including the sequence
		// numbers, whatever they happen to be. If a starting block is given, we
		// open from there, otherwise we open from the leftmost number available.
		// We assume by the time you've called this, you have a specific version or
		// whatever you want to open -- this doesn't crawl versions. If you have fragmented
		// content, the baseName needs the fragment marker on it.
		_baseName = baseName;
		_startingBlockIndex = startingBlockIndex;
	}
	
	public void setTimeout(int timeout) {
		_timeout = timeout;
	}

	@Override
	public int read() throws IOException {
		byte [] b = new byte[1];
		read(b, 0, 1);
		return b[0];
	}

	@Override
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}

	/**
	 * Reads a packet/block into the buffer. If the buffer is shorter than
	 * the packet's length, reads out of the current block for now.
	 * Aim is really to do packet-sized reads. Probably ought to be a DatagramSocket subclass.
	 * @param buf the buffer into which to write.
	 * @param offset the offset into buf at which to write data
	 * @param len the number of bytes to write
	 * @return
	 * @throws IOException 
	 */
	@Override
	public int read(byte[] buf, int offset, int len) throws IOException {

		if (null == buf)
			throw new NullPointerException("Buffer cannot be null!");

		return readInternal(buf, offset, len);
	}
	
	protected abstract int readInternal(byte [] buf, int offset, int len) throws IOException;

	/**
	 * Three navigation options: get first (leftmost) block, get next block,
	 * or get a specific block.
	 **/
	protected ContentObject getBlock(int number) throws IOException {

        // Block name requested should be interpreted literally, not taken
        // relative to baseSegment().
		ContentName blockName = SegmentationProfile.segmentName(_baseName, number);

		if(_currentBlock!=null){
			//what block do we have right now?  maybe we already have it
			if(currentBlockNumber() == number){
				//we already have this block..
				return _currentBlock;
			}
		}
		
		Library.logger().info("getBlock: getting block " + blockName);
		/*
		 * TODO: Paul R. Comment - as above what to do about timeouts?
		 */
		ContentObject block = _library.getLower(blockName, 1, _timeout);

		if (null == block) {
			Library.logger().info("Cannot get block " + number + " of file " + _baseName + " expected block: " + blockName.toString());
			throw new IOException("Cannot get block " + number + " of file " + _baseName + " expected block: " + blockName.toString());
		} else {
			Library.logger().info("getBlock: retrieved block " + block.name());
		}
		// So for the block, we assume we have a potential document.
		if (!verifyBlock(block)) {
			return null;
		}
		return block;
	}
	

	protected ContentObject getNextBlock() throws IOException {
		Library.logger().info("getNextBlock: getting block after " + _currentBlock.name());

		// prefixCount note: next block name must exactly match current except
		// for the index itself which is the final component of the name we 
		// have, so we use count()-1.
		ContentName nextName = new ContentName(_currentBlock.name(), _currentBlock.contentDigest(), 
					_currentBlock.name().count()-1);
		Interest nextInterest = Interest.next(nextName);
		nextInterest.additionalNameComponents(2);
		ContentObject nextBlock = _library.get(nextInterest, _timeout);
		if (null != nextBlock) {
			Library.logger().info("getNextBlock: retrieved " + nextBlock.name());
			
			// Now need to verify the block we got
			if (!verifyBlock(nextBlock)) {
				return null;
			}
			
			return nextBlock;
		} 
		Library.logger().info("Timed out looking for block of stream.");
		return null;
	}

	protected ContentObject getFirstBlock() throws IOException {
		if (null != _startingBlockIndex) {
			return getBlock(_startingBlockIndex);
		}
		// DKS TODO FIX - use get left child; the following is a first stab at that.
		Library.logger().info("getFirstBlock: getting " + _baseName);
		ContentObject result =  _library.getLower(_baseName, 2, _timeout);
		if (null != result){
			Library.logger().info("getFirstBlock: retrieved " + result.name());
			// Now need to verify the block we got
			if (!verifyBlock(result)) {
				return null;
			}	
		}
		return result;
	}

	boolean verifyBlock(ContentObject block) {

		// First we verify. 
		// Low-level verify just checks that signer actually signed.
		// High-level verify checks trust.
		try {

			// We could have several options here. This block could be simply signed.
			// or this could be part of a Merkle Hash Tree. If the latter, we could
			// already have its signing information.
			if (null == block.signature().witness())
				return block.verify(null);

			// Compare to see whether this block matches the root signature we previously verified, if
			// not, verify and store the current signature.
			// We need to compute the proxy regardless.
			byte [] proxy = block.computeProxy();

			// OK, if we have an existing verified signature, and it matches this block's
			// signature, the proxy ought to match as well.
			if ((null != _verifiedRootSignature) || (Arrays.equals(_verifiedRootSignature, block.signature().signature()))) {
				if ((null == proxy) || (null == _verifiedProxy) || (!Arrays.equals(_verifiedProxy, proxy))) {
					Library.logger().warning("Found block: " + block.name().toString() + " whose digest fails to verify.");
				}
			} else {
				// Verifying a new block. See if the signature verifies, otherwise store the signature
				// and proxy.
				if (!ContentObject.verify(proxy, block.signature().signature(), block.signedInfo(), block.signature().digestAlgorithm(), null)) {
					Library.logger().warning("Found block: " + block.name().toString() + " whose signature fails to verify.");		
				} else {
					// Remember current verifiers
					_verifiedRootSignature = block.signature().signature();
					_verifiedProxy = proxy;
				}
			} 
			Library.logger().info("Got block: " + block.name().toString() + ", verified.");
		} catch (Exception e) {
			Library.logger().warning("Got an " + e.getClass().getName() + " exception attempting to verify block: " + block.name().toString() + ", treat as failure to verify.");
			Library.warningStackTrace(e);
			return false;
		}
		return true;
	}

	public int blockIndex() {
		if (null == _currentBlock) {
			return SegmentationProfile.baseSegment();
		} else {
			// This needs to work on streaming content that is not traditional fragments,
			// and so cannot use CCNLibrary.getFragmentNumber. In my hands, count() does not
			// count the digest component of the name, and so this gives me back the component
			// I want.
			String num = ContentName.componentPrintNative(_currentBlock.name().component(_currentBlock.name().count()-1));
			Library.logger().info("Name: " + _currentBlock.name() + " component " + num);
			return Integer.parseInt(num);
		}
	}
	
	protected int currentBlockNumber(){
		int ind = -1;
		if(_currentBlock!=null)
			ind = Integer.valueOf(_currentBlock.name().stringComponent(_currentBlock.name().count()-1));
		//Library.logger().info("checking block number: "+ind);
		return ind;
	}
	
}