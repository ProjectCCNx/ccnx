package test.ccn.network.rpc;

import java.io.IOException;
import java.net.InetAddress;

import org.acplt.oncrpc.OncRpcException;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.network.rpc.DataBlock;
import com.parc.ccn.network.rpc.Name;
import com.parc.ccn.network.rpc.NameComponent;
import com.parc.ccn.network.rpc.NameList;
import com.parc.ccn.network.rpc.Transport2RepoServerStub;

public class TestTransport2RepoServer extends Transport2RepoServerStub {

	public static TestTransport2RepoServer server = null;
	
	public TestTransport2RepoServer() throws OncRpcException, IOException {
		// TODO Auto-generated constructor stub
	}

	public TestTransport2RepoServer(int port) throws OncRpcException,
			IOException {
		super(port);
		// TODO Auto-generated constructor stub
	}

	public TestTransport2RepoServer(InetAddress bindAddr, int port)
			throws OncRpcException, IOException {
		super(bindAddr, port);
		// TODO Auto-generated constructor stub
	}

	@Override
	public NameList Enumerate_1(Name arg1) {
		Library.logger().info("Server: in Enumerate.");
		NameList nl = new NameList();
		nl.count = 1;
		nl.names = new Name[1];
		nl.names[0] = new Name();
		nl.names[0].component = new NameComponent[1];
		nl.names[0].component[0] = new NameComponent();
		nl.names[0].component[0].length = 1;
		nl.names[0].component[0].vals = new String("testComponent").getBytes();
		return nl;
	}

	@Override
	public DataBlock GetBlock_1(Name arg1) {
		Library.logger().info("Server: GetBlock.");
		Library.logger().info("Name: " + arg1.component.length + " components.");
		DataBlock block = new DataBlock();
		block.data = new String("This is data.").getBytes();
		block.length = block.data.length;
		return block;
	}

	@Override
	public int PutBlock_1(Name arg1, DataBlock arg2) {
		Library.logger().info("Server: PutBlock.");
		Library.logger().info("Name: " + arg1.component.length + " components.");
		Library.logger().info("Data: " + arg2.length + " bytes.");
		return 0;
	}

	/**
	 * @param args
	 */
	public static void main(String[] args) {
		Library.logger().info("Creating server.");
		try {
			server = 
				new TestTransport2RepoServer(SystemConfiguration.defaultRepositoryPort());
			Library.logger().info("Running server...");
			// straight run expects portmapper...
			server.run(server.transports);
		} catch (Exception e) {
			Library.logger().warning("Exception in server: " + e.getClass().getName() + " message: " + e.getMessage());
			Library.warningStackTrace(e);
		}

	}

}
