package com.parc.ccn.data.util;

import java.util.HashMap;

import com.parc.ccn.Library;

public class XMLCodecFactory {
	
	protected static String _defaultCodec;
	
	protected static HashMap<String,Class<XMLEncoder>> _registeredEncoders =
											new HashMap<String,Class<XMLEncoder>>();
	protected static HashMap<String,Class<XMLDecoder>> _registeredDecoders =
		new HashMap<String,Class<XMLDecoder>>();
	
	public static void registerEncoder(String name, Class<XMLEncoder> encoderClass) {
		_registeredEncoders.put(name, encoderClass);
	}
	
	public static void registerDecoder(String name, Class<XMLDecoder> decoderClass) {
		_registeredDecoders.put(name, decoderClass);
	}

	public static Class<XMLEncoder> getEncoderClass(String name) {
		return _registeredEncoders.get(name);
	}
	
	public static Class<XMLDecoder> getDecoderClass(String name) {
		return _registeredDecoders.get(name);
	}

	public static void setDefaultCodec(String name) {
		if ((null == getEncoderClass(name)) || (null == getDecoderClass(name))) {
			Library.logger().warning("Cannot set unknown codec " + name + " as default XML codec.");
			throw new IllegalArgumentException(name + " must be a registered codec!");
		}
		_defaultCodec = name;
	}
	
	public static String getDefaultCodecName() { return _defaultCodec; }
	
	public static Class<XMLEncoder> getDefaultEncoderClass() {
		return getEncoderClass(getDefaultCodecName());
	}
	
	public static Class<XMLDecoder> getDefaultDecoderClass() {
		return getDecoderClass(getDefaultCodecName());
	}
	

}
