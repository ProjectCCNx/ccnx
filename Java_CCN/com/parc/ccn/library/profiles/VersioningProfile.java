package com.parc.ccn.library.profiles;

import java.math.BigInteger;
import java.sql.Timestamp;
import java.lang.Exception;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.SignedInfo;

/**
 * From Michael's writeup:
 * Versions, when present, occupy the next-to-last component of the CCN name, 
 * not counting the digest component. They are chosen based (primarily) on time. 
 * The first byte of the version component is 0xFD. The remaining bytes are a 
 * big-endian binary encoding of the time, expressed in units of 2**(-12) seconds 
 * since the start of Unix time, using the minimum number of bytes. The time 
 * portion will thus take 48 bits until quite a few centuries from now 
 * (Sun, 20 Aug 4147 07:32:16 GMT, if you must know), at which point an 
 * additional byte will be required. With 12 bits of fraction, it allows 
 * for sub-millisecond resolution. The client generating the version stamp 
 * should try to avoid using a stamp earlier than (or the same as) any 
 * version of the file, to the extent that it knows about it. It should 
 * also avoid generating stamps that are unreasonably far in the future.
 * 
 * @author smetters
 *
 */
public class VersioningProfile implements CCNProfile {

	public static final byte VERSION_MARKER = (byte)0xFD;
	public static final byte [] FIRST_VERSION_MARKER = new byte []{VERSION_MARKER};

	/**
	 * Compute the name of this version based on a given numeric time.
	 * TODO -- DKS move from ms to femtos. Java Timestamps only go up to ns.
	 * @param name
	 * @param version
	 * @return
	 */
	public static ContentName versionName(ContentName name, long version) {
		// Need a minimum-bytes big-endian representation of version.
		ContentName baseName = name;
		if (isVersioned(name)) {
			baseName = versionRoot(name);
		}
		byte [] vcomp = null;
		if (0 == version) {
			vcomp = FIRST_VERSION_MARKER;
		} else {
			// DKS -- TODO FIX, wrong value
			byte [] varr = BigInteger.valueOf(version).toByteArray();
			vcomp = new byte[varr.length + 1];
			vcomp[0] = VERSION_MARKER;
			System.arraycopy(varr, 0, vcomp, 1, varr.length);
		}
		return new ContentName(baseName, vcomp);
	}
	
	public static ContentName versionName(ContentName name, Timestamp version) {
		// Timestamps go up to ns
		// DKS -- TODO FIX, wrong value

		long versionns = version.getTime() * 1000000000 + version.getNanos();
		return versionName(name, versionns*1000); // 48 bits till year 4147
	}
	
	/**
	 * Compute the name of this version based on now.
	 * @param name
	 * @return
	 */
	public static ContentName versionName(ContentName name) {
		return versionName(name, SignedInfo.now());
	}
	
	public static boolean isUnversioned(ContentName name) {
		byte [] vm = null;
		if (SegmentationProfile.isSegment(name)) {
			vm = name.component(name.count()-2);
		} else {
			vm = name.lastComponent(); // no fragment number, unusual
		}
		return ((null == vm) || (0 == vm.length) || (VERSION_MARKER != vm[0]));
	}

	public static boolean isVersioned(ContentName name) {
		return (!isUnversioned(name));
	}

	public static ContentName versionRoot(ContentName name) {
		int offset = 0;
		if (SegmentationProfile.isSegment(name)) {
			if (isVersioned(name)) {
				offset = name.count()-2;
			} else {
				// is a fragment, but not versioned, remove fragment marker
				offset = name.count()-1;
			}
		} else {
			// no fragment number, unusual
			if (isVersioned(name)) {
				offset = name.count()-1;
			} else {
				// already there
				return name;
			}
		}
		return new ContentName(offset, name.components());
	}

	/**
	 * Does this name represent a version of the given parent?
	 * DKS TODO -- do we need a tighter definition? e.g. is this a data block of
	 * this version, versus metadata, etc...
	 * @param version
	 * @param parent
	 * @return
	 */
	public static boolean isVersionOf(ContentName version, ContentName parent) {
		if (!isVersioned(version))
			return false;
		
		if (isVersioned(parent))
			parent = versionRoot(parent);
		
		return parent.isPrefixOf(version);
	}
	
	public static long getVersionNumber(ContentName name) throws VersionMissingException {
		byte [] vm = null;
		if (SegmentationProfile.isSegment(name)) {
			vm = name.component(name.count()-2);
		} else {
			vm = name.lastComponent(); // no fragment number, unusual
		}
		if ((null == vm) || (0 == vm.length) || (VERSION_MARKER != vm[0]))
			throw new VersionMissingException();
		
		byte [] versionData = new byte[vm.length - 1];
		System.arraycopy(vm, 1, versionData, 0, vm.length - 1);
		return new BigInteger(versionData).longValue();
	}

	/**
	 * Extract the version information from this name.
	 * TODO DKS the fragment number variant of this is static to StandardCCNLibrary, they
	 * 	probably ought to both be the same.
	 * 
	 * @param name
	 * @return Version number, or -1 if not versioned.

/*	public static int getVersionNumber(ContentName name) {
		int offset = name.containsWhere(VERSION_MARKER);
		if (offset < 0)
			return VersioningProfile.baseVersion() - 1; // no version information.
		return Integer.valueOf(ContentName.componentPrintURI(name.component(offset+1)));
	}

	
	public int getNextVersionNumber(ContentName name) {
		ContentName latestVersion = 
			getLatestVersionName(name, null);
	
		int currentVersion = VersioningProfile.baseVersion() - 1;
		if (null != latestVersion)
			// will return baseVersion() - 1 if unversioned 
			currentVersion = VersioningProfile.getVersionNumber(latestVersion);
		return currentVersion + 1;
	}
	
	private ContentName getNextVersionName(ContentName name) {
		return VersioningProfile.versionName(name, getNextVersionNumber(name));
	}
*/	
	/**
	 * Because getting just the latest version number would
	 * require getting the latest version name first, 
	 * just get the latest version name and allow caller
	 * to pull number.
	 * DKS TODO return complete name -- of header? Or what...
	 * DKS TODO match on publisher key id, or full publisher options?
	 * @return If null, no existing version found.
	 */
/*	public ContentName getLatestVersionName(ContentName name, PublisherKeyID publisher) {
		// Challenge -- Dan's proposed latest version syntax,
		// <name>/latestversion/1/2/3... works well if there
		// are 12 versions, not if there are a million. 
		// Need to do a limited get/enumerate just to get version
		// names, without enumerating all the blocks.
		// DKS TODO general way of doing this
		// right now use list children. Should be able to do
		// it in Jackrabbit with XPath.
		ContentName baseVersionName = 
			ContentName.fromNative(VersioningProfile.versionRoot(name), VERSION_MARKER);
		// Because we're just looking at children of
		// the name -- not actual pieces of content --
		// look only at ContentNames.
		ContentObject lastVersion;
		try {
			// Hack by paul r. - this probably should have a timeout because we have to have
			// one here - for now just use an arbitrary number
			lastVersion = getLatest(baseVersionName, 5000);
			if (null != lastVersion)		
				return lastVersion.name();
		} catch (Exception e) {
			Library.logger().warning("Exception getting latest version number of name: " + name + ": " + e.getMessage());
			Library.warningStackTrace(e);
		}
		return null;
	}
*/
	/**
	 * Control whether versions start at 0 or 1.
	 * @return
	 */
	public static final int baseVersion() { return 0; }

}
