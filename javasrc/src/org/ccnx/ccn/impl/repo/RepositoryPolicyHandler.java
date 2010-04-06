package org.ccnx.ccn.impl.repo;

import java.io.IOException;
import java.util.logging.Level;

import org.ccnx.ccn.impl.repo.PolicyXML.PolicyObject;
import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.io.content.ContentDecodingException;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.Interest;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;

/**
 * Special purpose handler to handle network files written to control the repository
 * Currently the only example of this are policy files.
 * 
 * This handler is used for anything whose prefix matches the global prefix for the
 * repository
 * 
 */
public class RepositoryPolicyHandler {

	/**
	 * For now just assume anything that comes here is a policy update.
	 * TODO separate out other kinds of files
	 * 
	 * @param origInterest
	 * @param interest
	 * @param server
	 * @throws ContentDecodingException
	 * @throws IOException
	 * @throws RepositoryException 
	 * @throws IOException 
	 * @throws ContentDecodingException 
	 * @throws MalformedContentNameStringException 
	 */
	public RepositoryPolicyHandler(Interest origInterest, Interest interest,
			RepositoryServer server) throws RepositoryException, ContentDecodingException, IOException, MalformedContentNameStringException {
		PolicyObject po = new PolicyObject(interest.name(), server.getHandle());
		PolicyXML pxml = po.policyInfo();
		Policy policy = server.getRepository().getPolicy();
		policy.update(pxml, true);
		ContentName policyName = VersioningProfile.addVersion(
				ContentName.fromNative(RepositoryStore.REPO_NAMESPACE + "/" + pxml._localName + "/" + RepositoryStore.REPO_POLICY));
		if (Log.isLoggable(Log.FAC_REPO, Level.INFO))
			Log.info("REPO: got policy update, global name {0} local name {1}, saving to {2}", policy.getGlobalPrefix(), policy.getLocalName(), policyName);
		server.resetNameSpaceFromHandler();
		
		// TODO need to update repository files from what we have (probably?)
		//ContentObject policyCo = new ContentObject(policyName, co.signedInfo(), co.content(), co.signature());
		//server.getRepository().saveContent(policyCo);
		//if (SystemConfiguration.getLogging(RepositoryStore.REPO_LOGGING))
		//	Log.info("REPO: Saved policy to repository: {0}", policyCo.name());
	}
}
