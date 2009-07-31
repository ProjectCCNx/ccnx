package com.parc.ccn.network.daemons.repo;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.security.InvalidParameterException;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherPublicKeyDigest;
import com.parc.ccn.data.security.SignedInfo;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.profiles.VersioningProfile;
import com.parc.ccn.network.daemons.repo.ContentTree.ContentFileRef;
import com.parc.ccn.Library;

/**
 * 
 * @author jthornto, rbraynar, rasmusse
 *
 * Implements a repository using sequential data files (log-structured) with an index for queries
 */

public class RFSLogImpl implements Repository, ContentTree.ContentGetter {

	public final static String CURRENT_VERSION = "1.4";
		
	public final static String META_DIR = ".meta";
	public final static String NORMAL_COMPONENT = "0";
	public final static String SPLIT_COMPONENT = "1";
	
	private static final String REPO_PRIVATE = "private";
	private static final String VERSION = "version";
	private static final String REPO_LOCALNAME = "local";
	private static final String REPO_GLOBALPREFIX = "global";

	private static String DEFAULT_LOCAL_NAME = "Repository";
	private static String DEFAULT_GLOBAL_NAME = "/parc.com/csl/ccn/Repos";
	private static String CONTENT_FILE_PREFIX = "repoFile";
	private static String DEBUG_TREEDUMP_FILE = "debugNamesTree";
	
	private static String DIAG_NAMETREE = "nametree"; // Diagnostic/signal to dump name tree to debug file
	private static String DIAG_NAMETREEWIDE = "nametreewide"; // Same as DIAG_NAMETREE but with wide names per node

	protected String _repositoryRoot = null;
	protected File _repositoryFile;
	protected RepositoryInfo _info = null;
	protected Policy _policy = null;

	Map<Integer,RepoFile> _files;
	RepoFile _activeWriteFile;
	ContentTree _index;
	
	public class RepoFile {
		File file;
		RandomAccessFile openFile;
		long nextWritePos;
	}
	
	public boolean checkPolicyUpdate(ContentObject co)
			throws RepositoryException {
		if (_info.getPolicyName().isPrefixOf(co.name())) {
			ByteArrayInputStream bais = new ByteArrayInputStream(co.content());
			try {
				if (_policy.update(bais, true)) {
					ContentName policyName = VersioningProfile.versionName(
							ContentName.fromNative(REPO_NAMESPACE + "/" + _info.getLocalName() + "/" + REPO_POLICY));
					ContentObject policyCo = new ContentObject(policyName, co.signedInfo(), co.content(), co.signature());
	   				saveContent(policyCo);
				}
			} catch (Exception e) {
				Library.logStackTrace(Level.WARNING, e);
				e.printStackTrace();
			} 
			return true;
		}
		return false;
	}

	public ContentObject getContent(Interest interest)
			throws RepositoryException {
		return _index.get(interest, this);
	}

	public NameEnumerationResponse getNamesWithPrefix(Interest i) {
		return _index.getNamesWithPrefix(i, this);
	}

	public ArrayList<ContentName> getNamespace() {
		return _policy.getNameSpace();
	}

	public byte[] getRepoInfo(ArrayList<ContentName> names) {
		try {
			RepositoryInfo rri = _info;
			if (names != null)
				rri = new RepositoryInfo(_info.getLocalName(), _info.getGlobalPrefix(), CURRENT_VERSION, names);	
			return rri.encode();
		} catch (Exception e) {
			Library.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
		}
		return null;
	}

	public static String getUsage() {
		return " -root repository_root [-policy policy_file] [-local local_name] [-global global_prefix]\n";
	}

	protected Integer createIndex() {
		int max = 0;
		_index = new ContentTree();
		assert(null != _repositoryFile);
		assert(_repositoryFile.isDirectory());
		String[] filenames = _repositoryFile.list();
		for (int i = 0; i < filenames.length; i++) {
			if (filenames[i].startsWith(CONTENT_FILE_PREFIX)) {
				String indexPart = filenames[i].substring(CONTENT_FILE_PREFIX.length());
				if (null != indexPart && indexPart.length() > 0) {
					try {
						Integer index = Integer.parseInt(indexPart);
						if (index > max) {
							max = index.intValue();
						}
						RepoFile rfile = new RepoFile();
						rfile.file = new File(_repositoryFile,filenames[i]);
						rfile.openFile = new RandomAccessFile(rfile.file, "r");
						//InputStream is = new RandomAccessInputStream(rfile.openFile);
						InputStream is = new BufferedInputStream(new RandomAccessInputStream(rfile.openFile));
						//FileInputStream fis = new FileInputStream(rfile.file);
						//is = new BufferedInputStream(fis);
						//FileChannel fc = fis.getChannel();
					
						Library.logger().fine("Creating index for " + filenames[i]);
						while (true) {
							ContentFileRef ref = _index.new ContentFileRef();
							ref.id = index.intValue();
							ref.offset = rfile.openFile.getFilePointer();
							if(ref.offset > 0)
								ref.offset = ref.offset - is.available();
							ContentObject tmp = new ContentObject();
							try {
								if(rfile.openFile.getFilePointer()<rfile.openFile.length() || is.available()!=0){
									//tmp.decode(is);
									tmp.decode(is);
								}
								else{
									Library.logger().info("at the end of the file");
									rfile.openFile.close();
									rfile.openFile = null;
									break;
								}

							} catch (XMLStreamException e) {
								Library.logStackTrace(Level.WARNING, e);
								e.printStackTrace();
								// Failed to decode, must be end of this one
								//added check for end of file above
								rfile.openFile.close();
								rfile.openFile = null;
								break;
							}
							_index.insert(tmp, ref, rfile.file.lastModified(), this, null);
						}
						_files.put(index, rfile);
					} catch (NumberFormatException e) {
						// Not valid file
						Library.logger().warning("Invalid file name " + filenames[i]);
					} catch (FileNotFoundException e) {
						Library.logger().warning("Unable to open file to create index: " + filenames[i]);
					} catch (IOException e) {
						Library.logger().warning("IOException reading file to create index: " + filenames[i]);
					}
				}
			}
		}
		return new Integer(max);
	}
	
	public String[] initialize(String[] args, CCNLibrary library) throws RepositoryException {
		boolean policyFromFile = false;
		boolean nameFromArgs = false;
		boolean globalFromArgs = false;
		String localName = DEFAULT_LOCAL_NAME;
		String globalPrefix = DEFAULT_GLOBAL_NAME;
		String[] outArgs = args;
		_policy = new BasicPolicy(null);
		_policy.setVersion(CURRENT_VERSION);
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
					_policy.update(new FileInputStream(policyFile), false);
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
//		_locker = new RFSLocks(_repositoryRoot + File.separator + META_DIR);
		
		// Internal initialization
		_files = new HashMap<Integer, RepoFile>();
		int maxFileIndex = createIndex();
		
		// Internal initialization
		//moved the following...  getContent depends on having an index
		//_files = new HashMap<Integer, RepoFile>();
		//int maxFileIndex = createIndex();
		try {
			if (maxFileIndex == 0) {
				maxFileIndex = 1; // the index of a file we will actually write
				RepoFile rfile = new RepoFile();
				rfile.file = new File(_repositoryFile, CONTENT_FILE_PREFIX+"1");
				rfile.openFile = new RandomAccessFile(rfile.file, "rw");
				rfile.nextWritePos = 0;
				_files.put(new Integer(maxFileIndex), rfile);
				_activeWriteFile = rfile;
			} else {
				RepoFile rfile = _files.get(new Integer(maxFileIndex));
				long cursize = rfile.file.length();
				rfile.openFile = new RandomAccessFile(rfile.file, "rw");
				rfile.nextWritePos = cursize;
				_activeWriteFile = rfile;
			}
			
		} catch (FileNotFoundException e) {
			Library.logger().warning("Error opening content output file index " + maxFileIndex);
		}
		
		String version = checkFile(VERSION, CURRENT_VERSION, library, false);
		if (version != null && !version.trim().equals(CURRENT_VERSION))
			throw new RepositoryException("Bad repository version: " + version);
		
		String checkName = checkFile(REPO_LOCALNAME, localName, library, nameFromArgs);
		localName = checkName != null ? checkName : localName;
		try {
			_policy.setLocalName(localName);
		} catch (MalformedContentNameStringException e3) {
			throw new RepositoryException(e3.getMessage());
		}
		
		checkName = checkFile(REPO_GLOBALPREFIX, globalPrefix, library, globalFromArgs);
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
				Library.logger().info(REPO_NAMESPACE+"/" + _info.getLocalName() + "/" + REPO_POLICY);
				ContentObject policyObject = getContent(
						new Interest(ContentName.fromNative(REPO_NAMESPACE + "/" + _info.getLocalName() + "/" + REPO_POLICY)));
				if (policyObject != null) {
					ByteArrayInputStream bais = new ByteArrayInputStream(policyObject.content());
					_policy.update(bais, false);
				}
			} catch (MalformedContentNameStringException e) {} // None of this should happen
			  catch (XMLStreamException e) {} 
			  catch (IOException e) {}
		} else {
			saveContent(_policy.getPolicyContent());
		}
		
		try {
			_policy.setGlobalPrefix(globalPrefix);
		} catch (MalformedContentNameStringException e2) {
			throw new RepositoryException(e2.getMessage());
		}
		
		return outArgs;
	}


	public NameEnumerationResponse saveContent(ContentObject content) throws RepositoryException {
		// Make sure content is within allowable nameSpace
		boolean nameSpaceOK = false;
		for (ContentName name : _policy.getNameSpace()) {
			if (name.isPrefixOf(content.name())) {
				nameSpaceOK = true;
				break;
			}
		}
		if (!nameSpaceOK)
			return null;
		try {	
			NameEnumerationResponse ner = new NameEnumerationResponse();
			synchronized(_activeWriteFile) {
				assert(null != _activeWriteFile.openFile);
				ContentFileRef ref = _index.new ContentFileRef();
				ref.id = Integer.parseInt(_activeWriteFile.file.getName().substring(CONTENT_FILE_PREFIX.length()));
				ref.offset = _activeWriteFile.nextWritePos;
				_activeWriteFile.openFile.seek(_activeWriteFile.nextWritePos);
				OutputStream os = new RandomAccessOutputStream(_activeWriteFile.openFile);
				content.encode(os);
				_activeWriteFile.nextWritePos = _activeWriteFile.openFile.getFilePointer();
				_index.insert(content, ref, System.currentTimeMillis(), this, ner);
				if(ner==null || ner.prefix==null){
					Library.logger().finest("new content did not trigger an interest flag");
				}
				else{
					Library.logger().info("new content was added where there was a name enumeration response interest flag");
				}
				return ner;
			}
		} catch (IOException e) {
			throw new RepositoryException("Failed to write content: " + e.getMessage());
		} catch (XMLStreamException e) {
			throw new RepositoryException("Failed to encode content: " + e.getMessage());
		}
	}

	public void setPolicy(Policy policy) {
		_policy = policy;
	}

	public ContentObject get(ContentFileRef ref) {
		try {
			RepoFile file = _files.get(ref.id);
			if (null == file)
				return null;
			synchronized (file) {
				if (null == file.openFile) {
					file.openFile = new RandomAccessFile(file.file, "r");
				}
				file.openFile.seek(ref.offset);
				ContentObject content = new ContentObject();
				InputStream is = new BufferedInputStream(new RandomAccessInputStream(file.openFile));
				content.decode(is);
				return content;
			}
		} catch (IndexOutOfBoundsException e) {
			return null;
		} catch (FileNotFoundException e) {
			return null;
		} catch (IOException e) {
			return null;
		} catch (XMLStreamException e) {
			return null;
		}
	}
	
	/**
	 * Check data "file" - create new one if none exists or "forceWrite" is set.
	 * Files are always versioned so we can find the latest one.
	 * TODO - Need to handle data that can take up more than 1 co.
	 * TODO - Co Should be signed with the "repository's" signature.
	 * @throws RepositoryException
	 */
	private String checkFile(String fileName, String contents, CCNLibrary library, boolean forceWrite) throws RepositoryException {
		byte[][] components = new byte[3][];
		components[0] = META_DIR.getBytes();
		components[1] = REPO_PRIVATE.getBytes();
		components[2] = fileName.getBytes();
		ContentName name = new ContentName(components);
		ContentObject co = getContent(Interest.last(name, 3));
		
		if (!forceWrite && co != null) {
			return new String(co.content());
		}
		
		ContentName versionedName = VersioningProfile.versionName(name);
		PublisherPublicKeyDigest publisher = library.keyManager().getDefaultKeyID();
		PrivateKey signingKey = library.keyManager().getSigningKey(publisher);
		KeyLocator locator = library.keyManager().getKeyLocator(signingKey);
		try {
			co = new ContentObject(versionedName, new SignedInfo(publisher, locator), contents.getBytes(), signingKey);
		} catch (Exception e) {
			Library.logStackTrace(Level.WARNING, e);
			e.printStackTrace();
			return null;
		}
		saveContent(co);
		return null;
	}

	
	protected void dumpNames(int nodelen) {
		// Debug: dump names tree to file
		File namesFile = new File(_repositoryFile, DEBUG_TREEDUMP_FILE);
		try {
			Library.logger().info("Dumping names to " + namesFile.getAbsolutePath() + " (len " + nodelen + ")");
			PrintStream namesOut = new PrintStream(namesFile);
			if (null != _index) {
				_index.dumpNamesTree(namesOut, nodelen);
			}
		} catch (FileNotFoundException ex) {
			Library.logger().warning("Unable to dump names to " + namesFile.getAbsolutePath());
		}
	}

	public boolean diagnostic(String name) {
		if (0 == name.compareToIgnoreCase(DIAG_NAMETREE)) {
			dumpNames(35);
			return true;
		} else if (0 == name.compareToIgnoreCase(DIAG_NAMETREEWIDE)) {
			dumpNames(-1);
			return true;
		}
		return false;
	}

	public void shutDown() {
		if (null != _activeWriteFile.openFile) {
			try {
				_activeWriteFile.openFile.close();
			} catch (IOException e) {}
		}
	}
}
