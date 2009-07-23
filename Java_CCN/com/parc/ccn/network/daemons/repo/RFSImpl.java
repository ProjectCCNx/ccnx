package com.parc.ccn.network.daemons.repo;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.InvalidParameterException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.StringTokenizer;
import java.util.TreeMap;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.WirePacket;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNNameEnumerator;
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
	
	public final static String CURRENT_VERSION = "1.3";
	
	public final static String META_DIR = ".meta";
	public final static String NORMAL_COMPONENT = "0";
	public final static String SPLIT_COMPONENT = "1";
	
	private static final String REPO_PRIVATE = "private";
	private static final String VERSION = "version";
	private static final String REPO_LOCALNAME = "local";
	private static final String REPO_GLOBALPREFIX = "global";
	
	private static String DEFAULT_LOCAL_NAME = "Repository";
	private static String DEFAULT_GLOBAL_NAME = "/parc.com/csl/ccn/Repos";
		
	private static final int TOO_LONG_SIZE = 200;
	
	protected String _repositoryRoot = null;
	protected File _repositoryFile;
	protected RFSLocks _locker;
	protected Policy _policy = null;
	protected RepositoryInfo _info = null;
	protected ArrayList<ContentName> _nameSpace = new ArrayList<ContentName>();
	
	public String[] initialize(String[] args, CCNLibrary library) throws RepositoryException {
		
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
		File file = new File(_repositoryFile + interest.name().toString());
		ContentName lowerName = new ContentName(null != interest.nameComponentCount() ? interest.nameComponentCount() : interest.name().count(),
					interest.name().components());
		getAllFileResults(file, results, lowerName);
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
				try {
					getAllFileResults(f, results, new ContentName(name, ContentName.componentParseURI(f.getName())));
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
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
			File encodedFile = new File(_repositoryFile + encodedName.toString());
			if (encodedFile.isDirectory()) {
				getAllFileResults(encodedFile, results, encodedName);
			}
			else {
				encodedFile = new File(_repositoryFile + encodedName.toString());
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
	 * @throws RepositoryException 
	 */
	public void saveContent(ContentObject content) throws RepositoryException {
		File file = null;
		ContentName newName = content.name().clone();
		newName.components().add(content.contentDigest());
		newName = encodeName(newName);
		ContentName dirName = new ContentName(newName.count() - 1, newName.components());
		File dirFile = new File(_repositoryRoot, dirName.toString());
		dirFile.mkdirs();
		file = new File(_repositoryRoot, newName.toString());
		if (file.exists()) {
			boolean isDuplicate = true;
			ContentObject prevContent = null;
			if (file.isFile()) {			
				// New name clash
				try {
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
				} catch (RepositoryException e1) {
					// This could happen if there are 2 simultaneous requests
					// to create the data and the first isn't yet complete (I guess)
					// For now just remove the old data and try to recreate it.
					file.delete();
					isDuplicate = false;
				}
			} 
			
			if (isDuplicate) {
				try {
					file = File.createTempFile("RFS", ".rfs", file);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
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
			file.createNewFile();
			FileOutputStream fos = new FileOutputStream(file);
			WirePacket packet = new WirePacket(content);
			fos.write(packet.encode());
			fos.close();
		} catch (Exception e) {
			throw new RepositoryException(e.getMessage());
		}
	}
	
	/**
	 * Encode all components of a ContentName
	 * This could result in more components than originally input due to splitting of
	 * components.
	 * 
	 * @param name
	 * @return
	 */
	public static ContentName encodeName(ContentName name) {
		StringTokenizer st = new StringTokenizer(name.toString(), "/");
		String newUri = "/";
		while (st.hasMoreTokens()) {
			String token = st.nextToken();
			while ((token.length() + 1) > TOO_LONG_SIZE) {
				int length = TOO_LONG_SIZE - 1;
				String nextPiece = token.substring(0, length);
				
				// Avoid fragmenting binary encoded URI
				if (nextPiece.charAt(length - 1) == '%') length--;
				if (nextPiece.charAt(length - 2) == '%') length -= 2;
				
				newUri += SPLIT_COMPONENT + token.substring(0, length) + "/";
				token = token.substring(length);
			}
			newUri += NORMAL_COMPONENT + token + "/";
		}
		try {
			return ContentName.fromURI(newUri);
		} catch (MalformedContentNameStringException e) {
			return null; //shouldn't happen
		}
	}
	
	/**
	 * Decode a name built from filename components into a CCN name. The new
	 * name could contain less components than the input name.
	 * 
	 * @param name
	 * @return
	 */
	public static ContentName decodeName(ContentName name) {
		String newUri = "/";
		StringTokenizer st = new StringTokenizer(name.toString(), "/");
		String nextComponent = "";
		while (st.hasMoreTokens()) {
			String token = st.nextToken();
			if (token.startsWith(SPLIT_COMPONENT))
				nextComponent += token.substring(1);
			else {
				newUri += nextComponent + token.substring(1) + "/";
				nextComponent = "";
			}
		}
		try {
			return ContentName.fromURI(newUri);
		} catch (MalformedContentNameStringException e) {
			return null; //shouldn't happen
		}
	}
	
	private void addOneFileToMap(ContentName name, File file, TreeMap<ContentName, ArrayList<File>>map) {
		ArrayList<File> files = map.get(name);
		if (files == null)
			files = new ArrayList<File>();
		files.add(file);
		map.put(name, files);	
	}
	
	public static String getUsage() {
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
		Library.logger().setLevel(java.util.logging.Level.FINE);
		ArrayList<ContentName> names = new ArrayList<ContentName>();
		Timestamp interestTS = null;
		Timestamp fileTS = null;
		try{
			interestTS = VersioningProfile.getVersionAsTimestamp(i.name());
		}
		catch(Exception e){
			interestTS = null;
		}
		ContentName cropped = i.name().cut(CCNNameEnumerator.NEMARKER);
		
		ContentName encoded = RFSImpl.encodeName(cropped);
		File encodedFile = new File(_repositoryFile + encoded.toString());
		if(encodedFile.exists()){
			//we have something to work with!
			Library.logger().finest("this prefix was found in our content: "+encoded.toString());
		}
		else{
			//nothing here...  return null
			Library.logger().finest("this prefix is not found in our content: "+encoded.toString());
			return null;
		}
		long lastModified = encodedFile.lastModified();
		fileTS = new Timestamp(lastModified);
		if(interestTS!=null)
			Library.logger().fine("localTime: "+System.currentTimeMillis()+" interest time: "+interestTS.getTime()+" fileTS: "+fileTS.getTime());
		
		ContentName n = new ContentName();
		if(interestTS == null || fileTS.after(interestTS)){
			//we have something new to report
		
			Library.logger().fine("path to file: "+encodedFile.getName());
			String[] matches = encodedFile.list();
		
			if (matches != null) {
				for(String s: matches){
					names.add(RFSImpl.decodeName(new ContentName(n, s.getBytes())));
				}
			}
		}
		
		if(names.size() > 0){
			String toprint = "---names to return: ";
			
			for(ContentName ntr: names)
				toprint.concat(" "+ntr.toString());
			Library.logger().fine(toprint+" ---");

			return names;
		}
		else{
			Library.logger().finest("No new names for this prefix since the last request, dropping request and not responding.");
			return null;
		}
	}

	public boolean diagnostic(String name) {
		// No diagnostics supported
		return false;
	}

	public void shutDown() {
		// TODO Auto-generated method stub
		
	}
}
