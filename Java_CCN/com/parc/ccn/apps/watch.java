package com.parc.ccn.apps;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.query.CCNQueryDescriptor;
import com.parc.ccn.data.query.CCNQueryListener;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.StandardCCNLibrary;

public class watch extends Thread implements CCNQueryListener {
	
	protected boolean _stop = false;
	protected ArrayList<CCNQueryDescriptor> _queries = new ArrayList<CCNQueryDescriptor>();
	protected CCNLibrary _library = null;
	
	public watch(CCNLibrary library) {_library = library;}
	
	public void initialize() {}
	public void work() {}
	
	public void addQuery(CCNQueryDescriptor descriptor) {_queries.add(descriptor);}
	
	public void run() {
		_stop = false;
		initialize();
		
		System.out.println("Daemon thread started " + new Date().toString() +".");
		Library.logger().info("Daemon thread started " + new Date().toString() +".");

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
	
	public void cancelQueries() {
		for (int i=0; i < _queries.size(); ++i) {
			try {
				_library.cancelInterest(_queries.get(i));
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}
	
	public CCNQueryDescriptor[] getQueries() {
		return (CCNQueryDescriptor[])_queries.toArray();
	}
	
	public int handleResults(ArrayList<CompleteName> results) {
		for (int i=0; i < results.size(); ++i) {
			System.out.println("New content: " + results.get(i).name());
		}
		return results.size();
	}
	
	public boolean matchesQuery(CompleteName name) {
		for (int i=0; i < _queries.size(); ++i) {
			if (_queries.get(i).matchesQuery(name))
				return true;
		}
		return false;
	}
	
	public void queryCanceled(CCNQueryDescriptor query) {
		System.out.println("Canceled query for: " + query.name().name());
	}
	
	public void queryTimedOut(CCNQueryDescriptor query) {
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
				ContentName argName = new ContentName(args[i]);
			
				CCNQueryDescriptor query = library.expressInterest(argName, null, listener);
				listener.addQuery(query);
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
