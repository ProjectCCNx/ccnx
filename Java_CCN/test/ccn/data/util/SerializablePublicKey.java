package test.ccn.data.util;

import java.io.Serializable;
import java.security.PublicKey;

import com.parc.ccn.data.util.SerializableObject;

/**
 * Used in SerializedObjectTest.
 * @author smetters
 *
 */
public class SerializedPublicKey extends SerializableObject<PublicKey> implements Serializable {
	
	private static final long serialVersionUID = 1235874939485391189L;

	public SerializedPublicKey() {
		super(PublicKey.class);
	}
	
	public SerializedPublicKey(PublicKey publicKey) {
		super(PublicKey.class, publicKey);
	}

}
