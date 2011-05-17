/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2011 Palo Alto Research Center, Inc.
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
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.security.InvalidKeyException;
import java.security.InvalidParameterException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.KeyManager;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.config.UserConfiguration;
import org.ccnx.ccn.config.SystemConfiguration.DEBUGGING_FLAGS;
import org.ccnx.ccn.impl.repo.PolicyXML.PolicyObject;
import org.ccnx.ccn.impl.security.keys.BasicKeyManager;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.io.content.ContentEncodingException;
import org.ccnx.ccn.profiles.CCNProfile;
import org.ccnx.ccn.profiles.context.ServiceDiscoveryProfile;
import org.ccnx.ccn.profiles.nameenum.NameEnumerationResponse;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


/**
 * Implements a log-structured RepositoryStore on a filesystem using sequential data files with an index for queries
 */

public class LogStructRepoStore extends RepositoryStoreBase implements RepositoryStore, ContentTree.ContentGetter {

	public final static String CURRENT_VERSION = "1.4";
		
	public static class LogStructRepoStoreProfile implements CCNProfile {
		public final static String META_DIR = ".meta";
		public final static String NORMAL_COMPONENT = "0";
		public final static String SPLIT_COMPONENT = "1";
		
		public static final String REPO_IMPORT_DIR = "import";

		private static String DEFAULT_LOCAL_NAME = "Repository";
		private static String DEFAULT_GLOBAL_NAME = "/parc.com/csl/ccn/Repos";
		private static final String VERSION = "version";
		private static final String REPO_LOCALNAME = "local";
		private static final String REPO_GLOBALPREFIX = "global";
				
		public static final char [] KEYSTORE_PASSWORD = "Th1s 1s n0t 8 g00d R3p0s1t0ry p8ssw0rd!".toCharArray();
		public static final String KEYSTORE_FILE = "ccnx_repository_keystore";
		public static final String REPOSITORY_USER = "Repository";
		// OS dependencies -- some OSes seem to ignore case in keystore aliases
		public static final String REPOSITORY_KEYSTORE_ALIAS = REPOSITORY_USER.toLowerCase();

		public static String CONTENT_FILE_PREFIX = "repoFile";
		private static String DEBUG_TREEDUMP_FILE = "debugNamesTree";

		private static String DIAG_NAMETREE = "nametree"; // Diagnostic/signal to dump name tree to debug file
		private static String DIAG_NAMETREEWIDE = "nametreewide"; // Same as DIAG_NAMETREE but with wide names per node
	}
	
	protected String _repositoryRoot = null;
	protected String _repositoryMeta = null;
	protected File _repositoryFile;
	protected boolean _useStoredPolicy = true;

	Map<Integer,RepoFile> _files;
	RepoFile _activeWriteFile = null;
	Integer _currentFileIndex = 0;
	ContentTree _index;
	
	protected HashMap<String, String> _bulkImportInProgress = new HashMap<String, String>();
	
	public static class RepoFile {
		File file;
		RandomAccessFile openFile;
		long nextWritePos;
	}
	
	protected static class FileRef extends ContentRef {
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
		ContentObject co =  _index.get(interest, this);
		if( Log.isLoggable(Log.FAC_REPO, Level.FINE) )
			Log.fine(Log.FAC_REPO, "Looking for: " + interest.name() + (co == null ? ": Didn't find it" : ": Found it"));
		return co;
	}

	/**
	 * Check for content matching the given name, without retrieving the content itself.
	 * @param name ContentName to match exactly, including digest as final explicit component
	 * @return true if there is a ContentObject with exactly the given name, false otherwise
	 */
	public boolean hasContent(ContentName name) throws RepositoryException {
		return _index.matchContent(name);
	}

	/**
	 * Gets all names matching the given NameEnumeration interest
	 * 
	 * @param i the interest
	 * @returns the data as a NameEnumerationResponse
	 */
	public NameEnumerationResponse getNamesWithPrefix(Interest i, ContentName responseName) {
		return _index.getNamesWithPrefix(i, responseName);
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
			if (filenames[i].startsWith(LogStructRepoStoreProfile.CONTENT_FILE_PREFIX)) {
				String indexPart = filenames[i].substring(LogStructRepoStoreProfile.CONTENT_FILE_PREFIX.length());
				if (null != indexPart && indexPart.length() > 0) {
					Integer index = Integer.parseInt(indexPart);
					if (index > max) {
						max = index.intValue();
					}
					try {
						createIndex(filenames[i], index, false);
					} catch (RepositoryException e) {}	// This can't happen
				}
			}
		}
		return new Integer(max);
	}
	
	/**
	 * Create index from specific file. For now we will allow errors during the initial index creation,
	 * assuming that we want to keep trying if there's an error in the existing index files. If an import
	 * file has an error though we want to abort. The issue of handling corrupt data in the repo in general
	 * ought to be revisited.
	 * 
	 * Because index creation can now be done while the repo is actively doing file searches, care must be
	 * taken to synchronize events correctly.
	 * 
	 * @param fileName
	 * @param index
	 * @param fromImport - this is an "import" file.
	 * @throws RepositoryException 
	 */
	private void createIndex(String fileName, Integer index, boolean fromImport) throws RepositoryException {
		try {
			RepoFile rfile = new RepoFile();
			rfile.file = new File(_repositoryFile,fileName);
			rfile.openFile = new RandomAccessFile(rfile.file, "r");
			InputStream is = new BufferedInputStream(new RandomAccessInputStream(rfile.openFile),8192);
			
			if (Log.isLoggable(Log.FAC_REPO, Level.FINE)) {
				Log.fine(Log.FAC_REPO, "Creating index for {0}", fileName);
			}
			
			// Must be done before inserting into the index because once objects are inserted into the
			// index, a lookup to this file can occur. If the object is inserted, even if all objects
			// from the file are not yet inserted, a read of the file for the already inserted object
			// should be OK. By doing it this way, we avoid having to stall all gets while a bulk import
			// (which could be arbitrarily long) is in progress
			
			synchronized (_files) {
				_files.put(index, rfile);
			}
			
			// Its true that its "OK" for someone to be reading the nodes as we are creating them
			// but now we have to be careful to keep our filepointer correct, since it could be modified
			// by a reader. The seek to a new spot in get is synchronized under the "RepoFile", so we
			// keep track of where our pointer was also synchronized under the RepoFile so we can restore
			// it to where it was in the case someone was reading one of our previously created nodes
			// while the index creation is in progress.
			long nextOffset = 0;
			while (true) {
				FileRef ref = new FileRef();
				ContentObject tmp = new ContentObject();
				synchronized (rfile) {
					ref.id = index.intValue();
					ref.offset = nextOffset;
					rfile.openFile.seek(nextOffset);	// In case a get changed this in the meantime
					if(ref.offset > 0)
						ref.offset = ref.offset - is.available();
					try {
						if (rfile.openFile.getFilePointer()<rfile.openFile.length() || is.available()!=0) {
							tmp.decode(is);
							nextOffset = rfile.openFile.getFilePointer();
						}
						else{
							if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
								Log.info(Log.FAC_REPO, "at the end of the file");
							}
							rfile.openFile.close();
							rfile.openFile = null;
							break;
						}
	
					} catch (ContentDecodingException e) {
						// Failed to decode, must be end of this one
						//added check for end of file above
						rfile.openFile.close();
						rfile.openFile = null;
						if (fromImport)
							throw new RepositoryException(e.getMessage());
						Log.logStackTrace(Level.WARNING, e);
						e.printStackTrace();
						break;
					}
				}
				_index.insert(tmp, ref, rfile.file.lastModified(), this, null);
			}
		} catch (NumberFormatException e) {
			// Not valid file
			Log.warning(Log.FAC_REPO, "Invalid file name " +fileName);
		} catch (FileNotFoundException e) {
			Log.warning(Log.FAC_REPO, "Unable to open file to create index: " + fileName);
		} catch (IOException e) {
			Log.warning(Log.FAC_REPO, "IOException reading file to create index: " + fileName);
		}
	}
	
	/**
	 * Initialize the repository
	 * 
	 * @param repositoryRoot the directory containing the files to store a repository. A new directory is created if this doesn't yet exist
	 * @param policyFile a file containing policy data to define the initial repository policy (see BasicPolicy)
	 * @param localName the local name for this repository as a slash separated String (defaults if null)
	 * @param globalPrefix the global prefix for this repository as a slash separated String (defaults if null)
	 * @param An initial namespace (defaults to namespace stored in repository, or / if none)
	 * @throws RepositoryException if the policyFile, localName, or globalName are improperly formatted
	 * @param handle optional CCNHandle if caller wants to override the
	 * 	default connection/identity behavior of the repository -- this
	 * 	provides a KeyManager and handle for the repository to use to 
	 * 	obtain its keys and communicate with ccnd. If null, the repository
	 * 	will configure its own based on policy, or if none, create one
	 * 	using the executing user's defaults.
	 */
	public void initialize(String repositoryRoot, File policyFile, String localName, String globalPrefix,
				String namespace, CCNHandle handle) throws RepositoryException {
		
		Log.info(Log.FAC_REPO, "LogStructRepoStore.initialize()");
		
		// If a new policy file or namespace was requested from the command line, we don't use policy
		// that was already stored in the repo if there was any
		if (null != policyFile || null != namespace)
			_useStoredPolicy = false;
		PolicyXML pxml = null;
		boolean nameFromArgs = (null != localName);
		boolean globalFromArgs = (null != globalPrefix);
		if (null == localName)
			localName = LogStructRepoStoreProfile.DEFAULT_LOCAL_NAME;
		if (null == globalPrefix) 
			globalPrefix = LogStructRepoStoreProfile.DEFAULT_GLOBAL_NAME;
		pxml = startInitPolicy(policyFile, namespace);

		if (repositoryRoot == null) {
			throw new InvalidParameterException();
		} else {
			_repositoryRoot = repositoryRoot;
		}
		
		_repositoryFile = new File(_repositoryRoot);
		_repositoryFile.mkdirs();
		_repositoryMeta = _repositoryRoot + UserConfiguration.FILE_SEP + LogStructRepoStoreProfile.META_DIR;
		File metaDirFile = new File(_repositoryMeta);
		metaDirFile.mkdirs();
		if (Log.isLoggable(Log.FAC_REPO, Level.WARNING)){
			Log.warning(Log.FAC_REPO, "Starting repository; repository root is: {0}", _repositoryFile.getAbsolutePath());
		}
		
		// DKS -- we probably don't want to encrypt startWrites and other messages, 
		// as the repository doesn't likely have write privileges (or read privileges)
		// for data. Without a fully public-key system, have to go for unencrypted writes.
		// Have to determine the best way to handle this, for now disable access control
		// for the repository write side.
		SystemConfiguration.setAccessControlDisabled(true);
		
		// Build our handle
		if (null == handle) {
			// Load our keystore. Make one if none exists; use the
			// default kestore type and key algorithm.
			try {
				_km = 
					new BasicKeyManager(LogStructRepoStoreProfile.REPOSITORY_USER, 
							_repositoryRoot, null, LogStructRepoStoreProfile.KEYSTORE_FILE,
							null, LogStructRepoStoreProfile.REPOSITORY_KEYSTORE_ALIAS, 
							LogStructRepoStoreProfile.KEYSTORE_PASSWORD);
				_km.initialize();

				// Let's use our key manager as the default. That will make us less
				// prone to accidentally loading the user's key manager. If we close it more than
				// once, that's ok.
				KeyManager.setDefaultKeyManager(_km);
				
				if( Log.isLoggable(Log.FAC_REPO, Level.FINEST))
					Log.finest(Log.FAC_REPO, "Initialized repository key store.");
				
				handle = CCNHandle.open(_km);
				
				if( Log.isLoggable(Log.FAC_REPO, Level.FINEST))
					Log.finest(Log.FAC_REPO, "Opened repository handle.");
				
				// Serve our key using the localhost key discovery protocol
				ServiceDiscoveryProfile.publishLocalServiceKey(ServiceDiscoveryProfile.REPOSITORY_SERVICE_NAME,
						null, _km);

			} catch (ConfigurationException e) {
				Log.warning(Log.FAC_REPO, "ConfigurationException loading repository key store: " + e.getMessage());
				throw new RepositoryException("ConfigurationException loading repository key store!", e);
			} catch (IOException e) {
				Log.warning(Log.FAC_REPO, "IOException loading repository key store: " + e.getMessage());
				throw new RepositoryException("IOException loading repository key store!", e);
			} catch (InvalidKeyException e) {
				Log.warning(Log.FAC_REPO, "InvalidKeyException loading repository key store: " + e.getMessage());
				throw new RepositoryException("InvalidKeyException loading repository key store!", e);
			}
		}
		_handle = handle;

		// Internal initialization
		_files = new HashMap<Integer, RepoFile>();
		_currentFileIndex = createIndex();
		
		try {
			if (_currentFileIndex == 0) {
				_currentFileIndex = 1; // the index of a file we will actually write
				RepoFile rfile = new RepoFile();
				rfile.file = new File(_repositoryFile, LogStructRepoStoreProfile.CONTENT_FILE_PREFIX+"1");
				rfile.openFile = new RandomAccessFile(rfile.file, "rw");
				rfile.nextWritePos = 0;
				_files.put(new Integer(_currentFileIndex), rfile);
				_activeWriteFile = rfile;
			} else {
				RepoFile rfile = _files.get(new Integer(_currentFileIndex));
				long cursize = rfile.file.length();
				rfile.openFile = new RandomAccessFile(rfile.file, "rw");
				rfile.nextWritePos = cursize;
				_activeWriteFile = rfile;
			}
			
		} catch (FileNotFoundException e) {
			Log.warning(Log.FAC_REPO, "Error opening content output file index " + _currentFileIndex);
		}
			
		// Verify stored policy info
		// TODO - we shouldn't do this if the user has specified a policy file which already has
		// this information
		String version = checkFile(LogStructRepoStoreProfile.VERSION, CURRENT_VERSION, false);
		if (version != null && !version.trim().equals(CURRENT_VERSION))
			throw new RepositoryException("Bad repository version: " + version);

		String checkName = checkFile(LogStructRepoStoreProfile.REPO_LOCALNAME, localName, nameFromArgs);
		localName = checkName != null ? checkName : localName;
		pxml.setLocalName(localName);		
		
		checkName = checkFile(LogStructRepoStoreProfile.REPO_GLOBALPREFIX, globalPrefix, globalFromArgs);
		globalPrefix = checkName != null ? checkName : globalPrefix;
		if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
			Log.info(Log.FAC_REPO, "REPO: initializing repository: global prefix {0}, local name {1}", globalPrefix, localName);
		}
		try {
			pxml.setGlobalPrefix(globalPrefix);
			if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
				Log.info(Log.FAC_REPO, "REPO: initializing policy location: {0} for global prefix {1} and local name {2}", localName, globalPrefix,  localName);
			}
		} catch (MalformedContentNameStringException e2) {
			throw new RepositoryException(e2.getMessage());
		}
		
		_policy = new BasicPolicy();
		_policy.setPolicyXML(pxml);
		try {
			finishInitPolicy(globalPrefix, localName);
		} catch (MalformedContentNameStringException e) {
			throw new RepositoryException(e.getMessage());
		}
	}
	
	/**
	 * Write/rewrite the policy file if different from what we have now
	 * @throws RepositoryException 
	 */
	public void policyUpdate() throws RepositoryException {
		PolicyXML storedPxml = null;
		try {
			storedPxml = readPolicy(_policy.getGlobalPrefix());
			if (null != storedPxml && _useStoredPolicy)
				_policy.setPolicyXML(storedPxml);
		} catch (Exception e) {
			throw new RepositoryException(e.getMessage());
		}
		
		/**
		 * If there are differences we need to write the updated version
		 */
		PolicyXML pxml = _policy.getPolicyXML();
		if (null == storedPxml || !pxml.equals(storedPxml)) {
			ContentName policyName = BasicPolicy.getPolicyName(pxml.getGlobalPrefix());
			try {
				PolicyObject po = new PolicyObject(policyName, pxml, null, null, new RepositoryInternalFlowControl(this, _handle));
				po.save();
			} catch (IOException e) {
				throw new RepositoryException(e.getMessage());
			}
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
		if (null == _activeWriteFile) {
			Log.warning(Log.FAC_REPO, "Tried to save: {0}, presumably after repo shutdown", content.name());
			return null;
		}
		try {	
			NameEnumerationResponse ner = new NameEnumerationResponse();
			synchronized(_activeWriteFile) {
				assert(null != _activeWriteFile.openFile);
				FileRef ref = new FileRef();
				ref.id = Integer.parseInt(_activeWriteFile.file.getName().substring(LogStructRepoStoreProfile.CONTENT_FILE_PREFIX.length()));
				ref.offset = _activeWriteFile.nextWritePos;
				_activeWriteFile.openFile.seek(_activeWriteFile.nextWritePos);
				OutputStream os = new RandomAccessOutputStream(_activeWriteFile.openFile);
				content.encode(os);
				_activeWriteFile.nextWritePos = _activeWriteFile.openFile.getFilePointer();
				_index.insert(content, ref, System.currentTimeMillis(), this, ner);
				if (ner==null || ner.getPrefix()==null) {
					if (Log.isLoggable(Log.FAC_REPO, Level.FINE)) {
						Log.fine(Log.FAC_REPO, "new content did not trigger an interest flag");
					}
				} else {
					if (Log.isLoggable(Log.FAC_REPO, Level.FINE)) {
						Log.fine(Log.FAC_REPO, "new content was added where there was a name enumeration response interest flag");
					}
				}
				return ner;
			}
		} catch (ContentEncodingException e) {
			throw new RepositoryException("Failed to encode content: " + e.getMessage());
		} catch (IOException e) {
			throw new RepositoryException("Failed to write content: " + e.getMessage());
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
			RepoFile file = null;
			synchronized (_files) {
				file = _files.get(fref.id);
			}
			if (null == file)
				return null;
			synchronized (file) {
				if (null == file.openFile) {
					file.openFile = new RandomAccessFile(file.file, "r");
				}
				file.openFile.seek(fref.offset);
				ContentObject content = new ContentObject();
				InputStream is = new BufferedInputStream(new RandomAccessInputStream(file.openFile), 8192);
				content.decode(is);
				return content;
			}
		} catch (Exception e) {
			Log.warning(Log.FAC_REPO, "Can't get content: " + e);
			return null;
		}
	}
	
	/**
	 * Check/write files that contain meta data for the repo
	 * @throws RepositoryException
	 * @throws IOException 
	 * @throws ConfigurationException 
	 */
	private String checkFile(String fileName, String contents, boolean forceWrite) throws RepositoryException {
		File f = new File(_repositoryMeta, fileName);
		if (!forceWrite) {
			if (f.exists()) {
				FileInputStream fis = null;
				try {
					fis = new FileInputStream(f);
					byte[] buf = new byte[fis.available()];
					fis.read(buf);
					return new String(buf);
				} catch (FileNotFoundException e) {} // Can't happen
				  catch (IOException ioe) {
					  throw new RepositoryException(ioe.getMessage());
				  }
				  finally {
					  if (null != fis)
						try {
							fis.close();
						} catch (IOException e) {}
				  }
			}
		}
		if (f.exists())
			f.delete();
			FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(f);
			fos.write(contents.getBytes());
			fos.close();
		} catch (FileNotFoundException e1) {}  // Can't happen
		  catch (IOException ioe) {
			  throw new RepositoryException(ioe.getMessage());
		  }
		  finally {
			  if (null != fos)
				try {
					fos.close();
				} catch (IOException e) {}
		  }
		return null;
	}

	
	protected void dumpNames(int nodelen) {
		// Debug: dump names tree to file
		File namesFile = new File(_repositoryFile, LogStructRepoStoreProfile.DEBUG_TREEDUMP_FILE);
		try {
			if (Log.isLoggable(Log.FAC_REPO, Level.INFO)) {
				Log.info(Log.FAC_REPO, "Dumping names to " + namesFile.getAbsolutePath() + " (len " + nodelen + ")");
			}
			PrintStream namesOut = new PrintStream(namesFile);
			if (null != _index) {
				_index.dumpNamesTree(namesOut, nodelen);
			}
		} catch (FileNotFoundException ex) {
			Log.warning(Log.FAC_REPO, "Unable to dump names to " + namesFile.getAbsolutePath());
		}
	}

	/**
	 * Dump all names of data stored in the repository into a special file within the repository 
	 * on diagnostic request from higher level code
	 * 
	 * @param name "nametree" or "nametreewide" to decide whether to limit the printout length of components
	 */
	public boolean diagnostic(String name) {
		if (0 == name.compareToIgnoreCase(LogStructRepoStoreProfile.DIAG_NAMETREE)) {
			dumpNames(35);
			return true;
		} else if (0 == name.compareToIgnoreCase(LogStructRepoStoreProfile.DIAG_NAMETREEWIDE)) {
			dumpNames(-1);
			return true;
		}
		return false;
	}

	/**
	 * Cleanup on shutdown
	 */
	public void shutDown() {
		Log.info(Log.FAC_REPO, "LogStructRepoStore.shutdown()");
		
		// THis closes ResponsitoryStoreBase._handle
		super.shutDown();
		
		if (null != _km) {
			KeyManager.closeDefaultKeyManager();
		}
		
		if (null != _activeWriteFile && null != _activeWriteFile.openFile) {
			try {
				synchronized (_activeWriteFile) {
					_activeWriteFile.openFile.close();
					_activeWriteFile.openFile = null;
				}
			} catch (IOException e) {}
		}
		if (SystemConfiguration.checkDebugFlag(DEBUGGING_FLAGS.REPO_EXITDUMP)) {
			Log.warning(Log.FAC_REPO, "Debug flag ({0}) is set: dumping nametree now (on shutdown)", DEBUGGING_FLAGS.REPO_EXITDUMP.toString());
			dumpNames(-1);
		}
	}

	public Object getStatus(String type) {
		return type.equals(RepositoryStore.REPO_SIMPLE_STATUS_REQUEST) 
				? ((null == _activeWriteFile.openFile) ? null : "running") : null;
	}

	synchronized public boolean bulkImport(String name) throws RepositoryException {
		if (name.contains(UserConfiguration.FILE_SEP))
			throw new RepositoryException("Bulk import data can not contain pathnames");
		File file;
		synchronized (_bulkImportInProgress) {
			file = new File(_repositoryRoot + UserConfiguration.FILE_SEP + LogStructRepoStoreProfile.REPO_IMPORT_DIR + UserConfiguration.FILE_SEP + name);
			if (!file.exists()) {		
				// Is this due to a reexpressed interest for bulk import already in progress?
				if (_bulkImportInProgress.containsKey(name))
						return false;		
				throw new RepositoryException("File does not exist: " + file);
			}
			
			_bulkImportInProgress.put(name, name);
		}
		_currentFileIndex++;
		File repoFile = new File(_repositoryFile, LogStructRepoStoreProfile.CONTENT_FILE_PREFIX + _currentFileIndex);
		if (!file.renameTo(repoFile))
			throw new RepositoryException("Can not rename file: " + file);
		try {
			createIndex(LogStructRepoStoreProfile.CONTENT_FILE_PREFIX + _currentFileIndex, _currentFileIndex, true);
		} catch (RepositoryException re) {
			// The seemingly logical thing to do would be to verify the data for errors first and then submit it if it
			// was OK. But that would require 2 passes through the data in the mainline case in which the data is good
			// so instead we rename the file back if its bad.
			repoFile.renameTo(file);
			_bulkImportInProgress.remove(name);
			throw re;
		}
		_bulkImportInProgress.remove(name);
		return true;
	}
}
