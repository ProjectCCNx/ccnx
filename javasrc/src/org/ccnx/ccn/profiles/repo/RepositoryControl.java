package org.ccnx.ccn.profiles.repo;

import java.io.IOException;

import javax.media.j3d.Link;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.repo.RepositoryInfo;
import org.ccnx.ccn.impl.repo.RepositoryInfo.RepoInfoType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNAbstractInputStream;
import org.ccnx.ccn.io.NoMatchingContentFoundException;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.Link.LinkObject;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.SignedInfo.ContentType;

/**
 * Provides support for controlling repository functions beyond
 * the basic operations for writing data from an application
 * into a repository supported by classes in the org.ccnx.ccn.io 
 * package.
 * @author jthornto
 *
 */
public class RepositoryControl {

	/**
	 * Request that a local repository preserve a copy
	 * of the exact contents of a given stream opened for reading
	 * with a given handle. A local repository is one connected
	 * directly to the same ccnd as the given handle; such a repository
	 * is likely to be special in the sense that it may be available to local
	 * applications even when there is no connectivity beyond the local 
	 * machine.  An application reading certain content that it did not originate
	 * may have reason to need that content to be available reliably as connectivity changes
	 * in the future. Since the application is not the source of the content, it need
	 * only ask the local repository to be interested in it rather than creating an
	 * output stream to write it. This method serves that purpose.
	 * 
	 * This method is experimental and the way of addressing this problem 
	 * is likely to change in the future.
	 * 
	 * This method may have no effect if the local repository is not already
	 * configured to support holding the data in question. For example, the 
	 * repository policy might not admit the namespace in question
	 * and this method does not override such overall policy.
	 * 
	 * There may be more than one local repository but this method does not 
	 * presently distinguish one if that is the case.  Any one local repository
	 * that is available may be used, and at most one should be expected to respond to
	 * the request. This method should verify that a confirmation is from an acceptable
	 * local repository.
	 * 
	 * A repository should not generate Interests for copies of stream content that it already
	 * holds in response to this request, so this operation should be inexpensive in the 
	 * case when the repository already holds the content.
	 * 
	 * It is possible to block waiting for confirmation from the repository that it 
	 * has the content or to make the request without waiting for "best effort" service.
	 * 
	 * @param handle the handle
	 * @param stream The stream for which the content should be preserved
	 * @param wait Set true to wait for positive response from repository.
	 * @return boolean true iff confirmation received from repository
	 * @throws IOException
	 */
	public static boolean localRepoSync(CCNHandle handle, CCNAbstractInputStream stream, boolean wait) throws IOException {
		boolean result;
		
		byte[] digest = stream.getFirstDigest(); // This forces reading if not done already
		ContentName name = stream.getBaseName();
		Long segment = stream.firstSegmentNumber();
		Log.fine("RepositoryControl.localRepoSync called for name {0}", name);

		// Request preserving the dereferenced content of the stream first
		result = internalRepoSync(handle, wait, name, segment, digest);
		
		// Now also deal with each of the links dereferenced to get to the ultimate content
		LinkObject link = stream.getDereferencedLink();
		while (null != link) {
			// Request preserving current link: note that all of these links have 
			// been dereferenced already to get to the content, and so have been read
			digest = link.getFirstDigest();
			name = link.getVersionedName(); // we need versioned name; link basename may or may not be
			segment = link.firstSegmentNumber();

			if (!internalRepoSync(handle, wait, name, segment, digest)) {
				result = false;
			}
			link = link.getDereferencedLink();
		}	
		return result;
	}
	
	/*
	 * Internal method to generate request for local repository to preserve content stream
	 * @param handle the CCNHandle to use
	 * @param wait Set true to wait for positive response from repository
	 * @param baseName The name of the content up to but not including segment number
	 * @param startingSegmentNumber Initial segment number of the stream
	 * @param firstDigest Digest of the first segment
	 */
	static boolean internalRepoSync(CCNHandle handle, boolean wait, ContentName baseName, Long startingSegmentNumber, byte[] firstDigest) throws IOException {
		// We do not use a nonce in this protocol, because a cached confirmation is satisfactory,
		// assuming verification of the repository that published it.
		
		ContentName repoCommandName = 
			new ContentName(baseName, new byte[][]{ CommandMarker.COMMAND_MARKER_REPO_SYNC_STREAM.getBytes(),
													SegmentationProfile.getSegmentNumberNameComponent(startingSegmentNumber), 
													firstDigest});
		Interest syncInterest = new Interest(repoCommandName);
		syncInterest.scope(0); // local repositories only
		
		// Pick timeout based on whether we are blocking or not
		long timeout = SystemConfiguration.NO_TIMEOUT;
		if (!wait) {
			timeout = 0;
		}
		// Send out Interest
		ContentObject co = handle.get(syncInterest, timeout);
		if (null == co) {
			return false;
		}
		
		// TODO verify object as published by local repository rather than just signed by anybody
		if (!handle.defaultVerifier().verify(co)) {
			// TODO need to bypass unacceptable data to see if something good is out there
			return false;
		}
		
		if (co.signedInfo().getType() != ContentType.DATA)
			return false;  // if verified response is not DATA then we don't have confirmation
		RepositoryInfo repoInfo = new RepositoryInfo();
		// Extra check that we can decode and get right type, the data really doesn't matter.
		try {
			repoInfo.decode(co.content());
			if (repoInfo.getType() != RepoInfoType.INFO) {
				return false;
			}
		} catch (ContentDecodingException e) {
			Log.info("ContentDecodingException parsing RepositoryInfo: {0} from content object {1}, skipping.",  e.getMessage(), co.name());
		}

		return true;
	}
}
