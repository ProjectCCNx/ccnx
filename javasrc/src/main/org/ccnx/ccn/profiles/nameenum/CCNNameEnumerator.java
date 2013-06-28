/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.profiles.nameenum;

import static org.ccnx.ccn.profiles.CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.logging.Level;

import org.ccnx.ccn.CCNContentHandler;
import org.ccnx.ccn.CCNContentInterest;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.CCNInterestHandler;
import org.ccnx.ccn.impl.QueuedContentHandler;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.io.content.Collection.CollectionObject;
import org.ccnx.ccn.profiles.CommandMarker;
import org.ccnx.ccn.profiles.SegmentationProfile;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse.NameEnumerationResponseMessage;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse.NameEnumerationResponseMessage.NameEnumerationResponseMessageObject;
import org.ccnx.ccn.profiles.security.KeyProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Exclude;
import org.ccnx.ccn.protocol.Interest;

/**
 * Implements the base Name Enumerator.  Applications register name prefixes.
 * Each prefix is explored until canceled by the application. This version
 * supports enumeration with multiple responders (repositories and applications).
 *
 * An application can have multiple enumerations active at the same time.
 * For each prefix, the name enumerator will generate an Interest.  Responses
 * to the Interest will be in the form of Collections (by a
 * NameEnumeratorResponder and repository implementations).  Returned Collections
 * will be parsed for the enumerated names and sent back to the application
 * using the callback with the applicable prefix and an array of names in
 * that namespace.  The application is expected to handle duplicate names from
 * multiple responses and should be able to handle names that are returned, but
 * may not be available at this time (for example, /a.com/b/c.txt might have
 * been enumerated but a.com content may not be available).
 *
 * @see CCNInterestHandler
 * @see CCNContentHandler
 * @see BasicNameEnumeratorListener
 * @see NameEnumerationResponse
 *
 */

public class CCNNameEnumerator implements CCNInterestHandler, CCNContentHandler {

	protected CCNHandle _handle = null;
	//protected ArrayList<ContentName> _registeredPrefixes = new ArrayList<ContentName>();
	protected BasicNameEnumeratorListener callback;
	protected ArrayList<ContentName> _registeredNames = new ArrayList<ContentName>();
	protected NEHandler _neHandler;

	/**
	 * A supporting class for CCNNameEnumerator.  NERequest objects hold registered prefixes and
	 * their corresponding active interests.
	 *
	 */

	private class NERequest{
		ContentName prefix = null;
		ArrayList<Interest> ongoingInterests = new ArrayList<Interest>();

		public NERequest(ContentName n) {
			prefix = n;
		}

		Interest getInterest(ContentName in) {
			for (Interest i : ongoingInterests)
				if (i.name().equals(in))
					return i;
			return null;
		}

		void removeInterest(Interest i) {
			ongoingInterests.remove(getInterest(i.name()));
		}

		void addInterest(Interest i) {
			if (getInterest(i.name()) == null)
				ongoingInterests.add(i);
		}

		ArrayList<Interest> getInterests() {
			return ongoingInterests;
		}

		public boolean containsInterest(Interest interest) {

			for (Interest i : ongoingInterests) {
				if(i.equals(interest))
					return true;
			}

			return false;
		}

	}

	/**
	 * A supporting class for CCNNameEnumerator.  NEResponse objects hold ContentName responses
	 * for incoming name enumeration requests.  Each NEResponse flag additionally has a dirty
	 * flag to determine if a new name enumeration response is needed.  If there is not any new
	 * information since the last request, a new response will not be sent.
	 *
	 */

	private class NEResponse {
		ContentName prefix = null;
		boolean dirty = true;

		public NEResponse(ContentName n) {
			prefix = n;
		}

		boolean isDirty() {
			return dirty;
		}

		void clean() {
			dirty = false;
		}

		void dirty() {
			dirty = true;
		}
	}

	/**
	 * Class to handle responses via a separate thread
	 */
	protected class NEHandler extends QueuedContentHandler<CCNContentInterest> {
		protected CCNHandle _handle;
		protected CCNContentHandler _handler;

		protected NEHandler(CCNHandle handle, CCNContentHandler handler) {
			_handle = handle;
			_handler = handler;
		}

		@Override
		public void process(CCNContentInterest ci) {
			ContentObject c = ci.getContent();
			Interest interest = ci.getInterest();
			ContentName prefix = interest.name().cut(CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes());
			NERequest ner = getCurrentRequest(prefix);

			//need to make sure the prefix is still registered
			if (ner==null)
				return;

			ner.removeInterest(interest);

			NameEnumerationResponseMessageObject neResponse;
			ArrayList<ContentName> names = new ArrayList<ContentName>();
			LinkedList<Link> links;
			Interest newInterest = interest;

			//update: now supports multiple responders!
			//note:  if responseIDs are longer than 1 component, need to revisit interest generation for followups
			if (c != null) {
				if (Log.isLoggable(Log.FAC_SEARCH, Level.FINE)) {
					Log.fine(Log.FAC_SEARCH, "we have a match for: {0} [{1}]", interest.name(), interest.toString());
				}
				ArrayList<Interest> newInterests = new ArrayList<Interest>();

				//we want to get new versions of this object
				newInterest = VersioningProfile.firstBlockLatestVersionInterest(c.name(), null);
				newInterests.add(newInterest);

				//does this content object have a response id in it?
				ContentName responseName = getIdFromName(c.name());

				if (responseName==null ) {
					//no response name...  this is an error!
					Log.warning(Log.FAC_SEARCH, "CCNNameEnumerator received a response without a responseID: {0} matching interest {1}", c.name(), interest.name());
				} else {
					//we have a response name.

					//supports single component response IDs
					//if response IDs are hierarchical, we need to avoid exploding the number of Interests we express

					//if the interest had a responseId in it, we don't need to make a new base interest with an exclude, we would have done this already.
					if (Log.isLoggable(Log.FAC_SEARCH, Level.FINE)) {
						Log.fine(Log.FAC_SEARCH, "response id from interest: {0}", getIdFromName(interest.name()));
					}

					if(getIdFromName(interest.name()) != null && getIdFromName(interest.name()).count() > 0) {
						//the interest has a response ID in it already...  skip making new base interest
					} else {
						//also need to add this responder to the exclude list to find more responders
						ContentName prefixWithMarker =
							new ContentName(prefix, CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes());
						Exclude excludes = interest.exclude();
						if(excludes==null)
							excludes = new Exclude();
						excludes.add(new byte[][]{responseName.component(0)});
						newInterest = Interest.constructInterest(prefixWithMarker, excludes, null, null, 4, null);

						//check to make sure the interest isn't already expressed
						if(!ner.containsInterest(newInterest))
							newInterests.add(newInterest);
					}

				}

				try {
					for(Interest i: newInterests) {
						_handle.expressInterest(i, _handler);
						ner.addInterest(i);
						if (Log.isLoggable(Log.FAC_SEARCH, Level.FINEST))
							Log.finest(Log.FAC_SEARCH, "expressed: {0}", i);
					}
				} catch (IOException e1) {
					// error registering new interest
					Log.warning(Log.FAC_SEARCH, "error registering new interest in handleContent");
					Log.warningStackTrace(Log.FAC_SEARCH, e1);
				}

				newInterests.clear();

				try {
					//need to make sure that the content object we got back is the first segment of the underlying stream.
					if (SegmentationProfile.isFirstSegment(c.getContentName())) {
						neResponse = new NameEnumerationResponseMessageObject(c, _handle);
					} else {
						neResponse = new NameEnumerationResponseMessageObject(SegmentationProfile.segmentRoot(c.getContentName()), _handle);
						Log.fine(Log.FAC_SEARCH, "Discovery interest got a content object that wasn't the base segment, stripping off segment number and opening object with name");
					}
					links = neResponse.contents();
					for (Link l: links) {
						names.add(l.targetName());
					}
					//strip off NEMarker before passing through callback
					//Note: we must not hold any locks here
					callback.handleNameEnumerator(
							interest.name().cut(CommandMarker.COMMAND_MARKER_BASIC_ENUMERATION.getBytes()), names);
				} catch(ContentDecodingException e) {
					Log.warning(Log.FAC_SEARCH, "Error parsing Collection from ContentObject in CCNNameEnumerator");
					Log.warningStackTrace(Log.FAC_SEARCH, e);
				} catch(IOException e) {
					Log.warning(Log.FAC_SEARCH, "error getting CollectionObject from ContentObject in CCNNameEnumerator.handleContent");
					Log.warningStackTrace(Log.FAC_SEARCH, e);
				}
			}
		}
	}

	protected ArrayList<NEResponse> _handledResponses = new ArrayList<NEResponse>();
	protected ArrayList<NERequest>  _currentRequests = new ArrayList<NERequest>();


	/**
	 * CCNNameEnumerator constructor.  Creates a CCNNameEnumerator, sets the CCNHandle,
	 * registers the callback and registers a prefix for enumeration.
	 *
	 * @param prefix ContentName to enumerate names under
	 * @param handle CCNHandle for sending and receiving collection objects during enumeration.
	 * @param c BasicNameEnumeratorListener callback to receive enumeration responses.
	 */

	public CCNNameEnumerator(ContentName prefix, CCNHandle handle, BasicNameEnumeratorListener c) throws IOException {
		_handle = handle;
		_neHandler = new NEHandler(handle, this);
		callback = c;
		registerPrefix(prefix);
	}


	/**
	 * CCNNameEnumerator constructor.  Creates a CCNNameEnumerator, sets the CCNHandle, and
	 * registers the callback.
	 *
	 * @param handle CCNHandle for sending and receiving collection objects during enumeration.
	 * @param c BasicNameEnumeratorListener callback to receive enumeration responses.
	 */

	public CCNNameEnumerator(CCNHandle handle, BasicNameEnumeratorListener c) {
		_handle = handle;
		_neHandler = new NEHandler(handle, this);
		callback = c;
	}

	public CCNHandle handle() { return _handle; }


	/**
	 * Method to register a prefix for name enumeration.  A NERequest and initial interest is created for new prefixes.
	 * Prefixes that are already registered return and do not impact the already active registration.
	 *
	 * @param prefix ContentName to enumerate
	 * @throws IOException
	 */

	public void registerPrefix(ContentName prefix) throws IOException {
		synchronized (_currentRequests) {
			NERequest r = getCurrentRequest(prefix);
			if (r != null) {
				// this prefix is already registered...
				if (Log.isLoggable(Log.FAC_SEARCH, Level.FINE))
					Log.fine(Log.FAC_SEARCH, "prefix {0} is already registered...  returning", prefix);
				return;
			} else {
				r = new NERequest(prefix);
				_currentRequests.add(r);
			}

			if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
				Log.info(Log.FAC_SEARCH, "Registered Prefix: {0}", prefix);

			ContentName prefixMarked =
				new ContentName(prefix, COMMAND_MARKER_BASIC_ENUMERATION);

			//we have minSuffixComponents to account for sig, version, seg and digest
			Interest pi = Interest.constructInterest(prefixMarked, null, null, null, 4, null);

			r.addInterest(pi);

			_handle.expressInterest(pi, this);
		}
	}

	/**
	 * Method to cancel active enumerations.  The active interests are retrieved from the corresponding
	 * NERequest object for the prefix.  Each interest is canceled and the NERequest object is removed
	 * from the list of active enumerations.
	 *
	 * @param prefix  ContentName to cancel enumeration
	 * @return boolean Returns if the prefix is successfully canceled.
	 */

	public boolean cancelPrefix(ContentName prefix) {
		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
			Log.info(Log.FAC_SEARCH, "cancel prefix: {0} ", prefix);
		synchronized(_currentRequests) {
			//cancel the behind the scenes interests and remove from the local ArrayList
			NERequest r = getCurrentRequest(prefix);
			if (r != null) {
				ArrayList<Interest> is = r.getInterests();
				if (Log.isLoggable(Log.FAC_SEARCH, Level.FINE))
					Log.fine(Log.FAC_SEARCH, "we have {0} interests to cancel", is.size());
				Interest i;
				while (!r.getInterests().isEmpty()) {
					i=r.getInterests().remove(0);
					_handle.cancelInterest(i, this);
				}

				_currentRequests.remove(r);
				return (getCurrentRequest(prefix) == null);
			}
			return false;
		}
	}

	/**
	 * Callback for name enumeration responses.  The results contain CollectionObjects containing the
	 * names under a prefix.  The collection objects are matched to registered prefixes and returned
	 * to the calling applications using their registered callback handlers.  Each response can create
	 * a new Interest that is used to further enumerate the namespace. The implementation
	 * explicitly handles multiple name enumeration responders.  The method may now create multiple
	 * interests to further enumerate the prefix.  Please note that the current implementation will
	 * need to be updated if responseIDs are more than one component long.
	 *
	 * @param c ContentObject containing the ContentNames under a registered prefix
	 * @param interest The interest matching or triggering a name enumeration response
	 *
	 * @return Interest Returns a new Interest to further enumerate or null to cancel the interest
	 * that matched these objects.  This implementation returns null since new interests are created and
	 * expressed as the returned CollectionObjects are processed.
	 *
	 * @see CollectionObject
	 * @see CCNInterestHandler
	 */

	public Interest handleContent(ContentObject c, Interest interest) {

		if (interest.name().contains(COMMAND_MARKER_BASIC_ENUMERATION)) {
			//the NEMarker is in the name...  good!
		} else {
			//COMMAND_MARKER_BASIC_ENUMERATION missing...  we have a problem
			Log.warning(Log.FAC_SEARCH, "the name enumeration marker is missing...  shouldn't have gotten this callback");
			return null;
		}

		if (Log.isLoggable(Log.FAC_SEARCH, Level.FINE)) {
			Log.fine(Log.FAC_SEARCH, "NE: received a response for interest {0}", interest);
		}

		_neHandler.add(new CCNContentInterest(c, interest));
		return null;
	}

	/**
	 * Method for receiving Interests matching the namespace for answering name enumeration requests.  Incoming Interests are
	 * verified to have the name enumeration marker.  The NEResponse matching the interest is found (if it already exists) and if
	 * new names have been registered under the prefix or if no matching NEResponse object is found, a name enumeration
	 * response is created.
	 *
	 * @param interest Interest object matching the namespace filter.
	 *
	 * @return boolean
	 */

	public boolean handleInterest(Interest interest) {


		boolean result = false;
		ContentName responseName = null;
		Link match;
		NameEnumerationResponseMessage nem;

		ContentName name = null;
		NEResponse r = null;
		if (Log.isLoggable(Log.FAC_SEARCH, Level.FINER)) {
			Log.finer(Log.FAC_SEARCH, "got an interest: {0}",interest.name());
		}
		name = interest.name();
		nem = new NameEnumerationResponseMessage();
		//Verify NameEnumeration Marker is in the name
		int cmbe = name.containsWhere(COMMAND_MARKER_BASIC_ENUMERATION);
		if (cmbe < 0) {
			//Skip...  we don't handle these
		} else {
			name = name.cut(cmbe);
			responseName = new ContentName(name, COMMAND_MARKER_BASIC_ENUMERATION);

			boolean skip = false;

			synchronized (_handledResponses) {
				//have we handled this response already?
				r = getHandledResponse(name);
				if (r != null) {
					//we have handled this before!
					if (r.isDirty()) {
						//this has updates to send back!!
					} else {
						//nothing new to send back...  go ahead and skip to next interest
						skip = true;
					}
				} else {
					//this is a new one...
					r = new NEResponse(name);
					_handledResponses.add(r);
				}

				if (!skip) {

					for (ContentName n: _registeredNames) {
						if (name.isPrefixOf(n) && name.count() < n.count()) {
							ContentName tempName = new ContentName(n.component(name.count()));
							match = new Link(tempName);
							if (!nem.contents().contains(match)) {
								nem.add(match);
							}
						}
					}
				}

				if (nem.size() > 0) {
					try {
						ContentName responseNameWithId = KeyProfile.keyName(responseName, _handle.keyManager().getDefaultKeyID());
						NameEnumerationResponseMessageObject nemobj = new NameEnumerationResponseMessageObject(responseNameWithId, nem, _handle);
						nemobj.saveLaterWithClose(interest);
						result = true;

						if (Log.isLoggable(Log.FAC_SEARCH, Level.FINE)) {
							Log.fine(Log.FAC_SEARCH, "Saved collection object in name enumeration: " + nemobj.getVersionedName());
						}

						r.clean();
					} catch(IOException e) {
						Log.warning(Log.FAC_SEARCH, "error processing an incoming interest..  dropping and returning");
						Log.warningStackTrace(Log.FAC_SEARCH, e);
						return false;
					}
				}

				if (Log.isLoggable(Log.FAC_SEARCH, Level.FINER))
					Log.finer(Log.FAC_SEARCH, "this interest did not have any matching names...  not returning anything.");
				if (r != null)
					r.clean();
			} //end of synchronized
		}  //end of name enumeration marker check

		return result;
	}

	/**
	 * Method to check if a name is already registered to be included in name enumeration responses for incoming Interests.
	 *
	 * @param name ContentName to check for in registered names for responses
	 * @return boolean Returns true if the name is registered and false if not
	 */

	public boolean containsRegisteredName(ContentName name) {
		if (name == null) {
			Log.warning(Log.FAC_SEARCH, "trying to check for null registered name");
			return false;
		}
		synchronized(_handledResponses) {
			if (_registeredNames.contains(name))
				return true;
			else
				return false;
		}
	}

	/**
	 * Method to register a namespace for filtering incoming Interests
	 *
	 * @param name ContentName to register for filtering incoming Interests
	 * @throws IOException
	 *
	 * @see CCNInterestHandler
	 */

	public void registerNameSpace(ContentName name) throws IOException {
		synchronized(_handledResponses) {
			if (!_registeredNames.contains(name)) {
				_registeredNames.add(name);
				_handle.registerFilter(name, this);
			}
		}

	}

	/**
	 * Method to register a name to include in incoming name enumeration requests.
	 *
	 * @param name ContentName to register for name enumeration responses
	 */

	public void registerNameForResponses(ContentName name) {

		if (name == null) {
			Log.warning(Log.FAC_SEARCH, "The content name for registerNameForResponses was null, ignoring");
			return;
		}
		//Do not need to register each name as a filter...  the namespace should cover it
		synchronized(_handledResponses) {
			if (!_registeredNames.contains(name)) {
				// DKS - if we don't care about order, could use a Set instead of an ArrayList,
				// then just call add as duplicates suppressed
				_registeredNames.add(name);
			}
			//check prefixes that were handled...  if so, mark them dirty
			updateHandledResponses(name);
		}
	}

	/**
	 * Method to get the NEResponse object for a registered name.  Returns null if no matching NEResponse is found.
	 *
	 * @param n ContentName identifying a NEResponse
	 * @return NEResponse Returns the NEResponse matching the name.
	 */

	protected NEResponse getHandledResponse(ContentName n) {
		//Log.info("checking handled responses...");
		synchronized (_handledResponses) {
			for (NEResponse t: _handledResponses) {
				if (t.prefix.equals(n))
					return t;
			}
			return null;
		}
	}

	/**
	 * Method to set the dirty flag for NEResponse objects that are updated as new names are registered for responses.
	 *
	 * @param n New ContentName to be included in name enumeration responses
	 */

	protected void updateHandledResponses(ContentName n) {
		synchronized (_handledResponses) {
			for (NEResponse t: _handledResponses) {
				if (t.prefix.isPrefixOf(n)) {
					t.dirty();
				}
			}
		}
	}

	/**
	 * Method to get the corresponding NERequest for a ContentName. Returns null
	 * if no NERequest is found.
	 *
	 * @param n ContentName for the NERequest to be found.
	 *
	 * @return NERequest NERequest instance with the supplied ContentName.
	 *         Returns null if no NERequest exists.
	 */

	protected NERequest getCurrentRequest(ContentName n) {
		synchronized (_currentRequests) {
			for (NERequest r : _currentRequests) {
				if (r.prefix.equals(n))
					return r;
			}
			return null;
		}
	}

	/**
	 * Method to cancel more than one prefix at a time.  This method will cancel all active Interests
	 * matching the prefix supplied. The matching NERequest objects are removed from the set of active
	 * registered prefixes and the corresponding Interests are canceled.
	 *
	 * @param prefixToCancel
	 */

	public void cancelEnumerationsWithPrefix(ContentName prefixToCancel) {
		if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
			Log.info(Log.FAC_SEARCH, "cancel prefix: {0}",prefixToCancel);
		synchronized(_currentRequests) {
			//cancel the behind the scenes interests and remove from the local ArrayList
			ArrayList<NERequest> toRemove = new ArrayList<NERequest>();
			for(NERequest n: _currentRequests){
				if(prefixToCancel.isPrefixOf(n.prefix))
					toRemove.add(n);
			}
			while(!toRemove.isEmpty()){
				if(cancelPrefix(toRemove.remove(0).prefix))
					if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
						Log.info(Log.FAC_SEARCH, "cancelled prefix: {0}", prefixToCancel);
				else
					if (Log.isLoggable(Log.FAC_SEARCH, Level.INFO))
						Log.info(Log.FAC_SEARCH, "could not cancel prefix: {0}", prefixToCancel);
			}
		}
	}

	private ContentName getIdFromName(ContentName name) {
		//get the response id, could be more than one component and have a version in it
		ContentName responseName = null;

		try {
			int index = name.containsWhere(COMMAND_MARKER_BASIC_ENUMERATION);
			ContentName prefix = name.subname(index+1, name.count());
			if(VersioningProfile.hasTerminalVersion(prefix))
				responseName = VersioningProfile.cutLastVersion(prefix);
			else
				responseName = prefix;
			if (Log.isLoggable(Log.FAC_SEARCH, Level.FINEST))
				Log.finest(Log.FAC_SEARCH, "NameEnumeration response ID: {0}", responseName);
		} catch(Exception e) {
			return null;
		}

		return responseName;
	}

}
