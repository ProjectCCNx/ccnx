package test.ccn.data.util;

import org.ccnx.ccn.io.content.Collection;
import org.ccnx.ccn.io.content.EncodableObject;



public class EncodableCollectionData extends EncodableObject<Collection> {

	private static final long serialVersionUID = 1233491939485391189L;

	public EncodableCollectionData() {
		super(Collection.class);
	}
	
	public EncodableCollectionData(Collection collectionData) {
		super(Collection.class, collectionData);
	}
}
