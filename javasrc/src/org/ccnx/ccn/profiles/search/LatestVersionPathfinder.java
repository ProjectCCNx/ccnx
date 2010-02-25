package org.ccnx.ccn.profiles.search;

import java.io.IOException;
import java.util.Set;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.profiles.VersioningProfile;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;

public class LatestVersionPathfinder extends Pathfinder {

	public LatestVersionPathfinder(ContentName startingPoint, ContentName stoppingPoint, ContentName desiredPostfix, 
			  boolean closestOnPath, boolean goneOK,
			  int timeout, 
			  Set<ContentName> searchedPathCache,
			  CCNHandle handle) throws IOException {
		super(startingPoint, stoppingPoint, desiredPostfix, closestOnPath, goneOK, timeout, searchedPathCache, handle);
	}

	public synchronized SearchResults waitForResults() {
		boolean searchSpaceEmpty = false;
		while (! searchSpaceEmpty) {
			SearchResults sr = super.waitForResults();
			ContentObject co = sr.getResult(); 
			if (null != co) {
				try {
					// get the latest version
					ContentObject coLV = 
						VersioningProfile.getFirstBlockOfLatestVersion(co.name(), null, null, 
								SystemConfiguration.getDefaultTimeout(), _handle.defaultVerifier(), _handle);
					if (null != coLV) co = coLV;
					if (goneOK() || (! co.isGone())) {
						sr.setResult(co);
						return sr;
					}
					// the latest version of the ContentObject found is GONE, and goneOK = false
					// so we cancel outstanding interests (if any),
					// we update the starting point or stopping point (depending on _closestOnPath)
					// and start a new search if the new range is non empty
					if (! done()) stopSearch();
					if (_closestOnPath) {
						ContentName icn = sr.getInterestName();
						icn = icn.cut(icn.count() - _postfix.count());
						System.out.println("Stopping point " + _stoppingPoint);
						System.out.println("interest: " + icn);
						if (icn.count() > _stoppingPoint.count()) {
							_startingPoint = icn.cut(icn.count() - 1);
							System.out.println("new starting point: " + _startingPoint);
						}
						else searchSpaceEmpty = true;
					}
					else {
						ContentName icn = sr.getInterestName();
						icn = icn.cut(icn.count() - _postfix.count());
						if (icn.count() < _startingPoint.count()) {
							_stoppingPoint = _startingPoint.cut(icn.count() + 1);												
						}
						else searchSpaceEmpty = true;
					}
					if (! searchSpaceEmpty) {
						startSearch();
					}
				} catch (IOException ioe) {
					ioe.printStackTrace();
					return new SearchResults(null, null);
				}
			}
			else return sr;
		}
		return new SearchResults(null, null);
	}
	
}
