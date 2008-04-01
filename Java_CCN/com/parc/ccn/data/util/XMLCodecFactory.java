package com.parc.ccn.data.util;

import java.lang.reflect.Constructor;
import java.util.HashMap;
import java.util.logging.Level;

import com.parc.ccn.Library;
import com.parc.ccn.config.SystemConfiguration;

public class XMLCodecFactory {
	
	protected static String _defaultCodec = null;
	
	protected static HashMap<String,Class<? extends XMLEncoder>> _registeredEncoders =
											new HashMap<String,Class<? extends XMLEncoder>>();
	protected static HashMap<String,Class<? extends XMLDecoder>> _registeredDecoders =
											new HashMap<String,Class<? extends XMLDecoder>>();
	
	public static void registerEncoder(String name, Class<? extends XMLEncoder> encoderClass) {
		_registeredEncoders.put(name, encoderClass);
	}
	
	public static void registerDecoder(String name, Class<? extends XMLDecoder> decoderClass) {
		_registeredDecoders.put(name, decoderClass);
	}

	public static void setDefaultCodec(String name) {
		if ((null == getEncoderClass(name)) || (null == getDecoderClass(name))) {
			Library.logger().warning("Cannot set unknown codec " + name + " as default XML codec.");
			throw new IllegalArgumentException(name + " must be a registered codec!");
		}
		_defaultCodec = name;
	}
	
	/**
	 * If default codec has been set for this runtime using setDefaultCodec,
	 * use that value. If not, go to SystemConfiguration to get either the
	 * command-line value if present or the compiled-in default.
	 * @return
	 */
	public static String getDefaultCodecName() { 
		if (null != _defaultCodec)
			return _defaultCodec; 
		return SystemConfiguration.getDefaultEncoding();
	}
	
	/**
	 * Get instance of default encoder.
	 * @return
	 */
	public static XMLEncoder getEncoder() {
		return getEncoder(null);
	}
	
	/**
	 * Get an instance of the specified encoder.
	 * @param codecName
	 * @return
	 */
	public static XMLEncoder getEncoder(String codecName) {
		Class<? extends XMLEncoder> encoderClass = getEncoderClass(codecName);
		
		// Have the class. Now need to use the default constructor.
		Class<?> argumentTypes[] = new Class[]{};
		Constructor<? extends XMLEncoder> ctr = null;
		try {
			ctr = encoderClass.getConstructor(argumentTypes);
		} catch (Exception e) {
			Library.logger().warning("Unexpected error: cannot get XMLEncoder constructor for repository class " + encoderClass.getName());
			Library.logStackTrace(Level.WARNING, e);
			throw new RuntimeException("Unexpected error: cannot get XMLEncoder constructor for repository class " + encoderClass.getName(), e);
		} 
		if (null == ctr) {
			Library.logger().warning("Unexpected error: repository class " + encoderClass.getName() + " has no default constructor.");
			throw new RuntimeException("Unexpected error: repository class " + encoderClass.getName() + " has no default constructor.");
		}
		
		// Now call it
		Object arglist[] = new Object[]{};
		XMLEncoder encoder = null;
		try {
			encoder = ctr.newInstance(arglist);
		} catch (IllegalArgumentException e) {
			Library.logger().warning("Illegal argument exception: cannot create instance of encoder class " + encoderClass.getName());
			Library.logStackTrace(Level.WARNING, e);
			throw e;
		} catch (Exception e) {
			Library.logger().warning("Unexpected error: cannot create instance of encoder class " + encoderClass.getName());
			Library.logStackTrace(Level.WARNING, e);
			throw new RuntimeException("Unexpected error: cannot create instance of encoder class " + encoderClass.getName(), e);
		}
		
		return encoder;
	}
	
	/**
	 * Get instance of default decoder.
	 */
	public static XMLDecoder getDecoder() {
		return getDecoder(null);
	}

	/**
	 * Get instance of specified decoder.
	 * @param codecName
	 * @return
	 */
	public static XMLDecoder getDecoder(String codecName) {
		Class<? extends XMLDecoder> encoderClass = getDecoderClass(codecName);
		
		// Have the class. Now need to use the default constructor.
		Class<?> argumentTypes[] = new Class[]{};
		Constructor<? extends XMLDecoder> ctr = null;
		try {
			ctr = encoderClass.getConstructor(argumentTypes);
		} catch (Exception e) {
			Library.logger().warning("Unexpected error: cannot get XMLEncoder constructor for repository class " + encoderClass.getName());
			Library.logStackTrace(Level.WARNING, e);
			throw new RuntimeException("Unexpected error: cannot get XMLEncoder constructor for repository class " + encoderClass.getName(), e);
		} 
		if (null == ctr) {
			Library.logger().warning("Unexpected error: repository class " + encoderClass.getName() + " has no default constructor.");
			throw new RuntimeException("Unexpected error: repository class " + encoderClass.getName() + " has no default constructor.");
		}
		
		// Now call it
		Object arglist[] = new Object[]{};
		XMLDecoder decoder = null;
		try {
			decoder = ctr.newInstance(arglist);
		} catch (IllegalArgumentException e) {
			Library.logger().warning("Illegal argument exception: cannot create instance of encoder class " + encoderClass.getName());
			Library.logStackTrace(Level.WARNING, e);
			throw e;
		} catch (Exception e) {
			Library.logger().warning("Unexpected error: cannot create instance of encoder class " + encoderClass.getName());
			Library.logStackTrace(Level.WARNING, e);
			throw new RuntimeException("Unexpected error: cannot create instance of encoder class " + encoderClass.getName(), e);
		}
		
		return decoder;
	}

	public static Class<? extends XMLEncoder> getEncoderClass(String codecName) {
		if (null == codecName)
			return getDefaultEncoderClass();
		return _registeredEncoders.get(codecName);
	}
	
	public static Class<? extends XMLDecoder> getDecoderClass(String codecName) {
		if (null == codecName)
			return getDefaultDecoderClass();
		return _registeredDecoders.get(codecName);
	}
	
	public static Class<? extends XMLEncoder> getDefaultEncoderClass() {
		return getEncoderClass(getDefaultCodecName());
	}
	
	public static Class<? extends XMLDecoder> getDefaultDecoderClass() {
		return getDecoderClass(getDefaultCodecName());
	}

}
