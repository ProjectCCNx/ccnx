package com.parc.ccn.network.daemons.repo;

import java.io.IOException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.CCNBase;
import com.parc.ccn.Library;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.content.LinkReference;
import com.parc.ccn.data.query.CCNFilterListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.CCNNameEnumerator;

/**
 * 
 * @author rasmusse
 *
 */

public class RepositoryInterestHandler implements CCNFilterListener {
	private RepositoryDaemon _daemon;
	private CCNLibrary _library;
	
	public RepositoryInterestHandler(RepositoryDaemon daemon) {
		_daemon = daemon;
		_library = daemon.getLibrary();
	}

	public int handleInterests(ArrayList<Interest> interests) {
		for (Interest interest : interests) {
			try {
				byte[] marker = interest.name().component(interest.name().count() - 2);
				Library.logger().fine("marker is " + new String(marker) + " in " + interest.name());
				if (Arrays.equals(marker, CCNBase.REPO_START_WRITE)) {
					startReadProcess(interest);
				} else if(interest.name().contains(CCNNameEnumerator.NEMARKER)){
					nameEnumeratorResponse(interest);
				}
				else {
					ContentObject content = _daemon.getRepository().getContent(interest);
					if (content != null) {
						_library.put(content);
					} else {
						Library.logger().fine("Unsatisfied interest: " + interest.name());
					}
				}
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return interests.size();
	}
	
	private void startReadProcess(Interest interest) throws XMLStreamException {
		for (RepositoryDataListener listener : _daemon.getDataListeners()) {
			if (listener.getOrigInterest().equals(interest))
				return;
		}
		
		/*
		 * For now we need to wait until all current sessions are complete before a namespace
		 * change which will reset the filters is allowed. So for now, we just don't allow any
		 * new sessions to start until a pending namespace change is complete to allow there to
		 * be space for this to actually happen. In theory we should probably figure out a way
		 * to allow new sessions that are within the new namespace to start but figuring out all
		 * the locking/startup issues surrounding this is complex so for now we just don't allow it.
		 */
		if (_daemon.getPendingNameSpaceState())
			return;
		
		ContentName listeningName = new ContentName(interest.name().count() - 2, interest.name().components());
		try {
			Integer count = interest.nameComponentCount();
			if (count != null && count > listeningName.count())
				count = null;
			Interest readInterest = Interest.constructInterest(listeningName, _daemon.getExcludes(), null, count);
			RepositoryDataListener listener = _daemon.addListener(interest, readInterest);
			_daemon.getWriter().put(interest.name(), _daemon.getRepository().getRepoInfo(null));
			_library.expressInterest(readInterest, listener);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (SignatureException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
	
	public void nameEnumeratorResponse(Interest interest) {
		//the name enumerator marker won't be at the end if the interest is a followup (created with .last())
		//else if(Arrays.equals(marker, CCNNameEnumerator.NEMARKER)){
		//System.out.println("handling interest: "+interest.name().toString());
		ContentName prefixName = interest.name().cut(CCNNameEnumerator.NEMARKER);
		ArrayList<ContentName> names = _daemon.getRepository().getNamesWithPrefix(interest);
		if(names!=null){
			try{
				ContentName collectionName = new ContentName(prefixName, CCNNameEnumerator.NEMARKER);
				//the following 6 lines are to be deleted after Collections are refactored
				LinkReference[] temp = new LinkReference[names.size()];
				for(int x = 0; x < names.size(); x++)
					temp[x] = new LinkReference(names.get(x));
				_library.put(collectionName, temp);
				
				//CCNEncodableCollectionData ecd = new CCNEncodableCollectionData(collectionName, cd);
				//ecd.save();
				//System.out.println("saved ecd.  name: "+ecd.getName());
			}
			catch(IOException e){
				
			}
			catch(SignatureException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
}
