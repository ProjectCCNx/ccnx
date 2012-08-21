/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011-2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.profiles.versioning;

import org.ccnx.ccn.profiles.VersionMissingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;

/**
 * Represent the version used in a CCNx name.
 * 
 * The core of this is based on a CCNTime, but CCNTime has two different
 * uses (getTime as long since epoch and binaryTime), which ends up being
 * confusing.  This class should eventually be updated to just operate on
 * the raw byte array.
 * 
 * A version number is an immutable object.
 * 
 * This class is currently only used in the versioning package, not in
 * the VersioningProfile or elsewhere in the code.
 */
public class VersionNumber implements Comparable<VersionNumber>, ContentName.ComponentProvider {
	
	/**
	 * Create a VersionNUmber with the current timestamp from 
	 * the system clock.
	 */
	public VersionNumber() {
		_version = CCNTime.now();
		_versionComponent = VersioningProfile.timeToVersionComponent(_version);
		_binaryTime = _version.toBinaryTimeAsLong();
	}

	/**
	 * Create a VersionNumber based on the binaryTime of #version
	 * @param version
	 */
	public VersionNumber(CCNTime version) {
		_version = new CCNTime(version);
		_versionComponent = VersioningProfile.timeToVersionComponent(_version);
		_binaryTime = _version.toBinaryTimeAsLong();
	}

	/**
	 * Create a version number from the milliseconds since epoch
	 * (as per System.currentTimeMillis or CCNTime.getTime).
	 * @param msecSinceEpoch
	 */
	public VersionNumber(long msecSinceEpoch) {
		_version = new CCNTime(msecSinceEpoch);
		_versionComponent = VersioningProfile.timeToVersionComponent(_version);
		_binaryTime = _version.toBinaryTimeAsLong();
	}
	
	/**
	 * Given a versioned ContentName, extract the version number
	 * from the end.
	 * @param versionedNamed
	 * @exception IllegalArgumentException if the name is unversioned
	 * @throws VersionMissingException 
	 */
	public VersionNumber(ContentName versionedNamed) throws VersionMissingException {
		_version = VersioningProfile.getLastVersionAsTimestamp(versionedNamed);
		_versionComponent = VersioningProfile.timeToVersionComponent(_version);
		_binaryTime = _version.toBinaryTimeAsLong();
	}
	
	/**
	 * Create a VersionNumber from a byte array, such as from
	 * a ContentName component.
	 * @param versionComponent
	 */
	public VersionNumber(byte [] versionComponent) {
		_version = VersioningProfile.getVersionComponentAsTimestamp(versionComponent);
		_versionComponent = copyOf(versionComponent, versionComponent.length);
		_binaryTime = _version.toBinaryTimeAsLong();
	}

	public VersionNumber(VersionNumber version1a) {
		this(version1a.getAsTime());
	}

	/**
	 * Return the byte array corresponding to this version, to
	 * be used in the construction of a ContentName.  Includes the version
	 * marker %FD.
	 * @return A copy of the internal byte array.
	 */
	public byte [] getVersionBytes() {
		return copyOf(_versionComponent, _versionComponent.length);
	}
	
	/**
	 * Returns a new CCNTime object representing the version
	 */
	public CCNTime getAsTime() {
		return new CCNTime(_version);
	}
	
	/**
	 * A representation of the version as milli-seconds since the epoch
	 * (as per System.currentTimeMillis).
	 * @return
	 */
	public long getAsMillis() {
		return _version.getTime();
	}
	
	// in case someone really wants it, we could do this....
//	/**
//	 * Internal binary representation.  Try not to use.
//	 * @return
//	 */
//	public long getAsBinaryTime() {
//		return _version.toBinaryTimeAsLong();
//	}
	
	/**
	 * Add (or subtract if negative) #arg from the current version and
	 * return a new object.  The caller should understand that the
	 * value is used as an unsigned long.
	 * @return
	 */
	public VersionNumber addAndReturn(long count) {
		long binaryTime = _version.toBinaryTimeAsLong();
		binaryTime += count;
		return new VersionNumber(CCNTime.fromBinaryTimeAsLong(binaryTime));
	}
	
	/**
	 * Add (or subtract) the given number of milliseconds from the
	 * version and return a new object. 
	 * 
	 * @param msec
	 * @return
	 */
	public VersionNumber addMillisecondsAndreturn(long msec) {
		long result = _version.getTime() + msec;
		return new VersionNumber(result);
	}
	
	public int compareTo(VersionNumber other) throws ClassCastException {
		if( null == other )
			throw new ClassCastException("Null value");
		
		if( isLessThanUnsigned(_binaryTime, other._binaryTime) )
			return -1;

		if( isLessThanUnsigned(other._binaryTime, _binaryTime) )
			return +1;
		
		return 0;
	}
	
	@Override
	public boolean equals(Object obj) throws ClassCastException {
		if( null == obj )
			throw new ClassCastException("Null value");

		if( !(obj instanceof VersionNumber) )
			throw new ClassCastException("Not a VersionNumber");
		
		VersionNumber other = (VersionNumber) obj;
		return (_binaryTime == other._binaryTime);
	}
	
	@Override
	public int hashCode() {
		return _version.hashCode();
	}
	
	/**
	 * return the URI-encoded representation and the msec representation as:
	 * "%s (%d)"
	 */
	@Override
	public String toString() {
		if( null == _asString ) {
			synchronized(this) {
				_asString = String.format("%s (%d)", VersioningProfile.printAsVersionComponent(_version), _version.getTime());
			}
		}
		return _asString;
	}

	/**
	 * Print the URI-encoded representation
	 * @return
	 */
	public String printAsVersionComponent() {
		return VersioningProfile.printAsVersionComponent(_version);
	}
	
	public static VersionNumber now() {
		return new VersionNumber(CCNTime.now());
	}

	// ========================================
	// static methods
	protected final static VersionNumber minVersionNumber = new VersionNumber(VersioningProfile.MIN_VERSION_MARKER);
	protected final static VersionNumber maxVersionNumber = new VersionNumber(VersioningProfile.MAX_VERSION_MARKER);
	
	
	public static VersionNumber getMaximumVersion() {
		return maxVersionNumber;
	}
	
	public static VersionNumber getMinimumVersion() {
		return minVersionNumber;
	}


	/**
	 * To compare versions, we need unsigned math
	 * @param n1
	 * @param n2
	 * @return true if n1 < n2 unsigned
	 */
	protected static boolean isLessThanUnsigned(long n1, long n2) {
		// see http://www.javamex.com/java_equivalents/unsigned_arithmetic.shtml
		return (n1 < n2) ^ ((n1 < 0) != (n2 < 0));
	}
	
	/**
	 * @param time
	 * @return true if this version is strictly less than #time
	 */
	public boolean before(VersionNumber time) {
		return compareTo(time) < 0;
	}
	
	/**
	 * @param time
	 * @return true if this version is strictly greater than #time
	 */
	public boolean after(VersionNumber time) {
		return compareTo(time) > 0;
	}
	
	/**
	 * @param time
	 * @return true if this version is strictly less than #time
	 */
	public boolean before(CCNTime time) {
		return _version.compareTo(time) < 0;
	}
	
	/**
	 * @param time
	 * @return true if this version is strictly greater than #time
	 */
	public boolean after(CCNTime time) {
		return _version.compareTo(time) > 0;
	}
	
	// =========================================
	// Internal implementation
	protected final CCNTime _version;
	protected final byte [] _versionComponent;
	protected final long _binaryTime;
	protected String _asString = null;
	
	/**
	 * Copy #input to a new array of #length, padding with 0's as necessary
	 * 
	 * This is necessary because Java 1.5 does not support Arrays.copyof()
	 * 
	 * @param input
	 * @param length
	 * @return
	 */
	protected static byte [] copyOf(byte [] input, int length) {
		byte [] output = new byte[length];
		int min = (input.length < length) ? input.length : length;
		for(int i = 0; i < min; i++)
			output[i] = input[i];
		for(int i = input.length; i < length; i++)
			output[i] = (byte) 0;
		return output;
	}

	public byte[] getComponent() {
		return _versionComponent;
	}
}
