package org.ccnx.ccn.io.content;

import java.io.IOException;
import java.util.logging.Level;

import org.ccnx.ccn.impl.support.Log;
import org.ccnx.ccn.profiles.repo.RepositoryControl;

/**
 * A listener for a network object that requests a local repository to hold
 * a copy of the object whenever it is updated or saved.  A local repository
 * is one connected directly to the same ccnd as the application; it may have 
 * a distinguished role as the repository that is always available 
 * for local configuration data regardless of external connectivity. If there
 * is more than one repository that is local, the behavior is undefined.
 * 
 * To add this functionality to an existing network object, add an instance of this
 * class as a listener on the object. 
 */
public class LocalCopyListener implements UpdateListener {
	
	protected static LocalCopyListener backupListener = new LocalCopyListener();
	
	public static void startBackup(CCNNetworkObject<?> objectToSyncToRepository) throws IOException {
		if (objectToSyncToRepository.isSaved()) {
			backupListener.localCopy(objectToSyncToRepository);
		}
		objectToSyncToRepository.addListener(backupListener);
	}

	@Override
	public void newVersionAvailable(CCNNetworkObject<?> newVersion) {
		localCopy(newVersion);
	}
	
	protected void localCopy(CCNNetworkObject<?> newVersion) {
		try {
			RepositoryControl.localRepoSync(newVersion.getHandle(), newVersion);
		} catch (IOException e) {
			if (Log.isLoggable(Level.INFO)) {
				Log.info("Local repo sync failed for network object: " + e.getMessage());
			}
		}
	}


}
