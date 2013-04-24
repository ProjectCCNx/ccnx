/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009, 2011, 2012 Palo Alto Research Center, Inc.
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
package org.ccnx.ccn.impl.security.crypto;

import static org.ccnx.ccn.impl.support.Serial.readObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.security.Key;
import java.security.PublicKey;

public class EncryptedObjectFileHelper {
	
	public static boolean writeEncryptedObject(File outputFile, 
						Serializable objectToWrite, PublicKey keyToEncryptFor)
				throws FileNotFoundException, IOException {
		
		// TODO -- actually encrypt -- put the data through a CipherOutputStream after the OOS
		// write the key block to the real output stream before attaching the COS
		ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(outputFile));
		try {
			oos.writeObject(objectToWrite);
		} finally {
			oos.close();
		}
		return true;
	}

	@SuppressWarnings("unchecked")
	public static <T extends Serializable> T readEncryptedObject(File inputFile, Key decryptionKey) throws FileNotFoundException, IOException, ClassNotFoundException {
		
		// TODO actually decrypt -- put the data through a CipherInputStream before the OIS
		// read the key block from the file before attaching the CIS
		FileInputStream fis = new FileInputStream(inputFile);
		ObjectInputStream input = new ObjectInputStream(fis);
		try {
			return (T)readObject(input);
		} finally {
			input.close();
		}
	}
}
