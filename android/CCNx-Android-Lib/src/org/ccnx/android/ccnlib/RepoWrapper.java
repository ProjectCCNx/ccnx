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
	
	public enum REPO_OPTIONS {
		REPO_DIRECTORY,
		REPO_DEBUG,
		REPO_LOCAL,
		REPO_GLOBAL,
		REPO_NAMESPACE
	}
	
	public RepoWrapper(Context ctx) {
		super(ctx);
		TAG = CLASS_TAG;
		Log.d(TAG,"Initializing");
		serviceClassName = "org.ccnx.android.services.repo.RepoService";
		serviceName = "org.ccnx.android.service.repo.SERVICE";
		setOption(REPO_OPTIONS.REPO_DEBUG, OPTION_LOG_LEVEL_DEFAULT);
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
}
