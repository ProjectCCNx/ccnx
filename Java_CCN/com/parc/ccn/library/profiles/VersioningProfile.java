package com.parc.ccn.library.profiles;

import java.math.BigInteger;
import java.security.InvalidParameterException;
import java.sql.Timestamp;
import java.util.ArrayList;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.query.BloomFilter;
import com.parc.ccn.data.query.ExcludeComponent;
import com.parc.ccn.data.query.ExcludeElement;
import com.parc.ccn.data.query.ExcludeFilter;
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
	public static final byte FF = (byte) 0xFF;
	public static final byte OO = (byte) 0x00;

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
	
	public static boolean isBaseVersionComponent(byte [] nameComponent) {
		return (isVersionComponent(nameComponent) && (1 == nameComponent.length));
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
		// DKS TODO -- should be able to just compare byte arrays, but would have to check version
		return getVersionComponentAsTimestamp(left).compareTo(getVersionComponentAsTimestamp(right));
	}
	
	/**
	 * See if version is a version of parent (not commutative).
	 * @return
	 */
	public static boolean isVersionOf(ContentName version, ContentName parent) {
		Tuple<ContentName, byte []>versionParts = cutTerminalVersion(version);
		if (!parent.equals(versionParts.first())) {
			return false; // not versions of the same thing
		}
		if (null == versionParts.second())
			return false; // version isn't a version
		return true;
    }
	
	/**
	 * This compares two names, with terminal versions, and determines whether one is later than the other.
	 * @param laterVersion
	 * @param earlierVersion
	 * @return
	 * @throws VersionMissingException
	 */
	public static boolean isLaterVersionOf(ContentName laterVersion, ContentName earlierVersion) throws VersionMissingException {
		// TODO -- remove temporary warning
		Library.logger().warning("SEMANTICS CHANGED: if experiencing unexpected behavior, check to see if you want to call isLaterVerisionOf or startsWithLaterVersionOf");
		Tuple<ContentName, byte []>earlierVersionParts = cutTerminalVersion(earlierVersion);
		Tuple<ContentName, byte []>laterVersionParts = cutTerminalVersion(laterVersion);
		if (!laterVersionParts.first().equals(earlierVersionParts.first())) {
			return false; // not versions of the same thing
		}
		return (compareVersionComponents(laterVersionParts.second(), earlierVersionParts.second()) > 0);
    }
	
	/**
	 * Finds out if you have a versioned name, and a ContentObject that might have a versioned name which is 
	 * a later version of the given name, even if that CO name might not refer to a segment of the original name.
	 * For example, given a name /parc/foo.txt/<version1> or /parc/foo.txt/<version1>/<segment>
	 * and /parc/foo.txt/<version2>/<stuff>, return true, whether <stuff> is a segment marker, a whole
	 * bunch of repo write information, or whatever. 
	 * @param newName Will check to see if this name begins with something which is a later version of previousVersion.
	 * @param previousVersion The name to compare to, must have a terminal version or be unversioned.
	 * @return
	 */
	public static boolean startsWithLaterVersionOf(ContentName newName, ContentName previousVersion) {
		// If no version, treat whole name as prefix and any version as a later version.
		Tuple<ContentName, byte []>previousVersionParts = cutTerminalVersion(previousVersion);
		if (!previousVersionParts.first().isPrefixOf(newName))
			return false;
		if (null == previousVersionParts.second()) {
			return ((newName.count() > previousVersionParts.first().count()) && 
					VersioningProfile.isVersionComponent(newName.component(previousVersionParts.first().count())));
		}
		try {
			return (compareVersionComponents(newName.component(previousVersionParts.first().count()), previousVersionParts.second()) > 0);
		} catch (VersionMissingException e) {
			return false; // newName doesn't have to have a version there...
		}
	}

	public static int compareTerminalVersions(ContentName laterVersion, ContentName earlierVersion) throws VersionMissingException {
		Tuple<ContentName, byte []>earlierVersionParts = cutTerminalVersion(earlierVersion);
		Tuple<ContentName, byte []>laterVersionParts = cutTerminalVersion(laterVersion);
		if (!laterVersionParts.first().equals(earlierVersionParts.first())) {
			throw new IllegalArgumentException("Names not versions of the same name!");
		}
		return (compareVersionComponents(laterVersionParts.second(), earlierVersionParts.second()));
    }

	/**
	 * Builds an Exclude filter that excludes components before or @ start, and components after
	 * the last valid version.
	 * @param startingVersionComponent The latest version component we know about. Can be null or
	 * 			VersioningProfile.isBaseVersionComponent() == true to indicate that we want to start
	 * 			from 0 (we don't have a known version we're trying to update). This exclude filter will
	 * 			find versions *after* the version represented in startingVersionComponent.
	 * @return An exclude filter.
	 * @throws InvalidParameterException
	 */
	public static ExcludeFilter acceptVersions(byte [] startingVersionComponent) {
		byte [] start = null;
		// initially exclude name components just before the first version, whether that is the
		// 0th version or the version passed in
		if ((null == startingVersionComponent) || VersioningProfile.isBaseVersionComponent(startingVersionComponent)) {
			start = new byte [] { VersioningProfile.VERSION_MARKER, VersioningProfile.OO, VersioningProfile.FF, VersioningProfile.FF, VersioningProfile.FF, VersioningProfile.FF, VersioningProfile.FF };
		} else {
			start = startingVersionComponent;
		}
		
		ArrayList<ExcludeElement> ees = new ArrayList<ExcludeElement>();
		
		ees.add(BloomFilter.matchEverything());
		ees.add(new ExcludeComponent(start));
		ees.add(new ExcludeComponent(new byte [] {
				VERSION_MARKER+1, OO, OO, OO, OO, OO, OO } ));
		ees.add(BloomFilter.matchEverything());
		ExcludeFilter ef = new ExcludeFilter(ees);
		return ef;
	}
}
