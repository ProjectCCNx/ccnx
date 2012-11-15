/*
 * CCNx Android Helper Library.
 *
 * Copyright (C) 2010, 2011 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation. 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.android.ccnlib;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * This is the "client side" interface to the repository service.
 */
public final class RepoWrapper extends CCNxWrapper {
	private static final String CLASS_TAG = "CCNxRepoWrapper";
	
	public static final String OPTION_LOG_LEVEL_DEFAULT = "WARNING";
	
	public enum REPO_OPTIONS { /* repo1 */
		REPO_DIRECTORY,
		REPO_DEBUG,
		REPO_LOCAL,
		REPO_GLOBAL,
		REPO_NAMESPACE
	}
	
	public enum CCNR_OPTIONS { /* repo2 */
		CCNR_DEBUG,
		CCNR_DIRECTORY,
		CCNR_GLOBAL_PREFIX,
		CCNR_BTREE_MAX_FANOUT,
		CCNR_BTREE_MAX_LEAF_ENTRIES,
		CCNR_BTREE_MAX_NODE_BYTES,
		CCNR_BTREE_NODE_POOL,
		CCNR_CONTENT_CACHE,
		CCNR_MIN_SEND_BUFSIZE,
		CCNR_PROTO,
		CCNR_LISTEN_ON,
		CCNR_STATUS_PORT
	}
	
	public enum CCNS_OPTIONS { /* sync */
		CCNS_DEBUG,
		CCNS_ENABLE,
		CCNS_REPO_STORE,
		CCNS_STABLE_ENABLED,
		CCNS_FAUX_ERROR,
		CCNS_HEARTBEAT_MICROS,
		CCNS_ROOT_ADVISE_FRESH,
		CCNS_ROOT_ADVISE_LIFETIME,
		CCNS_NODE_FETCH_LIFETIME,
		CCNS_MAX_FETCH_BUSY,
		CCNS_MAX_COMPARES_BUSY,
		CCNS_NOTE_ERR,
		CCNS_SYNC_SCOPE
	}
	
	public RepoWrapper(Context ctx) {
		super(ctx);
		TAG = CLASS_TAG;
		Log.d(TAG,"Initializing");
		serviceClassName = "org.ccnx.android.services.repo.RepoService";
		serviceName = "org.ccnx.android.service.repo.SERVICE";
		// setOption(REPO_OPTIONS.REPO_DEBUG, OPTION_LOG_LEVEL_DEFAULT);
	}
	
	@Override
	protected Intent getBindIntent() {
		Intent i = new Intent(serviceName);
		return i;
	}

	@Override
	protected Intent getStartIntent() {
		Intent i = new Intent(serviceName);
		fillIntentOptions(i);
		return i;
	}
	
	public void setOption(REPO_OPTIONS key, String value) {
		setOption(key.name(), value);
	}
	
	public void setOption(CCNR_OPTIONS key, String value) {
		setOption(key.name(), value);
	}
	
	public void setOption(CCNS_OPTIONS key, String value) {
		setOption(key.name(), value);
	}
}
