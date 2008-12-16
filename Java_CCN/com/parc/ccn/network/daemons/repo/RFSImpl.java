package com.parc.ccn.network.daemons.repo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.StringTokenizer;
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
	
	private final static String encoding = "UTF-8";
	
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
		constructEncodedMap(new File(_repositoryRoot + File.separator + META_DIR + File.separator + ENCODED_FILES));
		return outArgs;
	}
	
	/**
	 * Build initial map of encoded files already in the repository
	 * 
	 * @param root
	 */
	private void constructEncodedMap(File root) {
		if (root.isDirectory()) {
			File[] files = root.listFiles();
			for (File file : files) {
				if (file.isDirectory())
					constructEncodedMap(file);
				else if (file.isFile()) 
					mapFromPath(file);
			}
		}
	}
	
	private void mapFromPath(File file) {
		StringTokenizer st = new StringTokenizer(file.getPath(), new String(File.separator));
		ArrayList<byte[]> components = new ArrayList<byte[]>();
		boolean encodedArea = false;
		String currentValue = "";
		while (st.hasMoreTokens()) {
			String token = st.nextToken();
			if (encodedArea) {
				byte type = (byte)token.charAt(0);
				switch (type) {
				  case UTF8_COMPONENT:
					  currentValue += token.substring(1);
					  components.add(currentValue.getBytes());
					  currentValue = "";
					  break;
				  case BASE64_COMPONENT:
					  currentValue += new String(decodeBase64(token.substring(1)));
					  components.add(currentValue.getBytes());
					  currentValue = "";
					  break;
				  case SPLIT_COMPONENT:
					  currentValue += token.substring(1);
					  break;
				  default:
					  currentValue += token;
				  	  components.add(currentValue.getBytes());
				  	  currentValue = "";
					  break;
				}
			} else {
				if (token.equals(ENCODED_FILES))
					encodedArea = true;
			}
		}
		byte [] digestComponent = components.remove(components.size() - 1);
		ContentName cn = new ContentName(components.size(), components);
		if (null != encodedFiles.get(cn)) {
			components.add(components.size(), digestComponent);
			cn = new ContentName(components.size(), components);
		}
		encodedFiles.put(cn, file);
	}

	public ContentObject getContent(Interest interest) throws RepositoryException {
		File file = getBestMatch(interest);
		if (null == file || !file.exists())
			return null;
		return getContentFromFile(file);
	}
	
	/**
	 * Go through all the "possible matches" and find the best one
	 * to match the interest
	 * 
	 * @param interest
	 * @return
	 */
	private File getBestMatch(Interest interest) {
		TreeMap<ContentName, File> possibleResults = new TreeMap<ContentName, File>();
		TreeMap<ContentName, File>possibleMatches = getPossibleMatches(interest.name());
		for (ContentName name : possibleMatches.keySet()) {
			if (interest.matches(name, null)) {
				possibleResults.put(name, possibleMatches.get(name));
			}
		}
		ContentName bestMatch = null;
		for (ContentName name : possibleResults.keySet()) {
			if (bestMatch == null) {
				bestMatch = name;
			} else {
				if (interest.orderPreference()  != null) {
					if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME))
							== (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME)) {
						if (name.compareTo(bestMatch) > 0)
							bestMatch = name;
					} else if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME))
							== (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME)) {
						if (name.compareTo(bestMatch) < 0)
							bestMatch = name;
					}
				}
			}
		}
		if (bestMatch == null)
			return null;
		return possibleResults.get(bestMatch);
	}
	
	/**
	 * Pull out anything that might be a match to the interest. This includes
	 * any file that matches the prefix and any member of the "encodedNames"
	 * that matches the prefix.
	 */
	private TreeMap<ContentName, File> getPossibleMatches(ContentName name) {
		TreeMap<ContentName, File> results = new TreeMap<ContentName, File>();
		File file = new File(_repositoryFile + getStandardString(name));
		ContentName lowerName = new ContentName(null != name.prefixCount() ? name.prefixCount() : name.count(),
					name.components());
		getAllFileResults(file, results, lowerName);
		for (ContentName encodedName : encodedFiles.keySet()) {
			if (name.isPrefixOf(encodedName))
				results.put(encodedName, encodedFiles.get(encodedName));
		}
		return results;
	}
	
	/**
	 * Recursively get all files below us and add them to the results
	 * 
	 * @param file
	 * @param results
	 * @param name
	 */
	private void getAllFileResults(File file, TreeMap<ContentName, File> results, ContentName name) {
		if (file.isDirectory()) {
			for (File f : file.listFiles()) {
				getAllFileResults(f, results, new ContentName(name, f.getName().getBytes()));
			}
		} else if (file.exists()) {
			ContentName decodedName = name.clone();
			decodedName.components().remove(decodedName.count() - 1);
			decodedName.components().add(decodeBase64(file.getName()));
			results.put(decodedName, file);	
		} else {
			// Convert last component to "digest as file" form
			ContentName encodedName = encodeDigest(name);
			File encodedFile = new File(_repositoryFile + getStandardString(encodedName));
			if (encodedFile.exists()) {
				results.put(name, encodedFile);
			}
		}
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
		try {
			saveContentToFile(content.name(), content);
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
		ContentName newName = checkReserved(content.name()).clone();
		newName.components().add(convertDigest(content).getBytes());
		File file = new File(_repositoryRoot, getStandardString(newName));
		saveContentToFile(file, content);
		if (isReserved(newName))
			encodedFiles.put(new ContentName(content.name(), content.contentDigest()), file);
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
	
	private boolean isReserved(ContentName name) {
		return Arrays.equals(name.component(0), META_DIR.getBytes());
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
		File dirFile = new File(_repositoryRoot + specialName.toString());
		dirFile.mkdirs();
		String convertedDigest = convertDigest(content);
		File file = new File(_repositoryRoot + specialName.toString(), 
					new String(new byte[]{UTF8_COMPONENT}) + convertedDigest);
		ContentName keyName = name.clone();
		keyName.components().add(content.contentDigest());
		encodedFiles.put(keyName, file);
		return file;
	}
	
	private String convertDigest(ContentObject content) {
		return convertToBase64(content.contentDigest());
	}
	
	/**
	 * Convert input bytes to Base64 encoding, then remove '/'
	 * since this can be included
	 * For now we replace / with "%slash%"
	 * TODO - need to check this out on PCs
	 * @param bytes
	 * @return
	 */
	private String convertToBase64(byte[] bytes) {
		StringWriter writer = new StringWriter();
		try {
			Base64.encode(bytes, 0, bytes.length, writer);
		} catch (IOException e) {}
		String b64String = writer.toString();
		return b64String.replace("/", "%slash%");
	}
	
	private byte[] decodeBase64(String data) {
		data = data.replace("%slash%", "/");
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			Base64.decode(data, baos);
		} catch (IOException e) {}
		return baos.toByteArray();
	}
	
	/**
	 * Convert last piece of CN to Base64 digest form.
	 * We don't care if this is really a digest or not here.
	 * @param name
	 * @return
	 */
	private String cnDigestString(ContentName name) {
		return convertToBase64(name.component(name.components().size() - 1));
	}
	
	private ContentName encodeDigest(ContentName name) {
		// Convert last component to "digest as file" form
		ContentName newName = name.clone();
		String cnDigestString = cnDigestString(newName);
		newName.components().remove(newName.count() - 1);
		newName.components().add(cnDigestString.getBytes());
		return newName;
	}
	
	/**
	 * Get non URL encoded version of ContentName String
	 * for use as filename
	 * 
	 * @param name
	 * @return
	 */
	private String getStandardString(ContentName name) {
		if (0 == name.count()) return File.separator;
		StringBuffer nameBuf = new StringBuffer();
		int count = (null == name.prefixCount()) ? name.count() : name.prefixCount();
		for (int i=0; i < count; ++i) {
			nameBuf.append(File.separator);
			try {
				nameBuf.append(new String(name.component(i), encoding));
			} catch (UnsupportedEncodingException e) {}
		}
		return nameBuf.toString();
	}
}
