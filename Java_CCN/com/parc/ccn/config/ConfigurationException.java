package com.parc.ccn.config;

public class ConfigurationException extends Exception {
	
	private static final long serialVersionUID = -8498363015808971905L;

	public ConfigurationException(String message) {
		super(message);
	}
	
	public ConfigurationException(Exception e) {
		super(e);
	}

	public ConfigurationException() {
		super();
	}
}
