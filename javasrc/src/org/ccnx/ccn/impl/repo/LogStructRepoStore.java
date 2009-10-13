/**
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008, 2009 Palo Alto Research Center, Inc.
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

package org.ccnx.ccn.impl.repo;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.security.InvalidParameterException;
import java.security.PrivateKey;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.KeyLocator;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;
import org.ccnx.ccn.protocol.PublisherPublicKeyDigest;
import org.ccnx.ccn.protocol.SignedInfo;


/**
 * Implements a log-structured RepositoryStore on a filesystem using sequential data files with an index for queries
 */

public class LogStructRepoStore extends RepositoryStoreBase implements RepositoryStore, ContentTree.ContentGetter {

	public final static String CURRENT_VERSION = "1.4";
		
	public final static String META_DIR = ".meta";
	public final static String NORMAL_COMPONENT = "0";
	public final static String SPLIT_COMPONENT = "1";
	
	private static String DEFAULT_LOCAL_NAME = "Repository";
	private static String DEFAULT_GLOBAL_NAME = "/parc.com/csl/ccn/Repos";
	private static final String REPO_PRIVATE = "private";
	private static final String VERSION = "version";
	private static final String REPO_LOCALNAME = "local";
	private static final String REPO_GLOBALPREFIX = "global";

	private static String CONTENT_FILE_PREFIX = "repoFile";
	private static String DEBUG_TREEDUMP_FILE = "debugNamesTree";
	
	private static String DIAG_NAMETREE = "nametree"; // Diagnostic/signal to dump name tree to debug file
	private static String DIAG_NAMETREEWIDE = "nametreewide"; // Same as DIAG_NAMETREE but with wide names per node

	protected String _repositoryRoot = null;
	protected File _repositoryFile;

	Map<Integer,RepoFile> _files;
	RepoFile _activeWriteFile;
	ContentTree _index;
	
	public class RepoFile {
		File file;
		RandomAccessFile openFile;
		long nextWritePos;
	}
	
	protected class FileRef extends ContentRef {
		int id;
		long offset;
	}

	/**
	 * Gets content matching the given interest
	 * 
	 * @param interest the given interest
	 * @return the closest matching ContentObject or null if no match
	 */
	public ContentObject getContent(Interest interest)
			throws RepositoryException {
		return _index.get(interest, this);
	}

	/**
	 * Gets all names matching the given NameEnumeration interest
	 * 
	 * @param i the interest
	 * @returns the data as a NameEnumerationResponse
	 */
	public NameEnumerationResponse getNamesWithPrefix(Interest i) {
		return _index.getNamesWithPrefix(i);
	}

	/**
	 * Gets the current policy for this repository
	 * @returns the policy
	 */
	public Policy getPolicy() {
		return _policy;
	}

	/**
	 * Gets the current version of this RepositoryStore
	 * 
	 * @return the version as a String
	 */
	public String getVersion() {
		return CURRENT_VERSION;
	}

	/**
	 * Read the current repository file(s) for this repository and create an index for them.
	 * WARNING: multiple files are not well tested
	 * 
	 * @return the number of files making up the repository
	 */
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
						InputStream is = new BufferedInputStream(new RandomAccessInputStream(rfile.openFile));
						
						if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
							Log.fine("Creating index for {0}", filenames[i]);
						}
						while (true) {
							FileRef ref = new FileRef();
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
									if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
										Log.info("at the end of the file");
									}
									rfile.openFile.close();
									rfile.openFile = null;
									break;
								}

							} catch (XMLStreamException e) {
								Log.logStackTrace(Level.WARNING, e);
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
						Log.warning("Invalid file name " + filenames[i]);
					} catch (FileNotFoundException e) {
						Log.warning("Unable to open file to create index: " + filenames[i]);
					} catch (IOException e) {
						Log.warning("IOException reading file to create index: " + filenames[i]);
					}
				}
			}
		}
		return new Integer(max);
	}
	
	/**
	 * Initialize the repository
	 * 
	 * @param handle a CCNHandle used to retrieve keys for storing repository private data as ContentObjects
	 * @param repositoryRoot the directory containing the files to store a repository. A new directory is created if this doesn't yet exist
	 * @param policyFile a file containing policy data to define the initial repository policy (see BasicPolicy)
	 * @param localName the local name for this repository as a slash separated String (defaults if null)
	 * @param globalPrefix the global prefix for this repository as a slash separated String (defaults if null)
	 * @param An initial namespace (defaults to namespace stored in repository, or / if none)
	 * @throws RepositoryException if the policyFile, localName, or globalName are improperly formatted
	 */
	public void initialize(CCNHandle handle, String repositoryRoot, File policyFile, String localName, String globalPrefix,
				String namespace) throws RepositoryException {
		boolean nameFromArgs = (null != localName);
		boolean globalFromArgs = (null != globalPrefix);
		if (null == localName)
			localName = DEFAULT_LOCAL_NAME;
		if (null == globalPrefix) 
			globalPrefix = DEFAULT_GLOBAL_NAME;
		startInitPolicy(policyFile, namespace);

		if (repositoryRoot == null) {
			throw new InvalidParameterException();
		} else {
			_repositoryRoot = repositoryRoot;
		}
		
		_repositoryFile = new File(_repositoryRoot);
		_repositoryFile.mkdirs();
		
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
			Log.warning("Error opening content output file index " + maxFileIndex);
		}
		
		// Verify stored policy info
		String version = checkFile(VERSION, CURRENT_VERSION, handle, false);
		if (version != null && !version.trim().equals(CURRENT_VERSION))
			throw new RepositoryException("Bad repository version: " + version);

		String checkName = checkFile(REPO_LOCALNAME, localName, handle, nameFromArgs);
		localName = checkName != null ? checkName : localName;
		try {
			_policy.setLocalName(localName);		
		} catch (MalformedContentNameStringException e3) {
			throw new RepositoryException(e3.getMessage());
		}
		
		/**
		 * Try to read policy from storage if we don't have full policy source yet
		 */
		if (null == policyFile) {
			readPolicy(localName);
		}
		
		checkName = checkFile(REPO_GLOBALPREFIX, globalPrefix, handle, globalFromArgs);
		globalPrefix = checkName != null ? checkName : globalPrefix;
		if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
			Log.info("REPO: initializing repository: global prefix {0}, local name {1}", globalPrefix, localName);
		}
		try {
			_policy.setGlobalPrefix(globalPrefix);
			if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
				Log.info("REPO: initializing policy location: {0} for global prefix {1} and local name {2}", localName, globalPrefix,  localName);
			}
		} catch (MalformedContentNameStringException e2) {
			throw new RepositoryException(e2.getMessage());
		}
		saveContent(_policy.getPolicyContent());
		try {
			finishInitPolicy(globalPrefix, localName);
		} catch (MalformedContentNameStringException e) {
			throw new RepositoryException(e.getMessage());
		}
	}

	/**
	 * Save the given content in the repository store
	 * 
	 * @param content the content to save
	 * @throws RepositoryException it the content can not be written or encoded
	 * @returns NameEnumerationResponse if this satisfies an outstanding NameEnumeration request
	 */
	public NameEnumerationResponse saveContent(ContentObject content) throws RepositoryException {
		// Make sure content is within allowable nameSpace
		boolean nameSpaceOK = false;
		synchronized (_policy) {
			for (ContentName name : _policy.getNameSpace()) {
				if (name.isPrefixOf(content.name())) {
					nameSpaceOK = true;
					break;
				}
			}
		}
		if (!nameSpaceOK) {
			if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
				Log.info("Repo rejecting content: {0}, not in registered namespace.", content.name());
			}
			return null;
		}
		try {	
			NameEnumerationResponse ner = new NameEnumerationResponse();
			synchronized(_activeWriteFile) {
				assert(null != _activeWriteFile.openFile);
				FileRef ref = new FileRef();
				ref.id = Integer.parseInt(_activeWriteFile.file.getName().substring(CONTENT_FILE_PREFIX.length()));
				ref.offset = _activeWriteFile.nextWritePos;
				_activeWriteFile.openFile.seek(_activeWriteFile.nextWritePos);
				OutputStream os = new RandomAccessOutputStream(_activeWriteFile.openFile);
				content.encode(os);
				_activeWriteFile.nextWritePos = _activeWriteFile.openFile.getFilePointer();
				_index.insert(content, ref, System.currentTimeMillis(), this, ner);
				if(ner==null || ner.getPrefix()==null){
					if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
						Log.fine("new content did not trigger an interest flag");
					}
				} else {
					if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
						Log.fine("new content was added where there was a name enumeration response interest flag");
					}
				}
				return ner;
			}
		} catch (IOException e) {
			throw new RepositoryException("Failed to write content: " + e.getMessage());
		} catch (XMLStreamException e) {
			throw new RepositoryException("Failed to encode content: " + e.getMessage());
		}
	}

	/**
	 * Get content for the given reference from the storage files. Used to retrieve content for 
	 * comparison operations.
	 * 
	 * @param ref the reference
	 * @return ContentObject at the referenced slot in the storage files
	 */
	public ContentObject get(ContentRef ref) {
		// This is a call back based on what we put in ContentTree, so it must be
		// using our subtype of ContentRef
		FileRef fref = (FileRef)ref;
		try {
			RepoFile file = _files.get(fref.id);
			if (null == file)
				return null;
			synchronized (file) {
				if (null == file.openFile) {
					file.openFile = new RandomAccessFile(file.file, "r");
				}
				file.openFile.seek(fref.offset);
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
	private String checkFile(String fileName, String contents, CCNHandle handle, boolean forceWrite) throws RepositoryException {
		byte[][] components = new byte[3][];
		components[0] = META_DIR.getBytes();
		components[1] = REPO_PRIVATE.getBytes();
		components[2] = fileName.getBytes();
		ContentName name = new ContentName(components);
		ContentObject co = getContent(Interest.last(name, 3));
		
		if (!forceWrite && co != null) {
			return new String(co.content());
		}
		
		ContentName versionedName = VersioningProfile.addVersion(name);
		PublisherPublicKeyDigest publisher = handle.keyManager().getDefaultKeyID();
		PrivateKey signingKey = handle.keyManager().getSigningKey(publisher);
		KeyLocator locator = handle.keyManager().getKeyLocator(signingKey);
		try {
			co = new ContentObject(versionedName, new SignedInfo(publisher, locator), contents.getBytes(), signingKey);
		} catch (Exception e) {
			Log.logStackTrace(Level.WARNING, e);
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
			if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING)) {
				Log.info("Dumping names to " + namesFile.getAbsolutePath() + " (len " + nodelen + ")");
			}
			PrintStream namesOut = new PrintStream(namesFile);
			if (null != _index) {
				_index.dumpNamesTree(namesOut, nodelen);
			}
		} catch (FileNotFoundException ex) {
			Log.warning("Unable to dump names to " + namesFile.getAbsolutePath());
		}
	}

	/**
	 * Dump all names of data stored in the repository into a special file within the repository 
	 * on diagnostic request from higher level code
	 * 
	 * @param name "nametree" or "nametreewide" to decide whether to limit the printout length of components
	 */
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

	/**
	 * Cleanup on shutdown
	 */
	public void shutDown() {
		if (null != _activeWriteFile.openFile) {
			try {
				_activeWriteFile.openFile.close();
			} catch (IOException e) {}
		}
	}
}
