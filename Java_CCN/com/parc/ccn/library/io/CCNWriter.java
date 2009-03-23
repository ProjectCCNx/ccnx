package com.parc.ccn.library.io;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherKeyID;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.security.SignedInfo.ContentType;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNSegmenter;
import com.parc.ccn.library.profiles.VersioningProfile;

/**
 * Simple "bag o bytes" putter. Puts blocks of data, optionally
 * fragmenting them if need be.
 * @author smetters
 *
 */
public class CCNWriter {
	
	protected CCNLibrary _library;
	protected CCNSegmenter _segmenter;
	
	public CCNWriter(CCNLibrary library) {
		_library = library;
	}

	public CCNWriter(CCNFlowControl flowControl) {
		_segmenter = new CCNSegmenter(flowControl);
	}
	
	/**
	 * Publish a piece of content under a particular identity.
	 * All of these automatically make the final name unique.
	 * @param name
	 * @param contents
	 * @param publisher selects one of our identities to publish under
	 * @throws SignatureException 
	 * @throws IOException 
	 */
	public ContentObject put(ContentName name, byte[] contents, 
			PublisherKeyID publisher) throws SignatureException, IOException {
		return put(name, contents, SignedInfo.ContentType.LEAF, publisher);
	}
	
	public ContentObject put(String name, String contents) throws SignatureException, MalformedContentNameStringException, IOException {
		return put(ContentName.fromURI(name), contents.getBytes());
	}
	
	public ContentObject put(ContentName name, byte[] contents) 
				throws SignatureException, IOException {
		return put(name, contents, _library.getDefaultPublisher());
	}

	public ContentObject put(CCNFlowControl cf, ContentName name, byte[] contents, 
							PublisherKeyID publisher) throws SignatureException, IOException {
		return put(name, contents, SignedInfo.ContentType.LEAF, publisher);
	}

	public ContentObject put(ContentName name, byte[] contents, 
							SignedInfo.ContentType type,
							PublisherKeyID publisher) throws SignatureException, IOException {
		try {
			return _segmenter.put(name, contents, type, publisher, 
					   				null, null);
		} catch (InvalidKeyException e) {
			Library.logger().info("InvalidKeyException using default key.");
			throw new SignatureException(e);
		} catch (SignatureException e) {
			Library.logger().info("SignatureException using default key.");
			throw e;
		} catch (NoSuchAlgorithmException e) {
			Library.logger().info("NoSuchAlgorithmException using default key.");
			throw new SignatureException(e);
		}
	}

	/**
	 * This does the actual work of generating a new version's name and doing 
	 * the corresponding put. Handles fragmentation.
	 */
	public ContentObject addVersion(
			ContentName name, int version, byte [] contents,
			ContentType type,
			PublisherKeyID publisher, KeyLocator locator,
			PrivateKey signingKey) throws SignatureException, 
			InvalidKeyException, NoSuchAlgorithmException, IOException {

		if (null == signingKey)
			signingKey = _library.keyManager().getDefaultSigningKey();

		if (null == locator)
			locator = _library.keyManager().getKeyLocator(signingKey);
		
		if (null == publisher) {
			publisher = _library.keyManager().getPublisherKeyID(signingKey);
		}
		
		if (null == type)
			type = ContentType.LEAF;
		
		// Construct new name
		// <name>/<VERSION_MARKER>/<version_number>
		ContentName versionedName = VersioningProfile.versionName(name, version);

		// put result
		return _segmenter.put(versionedName, contents, 
				 				 type, publisher, locator, signingKey);
	}
	
	/**
	 * Publishes a new version of this name with the given contents. First
	 * attempts to figure out the most recent version of that name, and
	 * then increments that to get the intended version number.
	 * 
	 * Right now have all sorts of uncertainties in versioning --
	 * do we know the latest version number of a piece of content?
	 * Even if we've read it, it isn't atomic -- by the time
	 * we write our new version, someone else might have updated
	 * the number...
	 */
	public ContentObject newVersion(ContentName name,
								   byte[] contents) throws SignatureException, IOException {
		return newVersion(name, contents, _library.getDefaultPublisher());
	}

	/**
	 * A specialization of newVersion that allows control of the identity to
	 * publish under. 
	 * 
	 * @param publisher Who we want to publish this as,
	 * not who published the existing version. If null, uses the default publishing
	 * identity.
	 */
	public ContentObject newVersion(
			ContentName name, 
			byte[] contents,
			PublisherKeyID publisher) throws SignatureException, IOException {
		return newVersion(name, contents, ContentType.LEAF, publisher);
	}
	
	/**
	 * A further specialization of newVersion that allows specification of the content type,
	 * primarily to handle links and collections. Could be made protected.
	 * @param publisher Who we want to publish this as,
	 * not who published the existing version.
	 */
	public ContentObject newVersion(
			ContentName name, 
			byte[] contents,
			ContentType type, // handle links and collections
			PublisherKeyID publisher) throws SignatureException, IOException {

		try {
			// DKS TODO fix versioning with versioning profile changes
			return addVersion(name, _library.getNextVersionNumber(name), contents, type, publisher, null, null);
		} catch (InvalidKeyException e) {
			Library.logger().info("InvalidKeyException using default key.");
			throw new SignatureException(e);
		} catch (SignatureException e) {
			Library.logger().info("SignatureException using default key.");
			throw e;
		} catch (NoSuchAlgorithmException e) {
			Library.logger().info("NoSuchAlgorithmException using default key.");
			throw new SignatureException(e);
		}
	}

}
