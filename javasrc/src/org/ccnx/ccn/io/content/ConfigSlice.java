/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2011 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.security.crypto.CCNDigestHelper;
import org.ccnx.ccn.profiles.sync.Sync;
import org.ccnx.ccn.protocol.ContentName;

import static org.ccnx.ccn.impl.CCNFlowControl.SaveType.LOCALREPOSITORY;
import static org.ccnx.ccn.impl.encoding.CCNProtocolDTags.ConfigSlice;
import static org.ccnx.ccn.impl.encoding.CCNProtocolDTags.ConfigSliceList;
import static org.ccnx.ccn.impl.encoding.CCNProtocolDTags.ConfigSliceOp;
import static org.ccnx.ccn.impl.encoding.CCNProtocolDTags.SyncVersion;

/**
 * A ConfigSlice describes what names under a particular
 * name space will be synchronized. It is always saved
 * to the local repository under the localhost namespace.
 * They are named by the hash of the contents. This leads
 * to slightly different NetworkObject semantics than usual.
 */
public class ConfigSlice extends GenericXMLEncodable {

	/**
	 * Config slice lists require a ConfigSliceOp written before the
	 * ContentName, although it does nothing. This class
	 * encodes and decodes the preceding ConfigSliceOp and the
	 * ContentName together, representing a single filter element.
	 */
	@SuppressWarnings("serial")
	public static class Filter extends ContentName {

		public Filter() {
		}

		public Filter(byte[][] arg) {
			super(arg);
		}

		@Override
		public void decode(XMLDecoder decoder) throws ContentDecodingException {
			decoder.readIntegerElement(ConfigSliceOp);
			super.decode(decoder);
		}

		@Override
		public void encode(XMLEncoder encoder) throws ContentEncodingException {
			encoder.writeElement(ConfigSliceOp, 0);
			super.encode(encoder);
		}
	}

	private static final int SLICE_VERSION = 20110614;

	/**
	 * A ConfigSlice is always saved in the local repository under
	 * /localhost/CS.s.cs, so the full name does not need to be passed
	 * to the constructors here. In addition the object is named after
	 * a hash of the contents, so this must be available when creating
	 * a NetworkObject.
	 */
	public static class NetworkObject extends CCNEncodableObject<ConfigSlice> {

		/**
		 * Read constructor. Use when you have a slice hash (perhaps from enumeration),
		 * and want to know if it's present or not.
		 * @param hash of slice data.
		 * @param handle
		 * @throws ContentDecodingException
		 * @throws IOException
		 */
		public NetworkObject(byte[] hash, CCNHandle handle) throws ContentDecodingException, IOException {
			super(ConfigSlice.class, true, nameFromHash(hash), handle);
			setSaveType(LOCALREPOSITORY);
		}

		/**
		 * Write constructor.
		 * @param data Used to generate the full object name (which is a hash
		 * of the data).
		 * @param handle
		 * @throws ContentDecodingException
		 * @throws IOException
		 */
		public NetworkObject(ConfigSlice data, CCNHandle handle) throws IOException {
			super(ConfigSlice.class, false, nameFromHash(data.getHash()), data, LOCALREPOSITORY, handle);
		}

		/**
		 * Convenience write constructor.
		 * Creates an ConfigSlice, calculates the hash and creates a NetworkObject together.
		 */
		public NetworkObject(ContentName topo, ContentName prefix, Collection<Filter> filters, CCNHandle handle) throws IOException {
			this(new ConfigSlice(topo, prefix, filters), handle);
		}

		public static ContentName nameFromHash(byte[] hash) {
			return new ContentName(Sync.SYNC_PREFIX, hash);
		}

		public ConfigSlice getData() { return _data; }
	}

	public int version = SLICE_VERSION;
	public ContentName topo;
	public ContentName prefix;

	protected LinkedList<Filter> filters = new LinkedList<Filter>();

	public byte[] getHash() {
		try {
			return CCNDigestHelper.digest(encode());
		} catch (ContentEncodingException e) {
			// should never happen since we're encoding our own data
			throw new RuntimeException(e);
		}
	}

	/**
	 * Used by NetworkObject read constructor
	 */
	public ConfigSlice() {
	}

	public ConfigSlice(ContentName topo, ContentName prefix, Collection<Filter> new_filters) {
		this.topo = topo;
		this.prefix = prefix;
		if (new_filters != null)
			filters.addAll(new_filters);
	}

	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		version = decoder.readIntegerElement(SyncVersion);
		topo = new ContentName();
		topo.decode(decoder);
		prefix = new ContentName();
		prefix.decode(decoder);
		decoder.readStartElement(ConfigSliceList);
		while (decoder.peekStartElement(CCNProtocolDTags.ConfigSliceOp)) {
			Filter f = new Filter();
			f.decode(decoder);
			filters.add(f);
		}
		decoder.readEndElement();
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		encoder.writeStartElement(getElementLabel());
		encoder.writeElement(SyncVersion, version);
		topo.encode(encoder);
		prefix.encode(encoder);
		encoder.writeStartElement(ConfigSliceList);
		for(Filter f : filters)
			f.encode(encoder);
		encoder.writeEndElement();
		encoder.writeEndElement();
	}

	@Override
	public long getElementLabel() {
		return ConfigSlice;
	}

	@Override
	public boolean validate() {
		return true;
	}

	/**
	 * Check that a sync ConfigSlice exists in the local repository, and if not create one.
	 * @param handle
	 * @param topo from ConfigSlice
	 * @param prefix from ConfigSlice
	 * @param filters from ConfigSlice
	 * @throws IOException
	 */
	public static void checkAndCreate(ContentName topo, ContentName prefix, Collection<Filter> filters, CCNHandle handle) throws ContentDecodingException, IOException {
		ConfigSlice slice = new ConfigSlice(topo, prefix, filters);
		ConfigSlice.NetworkObject csno = new ConfigSlice.NetworkObject(slice.getHash(), handle);
		if (!csno.available() || csno.isGone()) {
			csno.setData(slice);
			csno.save();
		}
		csno.close();
	}
}
