/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.profiles.repo;

import static org.ccnx.ccn.profiles.CommandMarker.COMMAND_MARKER_REPO_CHECKED_START_WRITE;
import static org.ccnx.ccn.profiles.SegmentationProfile.getSegmentNumberNameComponent;
import static org.ccnx.ccn.protocol.Component.NONCE;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.repo.RepositoryInfo;
import org.ccnx.ccn.impl.repo.RepositoryInfo.RepoInfoType;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.CCNAbstractInputStream;
import org.ccnx.ccn.io.content.CCNNetworkObject;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.Link.LinkObject;
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
	 * Temporary cache of blocks we have synced, to avoid double-syncing blocks.
	 */
    protected static Set<ContentName> syncedObjects = Collections.synchronizedSet(new HashSet<ContentName>());

	/**
	 * Request that a local repository preserve a copy
	 * of the exact contents of a given stream opened for reading
	 * with a given handle, and all links that were dereferenced
	 * to read the stream.
	 * 
	 * A local repository is one connected
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
	 * This method may fail (IOException) if the local repository is not already
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
	 * If the repository already holds the content it may confirm immediately, otherwise the repository
	 * will begin to retrieve and store the content but there is no guarantee that this is complete
	 * upon return from this method. The return value indicates whether data is already confirmed.
	 * 
	 * @param handle the handle
	 * @param stream The stream for which the content should be preserved
	 * @return boolean true iff confirmation received from repository
	 * @throws IOException if no repository responds or another communication error occurs
	 */
	public static boolean localRepoSync(CCNHandle handle, CCNAbstractInputStream stream) throws IOException {
		boolean result;
		
		byte[] digest = stream.getFirstDigest(); // This forces reading if not done already
		ContentName name = stream.getBaseName();
		Long segment = stream.firstSegmentNumber();
		if (null == segment) {
			throw new IOException("LocalRepoSync: Can't read stream: " + name);
		}

		Log.info("RepositoryControl.localRepoSync called for name {0}", name);

		// Request preserving the dereferenced content of the stream first
		result = internalRepoSync(handle, name, segment, digest, stream.getFirstSegment().fullName());
		
		// Now also deal with each of the links dereferenced to get to the ultimate content
		LinkObject link = stream.getDereferencedLink();
		while (null != link) {
			// Request preserving current link: note that all of these links have 
			// been dereferenced already to get to the content, and so have been read
			digest = link.getFirstDigest();
			name = link.getVersionedName(); // we need versioned name; link basename may or may not be
			segment = link.firstSegmentNumber();
			
			if (Log.isLoggable(Level.INFO)) {
				Log.info("localRepoSync synchronizing link: {0}", link);
			}

			if (!internalRepoSync(handle, name, segment, digest, link.getFirstSegment().fullName())) {
				result = false;
			}
			link = link.getDereferencedLink();
		}
		return result;
	}
	
	/**
	 * Method to do the same as above but using CCNNetworkObjects instead of streams
	 * @param handle
	 * @param obj
	 * @param syncSigner
	 * @return
	 * @throws IOException
	 */
	public static boolean localRepoSync(CCNHandle handle, CCNNetworkObject<?> obj) throws IOException {
		boolean result;
	
		byte[] digest = obj.getFirstDigest();
		ContentName name = obj.getVersionedName();
		
		if (Log.isLoggable(Level.INFO)) {
			Log.info("RepositoryControl.localRepoSync called for net obj name {0}", name);
		}
		
		Long segment = obj.firstSegmentNumber();  // This forces reading if not done already
		if (null == segment) {
			throw new IOException("LocalRepoSync: Can't read object: " + name);
		}
		
		// Request preserving the dereferenced content of the stream first
		result = internalRepoSync(handle, name, segment, digest, obj.getFirstSegment().fullName());
		
		// Now also deal with each of the links dereferenced to get to the ultimate content
		LinkObject link = obj.getDereferencedLink();
		while (null != link) {
			// Request preserving current link: note that all of these links have 
			// been dereferenced already to get to the content, and so have been read
			digest = link.getFirstDigest();
			name = link.getVersionedName(); // we need versioned name; link basename may or may not be
			segment = link.firstSegmentNumber();

			if (!internalRepoSync(handle, name, segment, digest, link.getFirstSegment().fullName())) {
				result = false;
			}
			link = link.getDereferencedLink();
		}	

		return result;
	}

	/*
	 * Internal method to generate request for local repository to preserve content stream
	 * @param handle the CCNHandle to use
	 * @param baseName The name of the content up to but not including segment number
	 * @param startingSegmentNumber Initial segment number of the stream
	 * @param firstDigest Digest of the first segment
	 */
	static boolean internalRepoSync(CCNHandle handle, ContentName baseName, Long startingSegmentNumber, 
									byte[] firstDigest, ContentName fullName) throws IOException {
		
		// UNNECESSARY OVERHEAD: shouldn't have to re-generate full name here, so hand it in.
		// probably better way than sending in both name and parts.
		if (syncedObjects.contains(fullName)) {
			if (Log.isLoggable(Log.FAC_IO, Level.INFO)) {
				Log.info(Log.FAC_IO, "Sync: skipping already-synced object {0}", fullName);
			}
			return true;
		}

		RepositoryInfo repoInfo = doLocalCheckedWrite(baseName,	startingSegmentNumber, firstDigest,
				handle);

		if (repoInfo.getType() == RepoInfoType.DATA) {	
			// This type from checked write is confirmation that content already held
			// TODO improve result handling. Currently we get true if repo has content already,
			// false if error or repo is storing content but didn't have it already. We don't care
			// whether repo had it already, all we care is whether it is already or will be synced --
			// want to separate errors, repo non-response from "repo will take care of it" responses.
			
			// At this point, a repo has responded and will deal with our data. Don't need to
			// sync it again. I don't understand all the issues above, but until they are resolved,
			// we don't want to declare the object "already synced" until we are actually going to
			// return true here.
			syncedObjects.add(fullName);
			return true;
		}

		return false;
	}

	public static RepositoryInfo doLocalCheckedWrite(ContentName baseName,
			Long startingSegmentNumber, byte[] firstDigest, CCNHandle handle)
			throws IOException, ContentDecodingException {
		// INCORRECT: the protocol is using a nonce.
		// We do not use a nonce in this protocol, because a cached confirmation is satisfactory,
		// assuming verification of the repository that published it.
		
		ContentName repoCommandName = new ContentName(
											baseName,
											COMMAND_MARKER_REPO_CHECKED_START_WRITE,
											NONCE,
											getSegmentNumberNameComponent(startingSegmentNumber),
											firstDigest
										);
		Interest interest = new Interest(repoCommandName);
		interest.scope(1); // local repositories only

		if (Log.isLoggable(Log.FAC_IO, Level.INFO)) {
			Log.info(Log.FAC_IO, "Checked write to repository: {0}", baseName);
		}

		// Send out Interest
		ContentObject co = handle.get(interest, SystemConfiguration.FC_TIMEOUT);

		if (null == co) {
			throw new Interest.NoResponseException(interest);
		}

		// TODO verify object as published by local repository rather than just signed by anybody
		if (!handle.defaultVerifier().verify(co)) {
			// TODO need to bypass unacceptable data to see if something good is out there
			throw new IOException("verify failed on " + co.fullName());
		}

		if (co.signedInfo().getType() != ContentType.DATA)
			throw new IOException("Invalid repository response for checked write, type " + co.signedInfo().getType());

		RepositoryInfo repoInfo = new RepositoryInfo();
		repoInfo.decode(co.content());
		return repoInfo;
	}
}
