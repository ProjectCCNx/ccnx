/*
 * Part of the CCNx Java Library.
 *
 * Copyright (C) 2008-2012 Palo Alto Research Center, Inc.
 *
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License version 2.1
 * as published by the Free Software Foundation.
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details. You should have received
 * a copy of the GNU Lesser General Public License along with this library;
 * if not, write to the Free Software Foundation, Inc., 51 Franklin Street,
 * Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.ccnx.ccn.impl.encoding;

import java.util.HashMap;
import java.util.logging.Level;

import org.ccnx.ccn.config.SystemConfiguration;
import org.ccnx.ccn.impl.support.Log;

/**
 * Factory class that given a string codec name, returns the XMLEncoder and XMLDecoder
 * that handle that codec. Allows new codecs to be registered on the fly for extensibility.
 */
public class XMLCodecFactory {

	protected static String _defaultCodec = null;

	static {
		// Make sure this happens before any registrations
		_registeredEncoders = new HashMap<String,Class<? extends XMLEncoder>>();
		_registeredDecoders = new HashMap<String,Class<? extends XMLDecoder>>();

		// Register standard system encoders and decoders here. If
		// registration placed in static initializers of their own class,
		// those aren't called till the classes are loaded, which doesn't
		// happen till we make one -- which we can't do till they're
		// registered. Chicken and egg... avoid by doing it here.
		XMLCodecFactory.registerEncoder(TextXMLCodec.codecName(),
										TextXMLEncoder.class);
		XMLCodecFactory.registerDecoder(TextXMLCodec.codecName(),
										TextXMLDecoder.class);
		XMLCodecFactory.registerDecoder(BinaryXMLCodec.codecName(),
										BinaryXMLDecoder.class);
		XMLCodecFactory.registerEncoder(BinaryXMLCodec.codecName(),
										BinaryXMLEncoder.class);
	}

	protected static HashMap<String,Class<? extends XMLEncoder>> _registeredEncoders;
	protected static HashMap<String,Class<? extends XMLDecoder>> _registeredDecoders;

	public static void registerEncoder(String name, Class<? extends XMLEncoder> encoderClass) {
		_registeredEncoders.put(name, encoderClass);
	}

	public static void registerDecoder(String name, Class<? extends XMLDecoder> decoderClass) {
		_registeredDecoders.put(name, decoderClass);
	}

	public static void setDefaultCodec(String name) {
		if ((null == getEncoderClass(name)) || (null == getDecoderClass(name))) {
			Log.warning(Log.FAC_ENCODING, "Cannot set unknown codec " + name + " as default XML codec.");
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

		if (null == encoderClass) {
			return null;
		}

		// Have the class. Now need to use the default constructor.
		XMLEncoder encoder = null;
		try {
			encoder = encoderClass.newInstance();
		} catch (Exception e) {
			Log.warning("Unexpected error: cannot create instance of encoder class " + encoderClass.getName());
			Log.logStackTrace(Level.WARNING, e);
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
		Class<? extends XMLDecoder> decoderClass = getDecoderClass(codecName);

		if (null == decoderClass) {
			return null;
		}
		// Have the class. Now need to use the default constructor.
		XMLDecoder decoder = null;
		try {
			decoder = decoderClass.newInstance();
		} catch (Exception e) {
			Log.warning("Unexpected error: cannot create instance of decoder class " + decoderClass.getName());
			Log.logStackTrace(Level.WARNING, e);
			throw new RuntimeException("Unexpected error: cannot create instance of decoder class " + decoderClass.getName(), e);
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
