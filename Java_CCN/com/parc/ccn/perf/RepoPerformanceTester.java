package com.parc.ccn.perf;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Date;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import org.ccnx.ccn.CCNHandle;
import org.ccnx.ccn.config.ConfigurationException;
import org.ccnx.ccn.impl.CCNFlowControl;
import org.ccnx.ccn.impl.repo.RFSLogImpl;
import org.ccnx.ccn.impl.repo.Repository;
import org.ccnx.ccn.impl.repo.RepositoryException;
import org.ccnx.ccn.impl.support.Library;
import org.ccnx.ccn.io.CCNOutputStream;
import org.ccnx.ccn.protocol.ContentName;
import org.ccnx.ccn.protocol.ContentObject;
import org.ccnx.ccn.protocol.MalformedContentNameStringException;


/**
 * 
 * @author rasmusse
 *
 */

public class RepoPerformanceTester extends CCNOutputStream {	

	private static RepoPerformanceTester _rpt = new RepoPerformanceTester();

	private class TestFlowControl extends CCNFlowControl {
		
		private Repository _repo = null;
		
		public TestFlowControl(String repoName, ContentName name, CCNHandle library)
				throws MalformedContentNameStringException, RepositoryException, IOException {
			super(name, library);
			if (repoName != null) {
				_repo = new RFSLogImpl();
				_repo.initialize(new String[] {"-root", repoName}, library);
			}
		}
		
		public ContentObject put(ContentObject co) throws IOException {
			if (_repo != null) {
				try {
					_repo.saveContent(co);
				} catch (RepositoryException e) {
					throw new IOException(e.getMessage());
				}
			}
			return co;
		}
	}
	
	public RepoPerformanceTester() {}
	
	public RepoPerformanceTester(String repoName, ContentName name, CCNHandle library)
			throws XMLStreamException, IOException, MalformedContentNameStringException, RepositoryException {
		super(name, null, null, _rpt.new TestFlowControl(repoName, name, library));
	}
	
	public RepoPerformanceTester(ContentName name, CCNFlowControl cf)
			throws XMLStreamException, IOException, MalformedContentNameStringException, RepositoryException {
		super(name, null, null, cf);
	}
	
	public RepoPerformanceTester getTester(String repoName, ContentName name, CCNHandle library) throws MalformedContentNameStringException, XMLStreamException, IOException, RepositoryException {
		return new RepoPerformanceTester(repoName, name, library);
	}
	
	public void doTest(String[] args) {
		ContentName argName;
		long startTime = new Date().getTime();
		Library.logger().setLevel(Level.SEVERE);	// turn off logging
		try {
			argName = ContentName.fromURI(args[0]);
			CCNHandle library = CCNHandle.open();
			
			File theFile = new File(args[1]);
			if (!theFile.exists()) {
				System.out.println("No such file: " + args[1]);
				return;
			}
			Library.logger().info("repo_test: putting file " + args[1] + " bytes: " + theFile.length());
			RepoPerformanceTester ostream = getTester(args.length > 2 ? args[2] : null, argName, library);
			do_write(ostream, theFile);
			
		} catch (MalformedContentNameStringException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (ConfigurationException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (XMLStreamException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (RepositoryException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	
		long endTime = new Date().getTime();
		System.out.println("Inserted file " + args[1] + " in " + (endTime - startTime) + " ms");
		System.exit(0);
		
	}
	
	private static int BLOCK_SIZE = 8096;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		_rpt.doTest(args);
	}
	
	private static void do_write(CCNOutputStream ostream, File file) throws IOException {
		FileInputStream fis = new FileInputStream(file);
		int size = BLOCK_SIZE;
		byte [] buffer = new byte[BLOCK_SIZE];
		do {
			Library.logger().info("do_write: " + fis.available() + " bytes left.");
			if (size > fis.available())
				size = fis.available();
			if (size > 0) {
				fis.read(buffer, 0, size);
				ostream.write(buffer, 0, size);
				Library.logger().info("do_write: wrote " + size + " bytes.");
			}
		} while (fis.available() > 0);
		ostream.close();
	}
	
}
