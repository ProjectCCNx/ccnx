package com.parc.ccn.data.security;

import java.security.cert.CertificateEncodingException;
import java.util.Arrays;

import javax.xml.stream.XMLStreamException;

import org.bouncycastle.asn1.x509.DigestInfo;

import com.parc.ccn.Library;
import com.parc.ccn.data.util.DataUtils;
import com.parc.ccn.data.util.GenericXMLEncodable;
import com.parc.ccn.data.util.XMLDecoder;
import com.parc.ccn.data.util.XMLEncodable;
import com.parc.ccn.data.util.XMLEncoder;
import com.parc.ccn.security.crypto.DigestHelper;
import com.parc.ccn.security.crypto.MerklePath;
import com.parc.ccn.security.crypto.certificates.OIDLookup;

public class Signature extends GenericXMLEncodable implements XMLEncodable,
		Comparable<Signature> {
	
    protected static final String SIGNATURE_ELEMENT = "Signature";
    protected static final String DIGEST_ALGORITHM_ELEMENT = "DigestAlgorithm";
	protected static final String WITNESS_ELEMENT = "Witness";
    protected static final String SIGNATURE_BITS_ELEMENT = "SignatureBits";

    byte [] _witness;
	byte [] _signature;
	String _digestAlgorithm;
	
	public Signature(String digestAlgorithm, byte [] witness, byte [] signature) {
    	_witness = witness;
    	_signature = signature;
    	_digestAlgorithm = digestAlgorithm;
	}

	public Signature(byte [] witness,
					 byte [] signature) {
		this(null, witness, signature);
	}

	public Signature(byte [] signature) {
		this(null, null, signature);
	}
	
	public Signature() {} // for use by decoders
	
	public byte [] signature() { return _signature; }
	
	public void signature(byte [] signature) { _signature = signature; }
	
	public byte [] witness() { return _witness; }

	public void witness(byte [] witness) { _witness = witness; }
	
	public String digestAlgorithm() {
		if (null == _digestAlgorithm)
			return DigestHelper.DEFAULT_DIGEST_ALGORITHM;
		return _digestAlgorithm;
	}
	
	public void digestAlgorithm(String digestAlgorithm) { _digestAlgorithm = digestAlgorithm; }

	@Override
	public void decode(XMLDecoder decoder) throws XMLStreamException {
		decoder.readStartElement(SIGNATURE_ELEMENT);

		if (decoder.peekStartElement(DIGEST_ALGORITHM_ELEMENT)) {
			_digestAlgorithm = decoder.readUTF8Element(DIGEST_ALGORITHM_ELEMENT); 
		}

		if (decoder.peekStartElement(WITNESS_ELEMENT)) {
			_witness = decoder.readBinaryElement(WITNESS_ELEMENT); 
		}

		_signature = decoder.readBinaryElement(SIGNATURE_BITS_ELEMENT);
		
		decoder.readEndElement();
	}

    @Override
	public void encode(XMLEncoder encoder) throws XMLStreamException {
    	
		if (!validate()) {
			throw new XMLStreamException("Cannot encode " + this.getClass().getName() + ": field values missing.");
		}
		
		encoder.writeStartElement(SIGNATURE_ELEMENT);
		
		if ((null != digestAlgorithm()) && (!digestAlgorithm().equals(DigestHelper.DEFAULT_DIGEST_ALGORITHM))) {
			encoder.writeElement(DIGEST_ALGORITHM_ELEMENT, OIDLookup.getDigestOID(digestAlgorithm()));
		}
		
		if (null != witness()) {
			// needs to handle null witness
			encoder.writeElement(WITNESS_ELEMENT, _witness);
		}

		encoder.writeElement(SIGNATURE_BITS_ELEMENT, _signature);

		encoder.writeEndElement();   		
	}

	@Override
	public boolean validate() {
		return null != signature();
	}

	public Signature clone() {
		return new Signature(digestAlgorithm(), (null != _witness) ? _witness.clone() : null, _signature.clone());
	}

	public int compareTo(Signature o) {
		int result = 0;
		if (null == digestAlgorithm()) {
			if (null != o.digestAlgorithm())
				result = DigestHelper.DEFAULT_DIGEST_ALGORITHM.compareTo(o.digestAlgorithm());
		} else {
			result = digestAlgorithm().compareTo((null == o.digestAlgorithm()) ? DigestHelper.DEFAULT_DIGEST_ALGORITHM : o.digestAlgorithm());
		}
		if (result == 0)
			result = DataUtils.compare(witness(), o.witness());
		if (result == 0)
			result = DataUtils.compare(this.signature(), o.signature());
		return result;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(_signature);
		result = prime * result + Arrays.hashCode(_witness);
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Signature other = (Signature) obj;
		if (null == digestAlgorithm()) {
			if (null != other.digestAlgorithm())
				if (!DigestHelper.DEFAULT_DIGEST_ALGORITHM.equals(other.digestAlgorithm()))
					return false;
		} else {
			if (!digestAlgorithm().equals((null == other.digestAlgorithm()) ? 
							DigestHelper.DEFAULT_DIGEST_ALGORITHM : other.digestAlgorithm()))
				return false;
		}
		if (!Arrays.equals(_signature, other._signature))
			return false;
		if (!Arrays.equals(_witness, other._witness))
			return false;
		return true;
	}

	public byte[] computeProxy(byte[] nodeContent, boolean isDigest) throws CertificateEncodingException {
		if (null == witness())
			return null;
		
		DigestInfo info = DigestHelper.digestDecoder(witness());
		
		byte [] proxy = null;
		
		if (MerklePath.isMerklePath(info)) {
			MerklePath mp = new MerklePath(info.getDigest());
			proxy = mp.root(nodeContent, isDigest);
		} else {
			Library.logger().warning("Unexpected witness type: " + info.getAlgorithmId().toString());
		}
		return proxy;
	}
}
