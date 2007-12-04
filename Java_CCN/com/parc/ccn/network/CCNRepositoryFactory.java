package com.parc.ccn.network;

import java.lang.reflect.Constructor;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.logging.Level;

import javax.jmdns.ServiceInfo;

import com.parc.ccn.Library;

/**
 * Map from a given ServiceInfo discovery response to
 * a particular repository class.
 * @author smetters
 *
 */
public class CCNRepositoryFactory {
	
	protected static HashMap<Class<?>, String> _repositoryClassToServiceType = new HashMap<Class<?>,String>();
	protected static HashMap<String, Class<?>> _serviceNameToRespositoryClass = new HashMap<String,Class<?>>();
	
	public static void registerRepositoryType(String serviceType, String serviceName,
											  Class<?> repositoryClass) {
		_serviceNameToRespositoryClass.put(serviceName, repositoryClass);
		_repositoryClassToServiceType.put(repositoryClass, serviceType);
	}
	
	public static GenericCCNRepository connect(ServiceInfo info) throws MalformedURLException {
		
		String url = info.getURL();
		// Assume URL is of the form:
		// serviceType://host:port/serviceName
		
		
		String [] nameSplit = url.split(GenericCCNRepository.NAME_SEPARATOR);
		if (nameSplit.length != 2) {
			Library.logger().info("Malformed URL in service discovery response: " + url);
			throw new MalformedURLException("Malformed URL in service discovery response: " + url);
		}
		
		Class<?> repoClass = _serviceNameToRespositoryClass.get(nameSplit[1]);
		if (_repositoryClassToServiceType.get(repoClass).equals(info.getType())) {
			Library.logger().info("Can't find class that matches both service name " + nameSplit[1] + 
								   " and service type " + info.getType() +
								   " best option is " + repoClass.getName());
			
			throw new MalformedURLException("Can't find class that matches both service name " + nameSplit[1] + 
								   " and service type " + info.getType() +
								   " best option is " + repoClass.getName());
		}
		
		// Have the class. Now need to use the serviceInfo constructor.
		Class<?> argumentTypes[] = new Class[]{ServiceInfo.class};
		Constructor<?> ctr = null;
		try {
			// Oy. I don't know how to parameterize this classwize
			// given that I don't know for sure what class repoClass is...
			ctr = repoClass.getConstructor(argumentTypes);
		} catch (Exception e) {
			Library.logger().warning("Unexpected error: cannot get ServiceInfo constructor for repository class " + repoClass.getName());
			Library.logStackTrace(Level.WARNING, e);
			throw new RuntimeException("Unexpected error: cannot get ServiceInfo constructor for repository class " + repoClass.getName(), e);
		} 
		if (null == ctr) {
			Library.logger().warning("Unexpected error: repository class " + repoClass.getName() + " has no ServiceInfo constructor.");
			throw new RuntimeException("Unexpected error: repository class " + repoClass.getName() + " has no ServiceInfo constructor.");
		}
		
		// Now call it
		Object arglist[] = new Object[]{info};
		GenericCCNRepository repository = null;
		try {
			repository = (GenericCCNRepository)ctr.newInstance(arglist);
		} catch (IllegalArgumentException e) {
			Library.logger().warning("Illegal argument exception: cannot create instance of repository class " + repoClass.getName() + " from service info: " + info.getURL());
			Library.logStackTrace(Level.WARNING, e);
			throw e;
		} catch (Exception e) {
			Library.logger().warning("Unexpected error: cannot create instance of repository class " + repoClass.getName() + " from service info: " + info.getURL());
			Library.logStackTrace(Level.WARNING, e);
			throw new RuntimeException("Unexpected error: cannot create instance of repository class " + repoClass.getName() + " from service info: " + info.getURL(), e);
		}
		
		return repository;
	}
}
