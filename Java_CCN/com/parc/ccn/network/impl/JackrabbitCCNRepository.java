package com.parc.ccn.network.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.logging.Level;

import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.Repository;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;
import javax.jcr.ValueFormatException;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.jcr.observation.Event;
import javax.jcr.observation.ObservationManager;
import javax.jcr.query.InvalidQueryException;
import javax.jcr.query.Query;
import javax.jcr.version.VersionException;
import javax.jmdns.ServiceInfo;
import javax.xml.stream.XMLStreamException;

import org.apache.jackrabbit.core.TransientRepository;
import org.apache.jackrabbit.rmi.client.ClientRepositoryFactory;
import org.apache.jackrabbit.rmi.remote.RemoteRepository;
import org.apache.jackrabbit.rmi.server.ServerAdapterFactory;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.data.util.XMLHelper;
import com.parc.ccn.network.CCNRepository;
import com.parc.ccn.network.CCNRepositoryFactory;
import com.parc.ccn.network.GenericCCNRepository;
import com.parc.ccn.network.discovery.CCNDiscovery;

/**
 * Jackrabbit does have a way of linking directly
 * to files. If we need efficiency, we could do that.
 * @author smetters
 *
 */
public class JackrabbitCCNRepository extends GenericCCNRepository implements CCNRepository {

	public static final int SERVER_PORT = 1101;
	public static final String PROTOCOL_TYPE = "rmi";
	public static final String SERVER_RMI_NAME = "jackrabbit";

	protected static String BASE64_MARKER = "_b_";
	protected static String LEADING_NUMBER_MARKER = "_n_";
	
	/**
	 * Where do we store stuff in Jackrabbit. 
	 */
	public static final String CONTENT_PROPERTY = "CONTENT";

	/**
	 * The bits of the authenticator, exploded.
	 */
	public static final String PUBLISHER_PROPERTY = "PUBLISHER";
	public static final String PUBLISHER_TYPE_PROPERTY = "PUBLISHER_TYPE";
	public static final String TIMESTAMP_PROPERTY = "TIMESTAMP";
	public static final String TYPE_PROPERTY = "CONTENT_TYPE";
	public static final String HASH_PROPERTY = "CONTENT_HASH_ELEMENT";
	public static final String KEY_LOCATOR_PROPERTY = "KEY_LOCATOR";
	public static final String SIGNATURE_PROPERTY = "SIGNATURE";
	protected static JackrabbitCCNRepository _theNetwork = null;

	protected Repository _repository;
	protected Session _session;
	
	/** 
	 * The stock RMI interface directly to a jackrabbit.
	 */
	public static final String JACKRABBIT_RMI_SERVICE_TYPE = "_ccn._jackrabbit_rmi";
	public static final String JACKRABBIT_RMI_SERVICE_NAME = "Jackrabbit";

	static {
		CCNRepositoryFactory.registerRepositoryType(JACKRABBIT_RMI_SERVICE_TYPE, JACKRABBIT_RMI_SERVICE_NAME, JackrabbitCCNRepository.class);
		CCNDiscovery.registerServiceType(JACKRABBIT_RMI_SERVICE_TYPE);
	}

	/**
	 * Use someone else's repository.
	 * @throws MalformedURLException 
	 */
	public JackrabbitCCNRepository(String host, int port) 
			throws ClassCastException, RemoteException, 
				NotBoundException, MalformedURLException {
		this(new ClientRepositoryFactory().getRepository(constructURL(PROTOCOL_TYPE, SERVER_RMI_NAME, host, port)));
		_info = CCNDiscovery.getServiceInfo(JACKRABBIT_RMI_SERVICE_TYPE, host, port);
	}
	
	/**
	 * Connect to a remote repository discovered through mDNS.
	 */
	public JackrabbitCCNRepository(ServiceInfo info) throws MalformedURLException, ClassCastException, RemoteException, NotBoundException {
		this(info.getHostAddress(), info.getPort());
		_info = info;
	}

	/**
	 * Start our own repository.
	 * @param repository
	 */
	public JackrabbitCCNRepository(int port) {
		try {
			_repository = createLocalRepository(port);
			_info = 
				CCNDiscovery.getServiceInfo(
						JACKRABBIT_RMI_SERVICE_TYPE, null, port);
			login();
			advertiseServer(port);
			Library.logger().info("Started Jackrabbit repository on port: " + port);
		} catch (Exception e) {
			Library.logger().warning("Exception attempting to create our repository or log into it.");
			Library.logStackTrace(Level.WARNING, e);
		}		

		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() { onShutdown(); }
		});
	}
	
	/**
	 * Start our own local repository on a standard port.
	 * Really only want to call this once per port per VM.
	 */
	public JackrabbitCCNRepository() {
		// TODO DKS: make constructor protected, use factory function
		// to get local Jackrabbit so we don't try to make
		// more than one.
		this(SERVER_PORT);
	}

	/**
	 * Internal constructor for using someone else's repository.
	 * @param repository
	 */
	protected JackrabbitCCNRepository(Repository repository) {
		try {
			_repository = repository;
			login();
		} catch (Exception e) {
			Library.logger().warning("Exception attempting to log into repository.");
			Library.logStackTrace(Level.WARNING, e);
		}		

		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() { onShutdown(); }
		});
	}

	/**
	 * Attempt to connect to existing Jackrabbit assumed
	 * to be on this machine, listening on port.
	 * @param port
	 * @return
	 * @throws NotBoundException 
	 * @throws UnknownHostException 
	 * @throws RemoteException 
	 * @throws ClassCastException 
	 * @throws MalformedURLException 
	 */
	public static JackrabbitCCNRepository getLocalJackrabbitRepository(int port) 
		throws RemoteException {
		// Skip discovery. See if there is one already
		// running on this machine in the usual place,
		// and return a wrapper around that.
		try {
			return new JackrabbitCCNRepository(
					SystemConfiguration.getLocalHost(),
					port);
		} catch (ClassCastException e) {
			Library.logger().warning("This should not happen: ClassCastException connecting to local Jackrabbit repository: " + e.getMessage());
			Library.warningStackTrace(e);
			throw new RemoteException("This should not happen: ClassCastException connecting to local Jackrabbit repository: " + e.getMessage());
		} catch (MalformedURLException e) {
			Library.logger().warning("This should not happen: MalformedURLException on a system-constructed URL! " + e.getMessage());
			Library.warningStackTrace(e);
			throw new RemoteException("This should not happen: MalformedURLException on a system-constructed URL! " + e.getMessage());
		/*} catch (UnknownHostException e) {
			Library.logger().warning("UnknownHostException connecting to localhost! " + e.getMessage());
			Library.warningStackTrace(e);
			throw new RemoteException("UnknownHostException connecting to localhost! " + e.getMessage());
		*/} catch (NotBoundException e) {
			Library.logger().warning("NotBoundException: no Jackrabbit repository available.");
			return null;
		}
	}
		
	/**
	 * Connect to an existing local Jackrabbit on the 
	 * default port.
	 **/
	public static JackrabbitCCNRepository getLocalJackrabbitRepository() 
		throws RemoteException {
		return getLocalJackrabbitRepository(SERVER_PORT);
	}
	
	public CompleteName put(ContentName name, ContentAuthenticator authenticator, byte[] content) throws IOException {

		if (null == name) {
			Library.logger().warning("CCN:put: name cannot be null.");
			throw new IllegalArgumentException("CCN:put: name cannot be null.");
		}

		// first, make sure all the nodes exist
		Node n;
		try {
			n = _session.getRootNode();
			// now, make sure the root node is versionable				
			if ((!n.isNodeType("mix:versionable")) || (!n.isNodeType("mix:referenceable"))) {
				if (!n.isNodeType("mix:versionable")) {
					n.addMixin("mix:versionable");
				}
				if (!n.isNodeType("mix:referenceable")) {
					n.addMixin("mix:referenceable");
				}
			
				_session.save();
			}

		} catch (RepositoryException e) {
			Library.logger().warning("JackrabbitCCNRepository: cannot find root node!");
			Library.logStackTrace(Level.WARNING, e);
			throw new IOException(e.getMessage());
		}

		int i;
		for (i = 0; i < name.count()-1; i++) {
			
			if (name.component(i).length == 0) continue;

			try {
			
				n = n.getNode(nameComponentToString(name.component(i)));

			} catch (PathNotFoundException e) {
				// Add an intermediate name level
				try {
					
					n = addSubNode(n, name.component(i));
					
				} catch (RepositoryException e1) {
					Library.logger().warning("JackrabbitCCNRepository: can't add subNode.");
					Library.logStackTrace(Level.WARNING, e);
					throw new IOException(e.getMessage());
				}

			} catch (RepositoryException e) {
				Library.logger().warning("JackrabbitCCNRepository: unexpected RepositoryException in put.");
				Library.logStackTrace(Level.WARNING, e);
				throw new IOException(e.getMessage());
			}
		}

		try {
			// Now we're down to the leaf node. Don't
			// add it if we already have it.
			n = addLeafNode(n, name.component(i), authenticator, content);
			
			Library.logger().info("Adding node: " + n.getCorrespondingNodePath(_session.getWorkspace().getName()));

		} catch (RepositoryException e) {
			throw new IOException(e.getMessage());
		}
		
		return new CompleteName(name, authenticator);
	}

	/**
	 * Adds a child node with a new name level.
	 * @return
	 * @throws RepositoryException 
	 */
	protected Node addSubNode(Node parent, byte [] name) throws RepositoryException {
		// TODO DKS: do we need to check out parent?
		Node n = null;
		String componentName = nameComponentToString(name);
		try {
			try {
				// DKS: to make file nodes: add file name, "nt:file"
				// 
				// DKS TODO: trouble with checkouts, root not versioned
				// parent.checkout();
				
				n = parent.addNode(componentName,"nt:unstructured");
				// now, make sure the leaf node is versionable				
				if (!n.isNodeType("mix:versionable")) {
					n.addMixin("mix:versionable");
				}
				if (!n.isNodeType("mix:referenceable")) {
					n.addMixin("mix:referenceable");
				}
				
				_session.save();
				// parent.checkin();

			} catch (NoSuchNodeTypeException e) {
				Library.logger().warning("Unexpected error: can't set built-in mixin types on a node.");
				Library.logStackTrace(Level.WARNING, e);
				throw new RuntimeException(e);
			} catch (ItemExistsException e) {
				Library.logger().warning("Configuration error: cannot add child of parent: " +
							parent.getPath() + " with name " + componentName +
							" because one already exists. But all parents should allow matching children.");
				Library.logStackTrace(Level.WARNING, e);
			} catch (PathNotFoundException e) {
				Library.logger().warning("Unexpected error: known parent " +
						parent.getPath() + " gives a path not found exception when adding child " +
						componentName);
				Library.logStackTrace(Level.WARNING, e);
			} catch (VersionException e) {
				Library.logger().warning("Unexpected error: known parent " +
						parent.getPath() + " gives a version exception when adding child " +
						componentName);
				Library.logStackTrace(Level.WARNING, e);
			} catch (ConstraintViolationException e) {
				Library.logger().warning("Unexpected error: constraint violation exception creating standard subnode.");
				Library.logStackTrace(Level.WARNING, e);
				throw new RuntimeException(e);
			} catch (LockException e) {
				Library.logger().warning("Unexpected error: known parent " +
						parent.getPath() + " gives a lock exception when adding child " +
						componentName);
				Library.logStackTrace(Level.WARNING, e);
			}
		} catch (RepositoryException e) {
			// parent.getPath() throws a RepositoryException
			Library.logger().warning("Unexpected error: known parent " +
					parent.getPath() + " gives a repository exception when adding child " +
					componentName);
			Library.logStackTrace(Level.WARNING, e);
			throw e;
		}
	
		return n;

//		resNode.setProperty ("jcr:mimeType", mimeType);
//     resNode.setProperty ("jcr:encoding", encoding);
//     resNode.setProperty ("jcr:data", new FileInputStream (file));
//     Calendar lastModified = Calendar.getInstance ();
//     lastModified.setTimeInMillis (file.lastModified ());
//     resNode.setProperty ("jcr:lastModified", lastModified);

	}

	/**
	 * We might want to have more than one signer adding data
	 * under the same name. Jackrabbit handles that.
	 * @param parent
	 * @param name
	 * @param authenticator
	 * @param content
	 * @return
	 * @throws RepositoryException
	 */
	protected Node addLeafNode(Node parent, byte [] name, ContentAuthenticator authenticator, byte[] content) throws RepositoryException {
		// TODO DKS: should refuse to insert exact dupes by complete
		// name. As long as sign timestamp, that shouldn't 
		// happen, but make sure.
		String componentName = nameComponentToString(name);		
		NodeIterator matchingChildren = parent.getNodes(componentName);
		Node thisChild = null;
		if ((null != matchingChildren) && (matchingChildren.getSize() > 0)) {
			while (matchingChildren.hasNext()) {
				thisChild = matchingChildren.nextNode();
				if (null == thisChild)
					continue;
				// If the publisherID is not the same, the
				// children do not match.
				PublisherID publisher = getPublisherID(thisChild);
				
				if (null != publisher) {
					if (publisher.equals(authenticator.publisher())) {
						Library.logger().info("Adding node with same name and publisher.");
						
						// We have nodes with the same name. 
						// If the signatures match, this data is identical
						// and we just return that node.
						byte [] signature = null;
						try {
							signature = getSignature(thisChild);
						} catch (IOException e) {
							Library.logger().info("IOException in getSignature: " + e.getMessage());
							throw new RepositoryException(e);
						}
					
						if (Arrays.equals(signature, authenticator.signature())) {
							Library.logger().info("Adding node with same signature, just returning previous version.");
							return thisChild;
						} else {
							
							// if the signatures don't match, but the content digest
							// and publisher ID do match, it could be an attempt
							// to update content -- timestamp is different.
							// if those are the same, and all other metadata
							// is the same, count it as updated and just
							// update timestamp and signature
							// 
							// WARNING: always verify signature before this
							byte [] contentDigest = null;
							try {
								contentDigest = getContentDigest(thisChild);
							} catch (IOException e) {
								Library.logger().info("IOException in getContentDigest: " + e.getMessage());
								throw new RepositoryException(e);
							}
							if (Arrays.equals(contentDigest, authenticator.contentDigest())) {
								Library.logger().info("Adding node with same content, check.");
								// timestamps could be different
								// if type is the same, assume this is just
								// an attempt to re-insert the same content
								// and update just the relevant bits
								ContentAuthenticator.ContentType type = ContentAuthenticator.nameToType(thisChild.getProperty(TYPE_PROPERTY).getString());
								if (type == authenticator.type()) {
									// same publisher, name, type
									Library.logger().info("Publisher adding node of same name and type. Verifying signatures.");
									
									// DKS TODO: verify signatures, and if they both match, update auth
									// info to reflect this new node
								}
							}
						}
					}
				}
			}
		}

		// TODO: DKS -- should we check out parent?
		Node n = addSubNode(parent, name);

		// TODO DKS: do we want to avoid dupes by appending
		// signature, content hash, or publisher ID to name
		// used internally by Jackrabbit (rather than storing
		// it as a property)? Would make the XPath more complicated,
		// but would keep us from having to search all the 
		// nodes to make sure there wasn't a duplicate before
		// insertion.
		// Only thing that would work would be signature, and
		// that wouldn't get reinsertions of same content
		// (timestamp changes).  Adding content digest would
		// help.
		// DKS: TODO trouble with checkouts, root not versioned
		// n.checkout();
		
		addContent(n, content);
		addAuthenticationInfo(n, authenticator);

		_session.save();
		// n.checkin();
		return n;
	}
	
	protected void addContent(Node n, byte [] content) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
		ByteArrayInputStream bai = new ByteArrayInputStream(content);
		n.setProperty(CONTENT_PROPERTY, bai);
	}
	
	boolean hasContent(Node n) throws RepositoryException {
		return n.hasProperty(CONTENT_PROPERTY);
	}
	
	protected static byte [] getContent(Node n) throws RepositoryException, IOException {
		return getBinaryProperty(n, CONTENT_PROPERTY);
	}

	protected static byte[] getBinaryProperty(Node n, String property) throws RepositoryException, IOException {

		InputStream in = n.getProperty(property).getStream();

		int contentLength = (int)n.getProperty(property).getLength();

		byte[] result = new byte[contentLength];
		int bytesToRead = contentLength;
		while(bytesToRead > 0) {
			int len = in.read(result, contentLength - bytesToRead, bytesToRead);
			if(len < 0) {
				Library.logger().warning("JackrabbitCCNRepository: Error reading property " + property + " value from server.");
				throw new IOException("JackrabbitCCNRepository: Error reading " + property + " value from server.");
			}
			bytesToRead -= len;
		}
		return result;
	}

	protected void addAuthenticationInfo(Node n, ContentAuthenticator authenticator) throws ValueFormatException, VersionException, LockException, ConstraintViolationException, RepositoryException {
		n.setProperty(PUBLISHER_PROPERTY, publisherToString(authenticator.publisher()));
		n.setProperty(PUBLISHER_TYPE_PROPERTY, PublisherID.typeToName(authenticator.publisherType()));
		Library.logger().info("Adding authentication info with type of " + authenticator.typeName() + " original type " + authenticator.type());
		n.setProperty(TYPE_PROPERTY, authenticator.typeName());
		n.setProperty(TIMESTAMP_PROPERTY, authenticator.timestamp().toString());
		ByteArrayInputStream hai = new ByteArrayInputStream(authenticator.contentDigest());
		n.setProperty(HASH_PROPERTY, hai);
		
		ByteArrayInputStream kli = new ByteArrayInputStream(authenticator.keyLocator().getEncoded());
		n.setProperty(KEY_LOCATOR_PROPERTY, kli);	

		ByteArrayInputStream sai = new ByteArrayInputStream(authenticator.signature());
		n.setProperty(SIGNATURE_PROPERTY, sai);
	}
	
	protected ContentAuthenticator getAuthenticationInfo(Node n) throws ValueFormatException, PathNotFoundException, RepositoryException, IOException {
		// Have to distinguish path and content nodes.
		
		PublisherID publisherID = getPublisherID(n);
		
		Property typeProperty = null;
		String propertyString = null;
		try {
			typeProperty = n.getProperty(TYPE_PROPERTY);
			if (null == typeProperty) {
				Library.logger().warning("No type property available on node: " + n.getPath());
				throw new ValueFormatException("No type property on node: " + n.getPath());
			}
			propertyString = typeProperty.getString();
		} catch (PathNotFoundException b) {
			Library.logger().warning("Error: cannot get content type from node: " + n.getPath());
		}
		
		ContentAuthenticator.ContentType type = ContentAuthenticator.nameToType(propertyString);
		Timestamp timestamp = Timestamp.valueOf(n.getProperty(TIMESTAMP_PROPERTY).getString());
		
		byte [] hash = getContentDigest(n);
		byte [] encodedKeyLocator = getBinaryProperty(n, KEY_LOCATOR_PROPERTY);
	
		KeyLocator loc = null;
		try {
			loc = new KeyLocator(encodedKeyLocator);
		} catch (XMLStreamException e) {
			Library.logger().log(Level.WARNING, "This should not happen: cannot retrieve and decode KeyLocator value we encoded and stored.");
			throw new ValueFormatException(e);
		}
		byte [] signature = getBinaryProperty(n, SIGNATURE_PROPERTY);
		
		ContentAuthenticator auth = new ContentAuthenticator(publisherID,
															 timestamp, type,
															 hash, loc, signature);		
		return auth;
	}
		
	/**
	 * Get immediate results to a query.
	 * DKS: Caution required to make sure that the idea of
	 * what matches here is the same as the one in corresponding version in 
	 * CCNQueryDescriptor. 
	 * @param query
	 * @return
	 * @throws IOException 
	 * @throws RepositoryException 
	 * @throws InvalidQueryException 
	 */
	public ArrayList<ContentObject> get(ContentName name, ContentAuthenticator authenticator) throws IOException {

		ArrayList<ContentObject> objects = new ArrayList<ContentObject>();

		try {
			// Strips trailing '*' if there is one.
			String queryString = getQueryString(name, authenticator);
			// Might not need this if query string bakes it in.

			Query q = null;
			try {
				q = _session.getWorkspace().getQueryManager().createQuery(queryString, Query.XPATH);
			} catch (InvalidQueryException e) {
				Library.logger().warning("Invalid query string: " + queryString);
				Library.logger().warning("Exception: " + e.getClass().getName() + " m: " + e.getMessage());
				throw new IOException("Exception: " + e.getClass().getName() + " m: " + e.getMessage());
			}

			NodeIterator iter = q.execute().getNodes();
			while (iter.hasNext()) {
				Node node = (Node) iter.next();
				if (isCCNNode(node)) {
					// DKS TODO: this is a temporary hack to mark CCN nodes.
					// either need better query to only pull these out,
					// which might need a different node type
					objects.add(getContentObject(node));
				}
			}
			Library.logger().warning("Query returned " + iter.getSize() + " results, of which " + objects.size() + " were CCN nodes.");
		} catch (RepositoryException e) {
			Library.logger().warning("Invalid query or problem executing get: " + e.getMessage());
			Library.warningStackTrace(e);
			throw new IOException("Invalid query or problem executing get: " + e.getMessage());
		}
		return objects;
	}
	
	protected boolean isCCNNode(Node node) throws RepositoryException {
		// Slight hack, might want to use node types.
		return node.hasProperty(PUBLISHER_PROPERTY);
	}
	
	protected String getQueryString(ContentName name, ContentAuthenticator authenticator) {
		// TODO: DKS: make Xpath match full query including whatever
		// authentication info is specified by querier
		// nameToPath already strips trailing recursive bits
		// This gets us to the root of the name we want
		StringBuffer queryStringBuf = 
			new StringBuffer("/jcr:root" + nameToPath(name));
		if (isRecursiveQuery(name)) {
			// the two slashes says go down any number of levels
			queryStringBuf.append("//*");
		}
		// DKS: TODO: Add a requirement to make it a valid CCN node 
		// right now filtering results upstream
		// queryStringBuf.append("[@" + PUBLISHER_PROPERTY + "!= ''");
		Library.logger().info("Querying for name: " + name);
		Library.logger().info("Query is: " + queryStringBuf.toString());
		return queryStringBuf.toString();
	}
	
    protected static boolean isRecursiveQuery(ContentName name) {
    	if (Arrays.equals(name.component(name.count()-1), 
				CCNQueryDescriptor.RECURSIVE_POSTFIX_BYTES)) {
			return true;
		}
		return false;
	}

	@Override
	public CCNQueryDescriptor expressInterest(ContentName name, ContentAuthenticator authenticator, 
			CCNQueryListener callbackListener) throws IOException {

		int events = Event.NODE_ADDED | Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED | Event.PROPERTY_REMOVED;
		JackrabbitEventListener el = new JackrabbitEventListener(this, callbackListener, events);
		JackrabbitCCNQueryDescriptor descriptor = new JackrabbitCCNQueryDescriptor(name, authenticator, el, callbackListener);
		startListening(descriptor);
		return descriptor;
	}

	@Override
	public void cancelInterest(CCNQueryDescriptor query) throws IOException {
		try {
			ObservationManager o = _session.getWorkspace().getObservationManager();
			JackrabbitCCNQueryDescriptor jcqd = (JackrabbitCCNQueryDescriptor)query;
			
			JackrabbitEventListener el = jcqd.jackrabbitListener();
			if (null == el)
				return;
			o.removeEventListener(el);
			
			// Notify listener that queries were canceled.
			el.queryListener().queryCanceled(query);
		
		} catch (RepositoryException e) {
			Library.logger().warning("Exception canceling interest in: " + query.name());
			throw new IOException(e.getMessage());
		}
	}
	
	private void startListening(JackrabbitCCNQueryDescriptor jcqd) throws IOException {
		try {
			// Jackrabbit has a robust observation interface.
			// It will automatically handle recursion for us, as well
			// as filtering events generated by this session. So we can
			// avoid being notified for events generated by our own puts.
			ObservationManager o = _session.getWorkspace().getObservationManager();
			o.addEventListener(jcqd.jackrabbitListener(), jcqd.jackrabbitListener().events(), 
							   nameToPath(jcqd.name().name()), 
							   jcqd.recursive(), // isDeep -- do we pull only this node, or 
							   					 // tree under it
							   null, null, 
							   true); // noLocal -- don't pull our own
									  // events
		} catch (RepositoryException e) {
			Library.logger().warning("Exception starting to listen for events on path: " + jcqd.name().name());
			Library.warningStackTrace(e);
			throw new IOException(e.getMessage());
		}
		
		// Now tell them about the matches that already exist
		// Need to filter -- the eventing interface only selects
		// based on name; the listener might have other criteria.
		// This is where we check those.
		// DKS: could rely on listener to do this...
		ArrayList<CompleteName> currentMatches = 
				enumerate(jcqd.name());
		Iterator<CompleteName> it = currentMatches.iterator();
		CompleteName thisName = null;
		while (it.hasNext()) {
			thisName = it.next();
			if (!jcqd.jackrabbitListener().queryListener().matchesQuery(thisName)) {
				it.remove(); // only way to remove from an in-use iterator safely
			}
		}
		jcqd.jackrabbitListener().queryListener().handleResults(currentMatches);
			
		return;		
	}

	// Package
	Node getNode(String path) throws PathNotFoundException, RepositoryException {
		return (Node)_session.getItem(path);
	}
	
	void remove(Node node) {
		// TODO: DKS need to check out parent, or its nearest versionable
		// ancestor, remove node (which removes subtree), and
		// then do a save and presumably a checkin on parent.
	}
	
	ContentName getName(Node n) throws RepositoryException {
		return parsePath(n.getPath());
	}
	
	CompleteName getCompleteName(Node n) throws IOException {
		ContentName name = null;;
		ContentAuthenticator authenticator = null;
		try {
			name = getName(n);
			authenticator = getAuthenticationInfo(n);
			
		} catch (RepositoryException e) {
			Library.logger().warning("Repository exception extracting content from node: " + n.toString() + ": " + e.getMessage());
			Library.logStackTrace(Level.WARNING, e);
			throw new IOException("Repository exception extracting content from node: " + n.toString() + ": " + e.getMessage());
		}
		return new CompleteName(name, authenticator);
	}
	
	ContentObject getContentObject(Node n) throws IOException {
		CompleteName name = null;;
		byte [] content = null;
		try {
			name = getCompleteName(n);
			content = getContent(n);
			
		} catch (RepositoryException e) {
			Library.logger().warning("Repository exception extracting content from node: " + n.toString() + ": " + e.getMessage());
			Library.logStackTrace(Level.WARNING, e);
			throw new IOException("Repository exception extracting content from node: " + n.toString() + ": " + e.getMessage());
		}
		return new ContentObject(name, content);
	}

	protected PublisherID getPublisherID(Node n) throws ValueFormatException, RepositoryException {
		String strPublisher = null;
		try {
			strPublisher = n.getProperty(PUBLISHER_PROPERTY).getString();
		} catch (PathNotFoundException e) {
			// no such property -- no publisher set
			return null;
		}
		byte [] publisherID = stringToByte(strPublisher);
		String publisherType = n.getProperty(PUBLISHER_TYPE_PROPERTY).getString();
		return new PublisherID(publisherID, 
							   PublisherID.nameToType(publisherType));
	}
	
	protected byte [] getSignature(Node n) throws RepositoryException, IOException {
		
		return getBinaryProperty(n, SIGNATURE_PROPERTY);
	}
	
	protected byte [] getContentDigest(Node n) throws RepositoryException, IOException {
		
		return getBinaryProperty(n, HASH_PROPERTY);
	}
	
	public void login() throws IOException {
		SimpleCredentials sc = new SimpleCredentials(InetAddress.getLocalHost().getHostAddress(), "".toCharArray());
		try {
			_session = _repository.login(sc);
		} catch (RepositoryException e) {
			Library.logger().warning("Exception logging into Jackrabbit CCN repository: " + e.getMessage());
			Library.logStackTrace(Level.WARNING, e);
			throw new IOException("Exception logging into Jackrabbit CCN repository: " + e.getMessage());
		}
	}

	public void login(String user, String password) throws IOException {
		login();
	}

	public void logout() {
		_session.logout();
		_session = null;
	}

	public void disconnect() {
		logout();
	}
	
	public void reconnect() {
		try {
			login();
			// resubscribeAll();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}	

	protected void onShutdown() {
		try {
			if (_session != null) _session.save();
		} catch (RepositoryException e) {
			Library.logger().warning("Exception attempting to save Jackrabbit session!");
			Library.logStackTrace(Level.WARNING, e);
		}
		if (_session != null) _session.logout();
	}

	/**
	 * Really really only want to do this once per port per
	 * vm.
	 * @param port
	 * @return
	 * @throws IOException
	 */
	protected static Repository createLocalRepository(int port) throws IOException {
		Library.logger().info("Creating local repository on port : " + port);
		Repository repository = new TransientRepository();
		ServerAdapterFactory factory = new ServerAdapterFactory();
		RemoteRepository remote = factory.getRemoteRepository(repository);
		Library.logger().info("Created transient repository.");
		Registry reg = null;
		try {
			reg = LocateRegistry.createRegistry(port);
		} catch (Exception e) {
			Library.logger().info("Cannot create RMI registry. Must already exist");
			reg = LocateRegistry.getRegistry(port); // this always works, but gets unhappy
						// if registry not previously created
		}
		reg.rebind(SERVER_RMI_NAME, remote);
		Library.logger().info("Started Jackrabbit server on port: " + port);
	
		return repository;
	}
	
	public void shutdown() { // turn this one off
		Library.logger().info("Shutting down Jackrabbit repository.");
		onShutdown();
	}

	protected static void advertiseServer(int port) throws IOException {
		GenericCCNRepository.advertiseServer(JACKRABBIT_RMI_SERVICE_TYPE, port);
	}

	public static CCNRepository connect(ServiceInfo info) throws MalformedURLException, ClassCastException, RemoteException, NotBoundException {
		return new JackrabbitCCNRepository(info.getHostAddress(), info.getPort());		
	}
		
	
	/**
	 * CCNs know about names as sequences of binary components. 
	 * Jackrabbit thinks about names as (Java) strings, i.e. unicode.
	 * 
	 * @param component
	 * @return
	 */
	protected static String nameComponentToString(byte [] component) {
		// Instead of using byteToString, use ContentName's
		// componentPrint, which will get us human-readable
		// versions of much stuff.
		String str = ContentName.componentPrint(component);
		
		// Technically before applying any of these quoting
		// tricks, we need to make sure that the component
		// doesn't already include the quoting strings...
		
		// If componentPrint decides this was a binary string,
		// it will quote the bytes with %'s, which isn't
		// legal for jackrabbit. We need to take those
		// binary components, turn them back into bytes,
		// and base64 them. We then need to prefix them
		// with something recognizable.
		if (str.contains("%")) {
			str = BASE64_MARKER + XMLHelper.encodeElement(component);
			return str;
		} 
		
		// DKS -- TODO: must to real JSR quoting. This is
		// too weird a mix of URL quoting and JSR stuff.
		// Jackrabbit will insert nodes with +'s for spaces,
		// but won't query for them.
		if (str.contains("+")) {
			str = str.replace("+", "_x0020_");
		}
		
		if (Character.isDigit(str.charAt(0))) {
			// Then deal with leading integers, which XPath
			// can't handle. Need to quote them with a
			// quote character that we can can recognizably
			// remove. 
			str = LEADING_NUMBER_MARKER + str;
		}
		
		return str;
	}
	
	/**
	 * Undo any quoting we need to do above. In particular,
	 * XPath can't handle names with leading numerals. Add
	 * a _n_. We can't handle the names with the ending [#]
	 * that jackrabbit uses for disambiguation of repeated
	 * names. (We know the rest of the string is base64.)
	 * NOTE: the rest of the string is no longer base64... see
	 * byteToString/stringToByte
	 * 
	 * @param str
	 * @return
	 */
	protected static byte [] stringToNameComponent(String str) {
		if ((null == str) || (str.length() == 0)) 
			return null;
		
		String parseString = str;
		// Remove trailing numbers, which are used to
		// uniqueify duplicate names by Jackrabbit.
		if (parseString.charAt(parseString.length()-1) == ']') {
			parseString = 
				parseString.substring(0, 
					parseString.lastIndexOf('[')); 
		}
		
		if (parseString.startsWith(BASE64_MARKER)) {
			try {
				return XMLHelper.decodeElement(parseString.substring(BASE64_MARKER.length()));
			} catch (IOException e) {
				Library.logger().warning("Cannot decode base64-encoded element that we encoded: " + parseString);
				return new byte[0]; // DKS TODO need better answer
			}
		}
		
		// DKS -- TODO: must to real JSR quoting. This is
		// too weird a mix of URL quoting and JSR stuff.
		// Jackrabbit will insert nodes with +'s for spaces,
		// but won't query for them.
		if (parseString.contains("_x0020_")) {
			parseString = parseString.replace("_x0020_", "+");
		}
		
		if (parseString.startsWith(LEADING_NUMBER_MARKER)) {
			return ContentName.componentParse(parseString.substring(LEADING_NUMBER_MARKER.length()));
		}
		
		return ContentName.componentParse(parseString);
	}

	protected static String publisherToString(byte [] publisherID) {
		return byteToString(publisherID);
	}
	
	/**
	 * 
	 * Go between ContentNames and generated Jackrabbit paths.
	 * Have to cope with Jackrabbit-specific maps between bytes and strings.
	 * Jackrabbit's separator is also /.
	 * Jackrabbit returns path with empty root component
	 * on front, corresponding to jcr:root.
	 **/
	protected static ContentName parsePath(String path) {
		if (path == null) return ContentName.ROOT;
		if (path.length() == 0) return ContentName.ROOT;
		String[] parts = path.split(ContentName.SEPARATOR);
		// Jackrabbit puts a 0-length root component at
		// the front, corresponding tp jcr:root.
		int startComponent = (parts[0].length() == 0) ? 1 : 0;

		byte [][] byteParts = new byte[parts.length - startComponent][];
		for (int i=startComponent; i < parts.length; ++i) {
			byteParts[i-startComponent] = stringToNameComponent(parts[i]);
		}
		return new ContentName(byteParts);
	}
	
	/**
	 * Switch to use ContentName.toString of name, which does quoting.
	 * Have to cope with the fact that XPath
	 * can't have leading numbers in name components (though
	 * Jackrabbit can).
	 * @param name
	 * @return
	 */
	protected static String nameToPath(ContentName name) {
		if ((null == name) || (0 == name.count())) {
			return ContentName.SEPARATOR;
		}
		
		// In case this is a query with its last component
		// being "*", strip that.
		int componentCount = name.count();
		
		if (Arrays.equals(name.component(name.count()-1), 
				CCNQueryDescriptor.RECURSIVE_POSTFIX.getBytes())) {
			--componentCount;
		}
		StringBuffer buf = new StringBuffer();
		for (int i=0; i < componentCount; ++i) {
			byte [] component = name.component(i);
			if ((null == component) || (0 == component.length))
				continue;
			buf.append(ContentName.SEPARATOR);
			buf.append(nameComponentToString(name.component(i)));
		}
		return buf.toString();
	}
	
	protected static String byteToString(byte [] id) {
			try {
				return URLEncoder.encode(new String(id), "UTF-8");
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException("UTF-8 not supported", e);
			}
	}
	
	protected static byte [] stringToByte(String id) {
		try {
			return URLDecoder.decode(id, "UTF-8").getBytes();
		} catch (UnsupportedEncodingException e) {
			throw new RuntimeException("UTF-8 not supported", e);
		}
	}

	@Override
	public ArrayList<CompleteName> enumerate(CompleteName name) throws IOException {
		ArrayList<CompleteName> names = new ArrayList<CompleteName>();

		try {
			// Strips trailing '*' if there is one, makes
			// query recursive if there is.
			String queryString = getQueryString(name.name(), name.authenticator());
			Query q = null;
			try {
				q = _session.getWorkspace().getQueryManager().createQuery(queryString, Query.XPATH);
			} catch (InvalidQueryException e) {
				Library.logger().warning("Invalid query string: " + queryString);
				Library.logger().warning("Exception: " + e.getClass().getName() + " m: " + e.getMessage());
				throw new IOException("Exception: " + e.getClass().getName() + " m: " + e.getMessage());
			}

			NodeIterator iter = q.execute().getNodes();
			while (iter.hasNext()) {
				Node node = (Node) iter.next();
				if (isCCNNode(node)) {
					// DKS TODO: this is a temporary hack to mark CCN nodes.
					// either need better query to only pull these out,
					// which might need a different node type
					names.add(getCompleteName(node));
				}
			}
			Library.logger().warning("Query returned " + iter.getSize() + " results, of which " + names.size() + " were CCN nodes.");
		} catch (RepositoryException e) {
			Library.logger().warning("Invalid query or problem executing get: " + e.getMessage());
			Library.warningStackTrace(e);
			throw new IOException("Invalid query or problem executing get: " + e.getMessage());
		}
		return names;
	}

	@Override
	public CompleteName addProperty(CompleteName target, String propertyName, byte[] propertyValue) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public CompleteName addProperty(CompleteName target, String propertyName, String propertyValue) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public byte[] getByteProperty(CompleteName target, String propertyName) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public ArrayList<ContentObject> getInternal(ContentName name, ContentAuthenticator authenticator) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getStringProperty(CompleteName target, String propertyName) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public boolean isInternal(CompleteName name) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public CompleteName putInternal(ContentName name, ContentAuthenticator authenticator, byte[] content) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}
}
