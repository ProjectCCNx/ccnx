package com.parc.ccn.network.daemons.repo;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.security.InvalidParameterException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.TreeMap;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.WirePacket;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.library.CCNNameEnumerator;
import com.parc.ccn.library.profiles.SegmentationProfile;
import com.parc.ccn.library.profiles.VersioningProfile;

/**
 * An initial implementation of the repository using a file system
 * based repository.  We are using the XMLEncodeable representation to
 * represent content on the disk
 * 
 * Note: This layer purposefully should not make any non-static calls into the
 * CCN library
 * 
 * @author rasmusse
 *
 */

public class RFSImpl implements Repository {
	
	public final static String CURRENT_VERSION = "1.2";
	
	public final static String META_DIR = ".meta";
	public final static byte NORMAL_COMPONENT = '0';
	public final static byte BASE64_COMPONENT = '1';
	public final static byte SPLIT_COMPONENT = '2';
	public final static byte BASE64_AND_SPLIT_COMPONENT = '3';
	
	private final static String encoding = "UTF-8";
	
	private static final String META_CLASH = "%meta%";
	private static final String REPO_PRIVATE = "private";
	private static final String VERSION = "version";
	private static final String REPO_LOCALNAME = "local";
	private static final String REPO_GLOBALPREFIX = "global";
	private static final String INVALID_WINDOWS_CHARS = "<>:\"|?/";
	private static final byte[] INVALID_UTF8_BYTES = {(byte)0xf5, (byte)0xf6, (byte)0xf7, (byte)0xf8, 
												(byte)0xf9, (byte)0xfa, (byte)0xfb, (byte)0xfc, 
												(byte)0xfd, (byte)0xfe, (byte)0xff,
												(byte)0xc0, (byte)0xc1};
	
	private static String DEFAULT_LOCAL_NAME = "Repository";
	private static String DEFAULT_GLOBAL_NAME = "/parc.com/csl/ccn/Repos";
	
	private class CharToReplacement {
		private String _character;
		private String _replacement;
		
		private CharToReplacement(String character, String replacement) {
			_character = character;
			_replacement = replacement;
		}
	}
	
	private static CharToReplacement[] _startReplacements;
	private static CharToReplacement[] _allReplacements;
	
	static {
		RFSImpl beginElement = new RFSImpl();
		_startReplacements = new CharToReplacement[4];
		_startReplacements[0] = beginElement.new CharToReplacement(new String(new byte[] {VersioningProfile.VERSION_MARKER}), "%version%");
		_startReplacements[1] = beginElement.new CharToReplacement(new String(new byte[] {SegmentationProfile.SEGMENT_MARKER}), "%segment%");
		_startReplacements[2] = beginElement.new CharToReplacement(new String(new byte[] {BASE64_COMPONENT}), "%one%");
		_startReplacements[3] = beginElement.new CharToReplacement(new String(new byte[] {SPLIT_COMPONENT}), "%two%");
		_startReplacements[3] = beginElement.new CharToReplacement(new String(new byte[] {BASE64_AND_SPLIT_COMPONENT}), "%three%");
		
		_allReplacements = new CharToReplacement[2];
		_allReplacements[0] = beginElement.new CharToReplacement("/", "%slash%");
		_allReplacements[1] = beginElement.new CharToReplacement("/", "%slash%");
	}
		
	private static final int TOO_LONG_SIZE = 200;
	
	protected String _repositoryRoot = null;
	protected File _repositoryFile;
	protected RFSLocks _locker;
	protected Policy _policy = null;
	protected RepositoryInfo _info = null;
	protected ArrayList<ContentName> _nameSpace = new ArrayList<ContentName>();
	
	public String[] initialize(String[] args) throws RepositoryException {
		
		Library.logger().setLevel(Level.FINEST);

		
		boolean policyFromFile = false;
		boolean nameFromArgs = false;
		boolean globalFromArgs = false;
		String localName = DEFAULT_LOCAL_NAME;
		String globalPrefix = DEFAULT_GLOBAL_NAME;
		String[] outArgs = args;
		Policy policy = new BasicPolicy(null);
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-root")) {
				if (args.length < i + 2)
					throw new InvalidParameterException();
				_repositoryRoot = args[i + 1];
				i++;
			} else if (args[i].equals("-policy")) {
				policyFromFile = true;
				if (args.length < i + 2)
					throw new InvalidParameterException();
				File policyFile = new File(args[i + 1]);
				try {
					policy.update(new FileInputStream(policyFile), false);
				} catch (Exception e) {
					throw new InvalidParameterException(e.getMessage());
				}
			} else if (args[i].equals("-local")) {
				if (args.length < i + 2)
					throw new InvalidParameterException();
				localName = args[i + 1];
				nameFromArgs = true;
			} else if (args[i].equals("-global")) {
				if (args.length < i + 2)
					throw new InvalidParameterException();
				globalPrefix = args[i + 1];
				if (!globalPrefix.startsWith("/"))
					globalPrefix = "/" + globalPrefix;
				globalFromArgs = true;
			}
		}
		if (_repositoryRoot == null) {
			throw new InvalidParameterException();
		}
		
		_repositoryFile = new File(_repositoryRoot);
		_repositoryFile.mkdirs();
		_locker = new RFSLocks(_repositoryRoot + File.separator + META_DIR);
		
		String version = checkFile(VERSION, CURRENT_VERSION, false);
		if (version != null && !version.trim().equals(CURRENT_VERSION))
			throw new RepositoryException("Bad repository version: " + version);
		
		String checkName = checkFile(REPO_LOCALNAME, localName, nameFromArgs);
		localName = checkName != null ? checkName : localName;
		
		checkName = checkFile(REPO_GLOBALPREFIX, globalPrefix, globalFromArgs);
		globalPrefix = checkName != null ? checkName : globalPrefix;
		
		try {
			_info = new RepositoryInfo(localName, globalPrefix, CURRENT_VERSION);
		} catch (MalformedContentNameStringException e1) {
			throw new RepositoryException(e1.getMessage());
		}
		
		/*
		 * Read policy file from disk if it exists and we didn't read it in as an argument.
		 * Otherwise save the new policy to disk.
		 */
		if (!policyFromFile) {
			try {
				ContentObject policyObject = getContent(
						new Interest(ContentName.fromNative(REPO_NAMESPACE + "/" + _info.getLocalName() + "/" + REPO_POLICY)));
				if (policyObject != null) {
					ByteArrayInputStream bais = new ByteArrayInputStream(policyObject.content());
					policy.update(bais, false);
				}
			} catch (MalformedContentNameStringException e) {} // None of this should happen
			  catch (XMLStreamException e) {} 
			  catch (IOException e) {}
		} else {
			saveContent(policy.getPolicyContent());
		}
		setPolicy(policy);
		
		if (_nameSpace.size() == 0) {
			try {
				_nameSpace.add(ContentName.fromNative("/"));
			} catch (MalformedContentNameStringException e) {}
		}
	
		return outArgs;
	}
	
	/**
	 * Check data file - create new one if none exists
	 * @throws RepositoryException
	 */
	private String checkFile(String fileName, String contents, boolean forceWrite) throws RepositoryException {
		File dirFile = new File(_repositoryRoot + File.separator + META_DIR + File.separator + REPO_PRIVATE);
		File file = new File(_repositoryRoot + File.separator + META_DIR + File.separator + REPO_PRIVATE
					+ File.separator + fileName);
		
		try {
			if (!forceWrite && file.exists()) {
				FileInputStream fis = new FileInputStream(file);
				byte [] content = new byte[fis.available()];
				fis.read(content);
				fis.close();
				return new String(content);
			} else {
				dirFile.mkdirs();
				file.createNewFile();
				FileOutputStream fos = new FileOutputStream(file);
				fos.write(contents.getBytes());
				fos.close();
				return null;
			}
		} catch (FileNotFoundException e) {} catch (IOException e) {
			throw new RepositoryException(e.getMessage());
		}
		return null;
	}
	
	/**
	 * Check for data routed for the repository and take special
	 * action if it is.  Returns true if data is for repository.
	 * 
	 * @param co
	 * @return
	 * @throws RepositoryException 
	 */
	public boolean checkPolicyUpdate(ContentObject co) throws RepositoryException {
		if (_info.getPolicyName().isPrefixOf(co.name())) {
			ByteArrayInputStream bais = new ByteArrayInputStream(co.content());
			try {
				_policy.update(bais, true);
				_nameSpace = _policy.getNameSpace();
				ContentName policyName = ContentName.fromNative(REPO_NAMESPACE + "/" + _info.getLocalName() + "/" + REPO_POLICY);
				ContentObject policyCo = new ContentObject(policyName, co.signedInfo(), co.content(), co.signature());
   				saveContent(policyCo);
			} catch (XMLStreamException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			} catch (MalformedContentNameStringException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			return true;
		}
		return false;
	}
	
	/**
	 * Go through all the "possible matches" and find the best one
	 * to match the interest
	 * 
	 * @param interest
	 * @return
	 */
	public ContentObject getContent(Interest interest) throws RepositoryException {
		
		TreeMap<ContentName, ArrayList<File>>possibleMatches = getPossibleMatches(interest);
		// Note: these possible match names INCLUDE include the digest
		
		ContentObject bestMatch = null;
		for (ContentName name : possibleMatches.keySet()) {
			if (bestMatch == null) {
				bestMatch = checkMatch(interest, name, possibleMatches.get(name));
			} else {
				ContentObject checkMatch = null;
				if (interest.orderPreference()  != null) {
					/*
					 * Must test in this order since ORDER_PREFERENCE_LEFT is 0
					 */
					if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME))
							== (Interest.ORDER_PREFERENCE_RIGHT | Interest.ORDER_PREFERENCE_ORDER_NAME)) {
						if (name.compareTo(bestMatch.name()) > 0) {
							checkMatch = checkMatch(interest, name, possibleMatches.get(name));
							if (checkMatch != null)
								bestMatch = checkMatch;
						}
					} else if ((interest.orderPreference() & (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME))
							== (Interest.ORDER_PREFERENCE_LEFT | Interest.ORDER_PREFERENCE_ORDER_NAME)) {
						if (name.compareTo(bestMatch.name()) < 0) {
							checkMatch = checkMatch(interest, name, possibleMatches.get(name));
							if (checkMatch != null)
								bestMatch = checkMatch;
						}				
					}
				} else
					checkMatch = checkMatch(interest, name, possibleMatches.get(name));
				
				/*
				 * Try to get the closest match in size
				 */
				if (checkMatch != null) {
					int cOffset = checkMatch.name().count() - interest.name().count();
					int bOffset = bestMatch.name().count() - interest.name().count();
					if (cOffset < bOffset)
						bestMatch = checkMatch;
				}
			}
		}
		return bestMatch;
	}
	
	/**
	 * If the interest has no publisher ID we can optimize this by first checking the interest against the name
	 * and only reading the content from the file if this check matches. 
	 * JDT: Actually, a pre-check on the name should always be ok as long as final check is 
	 * performed on the content object itself.
	 * 
	 * @param interest
	 * @param name - ContentName WITH digest component
	 * @param file
	 * @return
	 * @throws RepositoryException
	 */
	private ContentObject checkMatch(Interest interest, ContentName name, ArrayList<File>files) throws RepositoryException {
		for (File file: files) {
			if (null == interest.publisherID()) {
				// Since the name INCLUDES digest component and the Interest.matches() convention for name
				// matching is that the name DOES NOT include digest component (conforming to the convention 
				// for ContentObject.name() that the digest is not present) we must REMOVE the content 
				// digest first or this test will not always be correct
				ContentName digestFreeName = new ContentName(name.count()-1, name.components());
				if (!interest.matches(digestFreeName, null))
					return null;
			}
			ContentObject testMatch = getContentFromFile(file);
			if (testMatch != null) {
				if (interest.matches(testMatch))
					return testMatch;
			}
		}
		return null;
	}
	
	/**
	 * Pull out anything that might be a match to the interest. This includes
	 * any file that matches the prefix and any member of the "encodedNames"
	 * that matches the prefix.
	 * Results returned map from ContentName without digest to list of Files
	 */
	private TreeMap<ContentName, ArrayList<File>> getPossibleMatches(Interest interest) {
		TreeMap<ContentName, ArrayList<File>> results = new TreeMap<ContentName, ArrayList<File>>();
		File file = new File(_repositoryFile + getStandardString(interest.name()));
		ContentName lowerName = new ContentName(null != interest.nameComponentCount() ? interest.nameComponentCount() : interest.name().count(),
					interest.name().components());
		getAllFileResults(file, results, lowerName);
		
		/*
		 * Special test to match data that might clash with the .meta directory
		 */
		if (Arrays.equals(lowerName.components().get(0), META_DIR.getBytes())) {
			ArrayList<byte[]> newComponents = new ArrayList<byte[]>();
			newComponents.add(META_CLASH.getBytes());
			for (int i = 0; i < lowerName.count(); i++)
				newComponents.add(lowerName.component(i));
			ContentName clashName = new ContentName(newComponents.size(), newComponents);
			// getAllFileResults will strip off the last component of the name so add arbitrary "digest" name
			lowerName = new ContentName(lowerName, "digest".getBytes());
			getAllFileResults(new File(_repositoryFile + getStandardString(clashName)), results, lowerName);
		}
		return results;
	}
	
	/**
	 * Subprocess of getting "possible" matching results.
	 * Recursively get all files below us and add them to the results.
	 * If we are at a leaf, we want to decode the filename to a ContentName,
	 * or encode the ContentName to a filename to check to see if it exists.
	 * 
	 * @param file
	 * @param results - map from ContentName WITH digest to File
	 * @param name
	 */
	private void getAllFileResults(File file, TreeMap<ContentName, ArrayList<File>> results, ContentName name) {
		if (file.isDirectory()) {
			for (File f : file.listFiles()) {
				getAllFileResults(f, results, new ContentName(name, f.getName().getBytes()));
			}
		} else if (file.exists()) {
			if (file.getName().endsWith(".rfs")) {
				/*
				 * We assume this is a file with identical data but different pub IDs
				 * Remove the last part and put all files into the "potential" results.
				 * We'll figure out which one(s) we want later
				 */
				name = new ContentName(name.count() - 1, name.components());
			}
			ContentName decodedName = decodeName(name);
			addOneFileToMap(decodedName, file, results);	
		} else {
			// Convert to name we can use as a file
			ContentName encodedName = encodeName(name);
			File encodedFile = new File(_repositoryFile + getStandardString(encodedName));
			if (encodedFile.isDirectory()) {
				getAllFileResults(encodedFile, results, encodedName);
			}
			else {
				encodedFile = new File(_repositoryFile + getStandardString(encodedName));
				if (encodedFile.exists()) {
					// The name here must contain a digest, for it maps to something 
					// that is not a directory in the filesystem, and the only files
					// are for the leaf content objects and have digest as last component
					addOneFileToMap(name, encodedFile, results);
				}
			}
		}
	}
	
	/**
	 * Restore ContentObject from wire data on disk
	 * @param fileName
	 * @return
	 * @throws RepositoryException
	 */
	public static ContentObject getContentFromFile(File fileName) throws RepositoryException {
		try {
			FileInputStream fis = new FileInputStream(fileName);
			byte[] buf = new byte[fis.available()];
			fis.read(buf);
			fis.close();
			WirePacket packet = new WirePacket();
			packet.decode(buf);
			if (packet.data().size() == 1)
				return packet.data().get(0);
		} catch (Exception e) {
			throw new RepositoryException(e.getMessage());
		}
		return null;	
	}

	/**
	 * Main routine to save a content object under a filename. Normally we just
	 * create a new file and save the data in XMLEncoded form in the file.
	 * 
	 * If we already had content saved under the same name do the following:
	 *   - Read the data and see if it matches - if so we're done.
	 *   - Otherwise we put all files with the same digest in a directory using 
	 *     the digest name as the directory name. So if we have a name clash
	 *     with a non-directory, this is the first name clash with an old
	 *     digest name and we need to remove the old data, change the file
	 *     to a directory, then put back the old data in the directory and
	 *     add the new data. If we already had a name clash, we can just
	 *     add the data to the new directory. 
	 */
	public void saveContent(ContentObject content) throws RepositoryException {
		File file = null;
		ContentName newName = checkReserved(content.name()).clone();
		newName.components().add(content.contentDigest());
		newName = encodeName(newName);
		ContentName dirName = new ContentName(newName.count() - 1, newName.components());
		File dirFile = new File(_repositoryRoot, getStandardString(dirName));
		dirFile.mkdirs();
		file = new File(_repositoryRoot, getStandardString(newName));
		if (file.exists()) {
			ContentObject prevContent = null;
			if (file.isFile()) {
				
				// New name clash
				prevContent = getContentFromFile(file);
				if (prevContent != null) {
					if (prevContent.equals(content))
						return;
				}
				file.delete();
				file.mkdir();
				try {
					File prevFile = File.createTempFile("RFS", ".rfs", file);
					saveContentToFile(prevFile, prevContent);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			} 
			
			try {
				file = File.createTempFile("RFS", ".rfs", file);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		saveContentToFile(file, content);
	}
	
	/**
	 * Algorithm is:
	 * Save wire data to fileName/<digest>
	 * 
	 * @param file
	 * @param content
	 * @throws RepositoryException
	 */
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
			ArrayList<byte[]> oldComponents = name.components();
			ArrayList<byte[]> newComponents = new ArrayList<byte[]>();
			newComponents.add(META_CLASH.getBytes());
			for (int i = 0; i < oldComponents.size(); i++)
				newComponents.add(oldComponents.get(i));
			name = new ContentName(newComponents.size(), newComponents);
		}
		return name;
	}
	
	private static boolean needsEncoding(byte[] component) {
		for (int i = 0; i < component.length; i++) {
			if (INVALID_WINDOWS_CHARS.indexOf(component[i]) >= 0)
				return true;
			/*
			 * Don't auto encode versions or segments
			 */
			if (i == 0) {
				if (component[i] == SegmentationProfile.SEGMENT_MARKER ||
						component[i] == VersioningProfile.VERSION_MARKER)
					continue;
			}
			for (byte ib : INVALID_UTF8_BYTES) {
				if (component[i] == ib) {
					return true;
				}
			}
		}
		return false;
	}
	
	/**
	 * Encode all components via name or directly.
	 * This could result in more components than originally input due to splitting of
	 * components.
	 * 
	 * @param name
	 * @return
	 */
	public ContentName encodeName(ContentName name) {
		byte[][] newComponents = encodeComponents(name, name.count());
		return new ContentName(newComponents);
	}
	
	public byte[][] encodeComponents(ContentName name, int count) {
		byte[][] encodeData;
		ArrayList<byte[]> encodedComponents = new ArrayList<byte[]>(0);
		for (int i = 0; i < count; i++) {
			encodeData = encodeComponent(name.component(i));
			encodedComponents.add(encodeData[0]);
			while (encodeData.length > 1) {
				encodeData = encodeComponent(encodeData[1]);
				encodedComponents.add(encodeData[0]);
			}
		}
		byte[][] outData = new byte[encodedComponents.size()][];
		encodedComponents.toArray(outData);
		return outData;
	}
	
	/**
	 * Convert a component into it's disk encoded form. This can involve replacing the start of
	 * the component with special strings, encoding into base64 and splitting the component into
	 * multiple pieces. If the original component is split, the remainder is put into the second
	 * element of the byte array.
	 * 
	 * @param component
	 * @return
	 */
	public static byte[][] encodeComponent(byte[] component) {
		byte[] additionalComponent = null;
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		byte[] newComponent = null;
		
		/*
		 * First replace the starting pieces
		 */
		for (CharToReplacement ctr : _startReplacements) {
			if (component[0] == ctr._character.getBytes()[0]) {
				String conversionString = "";
				if (component.length > 1) {
					byte[] conversionBytes = new byte[component.length - 1];
					System.arraycopy(component, 1, conversionBytes, 0, conversionBytes.length);
					conversionString = convertToBase64(conversionBytes);
				}
				newComponent = (ctr._replacement + conversionString).getBytes();
				break;
			}
		}
		if (null == newComponent) {
			newComponent = new byte[component.length];
			System.arraycopy(component, 0, newComponent, 0, component.length);
		}
		
		byte type = NORMAL_COMPONENT;
		int outSize = 1;
		if (needsEncoding(newComponent)) {
			type = BASE64_COMPONENT;
			newComponent = convertToBase64(newComponent).getBytes();
		}
		
		if (newComponent.length > TOO_LONG_SIZE) {
			outSize++;
			type = (type == NORMAL_COMPONENT) ? SPLIT_COMPONENT : BASE64_AND_SPLIT_COMPONENT;
			additionalComponent = new byte[newComponent.length - (TOO_LONG_SIZE - 1)];
			System.arraycopy(newComponent, TOO_LONG_SIZE - 1, additionalComponent, 0, newComponent.length - (TOO_LONG_SIZE - 1));
		}
		
		if (type != NORMAL_COMPONENT) {
			baos.write(type);
			baos.write(newComponent, 0, type == BASE64_COMPONENT ? newComponent.length : TOO_LONG_SIZE - 1);
			newComponent = baos.toByteArray();
		}
		
		byte[][] out = new byte[outSize][];
		out[0] = newComponent;
		if (outSize > 1)
			out[1] = additionalComponent;
		return out;
	}
	
	/**
	 * Decode a name built from filename components into a CCN name. The new
	 * name could contain less components than the input name.
	 * 
	 * @param name
	 * @return
	 */
	public ContentName decodeName(ContentName name) {
		byte[][] newComponents = decodeComponents(name, name.count());
		return new ContentName(newComponents);
	}
	
	public byte[][] decodeComponents(ContentName name, int count) {
		ArrayList<byte[]> decodedComponents = new ArrayList<byte[]>();
		String lastSplit = "";
		boolean decodeSplit = false;
		for (int i = 0; i < count; i++) {
			byte[] component = decodeComponent(name.component(i));
			byte type = component[0];
			switch (type) {
			  case BASE64_COMPONENT:
				  decodedComponents.add(decodeBase64(new String(component).substring(1)));
				  break;
			  case BASE64_AND_SPLIT_COMPONENT:
				  decodeSplit = true;	// Fall through
			  case SPLIT_COMPONENT:
				  lastSplit += new String(component).substring(1);
				  break;
			  default:
				  if (lastSplit.length() > 0) {
					  lastSplit += new String(component);
					  if (decodeSplit)
						  component = decodeBase64(lastSplit);
					  else
						  component = lastSplit.getBytes();
					  decodeSplit = false;
					  lastSplit = "";
				  }
			  	  decodedComponents.add(component);
				  break;
			}
		}
		byte[][] out = new byte[decodedComponents.size()][];
		decodedComponents.toArray(out);
		return out;
	}
	
	public static byte[] decodeComponent(byte[] component) {
		byte decodeByte = -1;
		int size = 0;
		String decodeString = new String(component);
		for (CharToReplacement ctr : _startReplacements) {
			if (DataUtils.arrayEquals(component, ctr._replacement.getBytes(), ctr._replacement.getBytes().length)) {
				size = ctr._replacement.getBytes().length;
				decodeByte = ctr._character.getBytes()[0];
				break;
			}
		}
		
		byte[] newComponent;
		if (decodeByte != -1) {
			byte[] decodeBytes = decodeBase64(decodeString.substring(size));
			newComponent = new byte[decodeBytes.length + 1];
			newComponent[0] = decodeByte;
			System.arraycopy(decodeBytes, 0, newComponent, 1, decodeBytes.length);
		} else {
			newComponent = new byte[component.length];
			System.arraycopy(component, 0, newComponent, 0, component.length);
		}
		
		return newComponent;
	}
	
	private void addOneFileToMap(ContentName name, File file, TreeMap<ContentName, ArrayList<File>>map) {
		ArrayList<File> files = map.get(name);
		if (files == null)
			files = new ArrayList<File>();
		files.add(file);
		map.put(name, files);	
	}
	
	/**
	 * Convert input bytes to Base64 encoding, then remove '/'
	 * since this can be included
	 * For now we replace / with "%slash%"
	 * ... and \n with "%return%
	 * TODO - need to check this out on PCs
	 * @param bytes
	 * @return
	 */
	private static String convertToBase64(byte[] bytes) {
		String b64String = new BASE64Encoder().encode(bytes);
		for (CharToReplacement ctp : _allReplacements)
			b64String = b64String.replace(ctp._character, ctp._replacement);
		return b64String;
	}
	
	private static byte [] decodeBase64(String data) {
		try {
			for (CharToReplacement ctp : _allReplacements)
				data = data.replace(ctp._replacement, ctp._character);
			return new BASE64Decoder().decodeBuffer(data);
		} catch (IOException e) {
			return new byte[0]; // TODO error handling...
		}
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
		for (int i=0; i < name.count(); ++i) {
			nameBuf.append(File.separator);
			try {
				nameBuf.append(new String(name.component(i), encoding));
			} catch (UnsupportedEncodingException e) {}
		}
		return nameBuf.toString();
	}

	public String getUsage() {
		return " -root repository_root [-policy policy_file] [-local local_name] [-global global_prefix]\n";
	}

	public void setPolicy(Policy policy) {
		_policy = policy;
		ArrayList<ContentName> newNameSpace = _policy.getNameSpace();
		_nameSpace.clear();
		for (ContentName name : newNameSpace)
			_nameSpace.add(name);
	}
	
	public ArrayList<ContentName> getNamespace() {
		return _nameSpace;
	}
	
	public byte[] getRepoInfo(ArrayList<ContentName> names) {
		try {
			RepositoryInfo rri = _info;
			if (names != null)
				rri = new RepositoryInfo(_info.getLocalName(), _info.getGlobalPrefix(), CURRENT_VERSION, names);	
			return rri.encode();
		} catch (XMLStreamException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return null;
	}

	public ArrayList<ContentName> getNamesWithPrefix(Interest i) {
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		ContentName n1 = null;
		ArrayList<File> files = null;
		long lastTS = 0;
		Timestamp interestTS = null;
		byte[][] na = new byte[1][1];
		try{
			interestTS = VersioningProfile.getVersionAsTimestamp(i.name());
		}
		catch(Exception e){
			interestTS = null;
			
		}
		ContentName cropped = i.name().cut(CCNNameEnumerator.NEMARKER);
		
		Interest croppedInterest = new Interest(cropped);
		croppedInterest.orderPreference(i.orderPreference());
		croppedInterest.nameComponentCount(i.nameComponentCount()-1);
		
		Library.logger().finest("Getting names with prefix = "+croppedInterest.name().toString());
		TreeMap<ContentName, ArrayList<File>>possibleMatches = getPossibleMatches(croppedInterest);
		for (ContentName name : possibleMatches.keySet()) {
			files = possibleMatches.get(name);
			for(File f: files){
				
				if(f.lastModified() > lastTS){
					lastTS = f.lastModified();
				}
			}
			
			na[0] = name.component(cropped.count());
			n1 = new ContentName(na);
			if(!names.contains(n1)){
				names.add(n1);
			}

		}
		try{
			interestTS = VersioningProfile.getVersionAsTimestamp(i.name());
		}
		catch(Exception e){
			interestTS = null;
		}
		
		
		if(names.size()>0 && (interestTS == null || interestTS.getTime() < lastTS))
			return names;
		else{
			if(names.size() > 0)
				Library.logger().finest("No new names for this prefix since the last request, dropping request and not responding.");
			return null;
		}
	}
}
