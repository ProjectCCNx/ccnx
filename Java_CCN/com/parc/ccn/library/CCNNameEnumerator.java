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

	protected CCNLibrary _library = null;
	protected ArrayList<ContentName> _registeredPrefixes = new ArrayList<ContentName>();
	protected BasicNameEnumeratorListener callback; 
	protected ArrayList<ContentName> _registeredNames = new ArrayList<ContentName>();
	
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
			
			Interest pi = new Interest(prefix);
			_library.expressInterest(pi, this);
			System.out.println("expressed Interest");

		}
	}
	
	
	public boolean cancelPrefix(ContentName prefix){
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
		Collection collection;
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		ArrayList<LinkReference> links;
		ContentName responseName = null;
		if(results!=null){
			for(ContentObject c: results){
				System.out.println("we have a match on "+interest.name());
				responseName = c.name();
				try{
					collection = Collection.contentToCollection(c);
					links = collection.contents();
					for(LinkReference l: links){
						names.add(l.targetName());
						System.out.println("names: "+l.targetName());
					}
					//callback.handleNameEnumerator(interest.name(), Collection.contentToCollection(c).contents());
					callback.handleNameEnumerator(interest.name(), names);
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
			//newInterest.orderPreference(newInterest.nameComponentCount()-2);
			//interest.
			System.out.println("new interest name: "+newInterest.name()+" total components: "+newInterest.nameComponentCount());
			try{
			System.out.println("version: "+VersioningProfile.getVersionAsTimestamp(responseName));
			}
			catch(Exception e){}
		}
		return newInterest;
	}
	
	// temporary workaround to test the callback without actually processing ContentObjects
	
	//public int handleContent(ArrayList<LinkReference> results, ContentName p) {
	public int handleContent(ArrayList<ContentName> results, ContentName p) {
		
		System.out.println("we recieved content matching our prefix...");

		System.out.println("we have a match on "+p.toString());
		if(_registeredPrefixes.contains(p)){
			
			callback.handleNameEnumerator(p, results);
			
		}
		
		return results.size();
	}
	
	
	public int handleInterests(ArrayList<Interest> interests) {
		System.out.println("Received Interests matching my filter!");
		
		//ArrayList<LinkReference> names = new ArrayList<LinkReference>();
		ContentName collectionName = null;
		LinkReference match;
		CollectionData cd;
				
		//TODO add check for NameEnumeration marker
		for(Interest i: interests){
			System.out.println("processing interest: "+i.name().toString());
			collectionName = i.name().clone();
			cd = new CollectionData();
			//names.clear();
			for(ContentName n: _registeredNames){
				System.out.println("checking registered name: "+n.toString());
				if(i.name().isPrefixOf(n)){
					ContentName tempName = n.clone();
					System.out.println("we have a match! ("+tempName.toString()+")");
					System.out.println("prefix size "+i.name().count()+" registered name size "+n.count());
					byte[] tn = n.component(i.name().count());
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
			
			if(cd.size()>0){
				System.out.println("we have a response to send back for "+i.name().toString());
				try{
					
					//the following 6 lines are to be deleted after Collections are refactored
					LinkReference[] temp = new LinkReference[cd.contents().size()];
					for(int x = 0; x < cd.contents().size(); x++)
						temp[x] = cd.contents().get(x);
					Collection coll = new Collection(collectionName, temp, null, null, (Signature)null);
					if(coll.validate())
					_library.put(coll);
					
					CCNEncodableCollectionData ecd = new CCNEncodableCollectionData(collectionName, cd);
					//ecd.save();
					System.out.println("saved ecd.  name: "+ecd.getName());

				}
				catch(IOException e){
					
				}
				catch (XMLStreamException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (ConfigurationException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
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
			System.out.println("registered "+ name.toString()+") as namespace");
			_library.registerFilter(name, this);
		}
		
	}
	
	public void registerNameForResponses(ContentName name){

		if(name==null){
			System.err.println("The content name for registerNameForResponses was null, ignoring");
			return;
		}
		
		_library.registerFilter(name, this);
		if(!_registeredNames.contains(name)){
		  _registeredNames.add(name);
		  System.out.println("registered "+ name.toString()+") for responses");		  
		}
	}
	
}
