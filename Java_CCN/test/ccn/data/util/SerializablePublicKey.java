package test.ccn.data.util;

import java.io.Serializable;
import java.security.PublicKey;

import org.ccnx.ccn.io.content.SerializableObject;


/**
 * Used in SerializableObjectTest.
 * @author smetters
 *
 */
public class SerializablePublicKey extends SerializableObject<PublicKey> implements Serializable {
	
	private static final long serialVersionUID = 1235874939485391189L;

	public SerializablePublicKey() {
		super(PublicKey.class);
	}
	
	public SerializablePublicKey(PublicKey publicKey) {
		super(PublicKey.class, publicKey);
	}

}
