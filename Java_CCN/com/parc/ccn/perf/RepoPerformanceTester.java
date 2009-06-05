package com.parc.ccn.perf;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.logging.Level;

import javax.xml.stream.XMLStreamException;

import com.parc.ccn.Library;
import com.parc.ccn.config.ConfigurationException;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.data.MalformedContentNameStringException;
import com.parc.ccn.data.WirePacket;
import com.parc.ccn.library.CCNFlowControl;
import com.parc.ccn.library.CCNLibrary;
import com.parc.ccn.library.io.CCNOutputStream;
import com.parc.ccn.network.daemons.repo.RepositoryException;

/**
 * 
 * @author rasmusse
 *
 */

public class RepoPerformanceTester extends CCNOutputStream {
	
	private static RepoPerformanceTester _rpt = new RepoPerformanceTester();
	private static TestFlowControl _tfc = null;

	private class TestFlowControl extends CCNFlowControl {
		
		private FileOutputStream _fos = null;
		
		public TestFlowControl(String repoName, ContentName name, CCNLibrary library)
				throws MalformedContentNameStringException, RepositoryException {
			super(name, library);
			_tfc = this;
			if (repoName != null) {
				File file = new File(repoName + "/" + "repoFile");
				try {
					File repoDir = new File(repoName);
					repoDir.mkdirs();
					file.createNewFile();
					_fos = new FileOutputStream(file);
				} catch (IOException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			
			}
		}
		
		public ContentObject put(ContentObject co) throws IOException {
			if (_fos != null) {
				try {
					WirePacket packet = new WirePacket(co);
					_fos.write(packet.encode());
				} catch (XMLStreamException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
			return co;
		}
	}
	
	public RepoPerformanceTester() {}

	public RepoPerformanceTester(String repoName, ContentName name, CCNLibrary library)
			throws XMLStreamException, IOException, MalformedContentNameStringException, RepositoryException {
		super(name, null, null, _rpt.new TestFlowControl(repoName, name, library));
	}
	
	public void close() throws IOException {
		super.close();
		if (_tfc._fos != null)
			_tfc._fos.close();
	}
	
	private static int BLOCK_SIZE = 8096;

	/**
	 * @param args
	 */
	public static void main(String[] args) {
	
		ContentName argName;
		long startTime = new Date().getTime();
		Library.logger().setLevel(Level.SEVERE);	// turn off logging
		try {
			argName = ContentName.fromURI(args[0]);
			CCNLibrary library = CCNLibrary.open();
			
			File theFile = new File(args[1]);
			if (!theFile.exists()) {
				System.out.println("No such file: " + args[1]);
				return;
			}
			Library.logger().info("repo_test: putting file " + args[1] + " bytes: " + theFile.length());
			RepoPerformanceTester ostream = new RepoPerformanceTester(args.length > 2 ? args[2] : null, argName, library);
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
