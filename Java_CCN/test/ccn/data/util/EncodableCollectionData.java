package test.ccn.data.util;

import com.parc.ccn.data.content.Collection;
import com.parc.ccn.data.util.EncodableObject;


public class EncodableCollectionData extends EncodableObject<Collection> {

	private static final long serialVersionUID = 1233491939485391189L;

	public EncodableCollectionData() {
		super(Collection.class);
	}
	
	public EncodableCollectionData(Collection collectionData) {
		super(Collection.class, collectionData);
	}
}
