package test.ccn.data.util;

import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.util.EncodableObject;


public class EncodableCollectionData extends EncodableObject<CollectionData> {

	private static final long serialVersionUID = 1233491939485391189L;

	public EncodableCollectionData() {
		super(CollectionData.class);
	}
	
	public EncodableCollectionData(CollectionData collectionData) {
		super(CollectionData.class, collectionData);
	}
}
