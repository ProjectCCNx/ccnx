package com.parc.ccn.library;

import java.io.IOException;

import com.parc.ccn.data.ContentObject;

/**
 * Allow hookup of backend processing to ccn IO operations
 * 
 * @author rasmusse
 *
 */

public interface CCNIOBackEnd {
	public void putBackEnd(ContentObject co) throws IOException;
}
