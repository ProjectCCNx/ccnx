package com.parc.ccn.network.daemons.repo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeMap;

import org.apache.jackrabbit.util.Base64;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.WirePacket;
import com.parc.ccn.data.query.Interest;

/**
 * An initial implementation of the repository using a file system
 * based repository.  We are using the XMLEncodeable representation to
 * represent content on the disk
 * 
 * Note: This layer purposefully should not make any calls into the
 * CCN library
 * 
 * @author rasmusse
 *
 */

public class RFSImpl implements Repository {
	
	public final static String META_DIR = ".meta";
	public final static byte UTF8_COMPONENT = '0';
	public final static byte BASE64_COMPONENT = '1';
	public final static byte SPLIT_COMPONENT = '2';
	
	private static String RESERVED_CLASH = "reserved";
	private static String ENCODED_FILES = "encoded_files";
	
	private static final int TOO_LONG_SIZE = 200;
	
	private String _repositoryRoot = "repo";
	private File _repositoryFile;
	private RFSLocks _locker;
	
	private TreeMap<ContentName, File> encodedFiles = new TreeMap<ContentName, File>();
	
	public String[] initialize(String[] args) throws RepositoryException {
		String[] outArgs = args;
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-root")) {
				if (args.length < i + 2)
					throw new InvalidParameterException();
				_repositoryRoot = args[i + 1];
				break;
			}
		}
		_repositoryFile = new File(_repositoryRoot);
		_repositoryFile.mkdirs();
		_locker = new RFSLocks(_repositoryRoot + File.separator + META_DIR);
		return outArgs;
	}

	/**
	 * XXX - need to do much more to support the different interest behaviors here.
	 * 
	 * If the file exists directly, we assume its the right one.
	 * If it has a subdirectory with one entry, we assume that's the digest.
	 * If the file has no subdirectory and doesn't match directly, we assume the last element is a
	 * digest and try converting it for a match.
	 */
	public ContentObject getContent(Interest interest) throws RepositoryException {
		ContentName checkedName = checkReserved(interest.name());
		File file = new File(_repositoryFile + checkedName.toString());
		if (file.isDirectory()) {
			File[] dirFiles = file.listFiles();
			if (dirFiles.length > 0)
				file = dirFiles[0]; // arbitrary for the moment
		} else {
			if (!file.exists()) {
				/*
				 * Try converting last piece to digest form
				 */
				if (checkedName.count() > 1) {
					// Convert last component to "digest as file" form
					ContentName newName = checkedName.clone();
					String cnDigestString = cnDigestString(interest.name());
					newName.components().remove(newName.count() - 1);
					file = new File(_repositoryFile + newName.toString() + File.separator + cnDigestString);
				}
			}
		}
		if (!file.exists())
			file = checkSpecialFiles(interest.name());
		return getContentFromFile(file);
	}
	
	private ContentObject getContentFromFile(File fileName) throws RepositoryException {
		try {
			FileInputStream fis = new FileInputStream(fileName);
			byte[] buf = new byte[fis.available()];
			fis.read(buf);
			WirePacket packet = new WirePacket();
			packet.decode(buf);
			if (packet.data().size() == 1)
				return packet.data().get(0);
		} catch (Exception e) {
			throw new RepositoryException(e.getMessage());
		}
		return null;	
	}

	public void saveContent(ContentObject content) throws RepositoryException {
		ContentName name = checkReserved(content.name());
		try {
			saveContentToFile(name, content);
			return;
		} catch (RepositoryException re) {}
		
		/*
		 * If direct pathname doesn't work, try "special path"
		 */
		File file = getSpecialPath(content);
		saveContentToFile(file, content);
	}
	
	/**
	 * Algorithm is:
	 *   Save wire data to fileName/<digest>
	 *
	 * @param fileName
	 * @param content
	 * @throws RepositoryException
	 */
	private void saveContentToFile(ContentName name, ContentObject content) throws RepositoryException {
		String filePathName = _repositoryRoot + name.toString();
		File dirFile = new File(filePathName);
		dirFile.mkdirs();
		saveContentToFile(new File(filePathName, convertDigest(content)), content);
	}
	
	private void saveContentToFile(File file, ContentObject content) throws RepositoryException {
		try {
			_locker.lock(file.getName());
			file.createNewFile();
			FileOutputStream fos = new FileOutputStream(file);
			WirePacket packet = new WirePacket(content);
			fos.write(packet.encode());
			fos.close();
		} catch (Exception e) {
			throw new RepositoryException(e.getMessage());
		}
		_locker.unLock(file.getName());
	}
	
	/**
	 * Make sure proposed name doesn't clash with a reserved name.
	 * If so change the root
	 * @param name
	 * @return
	 */
	private ContentName checkReserved(ContentName name) {
		if (Arrays.equals(name.components().get(0), META_DIR.getBytes())) {
			ContentName reservedName = name.clone();
			reservedName.components().add(0, RESERVED_CLASH.getBytes());
			reservedName.components().add(0, META_DIR.getBytes());
			name = reservedName;
		}
		return name;
	}
	
	/**
	 * Convert a non usable pathname into something we can use
	 * @param name
	 * @return
	 */
	private File getSpecialPath(ContentObject content) {
		ContentName name = content.name();
		ArrayList<byte[]> components = name.components();
		ArrayList<byte[]> newComponents = new ArrayList<byte[]>();
		newComponents.add(META_DIR.getBytes());
		newComponents.add(ENCODED_FILES.getBytes());
		for (byte[] component : components) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			int length = component.length;
			int offset = 0;
			while (length > TOO_LONG_SIZE) {
				baos.write(SPLIT_COMPONENT);
				baos.write(component, offset, TOO_LONG_SIZE);
				newComponents.add(baos.toByteArray());
				length -= TOO_LONG_SIZE;
				offset += TOO_LONG_SIZE;
				baos.reset();
			}
			baos.write(UTF8_COMPONENT);
			baos.write(component, offset, length);
			newComponents.add(baos.toByteArray());
		}
		ContentName specialName = new ContentName(newComponents.size(), newComponents);
		System.out.println("Special name is: " + name.toString());
		File dirFile = new File(_repositoryRoot + specialName.toString());
		dirFile.mkdirs();
		File file = new File(_repositoryRoot + specialName.toString(), convertDigest(content));
		encodedFiles.put(name.clone(), file);
		return file;
	}
	
	private File checkSpecialFiles(ContentName name) {
		return encodedFiles.get(name);
	}
	
	private String convertDigest(ContentObject content) {
		return convertValue(content.contentDigest());
	}
	
	/**
	 * Convert input bytes to Base64 encoding, then remove '/'
	 * since this can be included
	 * For now we replace / with "%slash%"
	 * TODO - need to check this out on PCs
	 * @param bytes
	 * @return
	 */
	private String convertValue(byte[] bytes) {
		StringWriter writer = new StringWriter();
		try {
			Base64.encode(bytes, 0, bytes.length, writer);
		} catch (IOException e) {}
		String b64String = writer.toString();
		return b64String.replace("/", "%slash%");
	}
	
	/**
	 * Convert last piece of CN to Base64 digest form.
	 * We don't care if this is really a digest or not here.
	 * @param name
	 * @return
	 */
	private String cnDigestString(ContentName name) {
		return convertValue(name.component(name.components().size() - 1));
	}
}
