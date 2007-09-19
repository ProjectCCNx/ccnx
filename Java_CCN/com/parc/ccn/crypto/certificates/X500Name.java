package com.parc.ccn.crypto.certificates;

import java.security.Principal;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.Vector;

import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DERObjectIdentifier;
import org.bouncycastle.asn1.DERPrintableString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERSet;
import org.bouncycastle.asn1.DERString;
import org.bouncycastle.asn1.DERUTF8String;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.asn1.x509.X509NameTokenizer;

/**
 * @author D.K. Smetters
 *
 * Replacement for BouncyCastle's X509Name. We can almost use that one,
 * but want to be able to do incremental construction.
 * 
 * Old version of X500Name using codec had some complex
 * canonicalization information inside it in order to
 * cope with a significant problem in Java prior to 1.4 or
 * 1.5, namely that Sun's own certificate implementation
 * would reorder DN components when it returned them,
 * so that if you decoded a certificate with various
 * certificate classes, you might get names out in
 * different orders; and that that might happen transparently
 * to the programmer (who didn't even think it was an issue
 * that they were using Sun's own classes). So we canonicalized
 * the names in the database, which made life confusing.
 * 
 * Java 1.5 is good about maintaining the ordering
 * of name components. It offers a few ways to spit out
 * names, but those have to do with formatting (what
 * string does "C" turn into), not ordering.
 * 
 * We want to move to an idea where the DNs in the database
 * are in the same order as they are in the certificate,
 * but where the certificate is always considered primary
 * just in case we still run into this.
 * 
 * When we issue a certificate, we want to maintain the 
 * DN ordering in the issuer certificate. For the subject
 * (unless given a DN ordering), we want to make sure
 * it goes in in the right order, which is CN first
 * (Sun's broken version did C first).
 */
public class X500Name implements Principal, DEREncodable {

    /**
     * look up table translating OID values into their common symbols.
     */
    public static Hashtable<DERObjectIdentifier,String> OIDLookUp = new Hashtable<DERObjectIdentifier,String>();

    /**
     * look up table translating common symbols into their OIDS.
     */
    public static Hashtable<String,DERObjectIdentifier> SymbolLookUp = new Hashtable<String,DERObjectIdentifier>();

    /**
     * Order of OIDs for canonical printout
     **/
    public static Vector<DERObjectIdentifier> CanonicalOrdering = 
    				new Vector<DERObjectIdentifier>();

    static
    {
        OIDLookUp.put(X509Name.C, "C");
        OIDLookUp.put(X509Name.O, "O");
        // Title
        OIDLookUp.put(X509Name.T, "T");
        OIDLookUp.put(X509Name.OU, "OU");
        OIDLookUp.put(X509Name.CN, "CN");
        OIDLookUp.put(X509Name.L, "L");
        OIDLookUp.put(X509Name.ST, "ST");
        // Surname
        OIDLookUp.put(X509Name.SN, "SN");
    	// Change the string associated with email address
        OIDLookUp.put(X509Name.EmailAddress, "EMAILADDRESS");
        // DomainComponent
        OIDLookUp.put(X509Name.DC, "DC");

        SymbolLookUp.put("c", X509Name.C);
        SymbolLookUp.put("o", X509Name.O);
        SymbolLookUp.put("t", X509Name.T);
        SymbolLookUp.put("ou", X509Name.OU);
        SymbolLookUp.put("cn", X509Name.CN);
        SymbolLookUp.put("l", X509Name.L);
        SymbolLookUp.put("st", X509Name.ST);
        SymbolLookUp.put("sn", X509Name.SN);
        SymbolLookUp.put("emailaddress", X509Name.E);
        SymbolLookUp.put("dc", X509Name.DC);
        SymbolLookUp.put("e", X509Name.E);
        
        /**
         * Change this order to change the order names are printed
         * by toCanonicalString. Order of name storage in the
         * database is the order given in the certificate, read
         * out using Java (Java 1.5 fixed the reordering done
         * in previous versions of Java, and now gives back the
         * order in the certificate).
         **/
        // Leave these out; they're almost never used, and 
        // we don't know how to order them.
        //CanonicalOrdering.add(X509Name.DC);
        //CanonicalOrdering.add(X509Name.SN);
        //CanonicalOrdering.add(X509Name.T);
        CanonicalOrdering.add(X509Name.CN);
        CanonicalOrdering.add(X509Name.OU);
        CanonicalOrdering.add(X509Name.O);
        CanonicalOrdering.add(X509Name.L);
        CanonicalOrdering.add(X509Name.ST);
        CanonicalOrdering.add(X509Name.C);
        CanonicalOrdering.add(X509Name.E);
    }

    protected Vector<DERObjectIdentifier> _ordering = new Vector<DERObjectIdentifier>();
    protected Vector<String> _values = new Vector<String>();
    protected ASN1Sequence _seq;

    public static X500Name getInstance(ASN1TaggedObject obj, boolean explicit) {
        return getInstance(ASN1Sequence.getInstance(obj, explicit));
    }

    public static X500Name getInstance(Object obj) {
        if (obj instanceof X509Name) {
            return (X500Name)obj;
        } else if (obj instanceof ASN1Sequence) {
            return new X500Name((ASN1Sequence)obj);
        }
        throw new IllegalArgumentException("Unknown object in factory");
    }

    /**
     * Constructor from DERConstructedSequence.
     *
     * the principal will be a list of constructed sets, each containing an (OID, String) pair.
     */
    public X500Name(ASN1Sequence  seq) {

        this._seq = seq;
        Enumeration e = seq.getObjects();
        while (e.hasMoreElements()) {
            DERSet  set = (DERSet)e.nextElement();
            Enumeration  s = set.getObjects();

            _ordering.addElement((DERObjectIdentifier)s.nextElement());
            _values.addElement(((DERString)s.nextElement()).getString());
        }
    }

    /**
     * constructor from a table of attributes.
     * <p>
     * it's is assumed the table contains OID/String pairs, and the contents
     * of the table are copied into an internal table as part of the 
     * construction process.
     * <p>
     * <b>Note:</b> if the name you are trying to generate should be
     * following a specific ordering, you should use the constructor
     * with the ordering specified below.
     */
    public X500Name(Hashtable  attributes)   {
        this(null, attributes);
    }

    /**
     * constructor from a table of attributes with ordering.
     * <p>
     * it's is assumed the table contains OID/String pairs, and the contents
     * of the table are copied into an internal table as part of the 
     * construction process. The ordering vector should contain the OIDs
     * in the order they are meant to be encoded or printed in toString.
     */
    public X500Name(Vector ordering, Hashtable attributes) {
        if (ordering != null) {
            for (int i = 0; i != ordering.size(); i++) {
                this._ordering.addElement((DERObjectIdentifier)ordering.elementAt(i));
            }
        } else {
            Enumeration e = attributes.keys();

            while (e.hasMoreElements()) {
                this._ordering.addElement((DERObjectIdentifier)e.nextElement());
            }
        }

        for (int i = 0; i != this._ordering.size(); i++) {
            DERObjectIdentifier oid = (DERObjectIdentifier)this._ordering.elementAt(i);

            if (OIDLookUp.get(oid) == null) {
                throw new IllegalArgumentException("Unknown object id - " + oid.getId() + " - passed to distinguished name");
            }

            if (attributes.get(oid) == null) {
                throw new IllegalArgumentException("No attribute for object id - " + oid.getId() + " - passed to distinguished name");
            }

            this._values.addElement((String)attributes.get(oid)); // copy the hash table
        }
    }

    /**
     * takes two vectors one of the oids and the other of the values.
     */
    public X500Name(Vector ordering, Vector values) {

        if (ordering.size() != values.size()) {
            throw new IllegalArgumentException("Ordering vector must be same length as values.");
        }

        for (int i = 0; i < ordering.size(); i++) {
            this._ordering.addElement((DERObjectIdentifier)ordering.elementAt(i));
            this._values.addElement((String)values.elementAt(i));
        }
    }

    /**
     * takes an X509 dir name as a string of the format "C=AU, ST=Victoria", or
     * some such, converting it into an ordered set of name attributes.
     */
    public X500Name(String dirName) {
        X509NameTokenizer   nTok = new X509NameTokenizer(dirName);

        while (nTok.hasMoreTokens()) {

            String  token = nTok.nextToken();
            int     index = token.indexOf('=');

            if (index == -1) {
                throw new IllegalArgumentException("Badly formated directory string");
            }

            String  name = token.substring(0, index).trim();
            String  value = token.substring(index + 1).trim();

            DERObjectIdentifier oid = (DERObjectIdentifier)SymbolLookUp.get(name.toLowerCase());
            if (oid == null) {
                throw new IllegalArgumentException("Unknown object id - " + name + " - passed to distinguished name");
            }

            this._ordering.addElement(oid);
            this._values.addElement(value);
        }
    }
    
    /**
     * Building constructor. Makes an empty name.
     */
    public X500Name() {}

	public String getElement(DERObjectIdentifier der) {
		for(int i = 0; i < _values.size(); i++) {
			if(_ordering.get(i).equals(der)) {
				return _values.get(i).toString();
			}
		}
		return null;			
	}

	public void addElement(DERObjectIdentifier der, String value) {
		_ordering.addElement(der);
		_values.addElement(value);
	}
	
	public String getCN() {
		return getElement(X509Name.CN);
	}

	public String getE() {
		return getElement(X509Name.E);
	}
   

    /**
     * return false if we have characters out of the range of a printable
     * string, true otherwise.
     */
    private boolean canBePrintable(String str) {
        for (int i = str.length() - 1; i >= 0; i--) {
            if (str.charAt(i) > 0x007f) {
                return false;
            }
        }
        return true;
    }

    public DERObject getDERObject() {
        if (_seq == null) {

            for (int i = 0; i != _ordering.size(); i++) {
            	ASN1EncodableVector ev = new ASN1EncodableVector();
                DERObjectIdentifier oid = (DERObjectIdentifier)_ordering.elementAt(i);

                ev.add(oid);

                String  str = (String)_values.elementAt(i);

                if (oid.equals(X509Name.EmailAddress)) {
                    ev.add(new DERIA5String(str));
                } else {
                    if (canBePrintable(str)) {
                        ev.add(new DERPrintableString(str));
                    } else {
                        ev.add(new DERUTF8String(str));
                    }
                }

                _seq = new DERSequence(new DERSet(ev));
            }
        }

        return _seq;
    }

	/**
	 * Checks equality component-wise. Does not require matching order
	 * of the components in the two names. If obj is not an X500Name, attempts
	 * to make it one (through a string conversion). Either X500Name, or 
	 * BouncyCastle's own X509Name will do equals comparisons this way.
	 **/
    public boolean equals(Object obj)  {

        if (obj == this) {
            return true;
        }

        if (obj == null) {
            return false;
        }
        
        X500Name intObj = null;
        if (obj instanceof X500Name)
        	intObj = (X500Name)obj;
        else 
        	intObj = new X500Name(obj.toString());
        	
        // Check equality component-wise
         int  orderingSize = _ordering.size();

        if(orderingSize != intObj._ordering.size()) {
			return false;
		}
		
		boolean[] indexes = new boolean[orderingSize];

		for(int i = 0; i < orderingSize; i++) {
			boolean found = false;
			String  oid   = ((DERObjectIdentifier)_ordering.elementAt(i)).getId();
			String  val   = (String)_values.elementAt(i);
			
			for(int j = 0; j < orderingSize; j++)  {
				if(indexes[j] == true) {
					continue;
				}
				
				String oOID = ((DERObjectIdentifier)intObj._ordering.elementAt(j)).getId();
				String oVal = (String)intObj._values.elementAt(j);

				if(oid.equals(oOID) && val.equals(oVal)) {
					indexes[j] = true;
					found      = true;
					break;
				}
			}

			if(!found) {
				return false;
			}
		}
		
		return true;
	}
	
    public int hashCode() {
        DERSequence seq = (DERSequence)this.getDERObject();
        Enumeration e = seq.getObjects();
        int hashCode = 0;

        while (e.hasMoreElements()) {
            hashCode ^= e.nextElement().hashCode();
        }

        return hashCode;
    }


    public String toString() {

        StringBuffer buf = new StringBuffer();
        boolean first = true;
        Enumeration e1 = _ordering.elements();
        Enumeration e2 = _values.elements();

        while (e1.hasMoreElements()) {
            Object oid = e1.nextElement();
            String sym = (String)OIDLookUp.get(oid);
            
            if (first) {
                first = false;
            } else {
                buf.append(",");
            }

            if (sym != null) {
                buf.append(sym);
            } else {
                buf.append(((DERObjectIdentifier)oid).getId());
            }

            buf.append("=");
            buf.append((String)e2.nextElement());
        }

        return buf.toString();
    }

    public String toCanonicalString() {
        StringBuffer buf = new StringBuffer();
        boolean first = true;
        Enumeration emap = CanonicalOrdering.elements();
        
        while (emap.hasMoreElements())  {
            Object oid = emap.nextElement();
            String sym = (String)OIDLookUp.get(oid);
            
            // Does this name contain this token? If so, output all ofthem.
            for (int i=0; i < _ordering.size(); ++i) {
            	if (oid.equals(_ordering.get(i))) {
            		if (first)
            			first = false;
            		else
            			buf.append(",");
            			
            		if (null != sym)
            			buf.append(sym);
            		else
            			buf.append(((DERObjectIdentifier)oid).getId());
            			
            		buf.append("=");
            		buf.append((String)_values.get(i));
            	}
            }
        }
        return buf.toString();
    }
    
	/**
	 * @see java.security.Principal#getName()
	 */
	public String getName() {
		return toString();
	}
	
	public static String trimDirString(String dirName) {
        StringBuffer buf = new StringBuffer();
        boolean first = true;
        
        X509NameTokenizer nTok = new X509NameTokenizer(dirName);
        Vector<DERObjectIdentifier> thisOrdering = new Vector<DERObjectIdentifier>();
        Vector<String> theseValues = new Vector<String>();

        while (nTok.hasMoreTokens()) {
            String token = nTok.nextToken();
            int index = token.indexOf('=');

            if (index == -1) {
                throw new IllegalArgumentException("Badly formated directory string");
            }

            String name = token.substring(0, index).trim();
            String value = token.substring(index + 1).trim();

            DERObjectIdentifier oid = (DERObjectIdentifier)SymbolLookUp.get(name.toLowerCase());
            if (oid == null) {
                throw new IllegalArgumentException("Unknown object id - " + name + " - passed to distinguished name");
            }

            thisOrdering.addElement(oid);
            theseValues.addElement(value);
        }

        Enumeration e1 = thisOrdering.elements();
        Enumeration e2 = theseValues.elements();

        while (e1.hasMoreElements())  {
            Object oid = e1.nextElement();
            String sym = (String)OIDLookUp.get(oid);
            
            if (first) {
                first = false;
            } else {
                buf.append(",");
            }

            if (sym != null) {
                buf.append(sym);
            } else {
                buf.append(((DERObjectIdentifier)oid).getId());
            }
            buf.append("=");
            buf.append((String)e2.nextElement());
        }
        return buf.toString();
	}
}
