package com.parc.ccn.data.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.Interest;

/**
 * Table of Interests, holding an arbitrary value for any  
 * Interest or ContentName.  This is conceptually like a Map<Interest, V> except it supports
 * duplicate entries and has operations for access based on CCN 
 * matching.  An InterestTable may be used to hold real Interests, or merely 
 * ContentNames only, though mixing the two in the same instance of InterestTable
 * is not recommended.
 * @author jthornto
 *
 */

public class InterestTable<V> {

	public interface Entry<T> {
		/**
		 * Get the ContentName of this entry.  All table entries have non-null
		 * ContentName.
		 * @return
		 */
		public ContentName name();
		/**
		 * Get the Interest of this entry.  If a name is entered in the table
		 * then the Interest will be null.
		 * @return Interest if present, null otherwise
		 */
		public Interest interest();
		/**
		 * Get the value of this entry.  A value may be null.
		 * @return
		 */
		public T value();
	}
	
	/**
	 * To get things in the truly correct order for this we need to sort by length over canonical order
	 * @author rasmusse
	 *
	 */
	private class ITContentName implements Comparable<ITContentName> {
		private ContentName _name;
		
		public ITContentName(ContentName name) {
			_name = name;
		}
		
		public ContentName name() {
			return _name;
		}
		
		public int compareTo(ITContentName o) {
			if (this == o)
				return 0;
			if (_name.count() < o._name.count())
				return -1;
			else if (_name.count() > o._name.count())
				return 1;
			return _name.compareTo(o._name);
		}	
	}

	protected SortedMap<ITContentName,List<Holder<V>>> _contents = new TreeMap<ITContentName,List<Holder<V>>>();

	
	protected abstract class Holder<T> implements Entry<T> {
		protected T value;
		public Holder(T v) {
			value= v;
		}
		public T value() {
			return value;
		}
	}
	protected class NameHolder<T> extends Holder<T> {
		protected ContentName name;
		public NameHolder(ContentName n, T v) {
			super(v);
			name = n;
		}
		public ContentName name() {
			return name;
		}
		public Interest interest() {
			return null;
		}
	}
	protected class InterestHolder<T> extends Holder<T> {
		protected Interest interest;
		public InterestHolder(Interest i, T v) {
			super(v);
			interest = i;
		}
		public ContentName name() {
			return interest.name();
		}
		public Interest interest() {
			return interest;
		}
	}
	
	public void add(Interest interest, V value) {
		if (null == interest) {
			throw new NullPointerException("InterestTable may not contain null Interest");
		}
		if (null == interest.name()) {
			throw new NullPointerException("InterestTable may not contain Interest with null name");
		}
		Library.logger().finest("adding interest " + interest.name());
		Holder<V> holder = new InterestHolder<V>(interest, value);
		add(holder);
	}
	
	public void add(ContentName name, V value) {
		if (null == name) {
			throw new NullPointerException("InterestTable may not contain null name");
		}
		Library.logger().finest("adding name " + name);
		Holder<V> holder = new NameHolder<V>(name, value);
		add(holder);
	}
	
	protected void add(Holder<V> holder) {
		if (_contents.containsKey(new ITContentName(holder.name()))) {
			_contents.get(new ITContentName(holder.name())).add(holder);
		} else {
			ArrayList<Holder<V>> list = new ArrayList<Holder<V>>(1);
			list.add(holder);
			_contents.put(new ITContentName(holder.name()), list);
		}	
	}
	
	protected Holder<V> getMatchByName(ContentName name, ContentObject target) {
		Library.logger().finest("name: " + name + " target: " + target.name());
		List<Holder<V>> list = _contents.get(new ITContentName(name));
		if (null != list) {
			for (Iterator<Holder<V>> holdIt = list.iterator(); holdIt.hasNext(); ) {
				Holder<V> holder = holdIt.next();
				if (null != holder.interest()) {
					if (holder.interest().matches(target)) {
						return holder;
					}
				}	
			}
		}
		return null;
	}
	
	// Internal: return all the entries having exactly the specified name,
	// useful once you have found the matching names to collect entries from them
	protected List<Holder<V>> getAllMatchByName(ContentName name, ContentObject target) {
		Library.logger().finest("name: " + name + " target: " + target.name());
		List<Holder<V>> matches = new ArrayList<Holder<V>>();
		List<Holder<V>> list = _contents.get(new ITContentName(name));
		if (null != list) {
			for (Iterator<Holder<V>> holdIt = list.iterator(); holdIt.hasNext(); ) {
				Holder<V> holder = holdIt.next();
				if (null != holder.interest()) {
					if (holder.interest().matches(target)) {
						matches.add(holder);
					}
				}	
			}
		}
		return matches;
	}

	protected Holder<V> removeMatchByName(ContentName name, ContentObject target) {
		Library.logger().finest("name: " + name + " target: " + target.name());
		List<Holder<V>> list = _contents.get(new ITContentName(name));
		if (null != list) {
			for (Iterator<Holder<V>> holdIt = list.iterator(); holdIt.hasNext(); ) {
				Holder<V> holder = holdIt.next();
				if (null != holder.interest()) {
					if (holder.interest().matches(target)) {
						holdIt.remove();
						if (list.size() == 0) {
							_contents.remove(new ITContentName(name));
						}
						return holder;
					}
				}	
			}
		}
		return null;
	}

	/**
	 * Remove first exact match entry (both name and value match).  
	 * @param name
	 * @param value
	 * @return
	 */
	public Entry<V> remove(ContentName name, V value) {
		Holder<V> result = null;
		List<Holder<V>> list = _contents.get(new ITContentName(name));
		if (null != list) {
			for (Iterator<Holder<V>> holdIt = list.iterator(); holdIt.hasNext(); ) {
				Holder<V> holder = holdIt.next();
				if (null == holder.value()) {
					if (null == value) {
						holdIt.remove();
						result = holder;
					}
				} else {
					if (holder.value().equals(value)) {
						holdIt.remove();
						result = holder;
					}
				}
			}
		}
		return result;
	}
	
	/**
	 * Remove first exact match entry (both interest and value match)
	 * @param interest
	 * @param value
	 * @return
	 */
	public Entry<V> remove(Interest interest, V value) {
		Holder<V> result = null;
		List<Holder<V>> list = _contents.get(new ITContentName(interest.name()));
		if (null != list) {
			for (Iterator<Holder<V>> holdIt = list.iterator(); holdIt.hasNext(); ) {
				Holder<V> holder = holdIt.next();
				if (interest.equals(holder.interest())) {
					if (null == holder.value()) {
						if (null == value) {
							holdIt.remove();
							result = holder;
						}
					} else {
						if (holder.value().equals(value)) {
							holdIt.remove();
							result = holder;
						}
					}
				}
			}
		}
		return result;
	}
	
	protected List<Holder<V>> removeAllMatchByName(ContentName name, ContentObject target) {
		List<Holder<V>> matches = new ArrayList<Holder<V>>();
		List<Holder<V>> list = _contents.get(new ITContentName(name));
		if (null != list) {
			for (Iterator<Holder<V>> holdIt = list.iterator(); holdIt.hasNext(); ) {
				Holder<V> holder = holdIt.next();
				if (null != holder.interest()) {
					if (holder.interest().matches(target)) {
						holdIt.remove();
						matches.add(holder);
					}
				}	
			}
			if (list.size() == 0) {
				_contents.remove(new ITContentName(name));
			}
		}
		return matches;
	}

	/**
	 * Get value of longest matching Interest for a ContentObject, where longest is defined
	 * as longest ContentName.  Any ContentName entries in the table will be 
	 * ignored by this operation. If there are multiple matches, first is returned.
	 * @param target - desired ContentObject
	 * @return Entry of longest match if any, null if no match
	 */
	public V getValue(ContentObject target) {
		Entry<V> match = getMatch(target);
		if (null != match) {
			return match.value();
		} else {
			return null;
		}
	}

	/**
	 * Get longest matching Interest for a ContentObject.  This is the same as 
	 * getValue() except that the Entry is returned so the matching item
	 * may be retrieved and null value may be detected. The Entry returned will have a 
	 * non-null interest because this method matches only Interests in the table.
	 * @param target - desired ContentObject
	 * @return Entry of longest match if any, null if no match
	 *
	 * Comment by Paul Rasmussen - these used to try to use headMap as an optimization but 
	 * that won't work because without examining the interest we can't know what subset of 
	 * _contents might contain a matching interest. Also since headMap requires a bunch of 
	 * compares I'm not so sure how much of an optimization it is anyway...
	 */
	public Entry<V> getMatch(ContentObject target) {
		Library.logger().finest("target: " + target.name());
		Entry<V> match = null;
		for (ITContentName name : _contents.keySet()) {
			Entry<V> found = getMatchByName(name.name(), target);
			if (null != found)
				match = found;
	    }
		return match;
	}

	/**
	 * Get values of all matching Interests for a ContentObject.
	 * Any ContentName entries in the table will be 
	 * ignored by this operation and any null values will be ignored.
	 */
	public List<V> getValues(ContentObject target) {
		Library.logger().finest("target: " + target.name());

		List<V> result = new ArrayList<V>();
		List<Entry<V>> matches = getMatches(target);
		for (Entry<V> entry : matches) {
			if (null != entry.value()) {
				result.add(entry.value());
			}
		}
		return result;
	}
	
	/**
	 * Get all matching Interests for a ContentObject.
	 * Any ContentName entries in the table will be 
	 * ignored by this operation, so every Entry returned will have a 
	 * non-null interest.  This is the same as getValues() except that 
	 * Entry objects are returned.
	 * @param target - desired ContentObject
	 * @return List of matches, empty if no match
	 */
	public List<Entry<V>> getMatches(ContentObject target) {
		Library.logger().finest("target object name: " + target.name());

		List<Entry<V>> matches = new ArrayList<Entry<V>>();
		if (null != target) {
			for (ITContentName name : _contents.keySet()) {
				// Name match - is there an interest match here?
				matches.addAll(getAllMatchByName(name.name(), target));
			}
			Collections.reverse(matches);
		}
		return matches;
	}
		
	/**
	 * Get value of longest matching Interest for a ContentName, where longest is defined
	 * as longest ContentName.  If there are multiple matches, first is returned.  
	 * This will return a mix of ContentName and Interest entries if they exist
	 * (and match) in the table, i.e. the Interest of an Entry may be null in some cases.
	 * @param target desired ContentName
	 * @return Entry of longest match if any, null if no match
	 */
	public V getValue(ContentName target) {
		Library.logger().finest("target: " + target);

		Entry<V> match = getMatch(target);
		if (null != match) {
			return match.value();
		} else {
			return null;
		}
	}

	/**
	 * Get longest matching Interest.  This method is the same as getValue()
	 * except that the  Entry is returned so the matching item may be retrieved 
	 * and null value may be detected.
	 * @param target
	 * @return
	 */
	public Entry<V> getMatch(ContentName target) {
		Library.logger().finest("target: " + target);

		Entry<V> match = null;
		for (ITContentName name : _contents.keySet()) {
			if (name.name().isPrefixOf(target)) {
				match = _contents.get(name).get(0);
			}
	    }
		return match;
	}
	
	public List<V> getValues(ContentName target) {
		Library.logger().finest("target: " + target);

		List<V> result = new ArrayList<V>();
		List<Entry<V>> matches = getMatches(target);
		for (Entry<V> entry : matches) {
			if (null != entry.value()) {
				result.add(entry.value());
			} 
		}
		return result;
	}
	
	/**
	 * Get all matching entries for a ContentName.
	 * This will return a mix of ContentName and Interest entries if they exist
	 * (and match) in the table, i.e. the Interest of an Entry may be null in some cases.
	 * @param target desired ContentName
	 * @return List of matches ordered from longest match to shortest, empty if no match
	 */
	public List<Entry<V>> getMatches(ContentName target) {
		Library.logger().finest("target: " + target);

		List<Entry<V>> matches = new ArrayList<Entry<V>>();
		for (ITContentName name : _contents.keySet()) {
			if (name.name().isPrefixOf(target)) {
				matches.addAll(_contents.get(name));
			}
	    }
	    Collections.reverse(matches);
	    return matches;
	}

	/**
	 * Get all entries.  This will return a mix of ContentName and Interest entries
	 * if they exist in the table, i.e. the Interest of an Entry may be null in some cases.
	 * @return Collection of entries in arbitrary order
	 */
	public Collection<Entry<V>> values() {
		List<Entry<V>> results =  new ArrayList<Entry<V>>();
		for (Iterator<ITContentName> keyIt = _contents.keySet().iterator(); keyIt.hasNext();) {
			ITContentName name = (ITContentName) keyIt.next();
			List<Holder<V>> list = _contents.get(name);
			results.addAll(list);
		}
		return results;
	}
	
	/**
	 * Remove and return value of the longest matching Interest for a ContentObject, where best is defined
	 * as longest ContentName.  Any ContentName entries in the table will be 
	 * ignored by this operation, as will null values.
	 * @param target - desired ContentObject
	 * @return value of longest match if any, null if no match
	 */	
	public V removeValue(ContentObject target) {
		Entry<V> match = removeMatch(target);
		if (null != match) {
			return match.value();
		} else {
			return null;
		}
	}
	
	/**
	 * Remove and return the longest matching Interest for a ContentObject, where best is defined
	 * as longest ContentName.  Any ContentName entries in the table will be 
	 * ignored by this operation, so the Entry returned will have a 
	 * non-null interest. 
	 * @param target - desired ContentObject
	 * @return Entry of longest match if any, null if no match
	 */
	public Entry<V> removeMatch(ContentObject target) {
		Entry<V> match = null;
		if (null != target) {
			ContentName matchName = null;
			for (ITContentName name : _contents.keySet()) {
				Entry<V> found = getMatchByName(name.name(), target);
				if (null != found) {
					match = found;
					matchName = name.name();
				}
				// Do not remove here -- need to find best match and avoid disturbing iterator
			}
			if (null != match) {
				return removeMatchByName(matchName, target);
			}
		}
		return match;
	}

	/**
	 * Remove and return values for all matching Interests for a ContentObject.
	 * Any ContentName entries in the table will be 
	 * ignored by this operation.  Null values will not be represented in returned
	 * list though their Interests will have been removed if any. 
	 * @param target - desired ContentObject
	 * @return List of matches ordered from longest match to shortest, empty if no match
	 */
	public List<V> removeValues(ContentObject target) {
		List<V> result = new ArrayList<V>();
		List<Entry<V>> matches = removeMatches(target);
		for (Entry<V> entry : matches) {
			if (null != entry.value()) {
				result.add(entry.value());
			}
		}
		return result;
	}
	
	/**
	 * Remove and return all matching Interests for a ContentObject.
	 * Any ContentName entries in the table will be 
	 * ignored by this operation, so every Entry returned will have a 
	 * non-null interest. 
	 * @param target - desired ContentObject
	 * @return List of matches ordered from longest match to shortest, empty if no match
	 */
	public List<Entry<V>> removeMatches(ContentObject target) {
		List<Entry<V>> matches = new ArrayList<Entry<V>>();
		List<ContentName> names = new ArrayList<ContentName>();
		for (ITContentName name : _contents.keySet()) {
			if (name.name().isPrefixOf(target.name())) {
				// Name match - is there an interest match here?
				matches.addAll(getAllMatchByName(name.name(), target));
				names.add(name.name());
			}
	    }
	    if (matches.size() != 0) {
	    	for (ContentName contentName : names) {
		    	removeAllMatchByName(contentName, target);				
			}
	    }
	    Collections.reverse(matches);
	    return matches;
	}
	
	/**
	 * Get the number of distinct entries in the table.  Note that duplicate entries
	 * are fully supported, so the number of entries may be much larger than the 
	 * number of ContentNames (sizeNames()).
	 * @return
	 */
	public int size() {
		int result = 0;
	    for (Iterator<ITContentName> nameIt = _contents.keySet().iterator(); nameIt.hasNext();) {
			ITContentName name = nameIt.next();
			List<Holder<V>> list = _contents.get(name);
			result += list.size();
	    }
	    return result;
	}
	
	/**
	 * Get the number of distinct ContentNames in the table.  Note that duplicate
	 * entries are fully supported, so the number of ContentNames may be much smaller
	 * than the number of entries (size()).
	 * @return
	 */
	public int sizeNames() {
		return _contents.size();
	}
	
	public void clear() {
		_contents.clear();
	}

}
