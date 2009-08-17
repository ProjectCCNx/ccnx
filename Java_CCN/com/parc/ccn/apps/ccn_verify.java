package com.parc.ccn.apps;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.spec.InvalidKeySpecException;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentObject;
import com.parc.ccn.security.crypto.util.CryptoUtil;

public class ccn_verify {
	
	public static void usage() {
		System.out.println("ccn_verify key_file ccnb_input_file [input_file [input_file...]] ");
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 2)  {
			usage();
			return;
		}
		
		try {
		
			PublicKey pubKey = readKeyFile(args[0]);
	
			for (int i=1; i < args.length; ++i) {
				ContentObject co = readObjectFile(args[i]);
				
				if (!co.verify(pubKey)) {
					System.out.println("BAD: Object: " + co.name() + " in file: " + args[i] + " failed to verify.");
				} else {
					System.out.println("GOOD: Object: " + co.name() + " in file: " + args[i] + " verified.");
				}
			}
		} catch (Exception e) {
			System.out.println("Exception in ccn_verify: " + e.getClass().getName() + ": " + e.getMessage());
			e.printStackTrace();
		}
	}
		
	public static ContentObject readObjectFile(String filePath) throws FileNotFoundException, XMLStreamException {
		FileInputStream fis = new FileInputStream(filePath);
		BufferedInputStream bis = new BufferedInputStream(fis);
		ContentObject co = new ContentObject();
		co.decode(bis);
		return co;
		
	}
	
	public static PublicKey readKeyFile(String filePath) throws IOException, FileNotFoundException, XMLStreamException, CertificateEncodingException, InvalidKeySpecException, NoSuchAlgorithmException {
		ContentObject keyObject = readObjectFile(filePath);
		try {
			return CryptoUtil.getPublicKey(keyObject.content());
		} catch (InvalidKeySpecException e) {
			System.out.println("Exception decoding public key! " + filePath + " " + e.getClass().getName() + ": " + e.getMessage());
			FileOutputStream fos = new FileOutputStream("contentDump.der");
			fos.write(keyObject.content());
			fos.close();
			throw e;
		}
	}

}
