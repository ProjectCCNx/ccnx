package com.parc.ccn.apps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNInterestListener;
import com.parc.ccn.data.query.Interest;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;

public class watch extends Thread implements CCNInterestListener {
	
	protected boolean _stop = false;
	protected ArrayList<Interest> _interests = new ArrayList<Interest>();
	protected CCNLibrary _library = null;
	
	public watch(CCNLibrary library) {_library = library;}
	
	public void initialize() {}
	public void work() {}
	
	public void addInterest(Interest interest) {_interests.add(interest);}
	
	public void run() {
		_stop = false;
		initialize();
		
		System.out.println("Watching: " + new Date().toString() +".");
		Library.logger().info("Watching: " + new Date().toString() +".");

		do {

			try {
				work();
			} catch (Exception e) {
				Library.logger().warning("Error in watcher thread: " + e.getMessage());
				Library.warningStackTrace(e);
			}

			try {
				Thread.sleep(10000);
			} catch (InterruptedException e) {
			}
		} while (!_stop);

	}
	
	
	public static void usage() {
		System.out.println("usage: watch <ccnname> [<ccnname>...]");
	}
	
	public void cancelInterests() {
		for (int i=0; i < _interests.size(); ++i) {
			try {
				_library.cancelInterest(_interests.get(i), this);
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public Interest[] getInterests() {
		return (Interest[])_interests.toArray();
	}
	
	public int handleResults(ArrayList<CompleteName> results) {
		for (int i=0; i < results.size(); ++i) {
			System.out.println("New content: " + results.get(i).name());
		}
		return results.size();
	}
	
	public boolean matchesInterest(CompleteName name) {
		for (int i=0; i < _interests.size(); ++i) {
			if (_interests.get(i).matches(name))
				return true;
		}
		return false;
	}
	
	public void interestCanceled(Interest interest) {
		System.out.println("Canceled interest in: " + interest.name());
	}
	
	public void interestTimedOut(Interest interest) {
		// TODO Auto-generated method stub
		
	}


	/**
	 * @param args
	 */
	public static void main(String[] args) {
		if (args.length < 1) {
			usage();
			return;
		}
		
		try {
			StandardCCNLibrary library = new StandardCCNLibrary();
			// Watches content, prints out what it sees.
			
			watch listener = new watch(library);
			
			for (int i=0; i < args.length; ++i) {
				Interest interest = new Interest(args[i]);
			
				library.expressInterest(interest, listener);
				listener.addInterest(interest);
			} 
			
			listener.run();
			try {
				listener.join();
			} catch (InterruptedException e) {
				
			}
			System.exit(0);
			
		} catch (ConfigurationException e) {
			System.out.println("Configuration exception in watch: " + e.getMessage());
			e.printStackTrace();
		} catch (MalformedContentNameStringException e) {
			System.out.println("Malformed name: " + e.getMessage());
			e.printStackTrace();
		} catch (IOException e) {
			System.out.println("IOException in enumerate: " + e.getMessage());
			e.printStackTrace();
		} 

	}

}
