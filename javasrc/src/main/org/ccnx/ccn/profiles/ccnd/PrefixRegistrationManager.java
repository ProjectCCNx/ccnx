/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2009-2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.ccnd;

import static org.ccnx.ccn.profiles.ccnd.FaceManager.CCNX;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.impl.CCNNetworkManager;
import org.ccnx.ccn.impl.CCNNetworkManager.RegisteredPrefix;
import org.ccnx.ccn.impl.encoding.BinaryXMLCodec;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLCodecFactory;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;

public class PrefixRegistrationManager extends CCNDaemonHandle {

	public enum ActionType {
		Register ("prefixreg"), SelfRegister("selfreg"), UnRegister("unreg");
		ActionType(String st) { this.st = st; }
		private final String st;
		public String value() { return st; }
	}
	
	// Forwarding flags - refer to doc/technical/Registration.txt for the meaning of these
	public static final int CCN_FORW_ACTIVE = 1;
	public static final int CCN_FORW_CHILD_INHERIT = 2;	// This entry may be used even if there is a longer
														// match available
	public static final int CCN_FORW_ADVERTISE = 4;		// Prefix may be advertised to other nodes	
	public static final int CCN_FORW_LAST = 8;			// Entry should be used last if nothing else worked
	public static final int CCN_FORW_CAPTURE = 16;		// No shorter prefix may be used, overriding
														// child-inherit bits that would otherwise make the
														// shorter entries usable.

	public static final int CCN_FORW_LOCAL = 32;		// Restricts namespace to use by applications on the
														// local machine
	public static final int CCN_FORW_TAP = 64;			// Causes the entry to be used right away - intended
														// for debugging and monitoring purposes.
	public static final int CCN_FORW_CAPTURE_OK = 128;	// Use this with CCN_FORW_CHILD_INHERIT to make it eligible for capture.
	public static final int CCN_FORW_PUBMASK = 	CCN_FORW_ACTIVE |
            									CCN_FORW_CHILD_INHERIT |
            									CCN_FORW_ADVERTISE     |
            									CCN_FORW_LAST          |
            									CCN_FORW_CAPTURE       |
            									CCN_FORW_LOCAL         |
            									CCN_FORW_TAP           |
										CCN_FORW_CAPTURE_OK;

	
	public static final Integer DEFAULT_SELF_REG_FLAGS = Integer.valueOf(CCN_FORW_ACTIVE + CCN_FORW_CHILD_INHERIT);

	/*
	 * 	#define CCN_FORW_ACTIVE         1
	 *	#define CCN_FORW_CHILD_INHERIT  2
	 *	#define CCN_FORW_ADVERTISE      4
	 *	#define CCN_FORW_LAST           8
	 */
		
	public static class ForwardingEntry extends GenericXMLEncodable implements XMLEncodable {
		/* extends CCNEncodableObject<PolicyXML>  */
		
		/**
		 * From the XML definitions:
		 * <xs:element name="ForwardingEntry" type="ForwardingEntryType"/>
		 * <xs:complexType name="ForwardingEntryType">
  		 *		<xs:sequence>
      	 *		<xs:element name="Action" type="xs:string" minOccurs="0" maxOccurs="1"/>
      	 * 		<xs:element name="Name" type="NameType" minOccurs="0" maxOccurs="1"/>
      	 * 		<xs:element name="PublisherPublicKeyDigest" type="DigestType" minOccurs="0" maxOccurs="1"/>
         * 		<xs:element name="FaceID" type="xs:nonNegativeInteger" minOccurs="0" maxOccurs="1"/>
       	 * 	 	<xs:element name="ForwardingFlags" type="xs:nonNegativeInteger" minOccurs="0" maxOccurs="1"/>
       	 * 		<xs:element name="FreshnessSeconds" type="xs:nonNegativeInteger" minOccurs="0" maxOccurs="1"/>
      	 * 		</xs:sequence>
      	 * 	</xs:complexType>
		 */

		protected String		_action;
		protected ContentName	_prefixName;
		protected PublisherPublicKeyDigest _ccndId;
		protected Integer		_faceID;
		protected Integer		_flags;
		protected Integer 		_lifetime = Integer.MAX_VALUE;  // in seconds


		public ForwardingEntry(ContentName prefixName, Integer faceID, Integer flags) {
			_action = ActionType.Register.value();
			_prefixName = new ContentName(prefixName); // in case ContentName gets subclassed
			_faceID = faceID;
			_flags = flags;
		}

		public ForwardingEntry(ActionType action, ContentName prefixName, PublisherPublicKeyDigest ccndId, 
								Integer faceID, Integer flags, Integer lifetime) {
			_action = action.value();
			_ccndId = ccndId;
			_prefixName = new ContentName(prefixName); // in case ContentName gets subclassed
			_faceID = faceID;
			_flags = flags;
			_lifetime = lifetime;
		}

		public ForwardingEntry(byte[] raw) {
			ByteArrayInputStream bais = new ByteArrayInputStream(raw);
			XMLDecoder decoder = XMLCodecFactory.getDecoder(BinaryXMLCodec.CODEC_NAME);
			try {
				decoder.beginDecoding(bais);
				decode(decoder);
				decoder.endDecoding();	
			} catch (ContentDecodingException e) {
				String reason = e.getMessage();
				Log.warning(Log.FAC_NETMANAGER, "Unexpected error decoding ForwardingEntry from bytes.  reason: " + reason + "\n");
				Log.warningStackTrace(e);
				throw new IllegalArgumentException("Unexpected error decoding ForwardingEntry from bytes.  reason: " + reason);
			}
		}
		
		public ForwardingEntry() {
		}

		public ContentName getPrefixName() { return _prefixName; }
		
		public Integer getFaceID() { return _faceID; }
		public void setFaceID(Integer faceID) { _faceID = faceID; }

		public String action() { return _action; }
		
		public PublisherPublicKeyDigest getccndId() { return _ccndId; }
		public void setccndId(PublisherPublicKeyDigest id) { _ccndId = id; }
		
		/**
		 * 
		 * @return lifetime of registration in seconds
		 */
		public Integer getLifetime() { return Integer.valueOf(_lifetime.intValue()); }
		

		public String toFormattedString() {
			StringBuilder out = new StringBuilder(256);
			if (null != _action) {
				out.append("Action: "+ _action + "\n");
			} else {
				out.append("Action: not present\n");
			}
			if (null != _faceID) {
				out.append("FaceID: "+ _faceID.toString() + "\n");
			} else {
				out.append("FaceID: not present\n");
			}
			if (null != _prefixName) {
				out.append("Prefix Name: "+ _prefixName + "\n");
			} else {
				out.append("Prefix Name: not present\n");
			}
			if (null != _flags) {
				out.append("Flags: "+ _flags.toString() + "\n");
			} else {
				out.append("Flags: not present\n");
			}
			if (null != _lifetime) {
				out.append("Lifetime: "+ _lifetime.toString() + "\n");
			} else {
				out.append("Lifetime: not present\n");
			}
			return out.toString();
		}	

		public boolean validateAction(String action) {
			if (action != null && action.length() != 0){
				if (action.equalsIgnoreCase(ActionType.Register.value()) ||
						action.equalsIgnoreCase(ActionType.SelfRegister.value()) ||
						action.equalsIgnoreCase(ActionType.UnRegister.value())) {
					return true;
				}
				return false;
			}
			return true; 	// Responses don't have actions
		}
		/**
		 * Used by NetworkObject to decode the object from a network stream.
		 * @see org.ccnx.ccn.impl.encoding.XMLEncodable
		 */
		public void decode(XMLDecoder decoder) throws ContentDecodingException {
			decoder.readStartElement(getElementLabel());
			if (decoder.peekStartElement(CCNProtocolDTags.Action)) {
				_action = decoder.readUTF8Element(CCNProtocolDTags.Action); 
			}
			if (decoder.peekStartElement(CCNProtocolDTags.Name)) {
				_prefixName = new ContentName();
				_prefixName.decode(decoder) ;
			}
			if (decoder.peekStartElement(CCNProtocolDTags.PublisherPublicKeyDigest)) {
				_ccndId = new PublisherPublicKeyDigest();
				_ccndId.decode(decoder);
			}
			if (decoder.peekStartElement(CCNProtocolDTags.FaceID)) {
				_faceID = decoder.readIntegerElement(CCNProtocolDTags.FaceID); 
			}
			if (decoder.peekStartElement(CCNProtocolDTags.ForwardingFlags)) {
				_flags = decoder.readIntegerElement(CCNProtocolDTags.ForwardingFlags); 
			}
			if (decoder.peekStartElement(CCNProtocolDTags.FreshnessSeconds)) {
				_lifetime = decoder.readIntegerElement(CCNProtocolDTags.FreshnessSeconds); 
			}
			decoder.readEndElement();
		}

		/**
		 * Used by NetworkObject to encode the object to a network stream.
		 * @see org.ccnx.ccn.impl.encoding.XMLEncodable
		 */
		public void encode(XMLEncoder encoder) throws ContentEncodingException {
			if (!validate()) {
				throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
			}
			encoder.writeStartElement(getElementLabel());
			if (null != _action && _action.length() != 0)
				encoder.writeElement(CCNProtocolDTags.Action, _action);	
			if (null != _prefixName) {
				_prefixName.encode(encoder);
			}
			if (null != _ccndId) {
				_ccndId.encode(encoder);
			}
			if (null != _faceID) {
				encoder.writeElement(CCNProtocolDTags.FaceID, _faceID);
			}
			if (null != _flags) {
				encoder.writeElement(CCNProtocolDTags.ForwardingFlags, _flags);
			}
			if (null != _lifetime) {
				encoder.writeElement(CCNProtocolDTags.FreshnessSeconds, _lifetime);
			}
			encoder.writeEndElement();   			
		}

		@Override
		public long getElementLabel() { return CCNProtocolDTags.ForwardingEntry; }

		@Override
		public boolean validate() {
			if (validateAction(_action)){
				return true;
			}
			return false;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((_action == null) ? 0 : _action.hashCode());
			result = prime * result + ((_prefixName == null) ? 0 : _prefixName.hashCode());
			result = prime * result + ((_ccndId == null) ? 0 : _ccndId.hashCode());
			result = prime * result + ((_faceID == null) ? 0 : _faceID.hashCode());
			result = prime * result + ((_flags == null) ? 0 : _flags.hashCode());
			result = prime * result + ((_lifetime == null) ? 0 : _lifetime.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) return true;
			if (obj == null) return false;
			if (getClass() != obj.getClass()) return false;
			ForwardingEntry other = (ForwardingEntry) obj;
			if (_action == null) {
				if (other._action != null) return false;
			} else if (!_action.equalsIgnoreCase(other._action)) return false;
			if (_prefixName == null) {
				if (other._prefixName != null) return false;
			} else if (!_prefixName.equals(other._prefixName)) return false;
			if (_ccndId == null) {
				if (other._ccndId != null) return false;
			} else if (!_ccndId.equals(other._ccndId)) return false;
			if (_faceID == null) {
				if (other._faceID != null) return false;
			} else if (!_faceID.equals(other._faceID)) return false;
			if (_flags == null) {
				if (other._flags != null) return false;
			} else if (!_flags.equals(other._flags)) return false;
			if (_lifetime == null) {
				if (other._lifetime != null) return false;
			} else if (!_lifetime.equals(other._lifetime)) return false;
			return true;
		}

	} /* ForwardingEntry */

	/*************************************************************************************/
	/*************************************************************************************/

	public PrefixRegistrationManager(CCNHandle handle) throws CCNDaemonException {
		super(handle);
	}

	public PrefixRegistrationManager(CCNNetworkManager networkManager) throws CCNDaemonException {
		super(networkManager);
	}

	public PrefixRegistrationManager() {
	}
	
	public void registerPrefix(ContentName prefix, Integer faceID, Integer flags) throws CCNDaemonException {
		this.registerPrefix(prefix, null, faceID, flags, Integer.MAX_VALUE);
	}

	public void registerPrefix(String uri, Integer faceID, Integer flags) throws CCNDaemonException {
		this.registerPrefix(uri, null, faceID, flags, Integer.MAX_VALUE);
	}
	
	public void registerPrefix(String uri, PublisherPublicKeyDigest publisher, Integer faceID, Integer flags, 
			Integer lifetime) throws CCNDaemonException {
		try {
			this.registerPrefix(ContentName.fromURI(uri), null, faceID, flags, Integer.MAX_VALUE);
		} catch (MalformedContentNameStringException e) {
			String reason = e.getMessage();
			String msg = ("MalformedContentName (" + uri + ") , reason: " + reason);
			Log.warning(Log.FAC_NETMANAGER, msg);
			Log.warningStackTrace(e);
			throw new CCNDaemonException(msg);
		}
	}
	
	public void registerPrefix(ContentName prefixToRegister, PublisherPublicKeyDigest publisher, Integer faceID, Integer flags, 
							Integer lifetime) throws CCNDaemonException {
		if (null == publisher) {
			try {
				publisher = _manager.getCCNDId();
			} catch (IOException e1) {
				Log.warning(Log.FAC_NETMANAGER, "Unable to get ccnd id");
				Log.warningStackTrace(e1);
				throw new CCNDaemonException(e1.getMessage());
			}
		}
		
		ForwardingEntry forward = new ForwardingEntry(ActionType.Register, prefixToRegister, publisher, faceID, flags, lifetime);
		// byte[] entryBits = super.getBinaryEncoding(forward);

		/*
		 * First create a name that looks like 'ccnx:/ccnx/CCNDId/action/ContentObjectWithForwardInIt'
		 */
		ContentName interestName = null;
		try {
			interestName = new ContentName(CCNX, _manager.getCCNDId().digest(), ActionType.Register.value());
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new CCNDaemonException(e.getMessage());
		}
		super.sendIt(interestName, forward, null, true);
	}

	
	public ForwardingEntry selfRegisterPrefix(String uri) throws CCNDaemonException {
		ContentName prefixToRegister;
		try {
			prefixToRegister = ContentName.fromURI(uri);
		} catch (MalformedContentNameStringException e) {
			String reason = e.getMessage();
			String msg = ("MalformedContentNameStringException for prefix to register (" + uri + ") , reason: " + reason);
			Log.warning(Log.FAC_NETMANAGER, msg);
			Log.warningStackTrace(e);
			throw new CCNDaemonException(msg);
		}
		return selfRegisterPrefix(prefixToRegister, null, DEFAULT_SELF_REG_FLAGS, Integer.MAX_VALUE);
	}
	
	public ForwardingEntry selfRegisterPrefix(ContentName prefixToRegister) throws CCNDaemonException {
		return selfRegisterPrefix(prefixToRegister, null, DEFAULT_SELF_REG_FLAGS, Integer.MAX_VALUE);
	}
	
	public ForwardingEntry selfRegisterPrefix(ContentName prefixToRegister, Integer faceID) throws CCNDaemonException {
		return selfRegisterPrefix(prefixToRegister, faceID, DEFAULT_SELF_REG_FLAGS, Integer.MAX_VALUE);
	}
	
	public ForwardingEntry selfRegisterPrefix(ContentName prefixToRegister, Integer faceID, Integer flags) throws CCNDaemonException {
		return selfRegisterPrefix(prefixToRegister, faceID, flags, Integer.MAX_VALUE);
	}
	
	public ForwardingEntry selfRegisterPrefix(ContentName prefixToRegister, Integer faceID, Integer flags, Integer lifetime) throws CCNDaemonException {
		PublisherPublicKeyDigest ccndId;
		try {
			ccndId = _manager.getCCNDId();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			throw new CCNDaemonException(e1.getMessage());
		}
		ContentName interestName;
		interestName = new ContentName(CCNX, ccndId.digest(), ActionType.SelfRegister.value());
		ForwardingEntry forward = new ForwardingEntry(ActionType.SelfRegister, prefixToRegister, ccndId, faceID, flags, lifetime);

		byte[] payloadBack = super.sendIt(interestName, forward, null, true);
		ForwardingEntry entryBack = new ForwardingEntry(payloadBack);
		Log.fine(Log.FAC_NETMANAGER, "registerPrefix: returned {0}", entryBack);
		return entryBack; 
	}
	
	public void unRegisterPrefix(ContentName prefixName, Integer faceID) throws CCNDaemonException {
		unRegisterPrefix(prefixName, null, faceID);
	}
	
	/**
	 * Unregister a prefix with ccnd
	 * 
	 * @param prefixName ContentName of prefix
	 * @param prefix has callback for completion
	 * @param faceID faceId that has the prefix registered
	 * @throws CCNDaemonException
	 */
	public void unRegisterPrefix(ContentName prefixName, RegisteredPrefix prefix, Integer faceID) throws CCNDaemonException {
		PublisherPublicKeyDigest ccndId;
		try {
			ccndId = _manager.getCCNDId();
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			throw new CCNDaemonException(e1.getMessage());
		}
		ContentName interestName = new ContentName(CCNX, ccndId.digest(), ActionType.UnRegister.value());
		ForwardingEntry forward = new ForwardingEntry(ActionType.UnRegister, prefixName, ccndId, faceID, null, null);

		super.sendIt(interestName, forward, prefix, false);
	}
}
