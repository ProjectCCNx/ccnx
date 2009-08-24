package org.ccnx.ccn.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;

public class GenericObjectInputStream<E> extends ObjectInputStream {

	public GenericObjectInputStream(InputStream paramInputStream)
			throws IOException {
		super(paramInputStream);
	}

	@SuppressWarnings("unchecked")
	public E genericReadObject() throws IOException, ClassNotFoundException {
		return (E) readObject();
	}
}