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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.StringTokenizer;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import sun.misc.BASE64Decoder;
import sun.misc.BASE64Encoder;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.WirePacket;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.util.DataUtils;
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
	
	public final static String CURRENT_VERSION = "1.1";
	
	public final static String META_DIR = ".meta";
	public final static byte UTF8_COMPONENT = '0';
	public final static byte BASE64_COMPONENT = '1';
	public final static byte SPLIT_COMPONENT = '2';
	
	public static final String ENCODED_FILES = "encoded_files";
	
	private final static String encoding = "UTF-8";
	
	private static final String RESERVED_CLASH = "reserved";
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
	
	private static final String REPLACE_SLASH = "%slash%";
	private static final String REPLACE_RETURN = "%return%";
	private static final String REPLACE_VERSION = "%version%";
	public static final String REPLACE_SEGMENT = "%segment%";
	
	private static final int TOO_LONG_SIZE = 200;
	
	protected String _repositoryRoot = null;
	protected File _repositoryFile;
	protected RFSLocks _locker;
	protected Policy _policy = null;
	protected RepositoryInfo _info = null;
	protected ArrayList<ContentName> _nameSpace = new ArrayList<ContentName>();
	
	protected TreeMap<ContentName, ArrayList<File>> _encodedFiles = new TreeMap<ContentName, ArrayList<File>>();
	
	public String[] initialize(String[] args) throws RepositoryException {
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
		constructEncodedMap(new File(_repositoryRoot + File.separator + META_DIR + File.separator + ENCODED_FILES));
		constructEncodedMap(new File(_repositoryRoot + File.separator + META_DIR + File.separator + RESERVED_CLASH));
		
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
		boolean reservedArea = false;
		String currentValue = "";
		while (st.hasMoreTokens()) {
			String token = st.nextToken();
			if (reservedArea) {
				components.add(token.getBytes());
			} else if (encodedArea) {
				byte type = (byte)token.charAt(0);
				switch (type) {
				  case UTF8_COMPONENT:
					  currentValue += token.substring(1);
					  components.add(decodeComponent(currentValue.getBytes()));
					  currentValue = "";
					  break;
				  case BASE64_COMPONENT:
					  currentValue += token.substring(1);
					  components.add(decodeBase64(currentValue));
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
				if (token.equals(RESERVED_CLASH))
					reservedArea = true;
			}
		}
		byte [] digestComponent = components.remove(components.size() - 1);
		components.add(components.size(), decodeBase64(new String(digestComponent)));
		ContentName cn = new ContentName(components.size(), components);
		ArrayList<File> files = _encodedFiles.get(cn);
		if (files == null)
			files = new ArrayList<File>();
		files.add(file);
		_encodedFiles.put(cn, files);
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
	 * If the interest has no publisher ID we can optimize this by just checking the interest against the name
	 * and only reading the content from the file if this check matches. This could potentially change if interest
	 * has other features we might care about. Its probably OK that we try to match the interest twice in that
	 * case though we could consider trying to optimize that also.
	 * 
	 * @param interest
	 * @param name
	 * @param file
	 * @return
	 * @throws RepositoryException
	 */
	private ContentObject checkMatch(Interest interest, ContentName name, ArrayList<File>files) throws RepositoryException {
		for (File file: files) {
			if (null == interest.publisherID()) {
				if (!interest.matches(name, null))
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
	 */
	private TreeMap<ContentName, ArrayList<File>> getPossibleMatches(Interest interest) {
		TreeMap<ContentName, ArrayList<File>> results = new TreeMap<ContentName, ArrayList<File>>();
		File file = new File(_repositoryFile + getStandardString(interest.name()));
		ContentName lowerName = new ContentName(null != interest.nameComponentCount() ? interest.nameComponentCount() : interest.name().count(),
					interest.name().components());
		getAllFileResults(file, results, lowerName);
		for (ContentName encodedName : _encodedFiles.keySet()) {
			/*
			 * Either piece could have a digest. This will be cleaner when we know this for sure
			 */
			if (encodedName.isPrefixOf(interest.name()) || interest.name().isPrefixOf(encodedName))
				results.put(encodedName, _encodedFiles.get(encodedName));
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
	 * @param results
	 * @param name
	 */
	private void getAllFileResults(File file, TreeMap<ContentName, ArrayList<File>> results, ContentName name) {
		if (file.isDirectory()) {
			for (File f : file.listFiles()) {
				getAllFileResults(f, results, new ContentName(name, f.getName().getBytes()));
			}
		} else if (file.exists()) {
			ContentName decodedName = decodeVersionAndSegment(name);
			decodedName.components().remove(decodedName.count() - 1);
			decodedName.components().add(decodeBase64(file.getName()));
			addOneFileToMap(decodedName, file, results);	
		} else {
			// Convert to name we can use as a file
			ContentName encodedName = encodeStandardElements(name);
			File encodedFile = new File(_repositoryFile + getStandardString(encodedName));
			if (encodedFile.isDirectory()) {
				getAllFileResults(encodedFile, results, encodedName);
			}
			else {
				encodedName = encodeDigest(encodedName);
				encodedFile = new File(_repositoryFile + getStandardString(encodedName));
				if (encodedFile.exists()) {
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

	public void saveContent(ContentObject content) throws RepositoryException {
		File file = null;
		if (needsEncoding(content.name()))
			file = getSpecialPath(content);
		else {
			ContentName newName = checkReserved(content.name()).clone();
			newName = encodeVersionAndSegment(newName);
			newName.components().add(convertDigest(content).getBytes());
			ContentName dirName = new ContentName(newName.count() - 1, newName.components());
			File dirFile = new File(_repositoryRoot, getStandardString(dirName));
			dirFile.mkdirs();
			file = new File(_repositoryRoot, getStandardString(newName));
			if (isEncoded(newName))
				addOneFileToMap(new ContentName(content.name(), content.contentDigest()), file, _encodedFiles);
			if (file.exists()) {
				ContentObject prevContent = getContentFromFile(file);
				if (prevContent != null) {
					if (prevContent.equals(content))
						return;
				}
				file = getSpecialPath(content);
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
			ContentName reservedName = name.clone();
			reservedName.components().add(0, RESERVED_CLASH.getBytes());
			reservedName.components().add(0, META_DIR.getBytes());
			name = reservedName;
		}
		return name;
	}
	
	private boolean isEncoded(ContentName name) {
		return Arrays.equals(name.component(0), META_DIR.getBytes());
	}
	
	/**
	 * Check to see if we will need to encode the filename corresponding
	 * to a ContentName
	 * @param name
	 * @return
	 */
	private boolean needsEncoding(ContentName name) {
		for (byte[] component : name.components()) {
			if (component.length > TOO_LONG_SIZE)
				return true;
			if (needsEncoding(component))
				return true;
		}
		return false;
	}
	
	private boolean needsEncoding(byte[] component) {
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
	 * If we just put versioned and/or segmented files into the "encoded" space
	 * practically everything would be encoded so we encode/decode these specially
	 * 
	 * @param name
	 * @return
	 */
	public ContentName encodeVersionAndSegment(ContentName name) {
		byte[][] newComponents = new byte[name.count()][];
		encodeVersionAndSegment(name, newComponents, name.count());
		return new ContentName(newComponents);
	}
	
	public void encodeVersionAndSegment(ContentName name, byte[][] components, int count) {
		for (int i = 0; i < count; i++) {
			components[i] = encodeComponent(name.component(i));
		}
	}
	
	public static byte[] encodeComponent(byte[] component) {
		if (component[0] == VersioningProfile.VERSION_MARKER || 
				component[0] == SegmentationProfile.SEGMENT_MARKER) {
			String startString = component[0] == VersioningProfile.VERSION_MARKER ? REPLACE_VERSION : REPLACE_SEGMENT;
			String conversionString = "";
			if (component.length > 1) {
				byte[] conversionBytes = new byte[component.length - 1];
				System.arraycopy(component, 1, conversionBytes, 0, conversionBytes.length);
				conversionString = convertToBase64(conversionBytes);
			}
			return (startString + conversionString).getBytes();
		}
		byte[] newComponent = new byte[component.length];
		System.arraycopy(component, 0, newComponent, 0, component.length);
		return newComponent;
	}
	
	public ContentName decodeVersionAndSegment(ContentName name) {
		byte[][] newComponents = new byte[name.count()][];
		decodeVersionAndSegment(name, newComponents, name.count());
		return new ContentName(newComponents);
	}
	
	public void decodeVersionAndSegment(ContentName name, byte[][] components, int count) {
		for (int i = 0; i < count; i++) {
			components[i] = decodeComponent(name.component(i));
		}
	}
	
	public static byte[] decodeComponent(byte[] component) {
		byte decodeByte = '0';
		int size = 0;
		String decodeString = new String(component);
		if (DataUtils.arrayEquals(component, REPLACE_VERSION.getBytes(), REPLACE_VERSION.getBytes().length)) {
			decodeByte = VersioningProfile.VERSION_MARKER;
			size = REPLACE_VERSION.getBytes().length;
		}
		if (DataUtils.arrayEquals(component, REPLACE_SEGMENT.getBytes(), REPLACE_SEGMENT.getBytes().length)) {
			decodeByte = SegmentationProfile.SEGMENT_MARKER;
			size = REPLACE_SEGMENT.getBytes().length;
		}
		byte[] newComponent;
		if (decodeByte != '0') {
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
	
	/**
	 * Convert a non usable or already used pathname into something usable and unique
	 * 
	 * @param name
	 * @return
	 * @throws RepositoryException 
	 */
	private File getSpecialPath(ContentObject content) throws RepositoryException {
		ContentName name = content.name();
		ArrayList<byte[]> components = name.components();
		ArrayList<byte[]> newComponents = new ArrayList<byte[]>();
		newComponents.add(META_DIR.getBytes());
		newComponents.add(ENCODED_FILES.getBytes());
		for (byte[] component : components) {
			byte type = UTF8_COMPONENT;
			byte[] modifiedComponent = encodeComponent(component);
			if (needsEncoding(modifiedComponent)) {
				type = BASE64_COMPONENT;
				modifiedComponent = convertToBase64(component).getBytes();
			}
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			int length = modifiedComponent.length;
			int offset = 0;
			while (length > TOO_LONG_SIZE) {
				baos.write(SPLIT_COMPONENT);
				baos.write(modifiedComponent, offset, TOO_LONG_SIZE);
				newComponents.add(baos.toByteArray());
				length -= TOO_LONG_SIZE;
				offset += TOO_LONG_SIZE;
				baos.reset();
			}
			baos.write(type);
			baos.write(modifiedComponent, offset, length);
			newComponents.add(baos.toByteArray());
		}
		ContentName specialName = new ContentName(newComponents.size(), newComponents);
		File dirFile = new File(_repositoryRoot + getStandardString(specialName));
		dirFile.mkdirs();
		String convertedDigest = convertDigest(content);
		File file = new File(_repositoryRoot + getStandardString(specialName), 
					new String(new byte[]{UTF8_COMPONENT}) + convertedDigest);
		
		/*
		 * Handle content with the same digest as another digest
		 * We put all files with the same digest in a directory using the
		 * digest name as the directory name. So if we have a name clash
		 * with a non-directory, this is the first name clash with an old
		 * digest name and we need to remove the old data, change the file
		 * to a directory, then put back the old data in the directory and
		 * add the new data. If we already had a name clash, we can just
		 * add the data to the new directory. Also we have to handle
		 * fixing up the data in "_encodedFiles" here.
		 */
		ArrayList<File> files = _encodedFiles.get(content.name());
		if (files == null)
			files = new ArrayList<File>();
		if (file.exists() && file.isFile()) {
			ContentObject prevContent = getContentFromFile(file);
			for (File oldFile : files) {
				if (oldFile.equals(file)) {
					files.remove(oldFile);
					break;
				}
			}
			file.delete();
			file.mkdir();
			try {
				File prevFile = File.createTempFile("RFS", "xxx", file);
				saveContentToFile(prevFile, prevContent);
				files.add(prevFile);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		if (file.isDirectory()) {
			try {
				file = File.createTempFile("RFS", "xxx", file);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		
		ContentName keyName = name.clone();
		keyName.components().add(content.contentDigest());
		files.add(file);
		_encodedFiles.put(keyName, files);
		return file;
	}
	
	private void addOneFileToMap(ContentName name, File file, TreeMap<ContentName, ArrayList<File>>map) {
		ArrayList<File> files = new ArrayList<File>();
		files.add(file);
		map.put(name, files);	
	}
	
	private String convertDigest(ContentObject content) {
		return convertToBase64(content.contentDigest());
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
		b64String = b64String.replace("/", REPLACE_SLASH);
		return b64String.replace("\n", REPLACE_RETURN);
	}
	
	private static byte [] decodeBase64(String data) {
		try {
			data = data.replace(REPLACE_SLASH, "/");
			data = data.replace(REPLACE_RETURN, "\n");
			return new BASE64Decoder().decodeBuffer(data);
		} catch (IOException e) {
			return new byte[0]; // TODO error handling...
		}
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
	
	private ContentName encodeStandardElements(ContentName name) {
		byte[][] newComponents = new byte[name.count()][];
		encodeVersionAndSegment(name, newComponents, name.count());
		return new ContentName(newComponents);
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
}
