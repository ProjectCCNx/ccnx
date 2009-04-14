package com.parc.ccn.library;

import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import test.ccn.data.content.CCNEncodableCollectionData;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.Signature;
import com.parc.ccn.library.profiles.VersioningProfile;



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

public class CCNNameEnumerator implements CCNFilterListener, CCNInterestListener{

	public static final byte NAME_ENUMERATION_MARKER = (byte)0xFE;
	public static final byte [] NEMARKER = new byte []{NAME_ENUMERATION_MARKER};
	
	protected CCNLibrary _library = null;
	protected ArrayList<ContentName> _registeredPrefixes = new ArrayList<ContentName>();
	protected BasicNameEnumeratorListener callback; 
	protected ArrayList<ContentName> _registeredNames = new ArrayList<ContentName>();
	
	private class NERequest{
		ContentName prefix = null;
		ArrayList<Interest> ongoingInterests = new ArrayList<Interest>();
		
		public NERequest(ContentName n){
			prefix = n;
		}
		
		Interest getInterest(ContentName in){
			for(Interest i : ongoingInterests)
				if(i.name().equals(in))
					return i;
			return null;
		}
		
		void removeInterest(Interest i){
			ongoingInterests.remove(getInterest(i.name()));
		}
		
		void addInterest(Interest i){
			if(getInterest(i.name())==null)
				ongoingInterests.add(i);
		}
		
	}
	
	
	private class NEResponse{
		ContentName prefix = null;
		boolean dirty = true;
		
		public NEResponse(ContentName n){
			prefix = n;
		}
		
		boolean isDirty(){
			return dirty;
		}
		
		void clean(){
			dirty = false;
		}
		
		void dirty(){
			dirty = true;
		}
	}
	
	protected ArrayList<NEResponse> _handledResponses = new ArrayList<NEResponse>();
	protected ArrayList<NERequest>  _currentRequests = new ArrayList<NERequest>();
	
	public CCNNameEnumerator(ContentName prefix, CCNLibrary library, BasicNameEnumeratorListener c) throws IOException{
		_library = library;
		callback = c;
		registerPrefix(prefix);
	}
	
	public CCNNameEnumerator(CCNLibrary library, BasicNameEnumeratorListener c){
		_library = library;
		callback = c;
	}
	
	public void registerPrefix(ContentName prefix) throws IOException{
		
		if(!_registeredPrefixes.contains(prefix)){
			_registeredPrefixes.add(prefix);
			
			System.out.println("Registered Prefix");
			System.out.println("creating Interest");
			
			ContentName prefixMarked = new ContentName(prefix, NEMARKER, prefix.count()+1);
			
			Interest pi = new Interest(prefixMarked);
			pi.orderPreference(Interest.ORDER_PREFERENCE_ORDER_NAME);
			
			System.out.println("interest name: "+pi.name().toString()+" prefix: "+pi.name().prefixCount()+" order preference "+pi.orderPreference());
			NERequest n = new NERequest(prefix);
			n.addInterest(pi);
			_currentRequests.add(n);
			_library.expressInterest(pi, this);
			
			System.out.println("expressed Interest: "+prefixMarked.toString());
		}
	}
	
	
	public boolean cancelPrefix(ContentName prefix){
		//TODO need to cancel the behind the scenes interests and remove from the local ArrayList
		
		//_library.cancelInterest(interest, this);
				return _registeredPrefixes.remove(prefix);
	}
	
	
	public ArrayList<ContentName> parseCollection(Collection c){
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		
		// TODO fill in Collection to Names translation....
		// can we just get the body of the collection object to avoid a copy?
		
		return names;
	}
	
	
	public Interest handleContent(ArrayList<ContentObject> results, Interest interest) {
		
		System.out.println("we recieved a Collection matching our prefix...");
		
		if(interest.name().contains(NEMARKER)){
			//the NEMarker is in the name...  good!
		}
		else{
			//NEMARKER missing...  we have a problem
			System.err.println("the name enumeration marker is missing...  shouldn't have gotten this callback");
			_library.cancelInterest(interest, this);
			return null;
		}
		Collection collection;
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		ArrayList<LinkReference> links;
		ContentName responseName = null;
		
		//TODO  integrate handling for multiple responders, for now, just handles one result properly
		if(results!=null){
			for(ContentObject c: results){
				System.out.println("we have a match on "+interest.name());
				//responseName = c.name();
				responseName = new ContentName(c.name(), c.contentDigest(), interest.name().prefixCount());
				
				try{
					collection = Collection.contentToCollection(c);
					links = collection.contents();
					for(LinkReference l: links){
						names.add(l.targetName());
						System.out.println("names: "+l.targetName());
					}
					//strip off NEMarker before passing through callback
					callback.handleNameEnumerator(interest.name().cut(NEMARKER), names);
				}
				catch(XMLStreamException e){
					e.printStackTrace();
					System.err.println("Error getting CollectionData from ContentObject in CCNNameEnumerator");
				}		
			}
		}
		Interest newInterest = interest;
		if(responseName!=null){
			
			//TODO modify interest to exclude the current version?  use getNext?
			newInterest = Interest.last(responseName);
			//newInterest.orderPreference(newInterest.name().count()-2);
			newInterest.orderPreference(Interest.ORDER_PREFERENCE_ORDER_NAME);// | Interest.ORDER_PREFERENCE_RIGHT);
			NERequest ner = getCurrentRequest(interest.name().cut(NEMARKER));
			ner.removeInterest(interest);
			ner.addInterest(newInterest);
			System.out.println("new interest name: "+newInterest.name()+" total components: "+newInterest.name().count());
			try{
				System.out.println("version: "+VersioningProfile.getVersionAsTimestamp(responseName));
			}
			catch(Exception e){}
		}
		return newInterest;
	}
	
	// temporary workaround to test the callback without actually processing ContentObjects
	
	public int handleContent(ArrayList<ContentName> results, ContentName p) {
		
		System.out.println("we recieved content matching our prefix...");
		
		//Need to make sure the response has the NEMarker in it
		if(!p.contains(NEMARKER)){
			System.err.println("something is wrong...  we should have had the Name Enumeration Marker in the name");
		}
		else{
			System.out.println("we have a match on "+p.toString()+" and the NEMarker is in there!");
			if(_registeredPrefixes.contains(p)){
			
				callback.handleNameEnumerator(p, results);
			
			}
		}
		return results.size();
	}
	
	
	public int handleInterests(ArrayList<Interest> interests) {
		System.out.println("Received Interests matching my filter!");
		
		ContentName collectionName = null;
		LinkReference match;
		CollectionData cd;
				
		//TODO check for version...  not really needed when new Collection type is integrated...
			//alternative...  check if dirty and write out now if it is...  
		//Verify NameEnumeration Marker is in the name
		
		ContentName name = null;
		NEResponse r = null;
		for(Interest i: interests){
			name = i.name().clone();
			System.out.println("processing interest: "+name.toString());
			//collectionName = i.name().clone();
			
			cd = new CollectionData();
			//Verify NameEnumeration Marker is in the name
			if(!name.contains(NEMARKER)){
				//Skip...  we don't handle these
			}
			else{
				System.out.println("this interest contains the NE marker!");
				name = name.cut(NEMARKER);
				collectionName = new ContentName(name, NEMARKER, name.count()+1);
				
				
				boolean skip = false;
				//have we handled this response already?
				r = getHandledResponse(name);
				if(r!=null){
					//we have handled this before!
					if(r.isDirty()){
						//this has updates to send back!!
						System.out.println("the marker is dirty!  we have new names to send back!");
					}
					else{
						//nothing new to send back...  go ahead and skip to next interest
						skip = true;
						System.out.println("no new names to report...  skipping");
					}
				}
				else{
					//this is a new one...
					System.out.println("adding new handled response: "+name.toString());
					r = new NEResponse(name);
					_handledResponses.add(r);
				}
				if(!skip){
					for(ContentName n: _registeredNames){
						System.out.println("checking registered name: "+n.toString());
						if(name.isPrefixOf(n)){
							ContentName tempName = n.clone();
							System.out.println("we have a match! ("+tempName.toString()+")");
							System.out.println("prefix size "+name.count()+" registered name size "+n.count());
							byte[] tn = n.component(name.count());
							byte[][] na = new byte[1][tn.length];
							na[0] = tn;
							tempName = new ContentName(na);
							match = new LinkReference(tempName);
							//names.add(match);
							if(!cd.contents().contains(match)){
								cd.add(match);
								System.out.println("added name to response: "+tempName);
							}
						}
					}
				}
			}
			
			if(cd.size()>0){
				System.out.println("we have a response to send back for "+i.name().toString());
				System.out.println("Collection Name: "+collectionName.toString());
				try{
					
					//the following 6 lines are to be deleted after Collections are refactored
					LinkReference[] temp = new LinkReference[cd.contents().size()];
					for(int x = 0; x < cd.contents().size(); x++)
						temp[x] = cd.contents().get(x);
					_library.put(collectionName, temp);
					
					//CCNEncodableCollectionData ecd = new CCNEncodableCollectionData(collectionName, cd);
					//ecd.save();
					//System.out.println("saved ecd.  name: "+ecd.getName());
					r.clean();

				}
				catch(IOException e){
					
				}
				catch (SignatureException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			else{
				System.out.println("this interest did not have any matching names...  not returning anything.");
				r.clean();
			}
		}
		
		
		return 0;
	}

	public boolean containsRegisteredName(ContentName name){
		if(name==null){
			System.err.println("trying to check for null registered name");
			return false;
		}
		if(_registeredNames.contains(name))
			return true;
		else
			return false;
	}
	
	public void registerNameSpace(ContentName name){
		
		if(!_registeredNames.contains(name)){
			_registeredNames.add(name);
			System.out.println("registered "+ name.toString()+" as namespace");
			_library.registerFilter(name, this);
		}
		
	}
	
	public void registerNameForResponses(ContentName name){

		if(name==null){
			System.err.println("The content name for registerNameForResponses was null, ignoring");
			return;
		}
		//Do not need to register each name as a filter...  the namespace should cover it
		//_library.registerFilter(name, this);
		if(!_registeredNames.contains(name)){
		  _registeredNames.add(name);
		  System.out.println("registered "+ name.toString()+") for responses");		  
		}
		
		//check prefixes that were handled...  if so, mark them dirty
		updateHandledResponses(name);
	}
	
	protected NEResponse getHandledResponse(ContentName n){
		System.out.println("checking handled responses...");
		for(NEResponse t: _handledResponses){
			System.out.println("getHandledResponse: "+t.prefix.toString());
			if(t.prefix.equals(n))
				return t;
		}
		return null;
	}
	
	protected void updateHandledResponses(ContentName n){
		for(NEResponse t: _handledResponses){
			if(t.prefix.isPrefixOf(n)){
				t.dirty();
			}
		}
	}
	
	protected NERequest getCurrentRequest(ContentName n){
		System.out.println("checking current requests...");
		for(NERequest r: _currentRequests){
			if(r.prefix.equals(n))
				return r;
		}
		return null;
	}
	
}
