package com.parc.ccn.library.profiles;

import java.math.BigInteger;
import java.sql.Timestamp;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.DataUtils.Tuple;

/**
 * Versions, when present, occupy the penultimate component of the CCN name, 
 * not counting the digest component. They may be chosen based on time.
 * The first byte of the version component is 0xFD. The remaining bytes are a
 * big-endian binary number. If based on time they are expressed in units of
 * 2**(-12) seconds since the start of Unix time, using the minimum number of
 * bytes. The time portion will thus take 48 bits until quite a few centuries
 * from now (Sun, 20 Aug 4147 07:32:16 GMT). With 12 bits of precision, it allows 
 * for sub-millisecond resolution. The client generating the version stamp 
 * should try to avoid using a stamp earlier than (or the same as) any 
 * version of the file, to the extent that it knows about it. It should 
 * also avoid generating stamps that are unreasonably far in the future.
 */
public class VersioningProfile implements CCNProfile {

	public static final byte VERSION_MARKER = (byte)0xFD;
	public static final byte [] FIRST_VERSION_MARKER = new byte []{VERSION_MARKER};

	/**
	 * Add a version field to a ContentName.
	 * @return ContentName with a version appended. Does not affect previous versions.
	 */
	public static ContentName addVersion(ContentName name, long version) {
		// Need a minimum-bytes big-endian representation of version.
		byte [] vcomp = null;
		if (0 == version) {
			vcomp = FIRST_VERSION_MARKER;
		} else {
			byte [] varr = BigInteger.valueOf(version).toByteArray();
			vcomp = new byte[varr.length + 1];
			vcomp[0] = VERSION_MARKER;
			System.arraycopy(varr, 0, vcomp, 1, varr.length);
		}
		return new ContentName(name, vcomp);
	}
	
	/**
	 * Converts a timestamp into a fixed point representation, with 12 bits in the fractional
	 * component, and adds this to the ContentName as a version field. The timestamp is rounded
	 * to the nearest value in the fixed point representation.
	 * <p>
	 * This allows versions to be recorded as a timestamp with a 1/4096 second accuracy.
	 * @see #addVersion(ContentName, long)
	 */
	public static ContentName addVersion(ContentName name, Timestamp version) {
		if (null == version)
			throw new IllegalArgumentException("Version cannot be null!"); 
		return addVersion(name, DataUtils.timestampToBinaryTime12AsLong(version));
	}
	
	/**
	 * Add a version field based on the current time, accurate to 1/4096 second.
	 * @see #addVersion(ContentName, Timestamp)
	 */
	public static ContentName addVersion(ContentName name) {
		return addVersion(name, SignedInfo.now());
	}
	
	/**
	 * Adds a version to a ContentName; if there is a terminal version there already,
	 * first removes it.
	 */
	public static ContentName updateVersion(ContentName name, long version) {
		return addVersion(cutTerminalVersion(name).first(), version);
	}
	
	/**
	 * Adds a version to a ContentName; if there is a terminal version there already,
	 * first removes it.
	 */
	public static ContentName updateVersion(ContentName name, Timestamp version) {
		return addVersion(cutTerminalVersion(name).first(), version);
	}

	/**
	 * Add updates the version field based on the current time, accurate to 1/4096 second.
	 * @see #updateVersion(ContentName, Timestamp)
	 */
	public static ContentName updateVersion(ContentName name) {
		return updateVersion(name, SignedInfo.now());
	}

	/**
	 * Finds the last component that looks like a version in name.
	 * @param name
	 * @return the index of the last version component in the name, or -1 if there is no version
	 *					component in the name
	 */
	public static int findLastVersionComponent(ContentName name) {
		int i = name.count();
		for (;i >= 0; i--)
			if (isVersionComponent(name.component(i)))
				return i;
		return -1;
	}

	/**
	 * Checks to see if this name has a validly formatted version field anywhere in it.
	 */
	public static boolean containsVersion(ContentName name) {
		return findLastVersionComponent(name) != -1;
	}
	
	/**
	 * Checks to see if this name has a validly formatted version field either in final
	 * component or in next to last component with final component being a segment marker.
	 */
	public static boolean hasTerminalVersion(ContentName name) {
		if ((name.count() > 0) && 
			((isVersionComponent(name.lastComponent()) || 
			 ((name.count() > 1) && SegmentationProfile.isSegment(name) && isVersionComponent(name.component(name.count()-2)))))) {
			return true;
		}
		return false;
	}
	
	/**
	 * Check a name component to see if it is a valid version field
	 */
	public static boolean isVersionComponent(byte [] nameComponent) {
		return (null != nameComponent) && (0 != nameComponent.length) && 
			   (VERSION_MARKER == nameComponent[0]) && 
			   ((nameComponent.length == 1) || (nameComponent[1] != 0));
	}
	

	/**
	 * Remove a terminal version marker (one that is either the last component of name, or
	 * the next to last component of name followed by a segment marker) if one exists, otherwise
	 * return name as it was passed in.
	 * @param name
	 * @return
	 */
	public static Tuple<ContentName, byte[]> cutTerminalVersion(ContentName name) {
		if (name.count() > 0) {
			if (isVersionComponent(name.lastComponent())) {
				return new Tuple<ContentName, byte []>(name.parent(), name.lastComponent());
			} else if ((name.count() > 2) && SegmentationProfile.isSegment(name) && isVersionComponent(name.component(name.count()-2))) {
				return new Tuple<ContentName, byte []>(name.cut(name.count()-2), name.component(name.count()-2));
			}
		}
		return new Tuple<ContentName, byte []>(name, null);
	}
	
	/**
	 * Take a name which may have one or more version components in it,
	 * and strips the last one and all following components. If no version components
	 * present, returns the name as handed in.
	 */
	public static ContentName cutLastVersion(ContentName name) {
		int offset = findLastVersionComponent(name);
		return (offset == -1) ? name : new ContentName(offset, name.components());
	}

	/**
	 * Function to get the version field as a long.  Starts from the end and checks each name component for the version marker.
	 * @param name
	 * @return long
	 * @throws VersionMissingException
	 */
	public static long getLastVersionAsLong(ContentName name) throws VersionMissingException {
		int i = findLastVersionComponent(name);
		if (i == -1)
			throw new VersionMissingException();
		
		return getVersionComponentAsLong(name.component(i));
	}
	
	public static long getVersionComponentAsLong(byte [] versionComponent) {
		byte [] versionData = new byte[versionComponent.length - 1];
		System.arraycopy(versionComponent, 1, versionData, 0, versionComponent.length - 1);
		if (versionData.length == 0)
			return 0;
		return new BigInteger(versionData).longValue();
	}

	public static Timestamp getVersionComponentAsTimestamp(byte [] versionComponent) {
		return versionLongToTimestamp(getVersionComponentAsLong(versionComponent));
	}

	/**
	 * Extract the version from this name as a Timestamp.
	 * @throws VersionMissingException 
	 */
	public static Timestamp getLastVersionAsTimestamp(ContentName name) throws VersionMissingException {
		long time = getLastVersionAsLong(name);
		return DataUtils.binaryTime12ToTimestamp(time);
	}
	
	/**
	 * Returns null if no version, otherwise returns the last version in the name. 
	 * @param name
	 * @return
	 */
	public static Timestamp getLastVersionAsTimestampIfVersioned(ContentName name) {
		int versionComponent = findLastVersionComponent(name);
		if (versionComponent < 0)
			return null;
		return getVersionComponentAsTimestamp(name.component(versionComponent));
	}
	
	public static Timestamp getTerminalVersionAsTimestampIfVersioned(ContentName name) {
		if (!hasTerminalVersion(name))
			return null;
		int versionComponent = findLastVersionComponent(name);
		if (versionComponent < 0)
			return null;
		return getVersionComponentAsTimestamp(name.component(versionComponent));
	}
	
	public static Timestamp versionLongToTimestamp(long version) {
		return DataUtils.binaryTime12ToTimestamp(version);
	}
	/**
	 * Control whether versions start at 0 or 1.
	 * @return
	 */
	public static final int baseVersion() { return 0; }

	/**
	 * Compares terminal version (versions at the end of, or followed by only a segment
	 * marker) of a name to a given timestamp.
	 * @param left
	 * @param right
	 * @return
	 */
	public static int compareVersions(
			Timestamp left,
			ContentName right) {
		if (!hasTerminalVersion(right)) {
			throw new IllegalArgumentException("Both names to compare must be versioned!");
		}
		try {
			return left.compareTo(getLastVersionAsTimestamp(right));
		} catch (VersionMissingException e) {
			throw new IllegalArgumentException("Name that isVersioned returns true for throws VersionMissingException!: " + right);
		}
	}
	
	public static int compareVersionComponents(
			byte [] left,
			byte [] right) throws VersionMissingException {
		// Propagate correct exception to callers.
		if ((null == left) || (null == right))
			throw new VersionMissingException("Must compare two versions!");
		return getVersionComponentAsTimestamp(left).compareTo(getVersionComponentAsTimestamp(right));
	}
	
	/**
	 * See if two names are the same modulo potentially different terminal versions.
	 * (If no terminal versions, will just compare the names.)
	 * @param version1
	 * @param version2
	 * @return
	 */
	public static boolean isVersionOf(ContentName version1, ContentName version2) {
		Tuple<ContentName, byte []>earlierVersionParts = cutTerminalVersion(version2);
		Tuple<ContentName, byte []>laterVersionParts = cutTerminalVersion(version1);
		if (!laterVersionParts.first().equals(earlierVersionParts.first())) {
			return false; // not versions of the same thing
		}
		return true;
    }
	
	public static boolean isLaterVersionOf(ContentName laterVersion, ContentName earlierVersion) throws VersionMissingException {
		Tuple<ContentName, byte []>earlierVersionParts = cutTerminalVersion(earlierVersion);
		Tuple<ContentName, byte []>laterVersionParts = cutTerminalVersion(laterVersion);
		if (!laterVersionParts.first().equals(earlierVersionParts.first())) {
			return false; // not versions of the same thing
		}
		return (compareVersionComponents(laterVersionParts.second(), earlierVersionParts.second()) > 0);
    }

	public static int compareVersions(ContentName laterVersion, ContentName earlierVersion) throws VersionMissingException {
		Tuple<ContentName, byte []>earlierVersionParts = cutTerminalVersion(earlierVersion);
		Tuple<ContentName, byte []>laterVersionParts = cutTerminalVersion(laterVersion);
		if (!laterVersionParts.first().equals(earlierVersionParts.first())) {
			throw new IllegalArgumentException("Names not versions of the same name!");
		}
		return (compareVersionComponents(laterVersionParts.second(), earlierVersionParts.second()));
    }
}
