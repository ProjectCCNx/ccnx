package com.parc.ccn.library;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNFilterListener;
import org.ccnx.ccn.CCNInterestListener;
import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.Library;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;

import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.Link;
import com.parc.ccn.data.content.Collection.CollectionObject;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;



/**
 * Implements the base Name Enumerator.  Applications register name prefixes.
 * Each prefix is explored until canceled by the application.
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
 * @author rbraynar
 *
 */

public class CCNNameEnumerator implements CCNFilterListener, CCNInterestListener {

	public static final byte NAME_ENUMERATION_MARKER = (byte)0xFE;
	public static final byte [] NEMARKER = new byte []{NAME_ENUMERATION_MARKER};
	
	protected CCNHandle _library = null;
	//protected ArrayList<ContentName> _registeredPrefixes = new ArrayList<ContentName>();
	protected BasicNameEnumeratorListener callback; 
	protected ArrayList<ContentName> _registeredNames = new ArrayList<ContentName>();
	
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
		
	}
	
	
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
	
	protected ArrayList<NEResponse> _handledResponses = new ArrayList<NEResponse>();
	protected ArrayList<NERequest>  _currentRequests = new ArrayList<NERequest>();
	
	public CCNNameEnumerator(ContentName prefix, CCNHandle library, BasicNameEnumeratorListener c) throws IOException {
		_library = library;
		callback = c;
		registerPrefix(prefix);
	}
	
	public CCNNameEnumerator(CCNHandle library, BasicNameEnumeratorListener c) {
		_library = library;
		callback = c;
	}
	
	public void registerPrefix(ContentName prefix) throws IOException{
		synchronized(_currentRequests) {
			NERequest r = getCurrentRequest(prefix);
			if (r!=null) {
				//this prefix is already registered...
				Library.logger().info("prefix "+prefix.toString()+" is already registered...  returning");
			}
			else{
				r = new NERequest(prefix);
				_currentRequests.add(r);
			}
			
			Library.logger().info("Registered Prefix: "+prefix.toString());
			//Library.logger().info("creating Interest");
			
			ContentName prefixMarked = new ContentName(prefix, NEMARKER);
			
			Interest pi = new Interest(prefixMarked);
			
			//Library.logger().info("interest name: "+pi.name().toString()+" prefix: "+pi.name().prefixCount()+" order preference "+pi.orderPreference());
			r.addInterest(pi);
		
			_library.expressInterest(pi, this);
			
			//Library.logger().info("expressed Interest: "+prefixMarked.toString());
		}
	}
	
	
	public boolean cancelPrefix(ContentName prefix) {
		Library.logger().info("cancel prefix: "+prefix.toString());
		synchronized(_currentRequests) {
			//cancel the behind the scenes interests and remove from the local ArrayList
			NERequest r = getCurrentRequest(prefix);
			if (r != null) {
				ArrayList<Interest> is = r.getInterests();
				Library.logger().fine("we have "+is.size()+" interests to cancel");
				Interest i;
				while(!r.getInterests().isEmpty()) {
					i=r.getInterests().remove(0);
					_library.cancelInterest(i, this);
				}
			
				_currentRequests.remove(r);
				return (getCurrentRequest(prefix) == null);
			}
			return false;
		}
	}
	
	public Interest handleContent(ArrayList<ContentObject> results, Interest interest) {
		
		//Library.logger().info("we received a Collection matching our prefix...");
		
		if (interest.name().contains(NEMARKER)) {
			//the NEMarker is in the name...  good!
		} else {
			//NEMARKER missing...  we have a problem
			Library.logger().warning("the name enumeration marker is missing...  shouldn't have gotten this callback");
			//_library.cancelInterest(interest, this);
			return null;
		}
		
		synchronized(_currentRequests) {
			ContentName prefix = interest.name().cut(NEMARKER);
			NERequest ner = getCurrentRequest(prefix);
		
			//need to make sure the prefix is still registered
			if (ner==null) {
				//this is no longer registered...  no need to keep refreshing the interest use the callback
				//_library.cancelInterest(interest, this);
				return null;
			} else {
				ner.removeInterest(interest);
            }

			CollectionObject collection;
			ArrayList<ContentName> names = new ArrayList<ContentName>();
			LinkedList<Link> links;
			Interest newInterest = interest;
		
			//TODO  integrate handling for multiple responders, for now, just handles one result properly
			if (results != null) {
				for (ContentObject c: results) {
					//Library.logger().info("we have a match on "+interest.name());

					newInterest = Interest.last(c.name(), c.name().containsWhere(NEMARKER) + 1);
					try {
						_library.expressInterest(newInterest, this);
						ner.addInterest(newInterest);
					} catch (IOException e1) {
						// error registering new interest
						Library.logger().warning("error registering new interest in handleContent");
						Library.warningStackTrace(e1);
					}
				
					try {
						collection = new CollectionObject(c, _library);
						links = collection.contents();
						for (Link l: links) {
							names.add(l.targetName());
							//Library.logger().info("names: "+l.targetName());
						}
						//strip off NEMarker before passing through callback
						callback.handleNameEnumerator(interest.name().cut(NEMARKER), names);
					} catch(XMLStreamException e) {
						Library.logger().warning("Error getting Collection from ContentObject in CCNNameEnumerator");
						Library.warningStackTrace(e);
					} catch(IOException e) {
						Library.logger().warning("error getting CollectionObject from ContentObject in CCNNameEnumerator.handleContent");
						Library.warningStackTrace(e);
					}
				}
			}
		}
		//we now express the new interests as we process the responses...
		//return newInterest;
		return null; 
	}
	

	/*
	// temporary workaround to test the callback without actually processing ContentObjects
	public int handleContent(ArrayList<ContentName> results, ContentName p) {
		
		//Library.logger().info("we recieved content matching our prefix...");
		
		//Need to make sure the response has the NEMarker in it
		if (!p.contains(NEMARKER)) {
			Library.logger().warning("something is wrong...  we should have had the Name Enumeration Marker in the name");
		} else {
			//Library.logger().info("we have a match on "+p.toString()+" and the NEMarker is in there!");
			NERequest r = getCurrentRequest(p);
			if (r != null) {
				callback.handleNameEnumerator(p, results);
			}
		}
		return results.size();
	}
	*/
	
	
	public int handleInterests(ArrayList<Interest> interests) {
		//Library.logger().info("Received Interests matching my filter!");
		
		ContentName collectionName = null;
		Link match;
		Collection cd;
				
		
		ContentName name = null;
		NEResponse r = null;
		for (Interest i: interests) {
			name = i.name().clone();
			//Library.logger().info("processing interest: "+name.toString());
			//collectionName = i.name().clone();
			
			cd = new Collection();
			//Verify NameEnumeration Marker is in the name
			if (!name.contains(NEMARKER)) {
				//Skip...  we don't handle these
			} else {
				//Library.logger().info("this interest contains the NE marker!");
				name = name.cut(NEMARKER);
				collectionName = new ContentName(name, NEMARKER);
				
				boolean skip = false;
				synchronized (_handledResponses) {
					//have we handled this response already?
					r = getHandledResponse(name);
					if (r != null) {
						//we have handled this before!
						if (r.isDirty()) {
							//this has updates to send back!!
							//Library.logger().info("the marker is dirty!  we have new names to send back!");
						} else {
							//nothing new to send back...  go ahead and skip to next interest
							skip = true;
							//Library.logger().info("no new names to report...  skipping");
						}
					} else {
						//this is a new one...
						//Library.logger().info("adding new handled response: "+name.toString());
						r = new NEResponse(name);
						_handledResponses.add(r);
					} if (!skip) {
						for (ContentName n: _registeredNames) {
							//Library.logger().info("checking registered name: "+n.toString());
							if(name.isPrefixOf(n)) {
								ContentName tempName = n.clone();
								//Library.logger().info("we have a match! ("+tempName.toString()+")");
								//Library.logger().info("prefix size "+name.count()+" registered name size "+n.count());
								byte[] tn = n.component(name.count());
								byte[][] na = new byte[1][tn.length];
								na[0] = tn;
								tempName = new ContentName(na);
								match = new Link(tempName);
								//names.add(match);
								if (!cd.contents().contains(match)) {
									cd.add(match);
									//Library.logger().info("added name to response: "+tempName);
								}
							}
						}
					}
			
					if (cd.size()>0) {
						try {

							CollectionObject collobj = new CollectionObject(collectionName, cd, _library);
							collobj.save();
							Library.logger().info("Saved collection object in name enumeration: " + collobj.getCurrentVersionName());
							System.out.println("saved collection object");
							
							r.clean();

						} catch(IOException e) {
							Library.logger().warning("error processing an incoming interest..  dropping and returning");
							Library.warningStackTrace(e);
							return 0;
						}
					}
					Library.logger().finer("this interest did not have any matching names...  not returning anything.");
					if (r != null)
						r.clean();
				} //end of synchronized
			}  //end of name enumeration marker check
		} //end of interest processing loop
			
		return 0;
	}

	public boolean containsRegisteredName(ContentName name) {
		if (name == null) {
			System.err.println("trying to check for null registered name");
			return false;
		}
		synchronized(_handledResponses) {
			if (_registeredNames.contains(name))
				return true;
			else
				return false;
		}
	}
	
	public void registerNameSpace(ContentName name) {
		synchronized(_handledResponses) {
			if (!_registeredNames.contains(name)) {
				_registeredNames.add(name);
				//Library.logger().info("registered "+ name.toString()+" as namespace");
				_library.registerFilter(name, this);
			}
		}
		
	}
	
	public void registerNameForResponses(ContentName name) {

		if (name == null) {
			System.err.println("The content name for registerNameForResponses was null, ignoring");
			return;
		}
		//Do not need to register each name as a filter...  the namespace should cover it
		synchronized(_handledResponses) {
			//_library.registerFilter(name, this);
			if (!_registeredNames.contains(name)) {
				// DKS - if we don't care about order, could use a Set instead of an ArrayList,
				// then just call add as duplicates suppressed
				_registeredNames.add(name);
				//Library.logger().info("registered "+ name.toString()+") for responses");		  
			}
			//check prefixes that were handled...  if so, mark them dirty
			updateHandledResponses(name);
		}
	}
	
	protected NEResponse getHandledResponse(ContentName n) {
		//Library.logger().info("checking handled responses...");
		synchronized (_handledResponses) {
			for (NEResponse t: _handledResponses) {
				//Library.logger().info("getHandledResponse: "+t.prefix.toString());
				if (t.prefix.equals(n))
					return t;
			}
			return null;
		}
	}
	
	protected void updateHandledResponses(ContentName n) {
		synchronized (_handledResponses) {
			for (NEResponse t: _handledResponses) {
				if (t.prefix.isPrefixOf(n)) {
					t.dirty();
				}
			}
		}
	}
	
	protected NERequest getCurrentRequest(ContentName n) {
		//Library.logger().info("checking current requests...");
		synchronized (_currentRequests) {
			for (NERequest r: _currentRequests) {
				if (r.prefix.equals(n))
					return r;
			}
			return null;
		}
	}
	
}
