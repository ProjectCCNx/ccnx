package com.parc.ccn.data.util;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SignatureException;

import javax.xml.crypto.Data;
import javax.xml.crypto.OctetStreamData;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.TransformException;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import com.parc.ccn.Library;

/**
 * Helper class for objects that use the JAXP stream
 * encode and decode operations to read and write
 * themselves.
 * @author smetters
 *
 */
public abstract class GenericXMLEncodable implements XMLEncodable {

	protected GenericXMLEncodable() {}
	
	/**
	 * Don't provide a constructor that takes a byte[]. It
	 * can decode fine, but subclasses don't have their members
	 * set up to accept the data yet. Do the base constructor
	 * and then call decode.
	 */
	
 	public void decode(InputStream iStream) throws XMLStreamException {
		XMLEventReader reader = XMLHelper.beginDecoding(iStream);
		decode(reader);
		XMLHelper.endDecoding(reader);
	}
 	
 	public void decode(byte [] content) throws XMLStreamException {
 		ByteArrayInputStream bais = new ByteArrayInputStream(content);
 		decode(bais);
 	}
	
	public void encode(OutputStream oStream) throws XMLStreamException {
		XMLStreamWriter writer = XMLHelper.beginEncoding(oStream);
		encode(writer, true);
		XMLHelper.endEncoding(writer);	
	}

	public void encode(XMLStreamWriter writer) throws XMLStreamException {
		encode(writer, false);
	}
	
	public abstract void encode(XMLStreamWriter writer, 
				boolean isFirstElement) throws XMLStreamException;
	
	public abstract void decode(XMLEventReader reader) throws XMLStreamException;

	public abstract boolean validate();
	
	public byte [] encode() throws XMLStreamException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		encode(baos);
		return baos.toByteArray();
	}
	
	public byte [] canonicalizeAndEncode() throws XMLStreamException {
		// DKS TODO: figure out canonicalization
		// byte [] canonicalizedData = canonicalize(this, signingKey);
		byte[] canonicalizedData = null;
		try {
			canonicalizedData = encode();
		} catch (XMLStreamException e) {
			Library.logger().warning("Exception encoding toBeSigned: " + e.getMessage());
			Library.warningStackTrace(e);
		}
		return canonicalizedData;
	}
	
	/**
	 * Right now only easy canonicalization interface requires
	 * a key to make a context...
	 * @param key
	 * @return
	 * @throws XMLStreamException
	 */
	public byte [] canonicalizeAndEncode(PrivateKey key) throws XMLStreamException {
		byte [] encodedContents = null;
		try {
			// DKS TODO figure out canonicalization of content
		//	encodedContents = SignatureHelper.canonicalize(this, key);
			encodedContents = encode();
//		} catch (SignatureException e) {
		} catch (Exception e) {
			Library.logger().warning("Cannot canonicalize " + this.getClass().getName());
			Library.warningStackTrace(e);
			throw new XMLStreamException(e);
		}
		return encodedContents;
	}

	@Override
	public String toString() {
		return XMLHelper.toString(this);
	}
	
	/**
	 * This is really annoying -- we need to pass in a key
	 * to instantiate the appropriate context to canonicalize.
	 * For some reason the Java XML signature API is focusing
	 * on canonicalization of signature info objects, rather
	 * than canonicalization of the things below them to
	 * actually be signed. 
	 * DKS TODO: look into the Apache API.
	 * @param toBeSigned
	 * @param signingKey
	 * @return
	 * @throws SignatureException
	 */
	public static byte [] canonicalize(XMLEncodable toBeSigned, PrivateKey signingKey) throws SignatureException {

		byte[] encoded;
		try {
			encoded = toBeSigned.encode();
		} catch (XMLStreamException e1) {
			Library.logger().warning("This should not happen: we cannot encode " + toBeSigned.getClass().getName() + " to be signed!");
			Library.warningStackTrace(e1);
			throw new SignatureException(e1);
		}
		
		ByteArrayInputStream bais = new ByteArrayInputStream(encoded);
		// Canonicalize XML document
        XMLSignatureFactory xmlSignatureFactory =
            XMLSignatureFactory.getInstance();
        
        DocumentBuilderFactory documentBuilderFactory =
            DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = null;
		try {
			builder = documentBuilderFactory.newDocumentBuilder();
		} catch (ParserConfigurationException e1) {
			Library.handleException("This really should not happen: parser configuration exception -- error in DOM setup.", e1);
		}
        Document document = null;
		try {
			document = builder.parse(bais);
		} catch (SAXException e1) {
			Library.handleException("This should not happen: we cannot parse mapping information to be signed!", e1);
		} catch (IOException e1) {
			Library.handleException("This should not happen: we cannot read mapping information to be signed!", e1);
		}
    	Node node = document.getDocumentElement();
    	
    	bais.reset();
		OctetStreamData osd = new OctetStreamData(bais);
		
        DOMSignContext cryptoContext = new DOMSignContext(signingKey, node);

        String canonicalizationAlg = CanonicalizationMethod.EXCLUSIVE_WITH_COMMENTS;
        C14NMethodParameterSpec canonParams = null;
        Data out = null;
        String canonicalizedData = null;
        try {
			CanonicalizationMethod canonicalizationMethod =
			        xmlSignatureFactory.
			        newCanonicalizationMethod(
			        		canonicalizationAlg, canonParams);
			if (null == canonicalizationMethod) {
				Library.logger().warning("Cannot find canonicalization method: " + canonicalizationAlg);
				throw new TransformException("Cannot find canonicalization method: " + canonicalizationAlg);
			}
			out = canonicalizationMethod.transform(osd, cryptoContext);
			
			canonicalizedData = out.toString();
			
			Library.logger().info("Canonicalized data: " + canonicalizedData);
			
        } catch (NoSuchAlgorithmException e) {
        	Library.handleException("This really should not happen: configuration error -- cannot find canonicalization algorithm.", e);
		} catch (InvalidAlgorithmParameterException e) {
			Library.handleException("This really should not happen: configuration error -- cannot find canonicalization algorithm parameters.", e);
		} catch (TransformException e) {
			Library.handleException("This should not happen: we cannot canonicalize mapping information to be signed!", e);
		}
		// Is there a problem with locale issues?
		// DKS TODO: may need to get closer to real XML sigs.
		return canonicalizedData.getBytes();
	}

}


