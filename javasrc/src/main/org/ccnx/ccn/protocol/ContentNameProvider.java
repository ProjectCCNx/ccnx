package org.ccnx.ccn.protocol;

/**
 * Allows a class to be included as an argument to a ContentName builder.
 * @see ContentName#builder(ContentName.StringParser, Object[])
 */
public interface ContentNameProvider {
	public ContentName getContentName();		
}
