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
package org.ccnx.ccn.profiles.security.access;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.util.ArrayList;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl.SaveType;
import org.ccnx.ccn.impl.encoding.CCNProtocolDTags;
import org.ccnx.ccn.impl.encoding.GenericXMLEncodable;
import org.ccnx.ccn.impl.encoding.XMLDecoder;
import org.ccnx.ccn.impl.encoding.XMLEncoder;
import org.ccnx.ccn.io.ErrorStateException;
import org.ccnx.ccn.io.content.CCNEncodableObject;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.io.content.ContentGoneException;
import org.ccnx.ccn.io.content.ContentNotReadyException;
import org.ccnx.ccn.io.content.KeyValueSet;
import org.ccnx.ccn.profiles.namespace.NamespaceProfile;
import org.ccnx.ccn.profiles.namespace.ParameterizedName;
import org.ccnx.ccn.profiles.namespace.PolicyMarker;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

/**
 * We identify policies that apply to a given namespace (subtree of the name tree) by
 * placing policy markers, as data, into that namespace. (Questions of how to authenticate
 * these markers is up to the policy and namespace; they are signed as regular CCNx data
 * and authentication policies can be based on signer information.)
 * 
 * This class specifies a policy marker used to indicat that a given namespace is under
 * access control, and to specify what access control scheme should be used to protect
 * and retrieve data in that namespace (questions of whether organizing access control
 * by namespace are left to future work). This object contains a small amount of data --
 * the access control profile used for the namespace (a string, used to index into a
 * map of classes implementing that policy string), a set of ParameterizedName, defining
 * mappings from strings to names within this namespace of interest to a given access control
 * scheme (e.g. a prefix where access control groups might be defined, etc), and then a
 * KeyValueSet of other, arbitrary parameters, for use by an access control scheme to store
 * additional policy information that it requires.
 */
public class AccessControlPolicyMarker extends GenericXMLEncodable implements PolicyMarker {
	
	ProfileName _profileName;
	ArrayList<ParameterizedName> _parameterizedNames = new ArrayList<ParameterizedName>();
	KeyValueSet _parameters;
	
	public static class AccessControlPolicyMarkerObject extends CCNEncodableObject<AccessControlPolicyMarker> {

		public AccessControlPolicyMarkerObject(ContentName name, CCNHandle handle) throws IOException {
			super(AccessControlPolicyMarker.class, true, name, handle);
		}

		public AccessControlPolicyMarkerObject(ContentName name, AccessControlPolicyMarker r, SaveType saveType, CCNHandle handle) throws IOException {
			super(AccessControlPolicyMarker.class, true, name, r, saveType, handle);
		}

		public AccessControlPolicyMarkerObject(ContentObject firstBlock, CCNHandle handle)
				throws ContentDecodingException, IOException {
			super(AccessControlPolicyMarker.class, true, firstBlock, handle);
		}

		public ContentName namespace() {
			return _baseName.cut(_baseName.count()-2);
		}
		
		public AccessControlPolicyMarker policy() throws ContentNotReadyException, ContentGoneException, ErrorStateException { return data(); }
	}

	public static class ProfileName extends ContentName {
		
		private static final long serialVersionUID = 7724253492801976571L;

		public ProfileName(ContentName other) {
			super(other);
		}
		
		public ProfileName() {}
		
		@Override
		public long getElementLabel() {
			return CCNProtocolDTags.ProfileName;
		}
	}

	/**
	 * Set up a part of the namespace to be under access control.
	 * This method writes the root block to a repository. Type-specific
	 * initialization (e.g. writing ACLs) needs to be handled by the appropriate
	 * subclass.
	 * @param name The top of the namespace to be under access control
	 * @throws IOException 
	 * @throws ConfigurationException 
	 */
	public static void create(ContentName name, SaveType saveType, CCNHandle handle) throws IOException {
		AccessControlPolicyMarker r = new AccessControlPolicyMarker();

		ContentName policyPrefix = NamespaceProfile.policyNamespace(name);
		ContentName policyMarkerName = AccessControlProfile.getAccessControlPolicyName(policyPrefix);
		AccessControlPolicyMarkerObject ro = new AccessControlPolicyMarkerObject(policyMarkerName, r, saveType, handle);
		ro.save();
	}
	
	/**
	 * Set up a part of the namespace to be under access control.
	 * This method writes the root block to a repository.
	 * This needs to be generic, and can't
	 * know about particular access control types. It will make and initialize
	 * an access control manager of the appropriate type for this namespace,
	 * load it into the search path, and hand it back. Type-specific initialization
	 * must be done by the caller.
	 * @param name The top of the namespace to be under access control
	 * @param parameterizedNames
	 * @param parameters
	 * @param saveType
	 * @param handle
	 * @throws IOException
	 * @throws ConfigurationException
	 */
	public static AccessControlManager create(ContentName name, ContentName profileName, ArrayList<ParameterizedName> parameterizedNames,
			KeyValueSet parameters, SaveType saveType, CCNHandle handle) throws IOException, InvalidKeyException {
		
		AccessControlPolicyMarker r = new AccessControlPolicyMarker(profileName, parameterizedNames, parameters);
		
		ContentName policyPrefix = NamespaceProfile.policyNamespace(name);
		ContentName policyMarkerName = AccessControlProfile.getAccessControlPolicyName(policyPrefix);
		AccessControlPolicyMarkerObject ro = new AccessControlPolicyMarkerObject(policyMarkerName, r, saveType, handle);
		ro.save();

		AccessControlManager acm;
		try {
			acm = AccessControlManager.createAccessControlManager(ro, handle);
		} catch (InstantiationException e) {
			throw new IOException("Cannot create access control manager of type " + profileName + ": " + e);
		} catch (IllegalAccessException e) {
			throw new IOException("Cannot create access control manager of type " + profileName + ": " + e);
		}
		handle.keyManager().rememberAccessControlManager(acm);
		
		return acm;
	}	
	
	public AccessControlPolicyMarker(ContentName profileName) {
		_profileName = new ProfileName(profileName);
	}
	
	public AccessControlPolicyMarker(ContentName profileName, ArrayList<ParameterizedName> parameterizedNames, KeyValueSet parameters) {
		this(profileName);
		_parameterizedNames.addAll(parameterizedNames);
		_parameters = parameters;
	}

	public AccessControlPolicyMarker() {}
	
	public void addParameterizedName(ParameterizedName name) { _parameterizedNames.add(name); }
	
	public ContentName profileName() { return _profileName; }

	public ArrayList<ParameterizedName> parameterizedNames() { return _parameterizedNames; }
	
	public KeyValueSet parameters() { return _parameters; }
	
	public boolean emptyParameters() { return (null == parameters()); }
	
	@Override
	public void decode(XMLDecoder decoder) throws ContentDecodingException {
		decoder.readStartElement(getElementLabel());
		
		_profileName = new ProfileName();
		_profileName.decode(decoder);

		while (decoder.peekStartElement(CCNProtocolDTags.ParameterizedName)) {
			ParameterizedName pn = new ParameterizedName();
			pn.decode(decoder);
			_parameterizedNames.add(pn);
		}
		
		if (decoder.peekStartElement(CCNProtocolDTags.Parameters)) {
			_parameters = new KeyValueSet();
			_parameters.decode(decoder);
		}
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws ContentEncodingException {
		if (!validate()) {
			throw new ContentEncodingException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		encoder.writeStartElement(getElementLabel());
		
		profileName().encode(encoder);

		// not technically thread-safe, but odds we get used from more than one thread low....
		// make caller lock
		for (ParameterizedName pn : parameterizedNames()) {
			pn.encode(encoder);
		}
		
		if (!emptyParameters()) {
			parameters().encode(encoder);
		}

		encoder.writeEndElement();   		
	}

	@Override
	public boolean validate() {
		return (null != _profileName);
	}

	@Override
	public long getElementLabel() {
		return CCNProtocolDTags.Root;
	}
}

