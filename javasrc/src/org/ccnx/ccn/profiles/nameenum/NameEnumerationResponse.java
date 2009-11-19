package org.ccnx.ccn.profiles.nameenum;

import java.util.ArrayList;

import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.Link;
import org.ccnx.ccn.protocol.CCNTime;
import org.ccnx.ccn.protocol.ContentName;

/**
 * NameEnumerationResponse objects are used to respond to incoming NameEnumeration interests.
 * 
 * NameEnumerationResponses are generated in two ways, in direct response to an interest
 * where there is new information to return, and where a previous interest was not
 * satisfied (set the interest flag), but a later save occurs directly under the namespace.
 *
 */
public class NameEnumerationResponse {
	
	private ContentName _prefix;
	private ArrayList<ContentName> _names;
	private CCNTime _version;
	
	/**
	 * Empty NameEnumerationResponse constructor that sets the variables to null.
	 */
	public NameEnumerationResponse() {
		_prefix = null;
		_names = new ArrayList<ContentName>(0);
		_version = null;
	}
	
	/**
	 * NameEnumerationResponse constructor that populates the object's variables.
	 * 
	 * @param p ContentName that is the prefix for this response
	 * @param n ArrayList<ContentName> of the names under the prefix
	 * @param ts CCNTime is the timestamp used to create the version component
	 *   for the object when it is written out
	 */
	public NameEnumerationResponse(ContentName p, ArrayList<ContentName> n, CCNTime ts) {
		_prefix = p;
		_names = n;
		_version = ts;
	}
	
	/**
	 * Builds a NE response from name components -- NE responses contain ContentNames that
	 * only have a single component. Make a friendlier constructor that doesn't require
	 * pre-making names from the components we really want to list.
	 */
	public NameEnumerationResponse(ContentName p, byte [][] names, CCNTime ts) {
		_prefix = p;
		_version = ts;
		_names = new ArrayList<ContentName>((null == names) ? 0 : names.length);
		if (null != names) {
			for (int i=0; i < names.length; ++i) {
				_names.add(new ContentName(ContentName.ROOT, names[i]));
			}
		}
	}
	
	/**
	 * Method to set the NameEnumerationReponse prefix. Right now
	 * forces caller to add the command prefix (e.g. CommandMarkers.COMMAND_MARKER_BASIC_ENUMERATION),
	 * should make this cleverer (even if there are multiple NE protocols).
	 * 
	 * @param p ContentName of the prefix for the response
	 * @return void
	 */
	public void setPrefix(ContentName p) {
		_prefix = p;
	}
	
	/**
	 * Method to set the names to return under the prefix.
	 * 
	 * @param n ArrayList<ContentName> of the children for the response
	 * @return void
	 */
	public void setNameList(ArrayList<ContentName> n) {
		_names = n;
	}
	
	/**
	 * Add a name to the list
	 */
	public void add(ContentName name) {
		_names.add(name);
	}
	
	/**
	 * Add a single-component name to the list.
	 */
	public void add(byte [] name) {
		_names.add(new ContentName(ContentName.ROOT, name));
	}
	
	/**
	 * Add a single-component name to the list.
	 */
	public void add(String name) {
		_names.add(ContentName.fromNative(ContentName.ROOT, name));
	}
	
	/**
	 * Method to get the prefix for the response.
	 * 
	 * @return ContentName prefix for the response
	 */
	public ContentName getPrefix() {
		return _prefix;
	}
	
	/**
	 * Method to get the names for the response.
	 * 
	 * @return ArrayList<ContentName> Names to return in the response
	 */
	public ArrayList<ContentName> getNames() {
		return _names;
	}
	
	
	/**
	 * Method to set the timestamp for the response version.
	 * @param ts CCNTime for the ContentObject version
	 * @return void
	 */
	public void setTimestamp(CCNTime ts) {
		_version = ts;
	}
	
	
	/**
	 * Method to get the timestamp for the response object.
	 * 
	 * @return CCNTime for the version component of the object
	 */
	public CCNTime getTimestamp() {
		return _version;
	}
	
	/**
	 * Method to return a Collection object for the names in the response
	 * 
	 * @return Collection A collection of the names (as Link objects) to return.
	 */
	public Collection getNamesInCollectionData() {
		Link [] temp = new Link[_names.size()];
		for (int x = 0; x < _names.size(); x++) {
			temp[x] = new Link(_names.get(x));
		}
		return new Collection(temp);
	}
	
	/**
	 * Method to check if the NameEnumerationResponse object has names to return.
	 * 
	 * @return boolean True if there are names to return, false if there are no
	 *   names or the list of names is null
	 */
	public boolean hasNames() {
		if (_names != null && _names.size() > 0)
			return true;
		else
			return false;
	}
	
}