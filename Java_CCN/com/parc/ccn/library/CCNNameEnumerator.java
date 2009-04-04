package com.parc.ccn.library;

import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.query.BasicNameEnumeratorListener;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.Interest;


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

public class CCNNameEnumerator implements CCNFilterListener{

	protected CCNLibrary _library = null;
	protected ArrayList<ContentName> _registeredPrefixes = new ArrayList<ContentName>();
	protected BasicNameEnumeratorListener callback; 
	
	
	public CCNNameEnumerator(ContentName prefix, CCNLibrary library, BasicNameEnumeratorListener c){
		_library = library;
		if(!_registeredPrefixes.contains(prefix))
			_registeredPrefixes.add(prefix);
		_library.registerFilter(prefix, this);
		callback = c;
	}

	public CCNNameEnumerator(String prefix, CCNLibrary library, BasicNameEnumeratorListener c) throws MalformedContentNameStringException{
		this(ContentName.fromNative(prefix), library, c);
		
	}
	
	public CCNNameEnumerator(CCNLibrary library, BasicNameEnumeratorListener c){
		_library = library;
		callback = c;
	}
	
	public void registerPrefix(ContentName prefix){
		
		if(!_registeredPrefixes.contains(prefix))
			_registeredPrefixes.add(prefix);
	}
	
	public void registerPrefix(String prefix) throws MalformedContentNameStringException{
		ContentName p = ContentName.fromNative(prefix);
		if(!_registeredPrefixes.contains(p))
			_registeredPrefixes.add(p);
	}
	
	public boolean cancelPrefix(ContentName prefix){
		return _registeredPrefixes.remove(prefix);
	}
	
	public boolean cancelPrefix(String prefix) throws MalformedContentNameStringException{
		ContentName p = ContentName.fromNative(prefix);
		return _registeredPrefixes.remove(p);
	}
	
	public ArrayList<ContentName> parseCollection(Collection c){
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		
		// TODO fill in Collection to Names translation....
		// can we just get the body of the collection object to avoid a copy?
		
		return names;
	}
	
	// TODO  What is the returned int for?
	
	public int handleContent(ArrayList<ContentObject> results, Interest interest) {
		
		System.out.println("we recieved interests matching our prefix...");
		
		if(results!=null){
			for(ContentObject c: results){
				System.out.println("we have a match on "+interest.name());
				try{
					callback.handleNameEnumerator(interest.name(), Collection.contentToCollection(c).contents());
				}
				catch(XMLStreamException e){
					e.printStackTrace();
					System.err.println("Error getting CollectionData from ContentObject in CCNNameEnumerator");
				}		
			}
		}
		
		return results.size();
	}
	
	// temporary workaround to test the callback without actually processing ContentObjects
	
	public int handleContent(ArrayList<LinkReference> results, ContentName p) {
		
		System.out.println("we recieved interests matching our prefix...");

		System.out.println("we have a match on "+p.toString());
		if(_registeredPrefixes.contains(p))
			callback.handleNameEnumerator(p, results);
		
		return results.size();
	}
	
	
	public int handleInterests(ArrayList<Interest> interests) {
		// TODO Auto-generated method stub
		return 0;
	}

	
}
