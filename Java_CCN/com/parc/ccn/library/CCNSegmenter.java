package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.sql.Timestamp;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Header;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.SegmentationProfile.SegmentNumberType;
import com.parc.ccn.security.crypto.CCNDigestHelper;
import com.parc.ccn.security.crypto.CCNMerkleTree;

public class CCNSegmenter {

	public static final String PROP_BLOCK_SIZE = "ccn.lib.blocksize";
	public static final String HEADER_NAME = ".header"; // DKS currently not used; see below.

	protected int _blockSize = SegmentationProfile.DEFAULT_BLOCKSIZE;
	protected int _blockIncrement = SegmentationProfile.DEFAULT_INCREMENT;
	protected int _blockScale = SegmentationProfile.DEFAULT_SCALE;
	protected SegmentNumberType _sequenceType = SegmentNumberType.SEGMENT_FIXED_INCREMENT;
	
	protected CCNLibrary _library;
	// Eventually may not contain this; callers may access it exogenously.
	protected CCNFlowControl _flowControl;
	
	protected ContentName _baseName;
	protected ContentType _type;
	protected PrivateKey _signingKey; // eventually separate into signing object
	protected KeyLocator _locator;
	
	/**
	 * Eventually add encryption.
	 * @param baseName
	 * @param locator
	 * @param signingKey
	 */
	@Deprecated // potentially, TBD
	CCNSegmenter(ContentName baseName, ContentType type, KeyLocator locator, PrivateKey signingKey,
				CCNFlowControl flowControl) {
		this(flowControl);
		
		ContentName nameToOpen = baseName;
		// If someone gave us a fragment name, at least strip that.
		if (SegmentationProfile.isSegment(nameToOpen)) {
			 nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		}
		_type = type;

		if (SegmentationProfile.isSegment(nameToOpen)) {
			// DKS TODO: should we do this?
			nameToOpen = SegmentationProfile.segmentRoot(nameToOpen);
		}

		// Don't go looking for or adding versions. Might be unversioned,
		// unfragmented content (e.g. RTP streams). Assume caller knows
		// what name they want.
		_baseName = nameToOpen;	
	}
	
	@Deprecated // potentially, TBD
	CCNSegmenter(ContentName baseName, ContentType type, KeyLocator locator, PrivateKey signingKey,
				CCNLibrary library) {
		this(baseName, type, locator, signingKey, new CCNFlowControl(baseName, library));
	}
	
	public CCNSegmenter(CCNFlowControl flowControl) {
		_flowControl = flowControl;
		_library = flowControl.getLibrary();
		initializeBlockSize();
	}
	
	protected void initializeBlockSize() {
		String blockString = System.getProperty(PROP_BLOCK_SIZE);
		if (null != blockString) {
			try {
				_blockSize = new Integer(blockString).intValue();
				Library.logger().info("Using specified fragmentation block size " + _blockSize);
			} catch (NumberFormatException e) {
				// Do nothing
				Library.logger().warning("Error: malformed property value " + PROP_BLOCK_SIZE + ": " + blockString + " should be an integer.");
			}
		}
	}
	
	/**
	 * Set the fragmentation block size to use
	 * @param blockSize
	 */
	public void setBlockSize(int blockSize) {
		_blockSize = blockSize;
	}
	
	public int getBlockSize() {
		return _blockSize;
	}

	public void useByteCountSequenceNumbers() {
		setSequenceType(SegmentNumberType.SEGMENT_BYTE_COUNT);
		setBlockScale(1);
	}

	public void useFixedIncrementSequenceNumbers(int increment) {
		setSequenceType(SegmentNumberType.SEGMENT_FIXED_INCREMENT);
		setBlockIncrement(increment);
	}

	public void useScaledByteCountSequenceNumbers(int scale) {
		setSequenceType(SegmentNumberType.SEGMENT_BYTE_COUNT);
		setBlockScale(scale);
	}
	
	public void setSequenceType(SegmentNumberType seqType) { _sequenceType = seqType; }
	
	/**
	 * Set increment between block numbers.
	 * @param blockWidth
	 */
	public void setBlockIncrement(int blockIncrement) { _blockIncrement = blockIncrement; }
	
	public void setBlockScale(int blockScale) { _blockScale = blockScale; }
	
	
	/**
	 * If small enough, doesn't fragment. Otherwise, does.
	 * Return ContentObject of the thing they put (in the case
	 * of a fragmented thing, the header). That way the
	 * caller can then also easily link to that thing if
	 * it needs to, or put again with a different name.
	 * 
	 * We want to generate a unique name (just considering
	 * the name part) for transport and routing layer efficiency. 
	 * We want to do this in a way that
	 * gives us the following properties:
	 * <ol>
	 * <li>General CCN nodes do not need to understand any
	 *   name components.
	 * <li>General CCN nodes can verify content signatures if
	 * 	 they feel so inclined. That means that any components
	 *   added to the name to make it unique must be signed
	 *   along with the rest of the name.
	 * <li>General CCN nodes need to know as few algorithms
	 *   for verifying content signatures as possible; at
	 *   minimum one for leaf content and one for fragmented
	 *   content (probably also one for streamed content).
	 * <li>If a particular CCN node wishes to interpret the
	 * 	 content of the additional component (or query over it),
	 * 	 they can, but we don't require them to be able to.
	 * <li>Making content names unique shouldn't interfere with
	 * 	 making names or content private. Content can be encrypted
	 *   before hashing; name components could be encrypted even
	 *   after uniquification (so no one can tell that two blocks
	 *   have the same content, or even anything about the block
	 *   that maps to a name).
	 * </ol>
	 * Requiring the result to be unique means that the additional
	 * component added can't simply be the content digest, or
	 * the publisher ID. Either of these could be useful, but
	 * neither is guaranteed to be unique. The signature is guaranteed
	 * to be unique, but including the signature in the name itself
	 * (or the digest of the signature, etc) means that the name
	 * cannot be completely signed -- as the signature can't be
	 * included in the name for signing. At least the user-intended
	 * part of the name must signed, and including the signature
	 * in a distinguished component of the name means that CCN
	 * nodes must understand what parts of the name are signed
	 * and what aren't. While this is necessarily true, e.g. for
	 * fragmented data (see below), you either need a way to
	 * verify the remainder of the name (which is possible for
	 * fragmented data), or only require users to sign name prefixes.
	 * It is much better to require verification of the entire
	 * name, either by signing it completely (for unfragmented data),
	 * or by including the fragment names in the block information
	 * incorporated in the hash tree for signing (see below).
	 * So, what we use for unfragmented data is the digest of 
	 * the content signedInfo without the signature in it; 
	 * which in turn contains the digest
	 * of the content itself, as well as the publisher ID and
	 * the timestamp (which will make it unique). When we generate
	 * the signature, we still sign the name, the content signedInfo,
	 * and the content, as we cannot guarantee that the content
	 * signedInfo digest has been incorporated in the name.
	 * 
	 * For fragmented data, we only generate one signature,
	 * on the root of the Merkle hash tree. For that we use
	 * this same process to generate a unique name from the
	 * content name and content information. However, we then
	 * decorate that name to create the individual block names;
	 * rather than have CCN nodes understand how to separate
	 * that decoration and verify it, we incorporate the block
	 * names into the Merkle hash tree.
	 * 
	 * TODO: DKS: improve this to handle stream writes better.
	 * What happens if we want to write a block at a time.
	 *  * @param name
	 * @param authenticator
	 * @param signature
	 * @param content
	 * @return
	 * @throws IOException 
	 **/
	public ContentObject put(ContentName name, byte [] contents,
							SignedInfo.ContentType type,
							PublisherKeyID publisher, KeyLocator locator,
							PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
	
		if (null == signingKey)
			signingKey = _library.keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = _library.keyManager().getPublisherKeyID(signingKey);
		}
		if (contents.length >= getBlockSize()) {
			return fragmentedPut(name, contents, type, publisher, locator, signingKey);
		} else {
			try {
				// Generate signature
				ContentObject co = new ContentObject(name, new SignedInfo(publisher, type, locator), contents, signingKey);
				return _flowControl.put(co);
			} catch (IOException e) {
				Library.logger().warning("This should not happen: put failed with an IOExceptoin.");
				Library.warningStackTrace(e);
				throw e;
			}
		}
	}
	
	/** 
	 * Low-level fragmentation interface.
	 * @param name
	 * @param contents
	 * @param type
	 * @param publisher
	 * @param locator
	 * @param signingKey
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException 
	 */
	protected ContentObject fragmentedPut(ContentName name, byte [] contents,
										SignedInfo.ContentType type,
										PublisherKeyID publisher, KeyLocator locator,
										PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		// This will call into CCNBase after picking appropriate credentials
		// take content, blocksize (static), divide content into array of 
		// content blocks, call hash fn for each block, call fn to build merkle
		// hash tree.   Build header, for each block, get authinfo for block,
		// (with hash tree, block identifier, timestamp -- SQLDateTime)
		// insert header using mid-level insert, low-level insert for actual blocks.
		// We should implement a non-fragmenting put.   Won't do block stuff, will need to do latest version stuff.
		int blockSize = getBlockSize();
		int nBlocks = (contents.length + blockSize - 1) / blockSize;
		int from = 0;
		byte[][] contentBlocks = new byte[nBlocks][];
		Timestamp timestamp = new Timestamp(System.currentTimeMillis());
//		Library.logger().info("fragmentedPut: dividing content of " + contents.length + " into " + nBlocks + " of maximum length " + blockSize);
		for (int i = 0; i < nBlocks; i++) {
			int to = from + blockSize;
			// nice Arrays operations not in 1.5
			int end = (to < contents.length) ? to : contents.length;
//			contentBlocks[i] = Arrays.copyOfRange(contents, from, (to < contents.length) ? to : contents.length);
			contentBlocks[i] = new byte[end-from];
			System.arraycopy(contents, from, contentBlocks[i], 0, end-from);
//			System.out.println("block " + i + " length " + (end-from) + " from " + from + " to " + to + " end " + end + " content max " + contents.length);
			from += end-from;
		}
		
		if (SegmentationProfile.isSegment(name)) {
			Library.logger().info("Asked to store fragments under fragment name: " + name + ". Stripping fragment information");
		}
		
		ContentName fragmentBaseName = SegmentationProfile.segmentRoot(name);
		
		CCNMerkleTree tree = 
			putMerkleTree(fragmentBaseName, SegmentationProfile.baseSegment(),
						  contentBlocks, contentBlocks.length, 
						  0, contentBlocks[contentBlocks.length-1].length, 
						  timestamp, publisher, locator, signingKey);
		
		// construct the headerBlockContents;
		byte [] contentDigest = CCNDigestHelper.digest(contents);
		return putHeader(name, contents.length, blockSize, contentDigest, tree.root(),
						 timestamp, publisher, locator, signingKey);
	}
	
	public ContentObject putHeader(ContentName name, int contentLength, int blockSize, byte [] contentDigest, 
				byte [] contentTreeAuthenticator,
				Timestamp timestamp, 
				PublisherKeyID publisher, KeyLocator locator,
				PrivateKey signingKey) throws IOException, InvalidKeyException, SignatureException {

		if (null == signingKey)
			signingKey = _library.keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = _library.keyManager().getPublisherKeyID(signingKey);
		}		

		// Add another differentiator to avoid making header
		// name prefix of other valid names?
		ContentName headerName = headerName(name);
		Header header;
		try {
			header = new Header(headerName, contentLength, contentDigest, contentTreeAuthenticator, blockSize,
															publisher, locator, signingKey);
		} catch (XMLStreamException e) {
			Library.logger().warning("This should not happen: we cannot encode our own header!");
			Library.warningStackTrace(e);
			throw new IOException("This should not happen: we cannot encode our own header!" + e.getMessage());
		}
		ContentObject headerResult = null;
		try {
			headerResult = _flowControl.put(header);
		} catch (IOException e) {
			Library.logger().warning("This should not happen: we cannot put our own header!");
			Library.warningStackTrace(e);
			throw e;
		}
		return headerResult;		
	}
	
	/**
	 * Might want to make headerName not prefix of  rest of
	 * name, but instead different subleaf. For example,
	 * the header name of v.6 of name <name>
	 * was originally <name>/_v_/6; could be 
	 * <name>/_v_/6/.header or <name>/_v_/6/_m_/.header;
	 * the full uniqueified names would be:
	 * <name>/_v_/6/<sha256> or <name>/_v_/6/.header/<sha256>
	 * or <name>/_v_/6/_m_/.header/<sha256>.
	 * The first version has the problem that the
	 * header name (without the unknown uniqueifier)
	 * is the prefix of the block names; so we must use the
	 * scheduler or other cleverness to get the header ahead of the blocks.
	 * The second version of this makes it impossible to easily
	 * write a reader that gets both single-block content and
	 * fragmented content (and we don't want to turn the former
	 * into always two-block content).
	 * So having tried the second route, we're moving back to the former.
	 * @param name
	 * @return
	 */
	public static ContentName headerName(ContentName name) {
		// Want to make sure we don't add a header name
		// to a fragment. Go back up to the fragment root.
		// Currently no header name added.
		if (SegmentationProfile.isSegment(name)) {
			// return new ContentName(fragmentRoot(name), HEADER_NAME);
			return SegmentationProfile.segmentRoot(name);
		}
		// return new ContentName(name, HEADER_NAME);
		return name;
	}
	
	/**
	 * Puts a single block of content using a fragment naming convention.
	 * @param name
	 * @param fragmentNumber
	 * @param content
	 * @param timestamp
	 * @param publisher
	 * @param locator
	 * @param signingKey
	 * @return
	 * @throws IOException 
	 * @throws NoSuchAlgorithmException 
	 * @throws SignatureException 
	 * @throws InvalidKeyException 
	 */
	public ContentObject putFragment(ContentName name, int fragmentNumber, byte [] contents,
									 Timestamp timestamp, PublisherKeyID publisher, KeyLocator locator,
									 PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {
		if (null == signingKey)
			signingKey = _library.keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = _library.keyManager().getPublisherKeyID(signingKey);
		}		

		// DKS TODO -- non-string integers in names
		// DKS TODO -- change fragment markers
		return put(ContentName.fromNative(SegmentationProfile.segmentRoot(name),
								   						Integer.toString(fragmentNumber)),
				   contents, ContentType.FRAGMENT, publisher, locator, signingKey);
	}
	
	/**
	 * Puts an entire Merkle tree worth of content using fragment naming conventions.
	 * @param name
	 * @param baseNameIndex
	 * @param contentBlocks array of blocks of data, not all may be used
	 * @param blockCount how many blocks of the array to use - number of leaves in the tree
	 * @param lastBlockLength last block may not be full of data
	 * @param baseBlockIndex
	 * @param timestamp
	 * @param publisher
	 * @param locator
	 * @param signingKey
	 * @return
	 * @throws InvalidKeyException
	 * @throws SignatureException
	 * @throws NoSuchAlgorithmException
	 * @throws IOException
	 */
	public CCNMerkleTree putMerkleTree(
			ContentName name, int baseNameIndex,
			byte [][] contentBlocks, int blockCount, 
			int baseBlockIndex, int lastBlockLength,
			Timestamp timestamp,
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws InvalidKeyException, SignatureException, NoSuchAlgorithmException, IOException {

		if (null == signingKey)
			signingKey = _library.keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = _library.keyManager().getPublisherKeyID(signingKey);
		}		
		
		// Digest of complete contents
		// If we're going to unique-ify the block names
		// (or just in general) we need to incorporate the names
		// and signedInfos in the MerkleTree blocks. 
		// For now, this generates the root signature too, so can
		// ask for the signature for each block.
		// DKS TODO -- change fragment markers
    	CCNMerkleTree tree = 
    		new CCNMerkleTree(SegmentationProfile.segmentRoot(name), baseNameIndex,
    						  new SignedInfo(publisher, timestamp, ContentType.FRAGMENT, locator),
    						  contentBlocks, false, blockCount, baseBlockIndex, lastBlockLength, signingKey);

		for (int i = 0; i < blockCount-1; i++) {
			try {
				Library.logger().info("putMerkleTree: writing block " + i + " of " + blockCount + " to name " + tree.blockName(i));
				_flowControl.put(tree.block(i, contentBlocks[i]));
			} catch (IOException e) {
				Library.logger().warning("This should not happen: we cannot put our own blocks!");
				Library.warningStackTrace(e);
				throw e;
			}
		}
		// last block
		byte [] lastBlock;
		if (lastBlockLength < contentBlocks[blockCount-1].length) {
			lastBlock = new byte[lastBlockLength];
			System.arraycopy(contentBlocks[blockCount-1], 0, lastBlock, 0, lastBlockLength);
		} else {
			lastBlock = contentBlocks[blockCount-1];
		}
		try {
			Library.logger().info("putMerkleTree: writing last block of " + blockCount + " to name " + tree.blockName(blockCount-1));
			_flowControl.put(tree.block(blockCount-1, lastBlock));
		} catch (IOException e) {
			Library.logger().warning("This should not happen: we cannot put our own last block!");
			Library.warningStackTrace(e);
			throw e;
		}
		
		
		// Caller needs both root signature and root itself. For now, give back the tree.
		return tree;
	}
	

	
	/**
	 * DKS not currently adding a header-specific prefix. A header, however,
	 * should not be a fragment.
	 * @param headerName
	 * @return
	 */
	public static ContentName headerRoot(ContentName headerName) {
		// Do we want to handle fragment roots, etc, here too?
		if (!isHeader(headerName)) {
			Library.logger().warning("Name " + headerName + " is not a header name.");
			throw new IllegalArgumentException("Name " + headerName.toString() + " is not a header name.");
		}
		// Strip off any header-specific prefix info if we
		// add any. If not present, does nothing. Would be faster not to bother
		// calling at all.
		// return headerName.cut(HEADER_NAME); 
		return headerName;
	}
	
	public static boolean isHeader(ContentName name) {
		// with on-path header, no way to tell except
		// that it wasn't a fragment. With separate name,
		// easier to handle.
	//	return (name.contains(HEADER_NAME));
		return (!SegmentationProfile.isSegment(name));
	}

}
