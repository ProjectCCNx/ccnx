/**
 * 
 */
package com.parc.ccn.data.security;

import com.parc.ccn.data.ContentObject;

/**
 * @author smetters
 * 
 * A callback interface to allow low-level mechanisms to ask someone else to verify content.
 *
 */
public interface ContentVerifier {
	
	/**
	 * Verify this content object, perhaps in the context of other data held by the verifier.
	 * @param block Should we rename this?
	 * @return
	 */
	public boolean verify(ContentObject content);

}
