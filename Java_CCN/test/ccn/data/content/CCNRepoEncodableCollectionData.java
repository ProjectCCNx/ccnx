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
 * This will become the basis of the new Collection class once its functionality is merged
 * with existing Collections. Put it here temporarily so as to be able to test
 * CCNEncodableObject itself.
 * 
 * Force repo in order to be able to test repo object writes, regardless
 * of default setting.
 * 
 * Instead of making a paired raw/repo set like these (which are really
 * made for testing), let the user choose with appropriate constructors.
 * @author smetters
 *
 */
public class CCNRepoEncodableCollectionData extends CCNEncodableObject<CollectionData> {

	public CCNRepoEncodableCollectionData(ContentName name, CollectionData data, CCNLibrary library) throws ConfigurationException, IOException {
		super(CollectionData.class, name, data, false, library);
	}
	
	public CCNRepoEncodableCollectionData(ContentName name, PublisherPublicKeyDigest publisher,
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(CollectionData.class, name, publisher, false, library);
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
	public CCNRepoEncodableCollectionData(ContentName name, 
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(CollectionData.class, name, (PublisherPublicKeyDigest)null, false, library);
	}
	
	public CCNRepoEncodableCollectionData(ContentObject firstBlock,
			CCNLibrary library) throws ConfigurationException, IOException, XMLStreamException {
		super(CollectionData.class, firstBlock, false, library);
	}
}
