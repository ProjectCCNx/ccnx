package test.ccn.data.content;

import java.io.IOException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.CollectionData;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.util.CCNEncodableObject;
import com.parc.ccn.library.CCNLibrary;

/**
 * This will become the new Collection class once its functionality is merged
 * with existing Collections. Put it here temporarily so as to be able to test
 * CCNEncodableObject itself.
 * @author smetters
 *
 */
public class CCNEncodableCollectionData extends CCNEncodableObject<CollectionData> {

	public CCNEncodableCollectionData(ContentName name, CollectionData data, CCNLibrary library) throws ConfigurationException, IOException {
		super(CollectionData.class, name, data, library);
	}
	
	public CCNEncodableCollectionData(ContentName name, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(CollectionData.class, name, publisher, library);
	}
	
	/**
	 * Read constructor -- opens existing object.
	 * @param type
	 * @param name
	 * @param library
	 * @throws XMLStreamException
	 * @throws IOException
	 * @throws ClassNotFoundException 
	 */
	public CCNEncodableCollectionData(ContentName name, 
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(CollectionData.class, name, (PublisherPublicKeyDigest)null, library);
	}
	
	public CCNEncodableCollectionData(ContentObject firstBlock,
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(CollectionData.class, firstBlock, library);
	}
}
