/*
 * A CCNx example of extending ccnb encoding/decoding.
 *
 * Copyright (C) 2008, 2009, 2010, 2011 Palo Alto Research Center, Inc.
 *
 * This work is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License version 2 as published by the
 * Free Software Foundation.
 * This work is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details. You should have received a copy of the GNU General Public
 * License along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA 02110-1301, USA.
 */


package org.ccnx.ccn.apps.examples.ccnb;


public enum Enumeration {
	Enu0("Enu0"), Enu1("Enu1"), Enu2("Enu2");
	private final String st;
	Enumeration(String st) { this.st = st; }
	public String toString() { return st; }
	public static Enumeration getType(int value) {
		for (Enumeration ift : values()) {
			if (ift.ordinal() == value)
				return ift;
		}
		return Enu2;
	}

}

