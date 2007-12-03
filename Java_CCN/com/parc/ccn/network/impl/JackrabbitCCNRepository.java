package com.parc.ccn.network.impl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URLEncoder;
import java.net.URLDecoder;
import java.net.MalformedURLException;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.logging.Level;

import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.PathNotFoundException;
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
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.data.security.ContentAuthenticator;
import com.parc.ccn.data.security.KeyLocator;
import com.parc.ccn.data.security.PublisherID;
import com.parc.ccn.network.CCNRepository;
import com.parc.ccn.network.CCNRepositoryFactory;
import com.parc.ccn.network.discovery.CCNDiscovery;

/**
 * Jackrabbit does have a way of linking directly
 * to files. If we need efficiency, we could do that.
 * @author smetters
 *
 */
public class JackrabbitCCNRepository extends CCNRepository {

	// TODO DKS: Make sure jackrabbit-specific exceptions aren't visible.
	public static final int SERVER_PORT = 1101;
	public static final String PROTOCOL_TYPE = "rmi";
	public static final String SERVER_RMI_NAME = "jackrabbit";

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
	protected HashMap<CCNQueryListener, JackrabbitEventListener> _eventListeners = new HashMap<CCNQueryListener, JackrabbitEventListener>();
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
	 */
	public JackrabbitCCNRepository(String host, int port) 
			throws MalformedURLException, 
				ClassCastException, RemoteException, 
				NotBoundException {
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
	 * Use our own repository.
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

	public static JackrabbitCCNRepository getLocalJackrabbitRepository() {
		// TODO DKS: Eventually discover if there is one running locally and
		// return that...
		return new JackrabbitCCNRepository();
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
			// Now we're down to the leaf node
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
				n = parent.addNode(componentName,"nt:unstructured");
				// now, make sure the leaf node is versionable				
				if (!n.isNodeType("mix:versionable")) {
					n.addMixin("mix:versionable");
				}
				if (!n.isNodeType("mix:referenceable")) {
					n.addMixin("mix:referenceable");
				}
				
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
		if ((null != matchingChildren) && (matchingChildren.getSize() > 0)) {
			// We have nodes with the same name. 
			// If the signatures match, this data is identical
			// and we just return that node.
			
			// if the signatures don't match, but the content digest
			// and publisher ID do match, it could be an attempt
			// to update content -- timestamp is different.
			// if those are the same, and all other metadata
			// is the same, count it as updated and just
			// update timestamp and signature
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
		n.checkout();
		
		addContent(n, content);
		addAuthenticationInfo(n, authenticator);

		_session.save();
		n.checkin();
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
		String strPublisher = n.getProperty(PUBLISHER_PROPERTY).getString();
		byte [] publisherID = stringToByte(strPublisher);
		String publisherType = n.getProperty(PUBLISHER_TYPE_PROPERTY).getString();
		
		ContentAuthenticator.ContentType type = ContentAuthenticator.nameToType(n.getProperty(TYPE_PROPERTY).getString());

		Timestamp timestamp = Timestamp.valueOf(n.getProperty(TIMESTAMP_PROPERTY).getString());
		
		byte [] hash = getBinaryProperty(n, HASH_PROPERTY);
		byte [] encodedKeyLocator = getBinaryProperty(n, KEY_LOCATOR_PROPERTY);
	
		KeyLocator loc;
		try {
			loc = new KeyLocator(encodedKeyLocator);
		} catch (XMLStreamException e) {
			Library.logger().log(Level.WARNING, "This should not happen: cannot retrieve and decode KeyLocator value we encoded and stored.");
			throw new ValueFormatException(e);
		}
		byte [] signature = getBinaryProperty(n, SIGNATURE_PROPERTY);
		
		ContentAuthenticator auth = new ContentAuthenticator(publisherID, PublisherID.nameToType(publisherType),
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
			String queryString = getQueryString(query);

			Query q = null;
			try {
				q = _session.getWorkspace().getQueryManager().createQuery(queryString, Query.XPATH);
			} catch (InvalidQueryException e) {
				Library.logger().warning("Invalid query string: " + queryString);
				Library.logger().warning("Exception: " + e.getClass().getName() + " m: " + e.getMessage());
				throw new IOException(e);
			}

			NodeIterator iter = q.execute().getNodes();
			while (iter.hasNext()) {
				Node node = (Node) iter.next();
				objects.add(getContentObject(node));
			}
		} catch (RepositoryException e) {
			Library.logger().warning("Invalid query or problem executing get: " + e.getMessage());
			Library.warningStackTrace(e);
			throw new IOException(e);
		}
		return objects;
	}
	
	public void cancel(CCNQueryDescriptor query) throws IOException {
		// Find the listener responding to this query
		for (CCNQueryListener l : _eventListeners.keySet()) {
			if (l.getQuery().equals(query)) {
				unsubscribe(l);
			}
		}
	}
	
	protected String getQueryString(CompleteName queryName) {
		// TODO: DKS: make Xpath match full query including whatever
		// authentication info is specified by querier
		String queryString = "/jcr:root" + nameToPath(queryName.name());
		return queryString;
	}
	
	private void startListening(JackrabbitEventListener el) throws IOException {
		try {
			ObservationManager o = _session.getWorkspace().getObservationManager();
			o.addEventListener(el, el.events(), el.getQueryPath(), true, null, null, false);
		} catch (RepositoryException e) {
			throw new IOException(e.getMessage());
		}
		// now tell them about the existing nodes
		ArrayList<ContentObject> currentMatches = get(el.queryDescriptor());
		el.listener().handleResults(currentMatches);
			
		return;		
	}

	/**
	 * What's the difference between the query interface
	 * and the subscription/observer interface? Maybe
	 * just not pulling the data?
	 */
	public void subscribe(CCNQueryListener l) throws IOException {

		int events = Event.NODE_ADDED | Event.NODE_REMOVED | Event.PROPERTY_ADDED | Event.PROPERTY_CHANGED | Event.PROPERTY_REMOVED;
		JackrabbitEventListener el = new JackrabbitEventListener(this, l, events);
		_eventListeners.put(l, el);
		startListening(el);
	}

	public void resubscribeAll() throws IOException {
		for (JackrabbitEventListener el : _eventListeners.values()) {
			startListening(el);
		}
	}

	public void unsubscribe(CCNQueryListener l) throws IOException {
		try {
			ObservationManager o = _session.getWorkspace().getObservationManager();
			JackrabbitEventListener el = _eventListeners.get(l);
			if (null == el)
				return;
			o.removeEventListener(el);
			l.cancelQueries();
			_eventListeners.remove(l);
		} catch (RepositoryException e) {
			throw new IOException(e.getMessage());
		}
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
	
	ContentObject getContentObject(Node n) throws IOException {
		ContentName name = null;;
		ContentAuthenticator authenticator = null;
		byte [] content = null;
		try {
			name = getName(n);
			authenticator = getAuthenticationInfo(n);
			content = getContent(n);
			
		} catch (RepositoryException e) {
			Library.logger().warning("Repository exception extracting content from node: " + n.toString() + ": " + e.getMessage());
			Library.logStackTrace(Level.WARNING, e);
			throw new IOException("Repository exception extracting content from node: " + n.toString() + ": " + e.getMessage());
		}
		return new ContentObject(name, authenticator, content);
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
			resubscribeAll();
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
		Repository repository = new TransientRepository();
		ServerAdapterFactory factory = new ServerAdapterFactory();
		RemoteRepository remote = factory.getRemoteRepository(repository);
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
		CCNRepository.advertiseServer(JACKRABBIT_RMI_SERVICE_TYPE, port);
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
		String str = byteToString(component);
		if (Character.isDigit(str.charAt(0))) {
			return "_" + str;
		}
		return str;
	}
	
	/**
	 * Undo any quoting we need to do above. In particular,
	 * XPath can't handle names with leading numerals. Add
	 * a _. We can't handle the names with the ending [#]
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
		
		if (str.charAt(str.length()-1) == ']') {
			return stringToByte(
				str.substring(
					((str.charAt(0) == '_') ? 1 : 0), 
					str.lastIndexOf('['))); 
		}
		if (str.charAt(0) == '_') {
			return stringToByte(str.substring(1));
		}
		return stringToByte(str);
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
	 * Could use toString of name, which does quoting.
	 * Right now have to cope with the fact that XPath
	 * can't have leading numbers in name components (though
	 * Jackrabbit can).
	 * @param name
	 * @return
	 */
	protected static String nameToPath(ContentName name) {
		// TODO DKS: evaluate using toString instead
		if ((null == name) || (0 == name.count())) {
			return ContentName.SEPARATOR;
		}
		StringBuffer buf = new StringBuffer();
		for (int i=0; i < name.count(); ++i) {
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
}
