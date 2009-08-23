package org.ccnx.ccn.protocol;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncoder;

/**
 * This element in an #ExcludeFilter matches all components.
 */
public class ExcludeAny extends ExcludeFilter.Filler implements Comparable<ExcludeAny> {
	public static final String ANY = "Any";
	
	public boolean match(byte [] component) {
		return true;
	}

	@Override
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(ANY);
		decoder.readEndElement();
	}

	@Override
	public void encode(XMLEncoder encoder) throws XMLStreamException {
		encoder.writeStartElement(ANY);
		encoder.writeEndElement();
	}

	@Override
	public boolean validate() {
		return true;
	}
	
	public int compareTo(ExcludeAny o) {
		return 0;	// always equal
	}
	
	/**
     * All ExcludeAny's are equal to each other (but only to ExcludeAnys). 
	 * Without overriding equals we get Object's ==, which isn't what we want.
	 */
	@Override
	public boolean equals(Object obj) {
		if (obj == null)
			return false;
		if (!(obj instanceof ExcludeAny))
			return false;
		return true; // match any ExcludeAny
	}
}
