package com.parc.ccn.apps;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class Utils {
	
	public static void daemonize() {
		System.out.close();
		System.err.close();
	}

	public static byte[] getBytesFromFile(File file) throws IOException {
        InputStream is = new FileInputStream(file);
    
        // Get the size of the file
        long length = file.length();
    
        if (length > Integer.MAX_VALUE) {
            // File is too large
        }
    
        // Create the byte array to hold the data
        byte[] bytes = new byte[(int)length];
    
        // Read in the bytes
        int offset = 0;
        int numRead = 0;
        while (offset < bytes.length
               && (numRead=is.read(bytes, offset, bytes.length-offset)) >= 0) {
            offset += numRead;
        }
    
        // Ensure all the bytes have been read in
        if (offset < bytes.length) {
            throw new IOException("Could not completely read file "+file.getName());
        }
    
        // Close the input stream and return bytes
        is.close();
        return bytes;
    }
	
	/**
	 * Recursively delete a directory and all its contents.
	 * If given File does not exist, this method returns with no error 
	 * but if it exists as a file not a directory, an exception will be thrown.
	 * Similar to org.apache.commons.io.FileUtils.deleteDirectory
	 * but avoids dependency on that library for minimal use.
	 * @param directory
	 * @throws IOException
	 */
	public static void deleteDirectory(File directory) throws IOException {
		if (!directory.exists()) {
			return;
		}
		if (!directory.isDirectory()) {
			throw new IOException(directory.getPath() + " is not a directory");
		}
		for (File child : directory.listFiles()) {
			if (child.isDirectory()) {
				deleteDirectory(child);
			} else {
				child.delete();
			}
		}
		directory.delete();
	}
}
