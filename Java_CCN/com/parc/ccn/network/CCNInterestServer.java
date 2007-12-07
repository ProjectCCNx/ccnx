package com.parc.ccn.network;

import java.io.IOException;
import java.util.ArrayList;

import javax.xml.stream.XMLStreamException;

import org.acplt.oncrpc.OncRpcException;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;
import com.parc.ccn.data.CompleteName;
import com.parc.ccn.data.ContentName;
import com.parc.ccn.data.ContentObject;
import com.parc.ccn.network.rpc.DataBlock;
import com.parc.ccn.network.rpc.Name;
import com.parc.ccn.network.rpc.NameList;
import com.parc.ccn.network.rpc.RepoTransport_TRANSPORTTOREPOPROG_ServerStub;

/**
 * Encapsulate the RPC server handling requests from
 * the transport in a more sensible interface.
 * 
 * We assume that this server will use a default
 * port and handle requests only from localhost.
 * @author smetters
 *
 */
public class CCNInterestServer extends RepoTransport_TRANSPORTTOREPOPROG_ServerStub {

	/**
	 * This is most likely a CCNRepositoryManager,
	 * brokering for several repositories. But could
	 * also just be the local jackrabbit.
	 */
	CCNRepository _theRepository = null; 
	
	public CCNInterestServer(CCNRepository primaryRepository) throws OncRpcException, IOException {
		this(SystemConfiguration.defaultRepositoryPort(), primaryRepository);
		Library.logger().info("CCNInterestServer: initialize.");
	}
	
	public CCNInterestServer(int port, CCNRepository primaryRepository) throws OncRpcException, IOException {
		super(port);
		// Hack to avoid having to discover a jackrabbit we
		// might already know about.
		_theRepository = CCNRepositoryManager.getRepositoryManager(primaryRepository);
		Library.logger().info("CCNInterestServer: initialize.");
	}

	@Override
	public NameList Enumerate_1(Name arg1) {
		// Right now we can only query over names,
		// not authenticators.
		// TODO: DKS cope with authenticators or otherwise
		//   make life cope with more than one piece of
		//   content with the same name.
		CompleteName name = new CompleteName(new ContentName(arg1), null);
		Library.logger().info("CCNInterestServer: Enumerating " + name.name());
		ArrayList<CompleteName> availableNames = null;
		try {
			Library.logger().info("About to call enumerate. Repository? " + ((null == _theRepository) ? "no" : "yes"));
			availableNames = _theRepository.enumerate(name);
			Library.logger().info("Enumerate_1: got " + availableNames.size() + " results.");
		
		} catch (IOException e) {
			Library.logger().warning("Exception in RPC server call Enumerate_1: " + e.getMessage());
			Library.warningStackTrace(e);
			Library.logger().warning("Returning error as a zero count in the List. Can't distinguish from no results.");
		}
		
		// Convert back to a NameList.
		NameList list = new NameList();
		list.count = ((null != availableNames) ? 
					   availableNames.size() : 0);
		list.names = new Name[list.count];
		for (int i=0; i < list.count; ++i) {
			list.names[i] = availableNames.get(i).name().toONCName();
		}
		return list;
	}

	@Override
	public DataBlock GetBlock_1(Name arg1) {
		// Put an XML encoded ContentObject.
		// Complain if more than one block matches name;
		// TODO cope if more than one block matches.
		ContentName name = new ContentName(arg1);
		Library.logger().info("CCNInterestServer: GetBlock, name = " + name);
		ArrayList<ContentObject> availableContent = null;
		try {
			availableContent = _theRepository.get(name, null);
		
			// Right now, can't send back more than
			// one block. If we get more than one, complain.
			if (availableContent.size() > 1) {
				Library.logger().info("GetBlock_1 retrieved " + availableContent.size() + " blocks of data for a single name.");
			}
			// Return the first block we got, if any.
			DataBlock block = new DataBlock();
			// The block contains an encoded ContentObject,
			// so a 0-length block means no results.
			if (availableContent.size() == 0) {
				Library.logger().info("No matching content found in GetBlock_1.");
				block.length = 0;
				block.data = new byte[0]; // don't know if this is necessary
			} else {				
				byte [] encodedContent = availableContent.get(0).encode();
				block.length = encodedContent.length;
				block.data = encodedContent;
				Library.logger().info("GetBlock_1 sending block of " + block.length + " bytes.");
			}
			return block;
			
		} catch (IOException e) {
			Library.logger().warning("Exception in RPC server call GetBlock_1: " + e.getMessage());
			Library.warningStackTrace(e);
			Library.logger().warning("Returning error as 0 block length. Can't distinguish from no content.");
			DataBlock block = new DataBlock();
			block.length = 0;
			block.data = new byte[0];
			return block;
		} catch (XMLStreamException e) {
			Library.logger().warning("Exception in RPC server call GetBlock_1: cannot encode content: " + e.getMessage());
			Library.warningStackTrace(e);
			Library.logger().warning("Returning error as 0 block length. Can't distinguish from no content.");
			DataBlock block = new DataBlock();
			block.length = 0;
			block.data = new byte[0];
			return block;
		}		
	}

	@Override
	public int PutBlock_1(Name arg1, DataBlock arg2) {
		// The data block should contain an XML
		// encoded ContentObject.
		try {
			Library.logger().info("CCNInterestServer: PutBlock");
			// Decode content object.
			ContentObject co = new ContentObject(arg2.data);
			_theRepository.put(co.name(), co.authenticator(), co.content());
			return 0;
			
		} catch (XMLStreamException e) {
			Library.logger().warning("Cannot decode data block!" + e.getMessage());
			Library.warningStackTrace(e);
			Library.logger().warning("Return negative error.");
			return -1;
		} catch (IOException e) {
			Library.logger().warning("Cannot put data block!" + e.getMessage());
			Library.warningStackTrace(e);
			Library.logger().warning("Return negative error.");
			return -2;
		}
	}

}
