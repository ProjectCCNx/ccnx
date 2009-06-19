package com.parc.ccn.library.io;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
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
	
	protected CCNSegmenter _segmenter;
	
	public CCNWriter(ContentName namespace, CCNLibrary library) {
		this(new CCNFlowControl(namespace, library));
	}
	
	public CCNWriter(String namespace, CCNLibrary library) throws MalformedContentNameStringException {
		this(new CCNFlowControl(ContentName.fromNative(namespace), library));
	}

	public CCNWriter(CCNLibrary library) {
		this(new CCNFlowControl(library));
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
	public ContentName put(ContentName name, byte[] contents, 
							PublisherPublicKeyDigest publisher) throws SignatureException, IOException {
		return put(name, contents, null, publisher, null);
	}
	
	public ContentName put(String name, String contents) throws SignatureException, MalformedContentNameStringException, IOException {
		return put(ContentName.fromURI(name), contents.getBytes(), null, null, null);
	}
	
	public ContentName put(String name, String contents, Integer freshnessSeconds) throws SignatureException, MalformedContentNameStringException, IOException {
		return put(ContentName.fromURI(name), contents.getBytes(), null, null, freshnessSeconds);
	}
	
	public ContentName put(ContentName name, byte[] contents) 
				throws SignatureException, IOException {
		return put(name, contents, null, null, null);
	}

	public ContentName put(CCNFlowControl cf, ContentName name, byte[] contents, 
							PublisherPublicKeyDigest publisher) throws SignatureException, IOException {
		return put(name, contents, null, publisher, null);
	}

	public ContentName put(ContentName name, byte[] contents, 
							SignedInfo.ContentType type,
							PublisherPublicKeyDigest publisher) throws SignatureException, IOException {
		return put(name, contents, null, publisher, null);
	}
	
	public ContentName put(ContentName name, byte[] contents, 
			SignedInfo.ContentType type,
			PublisherPublicKeyDigest publisher,
			Integer freshnessSeconds) throws SignatureException, IOException {
		try {
			_segmenter.put(name, contents, 0, ((null == contents) ? 0 : contents.length),
								  true, type, freshnessSeconds, null, publisher);
			return name;
		} catch (InvalidKeyException e) {
			Library.logger().info("InvalidKeyException using key for publisher " + publisher + ".");
			throw new SignatureException(e);
		} catch (SignatureException e) {
			Library.logger().info("SignatureException using key for publisher " + publisher + ".");
			throw e;
		} catch (NoSuchAlgorithmException e) {
			Library.logger().info("NoSuchAlgorithmException using key for publisher " + publisher + ".");
			throw new SignatureException(e);
		} catch (InvalidAlgorithmParameterException e) {
			throw new IOException("Cannot encrypt content -- bad algorithm parameter!: " + e.getMessage());
		} 
	}

	/**
	 * This does the actual work of generating a new version's name and doing 
	 * the corresponding put. Handles fragmentation.
	 * @throws InvalidAlgorithmParameterException 
	 */
	public ContentName newVersion(
			ContentName name, byte [] contents,
			ContentType type,
			KeyLocator locator, PublisherPublicKeyDigest publisher) throws SignatureException, 
			InvalidKeyException, NoSuchAlgorithmException, IOException, InvalidAlgorithmParameterException {
		
		// Construct new name
		// <name>/<VERSION_MARKER>/<version_number>
		ContentName versionedName = VersioningProfile.versionName(name);

		// put result; segmenter will fill in defaults
		_segmenter.put(versionedName, contents, 0, ((null == contents) ? 0 : contents.length),
							  true,
				 			  type, null, locator, publisher);
		return versionedName;
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
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 * @throws InvalidAlgorithmParameterException 
	 */
	public ContentName newVersion(ContentName name,
								    byte[] contents) throws SignatureException, IOException, InvalidKeyException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
		return newVersion(name, contents, null);
	}

	/**
	 * A specialization of newVersion that allows control of the identity to
	 * publish under. 
	 * 
	 * @param publisher Who we want to publish this as,
	 * not who published the existing version. If null, uses the default publishing
	 * identity.
	 * @throws NoSuchAlgorithmException 
	 * @throws InvalidKeyException 
	 * @throws InvalidAlgorithmParameterException 
	 */
	public ContentName newVersion(
			ContentName name, 
			byte[] contents,
			PublisherPublicKeyDigest publisher) throws SignatureException, IOException, InvalidKeyException, NoSuchAlgorithmException, InvalidAlgorithmParameterException {
		return newVersion(name, contents, null, null, publisher);
	}
	
	public CCNFlowControl getFlowControl() {
		return _segmenter.getFlowControl();
	}
	
	/**
	 * Warning - calling this risks packet drops. It should only
	 * be used for tests or other special circumstances in which
	 * you "know what you are doing".
	 */
	public void disableFlowControl() {
		getFlowControl().disable();
	}
	
	public void close() throws IOException {
		_segmenter.getFlowControl().waitForPutDrain();
	}
	
	public void setTimeout(int timeout) {
		getFlowControl().setTimeout(timeout);
	}
}
