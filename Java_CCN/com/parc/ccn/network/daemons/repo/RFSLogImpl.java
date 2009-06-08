package com.parc.ccn.network.daemons.repo;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.network.daemons.repo.ContentTree.ContentFileRef;
import com.parc.security.Library;
import com.sun.media.jai.codec.FileSeekableStream;

public class RFSLogImpl implements Repository, ContentTree.ContentGetter {

	public final static String CURRENT_VERSION = "1.4";

	private static String DEFAULT_LOCAL_NAME = "Repository";
	private static String DEFAULT_GLOBAL_NAME = "/parc.com/csl/ccn/Repos";
	private static String CONTENT_FILE_PREFIX = "repoFile";

	protected String _repositoryRoot = null;
	protected File _repositoryFile;
	protected RepositoryInfo _info = null;
	protected ArrayList<ContentName> _nameSpace = new ArrayList<ContentName>();

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
		// TODO Auto-generated method stub
		return false;
	}

	public ContentObject getContent(Interest interest)
			throws RepositoryException {
		return _index.get(interest, this);
	}

	public ArrayList<ContentName> getNamesWithPrefix(Interest i) {
		// TODO Auto-generated method stub
		return null;
	}

	public ArrayList<ContentName> getNamespace() {
		// TODO Auto-generated method stub
		return null;
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

	public String getUsage() {
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
						InputStream is = new RandomAccessInputStream(rfile.openFile);
						while (true) {
							ContentFileRef ref = _index.new ContentFileRef();
							ref.id = index.intValue();
							ref.offset = rfile.openFile.getFilePointer();
							ContentObject tmp = new ContentObject();
							try {
								tmp.decode(is);
							} catch (XMLStreamException e) {
								// Failed to decode, must be end of this one
								rfile.openFile.close();
								rfile.openFile = null;
								break;
							}
							_index.insert(tmp, ref);
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
//		_locker = new RFSLocks(_repositoryRoot + File.separator + META_DIR);
//		
//		String version = checkFile(VERSION, CURRENT_VERSION, false);
//		if (version != null && !version.trim().equals(CURRENT_VERSION))
//			throw new RepositoryException("Bad repository version: " + version);
		
//		String checkName = checkFile(REPO_LOCALNAME, localName, nameFromArgs);
//		localName = checkName != null ? checkName : localName;
//		
//		checkName = checkFile(REPO_GLOBALPREFIX, globalPrefix, globalFromArgs);
//		globalPrefix = checkName != null ? checkName : globalPrefix;
		
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
	
		// Internal initialization
		_files = new HashMap<Integer, RepoFile>();
		int maxFileIndex = createIndex();
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
			}
		} catch (FileNotFoundException e) {
			Library.logger().warning("Error opening content output file index " + maxFileIndex);
		}
		
		return outArgs;
	}

	public void saveContent(ContentObject content) throws RepositoryException {
		try {
			synchronized(_activeWriteFile) {
				assert(null != _activeWriteFile.openFile);
				_activeWriteFile.openFile.seek(_activeWriteFile.nextWritePos);
				OutputStream os = new RandomAccessOutputStream(_activeWriteFile.openFile);
				content.encode(os);
				_activeWriteFile.nextWritePos = _activeWriteFile.openFile.getFilePointer();
			}
		} catch (IOException e) {
			throw new RepositoryException("Failed to write content: " + e.getMessage());
		} catch (XMLStreamException e) {
			throw new RepositoryException("Failed to encode content: " + e.getMessage());
		}
	}

	public void setPolicy(Policy policy) {
		// TODO Auto-generated method stub

	}

	public ContentObject get(ContentFileRef ref) {
		try {
			RepoFile file = _files.get(ref.id);
			synchronized (file) {
				if (null == file.openFile) {
					file.openFile = new RandomAccessFile(file.file, "r");
				}
				file.openFile.seek(ref.offset);
				ContentObject content = new ContentObject();
				InputStream is = new RandomAccessInputStream(file.openFile);
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

}
