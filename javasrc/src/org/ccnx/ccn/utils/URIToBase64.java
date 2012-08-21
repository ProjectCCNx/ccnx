/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2010-2012 Palo Alto Research Center, Inc.
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

/**
 * PD org.ccnx.ccn.utils
 */
package org.ccnx.ccn.utils;

import org.ccnx.ccn.impl.support.DataUtils;
import org.ccnx.ccn.protocol.Component;

public class URIToBase64 {

	public static void usage() {
		System.out.println("usage: URIToBase64 uriString [uriString...]");
	}
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 1) {
			usage();
			return;
		}
		
		for (int i=0; i < args.length; ++i) {
			processArg(args[i]);
		}
	}
	
	public static void processArg(String arg) {
		try {
			byte [] binary = Component.parseURI(arg);
			System.out.println(new String(DataUtils.base64Encode(binary)));
		} catch (Exception e) {
			e.printStackTrace();
		} 
	}

}
