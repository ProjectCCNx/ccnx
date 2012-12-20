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

package org.ccnx.ccn.impl.security.crypto.util;

import java.lang.reflect.Method;
import java.security.AlgorithmParameters;
import java.security.Key;
import java.security.NoSuchAlgorithmException;
import java.security.Provider;
import java.security.Security;
import java.security.interfaces.DSAKey;
import java.security.interfaces.DSAParams;
import java.security.interfaces.RSAKey;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.InvalidParameterSpecException;
import java.util.Enumeration;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/**
 * Adapted from the CDC Standard Provider's JCA class, this class provides
 * the facilities to look up signature algorithm OIDs from digest and encryption
 * algorithms, and vice versa.
 * 
 * We start by preloading a lookup table with the standard algorithms, and 
 * then attempt to screen-scrape any new ones added by providers from
 * their property lists.
 * 
 * <ul>
 * <li> It attempts to map aliases to standard JCA/JCE names wherever possible,
 * and assumes that all Aliases for an engine must map to the same name.
 * <li> Two OID mappings with the same OID must map to the same name.
 * <li> The <i>Digest</i>with<i>CIpher</i> must be set up as an alias or primary name.
 * (i.e. Signature.foo or Alg.Alias.Signature.foo).
 * <li> Signature engines that do not have a corresponding cipher engine still require
 * a reverse OID mapping of the form Alg.Alias.Cipher.OID.<i>oid</i> = <i>name</i>,
 * where <i>name</i> is the cipher name component of the cipher component
 * that signature algorithm.  This is only a problem for signature algorithms without
 * a corresponding cipher component. The most common of these, DSA and ECDSA,
 * are handled by default.
 * </ul>
 */
public class OIDLookup {

	static final boolean debug = true;

	/**
	 * Map from cipher (for ciphers used in signatures; includes DSA) to OID.
	 **/
	private static Map<String,String> _c2oid = new HashMap<String,String>();

	/**
	 * Map from OID to cipher.
	 **/
	private static Map<String,String> _oid2c = new HashMap<String,String>();

	/**
	 * Map from digest to OID.
	 **/
	private static Map<String,String> _d2oid = new HashMap<String,String>();

	/**
	 * Map from OID to digest.
	 **/
	private static Map<String,String> _oid2d = new HashMap<String,String>();

	/**
	 * Map from DigestwithCipher names to OIDs. Only one OID (the preferred one)
	 * for a DigestwithCipher.
	 **/
	private static Map<String,String> _s2oid = new HashMap<String,String>();

	/**
	 * Map from OID to DigestwithCipher names. Multiway -- all known OIDs listed.
	 **/
	private static Map<String,String> _oid2s = new HashMap<String,String>();
	
	/**
	 * Map from OID to Mac algorithm names. Multiway -- all known OIDs listed.
	 **/
	private static Map<String,String> _oid2m = new HashMap<String,String>();
	
	/**
	 * Map from Mac algorithm names to OIDs. Only one OID (the preferred one)
	 * for a DigestwithCipher.
	 **/
	private static Map<String,String> _m2oid = new HashMap<String,String>();

	/**
	 * Map from engine type to OID maps.
	 **/
	private static Map<String,Map<String,String>> _e2oid2alg = new HashMap<String, Map<String,String>>();

	/**
	 * Map that goes the other way.
	 **/
	private static Map<String,Map<String,String>> _e2alg2oid = new HashMap<String,Map<String,String>>();

	/**
	 * Map from provider name to provider alias map.
	 **/
	protected static Map<String,Map<String,String>> _aliasMap;
	
	/**
	 * Preload the maps.
	 **/
	static {

		_e2oid2alg.put("Signature", _oid2s);
		_e2oid2alg.put("Cipher", _oid2c);
		_e2oid2alg.put("MessageDigest", _oid2d);
		_e2oid2alg.put("Mac", _oid2m);

		_e2alg2oid.put("Signature", _s2oid);
		_e2alg2oid.put("MessageDigest", _d2oid);
		_e2alg2oid.put("Cipher", _c2oid);
		_e2alg2oid.put("Mac", _m2oid);

		_s2oid.put("SHA1withRSA", "1.2.840.113549.1.1.5");
		_oid2s.put("1.2.840.113549.1.1.5", "SHA1withRSA");
		_oid2s.put("1.3.14.3.2.29", "SHA1withRSA");
		_s2oid.put("SHAwithRSA", "1.2.840.113549.1.1.5");
		_s2oid.put("SHA-1withRSA", "1.2.840.113549.1.1.5");

		_s2oid.put("SHA256withRSA", "1.2.840.113549.1.1.11");
		_oid2s.put("1.2.840.113549.1.1.11", "SHA256withRSA");
		_s2oid.put("SHA-256withRSA", "1.2.840.113549.1.1.11");

		_s2oid.put("SHA384withRSA", "1.2.840.113549.1.1.12");
		_oid2s.put("1.2.840.113549.1.1.12", "SHA384withRSA");
		_s2oid.put("SHA-384withRSA", "1.2.840.113549.1.1.12");

		_s2oid.put("SHA512withRSA", "1.2.840.113549.1.1.13");
		_oid2s.put("1.2.840.113549.1.1.13", "SHA512withRSA");
		_s2oid.put("SHA-512withRSA", "1.2.840.113549.1.1.13");

		_s2oid.put("MD5withRSA", "1.2.840.113549.1.1.4");
		_oid2s.put("1.2.840.113549.1.1.4", "MD5withRSA");
		_oid2s.put("1.3.14.3.2.25", "MD5withRSA");

		_s2oid.put("RipeMD160withRSA", "1.3.36.3.3.1.2");
		_s2oid.put("RIPEMD160withRSA", "1.3.36.3.3.1.2");
		_oid2s.put("1.3.36.3.3.2.1", "RIPEMD160withRSA");

		_s2oid.put("SHA1withDSA", "1.2.840.10040.4.3");
		_oid2s.put("1.3.14.3.2.13", "SHA1withDSA");
		_oid2s.put("1.2.840.10040.4.3", "SHA1withDSA");
		_oid2s.put("1.3.14.3.2.27", "SHA1withDSA");
		_s2oid.put("SHAwithDSA", "1.2.840.10040.4.3");
		_s2oid.put("SHA-1withDSA", "1.2.840.10040.4.3");

		_s2oid.put("SHA1withECDSA", "1.2.840.10045.4.1");
		_oid2s.put("1.2.840.10045.4.1", "SHA1withECDSA");

		_s2oid.put("SHA224withECDSA", "1.2.840.10045.4.3.1");
		_s2oid.put("SHA-224withECDSA", "1.2.840.10045.4.3.1");
		_oid2s.put("1.2.840.10045.4.3.1", "SHA224withECDSA");
        
		_s2oid.put("SHA256withECDSA", "1.2.840.10045.4.3.2");
		_s2oid.put("SHA-256withECDSA", "1.2.840.10045.4.3.2");
		_oid2s.put("1.2.840.10045.4.3.2", "SHA256withECDSA");
        
		_s2oid.put("SHA384withECDSA", "1.2.840.10045.4.3.3");
		_s2oid.put("SHA-384withECDSA", "1.2.840.10045.4.3.3");
		_oid2s.put("1.2.840.10045.4.3.3", "SHA384withECDSA");
        
		_s2oid.put("SHA512withECDSA", "1.2.840.10045.4.3.4");
		_s2oid.put("SHA-512withECDSA", "1.2.840.10045.4.3.4");
		_oid2s.put("1.2.840.10045.4.3.4", "SHA512withECDSA");
        
		_d2oid.put("SHA1", "1.3.14.3.2.26");
		_d2oid.put("SHA-1", "1.3.14.3.2.26");
		_d2oid.put("SHA", "1.3.14.3.2.26");
		_oid2d.put("1.3.14.3.2.26", "SHA1");

		_d2oid.put("SHA256", "2.16.840.1.101.3.4.2.1");
		_d2oid.put("SHA-256", "2.16.840.1.101.3.4.2.1");
		_oid2d.put("2.16.840.1.101.3.4.2.1", "SHA256");
		_d2oid.put("SHA384", "2.16.840.1.101.3.4.2.2");
		_d2oid.put("SHA-384", "2.16.840.1.101.3.4.2.2");
		_oid2d.put("2.16.840.1.101.3.4.2.2", "SHA384");
		_d2oid.put("SHA512", "2.16.840.1.101.3.4.2.3");
		_d2oid.put("SHA-512", "2.16.840.1.101.3.4.2.3");
		_oid2d.put("2.16.840.1.101.3.4.2.3", "SHA512");

		_d2oid.put("MD4", "1.2.840.113549.2.4");
		_oid2d.put("1.2.840.113549.2.4", "MD4");

		_d2oid.put("MD5", "1.2.840.113549.2.5");
		_oid2d.put("1.2.840.113549.2.5", "MD5");

		_d2oid.put("RIPEMD160", "1.3.36.3.2.1");
		_d2oid.put("RipeMD160", "1.3.36.3.2.1");
		_oid2d.put("1.3.36.3.2.1", "RIPEMD160");

		_d2oid.put("RIPEMD128", "1.3.36.3.2.2");
		_d2oid.put("RipeMD128", "1.3.36.3.2.2");
		_oid2d.put("1.3.36.3.2.2", "RIPEMD128");

		_d2oid.put("SHA1MHT", "1.2.840.113550.11.1.2.1");
		_d2oid.put("SHA-1MHT", "1.2.840.113550.11.1.2.1");
		_oid2d.put("1.2.840.113550.11.1.2.1", "SHA1MHT");
		_d2oid.put("SHA256MHT", "1.2.840.113550.11.1.2.2");
		_d2oid.put("SHA-256MHT", "1.2.840.113550.11.1.2.2");
		_oid2d.put("1.2.840.113550.11.1.2.2", "SHA256MHT");

		_c2oid.put("RSA", "1.2.840.113549.1.1.1");
		_oid2c.put("1.2.840.113549.1.1.1", "RSA");

		_c2oid.put("DSA", "1.2.840.10040.4.1");
		_oid2c.put("1.2.840.10040.4.1", "DSA");
		_oid2c.put("1.3.14.3.2.12", "DSA");

		_c2oid.put("ECDSA", "1.2.840.10045.2.1");
		_c2oid.put("EC", "1.2.840.10045.2.1");  // PrivateKey.getAlgorithm()
		_oid2c.put("1.2.840.10045.2.1", "ECDSA");

		_c2oid.put("ElGamal", "1.3.14.7.2.1.1");
		_oid2c.put("1.3.14.7.2.1.1", "ElGamal");
		
		_c2oid.put("DH", "1.2.840.113549.1.3.1");
		_oid2c.put("1.2.840.113549.1.3.1", "DH");
		_oid2c.put("1.2.840.10046.2.1", "DH"); // ANSI 942, used by BouncyCastle
		
		_m2oid.put("HMac-SHA256", "1.2.840.112549.2.9");
		_m2oid.put("HMACSHA256", "1.2.840.112549.2.9");
		_oid2m.put("1.2.840.112549.2.9", "HMac-SHA256");
	
		_aliasMap = initAliasLookup();
	}

	/**
	 * Map from a digest algorithm name and a cipher algorithm name to an OID.
	 * @param digestAlg the digest algorithm.
	 * @param cipherAlg the cipher algorithm.
	 * @return the OID
	 */
	public static String getSignatureAlgorithmOID(String digestAlg, String cipherAlg) {

		digestAlg = resolveDigestAlias(digestAlg);
		cipherAlg = resolveCipherAlias(cipherAlg);

		StringBuffer signatureAlgorithm = new StringBuffer(digestAlg);
		signatureAlgorithm.append("with");
		signatureAlgorithm.append(cipherAlg);

		String oid = mapGet(_s2oid, signatureAlgorithm.toString());

		return oid; // might be null
	}

	/**
	 * Map from a digest algorithm name and a cipher algorithm to a signature algorithm
	 * name. If the signature algorithm name doesn't exist, return null.
	 * @param digestAlg the digest algorithm.
	 * @param cipherAlg the cipher algorithm.
	 * @return the signature algorithm.
	 */
	public static String getSignatureAlgorithm(String digestAlg, String cipherAlg) {
		
		String resolvedCipherAlg = resolveCipherAlias(cipherAlg);
		if (null == resolvedCipherAlg)
			return resolveMacAlias(cipherAlg);
		
		digestAlg = resolveDigestAlias(digestAlg);

		StringBuffer signatureAlgorithm = new StringBuffer(digestAlg);
		signatureAlgorithm.append("with");
		signatureAlgorithm.append(resolvedCipherAlg);
		String sigAlg = signatureAlgorithm.toString();

		String oid = mapGet(_s2oid, sigAlg.toString());

		if (oid != null) {
			return 	sigAlg;
		}
		return null;
	}	

	/**
	 * Map from a signature algorithm to a cipher.
	 * @param signatureAlgorithm the signature algorithm.
	 * @return the cipher.
	 */
	public static String signatureAlgorithmToCipher(String signatureAlgorithm) {
		signatureAlgorithm = resolveSignatureAlias(signatureAlgorithm);		
		String [] dac =  signatureAlgorithmToDigestAndCipher(signatureAlgorithm);
		return dac[1];
	}

	/**
	 * Map from a signature algorithm to a digest.
	 * @param signatureAlgorithm the signature algorithm.
	 * @return the digest.
	 */
	public static String signatureAlgorithmToDigest(String signatureAlgorithm) {
		signatureAlgorithm = resolveSignatureAlias(signatureAlgorithm);		
		String [] dac =  signatureAlgorithmToDigestAndCipher(signatureAlgorithm);
		return dac[0];
	}

	/**
	 * Get the OID for a cipher algorithm.
	 * This only works for ciphers used in signatures.
	 * @param cipherAlgorithm the cipher algorithm.
	 * @return the OID.
	 */
	public static String getCipherOID(String cipherAlgorithm)  {
		cipherAlgorithm = resolveCipherAlias(cipherAlgorithm);
		return mapGet(_c2oid, cipherAlgorithm);
	}

	/**
	 * Return the preferred OID for a digest algorithm.
	 * @param digestAlgorithm the digest algorithm.
	 * @return the OID.
	 */
	public static String getDigestOID(String digestAlgorithm)  {
		digestAlgorithm = resolveDigestAlias(digestAlgorithm);
		return mapGet(_d2oid, digestAlgorithm);
	}

	/**
	 * Return the preferred OID for a signature algorithm.
	 **/
	public static String getSignatureOID(String algorithm)  {
		algorithm = resolveSignatureAlias(algorithm);
		return mapGet(_s2oid, algorithm);
	}

	/**
	 * Return the preferred name for a signature OID.
	 **/
	public static String getSignatureName(String oid)  {
		return mapGet(_oid2s, oid);
	}

	/**
	 * Return the preferred name for a digest OID.
	 **/
	public static String getDigestName(String oid)  {
		return mapGet(_oid2d, oid);
	}

	/**
	 * Return the preferred name for a cipher OID.
	 **/
	public static String getCipherName(String oid)  {
		return mapGet(_oid2c, oid);
	}

	/**
	 * Parse a DigestwithCipher name into its digest and cipher components.
	 * Attempt to cope with aliases, etc.
	 **/
	public static String[] signatureAlgorithmToDigestAndCipher(String signatureAlgorithm) {
		signatureAlgorithm = resolveSignatureAlias(signatureAlgorithm);

		if (signatureAlgorithm == null) {
			return new String[]{null, null};
		}

		// Should be a splittable string.
		String [] dandc = signatureAlgorithm.split("with");

		if (dandc.length != 2) {
			// We have a problem.
			String [] dandctry2 = signatureAlgorithm.split("/");
			if (dandctry2.length != 2) {
				System.out.println("System error: splitting canonical signature algorithm name: " +
						signatureAlgorithm + 
				" failed. Not of the form DigestwithCipher or Digest/Cipher.");
			} else {
				dandc = dandctry2;
			}
		}
		return dandc;
	}


	/*
	 static private void dump(Map map)
		{
			Iterator i;
			Map.Entry entry;

			for (i=map.entrySet().iterator(); i.hasNext();)
			 {
				 entry = (Map.Entry)i.next();
				 System.out.println(
					 entry.getKey()+" = "+entry.getValue());
			 }
		}
	 */


	/**
	 * This method maps a given digest algorithm OID and
	 * cipher algorithm OID onto the standard name of the
	 * combined signature algorithm. For this to work the
	 * aliases must be well defined such as described below:
	 * <dl>
	 * <dt> Digest Algorithm
	 * <dd> Alg.Alias.MessageDigest.<i>oid</i><sub>1</sub>
	 *   = <i>digestAlg</i>
	 * <dt> Cipher Algorithm
	 * <dd> Alg.Alias.Cipher.<i>oid</i><sub>2</sub>
	 *   = <i>cipherAlg</i>
	 * <dt> Signature Algorithm
	 * <dd> Alg.Alias.Signature.<i>digestAlg</i>/<i>cipherAlg</i>
	 *   = <i>signatureAlg</i>
	 * </dl>
	 * The <i>oid</i> denotes the sequence of OID numbers
	 * separated by dots but without a leading &quot;OID.&quot;.
	 * In some cases, such as the DSA, there is no cipher engine
	 * corresponding to <i>oid</i><sub>2</sub>. In this case,
	 * <i>oid</i><sub>2</sub> must be mapped to the corresponding
	 * name by other engine types, such as a KeyFactory.<p>
	 *
	 * All found mappings are cached for future use, as well
	 * as the reverse mapping, which is much more complicated
	 * to synthesise.
	 *
	 * @param doid The string representation of the digest
	 *   algorithm OID. The OID must have a &quot;OID.&quot;
	 *   prefix.
	 * @param coid The string representation of the cipher
	 *   algorithm OID. The OID must have a &quot;OID.&quot;
	 *   prefix.
	 * @return The standard JCE name of the signature algorithm
	 *   or <code>null</code> if no mapping could be found.
	 */
	public static String getSignatureAlgorithmFromOIDs(String doid, String coid) {
		String dn;
		String cn;

		dn = getDigestName(doid);
		cn = getCipherName(coid);

		if (dn == null || cn == null)
			return null;

		return getSignatureAlgorithm(dn, cn);
	}


	/**
	 * Reads the properties of the installed providers and
	 * builds an optimized alias lookup table. All entries
	 * of the form
	 * <ol>
	 * <li> &quot;Alg.Alias.&quot;+&lt;engine&gt;+&quot;.&quot;+&lt;alias&gt;
	 *   = &lt;value&gt;
	 * <li> &quot;Alg.Alias.&quot;+&lt;engine&gt;+&quot;.OID.&quot;+&lt;oid&gt;
	 *   = &lt;value&gt;
	 * <li> &quot;Alg.Alias.&quot;+&lt;engine&gt;+&quot;.&quot;+&lt;oid&gt;
	 *   = &lt;value&gt;
	 * </ol>
	 * are transformed and stored in a hashmap which is used
	 * by this class in order to do quick lookups of aliases
	 * and OID mappings. The stored entries are of the form:
	 * <ol>
	 * <li> &lt;engine&gt;+&quot;.&quot;+&lt;alias&gt;
	 *   = &lt;value&gt;
	 * <li> &quot;oid.&quot;+&lt;value&gt;
	 *   = &lt;oid&gt;
	 * <li> &quot;oid.&quot;+&lt;oid&gt;
	 *   = &lt;value&gt;
	 * </ol>
	 * In case multiple providers define mappings for the same
	 * keys the mapping of the first registered provider wins.
	 */
	static private Map<String,Map<String,String>> initAliasLookup() {

		Enumeration<?> e;
		Provider[] providers;
		String k; // key
		String v; // value
		String s; // string
		String p; // previous mapping
		Map<String,Map<String,String>> map;
		int i;
		int j;

		map = new HashMap<String,Map<String,String>>();
		providers = Security.getProviders();
		Map<String,String> submap = null; // keep our aliases separate.

		/* We start from the last provider and work our
		 * way to the first one such that aliases of
		 * preferred providers overwrite entries of
		 * less favored providers.
		 */
		for (i=providers.length-1; i>=0; i--)  {

			e = providers[i].propertyNames();

			while (e.hasMoreElements())  {
				k = (String)e.nextElement();
				v = providers[i].getProperty(k);

				if (!k.startsWith("Alg.Alias.")) 
					continue;

				// Truncate k to <engine>.<alias>
				k = k.substring(10).toLowerCase();
				j = k.indexOf('.');

				if (j<1)
					continue;

				/* Copy <engine> to s
				 * Truncate k to <alias>
				 */
				s = k.substring(0,j);
				k = k.substring(j+1);

				if (k.length() < 1)
					continue;

				/* Get the engine alias map and the engine-oid maps. */
				submap = map.get(s);
				if (null == submap) {
					submap = new HashMap<String,String>();
					map.put(s, submap);
				}

				/* If <alias> starts with a digit, then we
				 * assume it is an OID. OIDs are uniquely
				 * defined, hence we omit <engine> in the
				 * oid mappings. But we also include the
				 * alias mapping for this oid.
				 */
				if (Character.isDigit(k.charAt(0))) {
					p = submap.get(k);
					if (p != null && p.length() >= v.length())
						continue;

					submap.put(k,v);

					// should we check to see if we have this one already? Should we add it to main
					// maps?
					Map<String,String> oidmap = _e2oid2alg.get(s);
					Map<String,String> algmap = _e2alg2oid.get(s);
					if ((null != oidmap) && (null != algmap)) {
						if (!oidmap.containsKey(k)) {
							oidmap.put(k, v);
							if (!algmap.containsKey(v)) {
								algmap.put(v, k);
							}
						}
					}

				}  else if (k.startsWith("oid.")) {
					/* If <alias> starts with the string "OID."
					 * then we found a reverse mapping. In that
					 * case we swap <alias> and the value of the
					 * mapping, and make an entry of the form
					 * "oid."+<value> = <oid>
					 */
					k = k.substring(4);
					v = v.toLowerCase();

					Map<String,String> oidmap = _e2oid2alg.get(s);
					Map<String,String> algmap = _e2alg2oid.get(s);
					if ((null != oidmap) && (null != algmap)) {
						if (!oidmap.containsKey(k)) {
							oidmap.put(k, v);
							if (!algmap.containsKey(v)) {
								algmap.put(v, k);
							}
						}
					}

				} else {
					/* In all other cases we make an entry of the
					 * form <engine>+"."+<alias> = <value> as is
					 * defined in the providers.
					 */
					submap.put(k, v);
				}
			}
		}
		return map;
	}

	/**
	 * For specific types, attempts to see if the current name passed in is the
	 * canonical name. If not passes problem to resolveAlias.
	 **/
	public static String resolveCipherAlias(String alias) {
		return resolveAlias("Cipher", alias);
	}

	public static String resolveDigestAlias(String alias) {
		return resolveAlias("MessageDigest", alias);
	}
	
	public static String resolveMacAlias(String alias) {
		return resolveAlias("Mac", alias);
	}

	public static String resolveSignatureAlias(String alias) {
		return resolveAlias("Signature", alias);
	}
	
	/*
	 * AES OIDs depend on key length.
	 */
/*	public static String getAESOID(String algorithm, int keyLength) {
		
	}
	
	public static String aesOIDToAlgorithm(String oid) {
		
	}
	
	public static int aesOIDToKeyLength(String oid) {
		
	}
*/
	
	/**
	 * Resolves the given alias to the standard JCA name for the
	 * given engine type. If no appropriate mapping is defined
	 * then <code>null</code> is returned. If the given alias is
	 * actually an OID string and there is an appropriate alias
	 * mapping defined for that OID by some provider then the
	 * corresponding JCA name is returned.
	 * 
	 * @param engine The JCA engine type name.
	 * @param alias The alias to resolve for the given engine type.
	 * @return The standard JCA name or <code>null</code> if no
	 *   appropriate mapping could be found.
	 */
	public static String resolveAlias(String engine, String alias) {

		if (alias == null || engine == null)
			throw new NullPointerException("Engine or alias is null!");

		if (alias.length() < 1)
			throw new IllegalArgumentException("Zero-length alias!");

		Map<String,String> oid2alg = mapGet(_e2oid2alg, engine);
		Map<String,String> alg2oid = mapGet(_e2alg2oid, engine);

		if ((null != oid2alg) && (null != alg2oid)) {
			if (Character.isDigit(alias.charAt(0))) {
				// oid
				if (mapContainsKey(oid2alg, alias)) {
					return mapGet(oid2alg, alias);
				}
			} else {
				if (mapContainsKey(alg2oid, alias)) {
					return reverseLookup(alg2oid, oid2alg, alias);
				}
			}
		}

		Map<String,String> engineMap = _aliasMap.get(engine);
		if (null != engineMap) {

			alias = mapGet(engineMap, alias.toLowerCase());

			if ((null != alias) && (null != oid2alg) && (null != alg2oid)) {
				if (Character.isDigit(alias.charAt(0))) {
					// oid
					if (mapContainsKey(oid2alg, alias)) {
						return mapGet(oid2alg, alias);
					}
				} else {
					if (mapContainsKey(alg2oid, alias)) {
						return reverseLookup(alg2oid, oid2alg, alias);
					}
				}
			}
		}
		return null;
	}

	/**
	 * Map accessors that handle synchronization. 
	 * TODO replace with read-only maps, and make these go away after
	 * initialization. (Can also use Collection.synchronizedMap).
	 **/
	public static boolean mapContainsKey(Map<?,?> map, Object key) {

		synchronized(map) {
			return map.containsKey(key);
		}
	}

	public static <T1,T2> T2 mapGet(Map<T1,T2> map, T1 key) {
		synchronized(map) {
			return map.get(key);
		}
	}

	public static <T1,T2> void mapPut(Map<T1,T2> map, T1 key, T2 value) {
		synchronized(map) {
			if (!map.containsKey(key))
				map.put(key, value);
		}
	}

	public static String reverseLookup(Map<String,String> e2oid, Map<String,String> oid2e, String alias) {

		String oid = mapGet(e2oid, alias);
		// Shouldn't be null
		if (null == oid) {
			return oid;	
		}
		return mapGet(oid2e, oid);
	}

	/**
	 * Unfortunately, there's no easy way to do this.
	 * Need to add a way to get parameters from each new key type. Makes it hard to add
	 * new key types dynamically. The parameter interfaces should be cleaned up in Java.
	 * So instead, we try reflection...
	 **/
	public static AlgorithmParameters getParametersFromKey(Key key) 
		 throws NoSuchAlgorithmException, InvalidParameterSpecException {

		AlgorithmParameters algParams = null;

		// Handle the obvious cases, try to get a little general with reflection.
		if (key instanceof RSAKey) {
			// do nothing, params should be null (as opposed to RSAKeyGenerator parameters,
			// which actually do contain stuff). Don't use those here.
		} if (key instanceof DSAKey) {
			DSAParams params = ((DSAKey)key).getParams();

			algParams = AlgorithmParameters.getInstance("DSA");
			// the only class implementing DSAParams is DSAParameterSpec
			algParams.init((AlgorithmParameterSpec)params);

		} else {

			// Let's see if we can find a method called getParams or getParameters that 
			// returns something that can be coerced into an AlgorithmParameters or an AlgorithmParametersSpec.
			Method [] methods = key.getClass().getDeclaredMethods();

			// Try them in order that we get them.
			for (int i=0; i < methods.length; ++i) {
				if ((methods[i].getName().equalsIgnoreCase("getParams")) ||
						(methods[i].getName().equalsIgnoreCase("getParameters"))) {

					if (AlgorithmParameters.class.isAssignableFrom(methods[i].getReturnType())) {
						// Pass in null for any arguments.
						Object [] args = new Object[methods[i].getParameterTypes().length];
						try {
							algParams = (AlgorithmParameters)methods[i].invoke(key, args);
							if (null != algParams) {
								break; // we're done}
							}
						} catch (Exception ex) {
							// Go around again
							if (debug) {
								System.out.println("Tried invoking method: " + methods[i].getName() + " on object of type: " +
										key.getClass().getName() + ", got exception: " +
										ex.getClass().getName() + " message: " + ex.getMessage());
							}
							continue;
						}
					} else if (AlgorithmParameterSpec.class.isAssignableFrom(methods[i].getReturnType())) {
						// Pass in null for any arguments.
						Object [] args = new Object[methods[i].getParameterTypes().length];
						try {
							AlgorithmParameterSpec spec = (AlgorithmParameterSpec)methods[i].invoke(key, args);
							if (null == spec) {
								continue;
							}
							algParams = AlgorithmParameters.getInstance(key.getAlgorithm());

							if (algParams != null) {
								algParams.init(spec);
								if (algParams != null) {
									break;
								}
							}

						} catch (Exception ex) {
							// Go around again
							if (debug) {
								System.out.println("Tried invoking method: " + methods[i].getName() + " on object of type: " +
										key.getClass().getName() + ", got exception: " +
										ex.getClass().getName() + " message: " + ex.getMessage());
							}
							continue;
						}
					}						
				}
			}
		}

		return algParams;
	}

	public static AlgorithmParameters getParamsFromKey(RSAKey rsakey) {
		// don't want the generation parameters, want the null
		return null;
	}

	/**
	 * Prints the list of loaded providers.
	 */
	public static void listLoadedProviders() {
		Set<String> providers = _aliasMap.keySet();
		for (String s : providers) {
			System.out.println("OIDLookup: loaded provider " + s);
		}
	}

	/**
	 * Prints the list of loaded aliases.
	 */
	public static void listLoadedAliases() {
		Set<String> providers = _aliasMap.keySet();
		for (String p : providers) {
			Map<String,String> aliases = _aliasMap.get(p);
			System.out.println("Dumping aliases for provider: " + p);
			for (String a : aliases.keySet()) {
				System.out.println("	" + a + ": " + aliases.get(a));
			}
		}
	}

	/**
	 * Static use only.
	 **/
	private OIDLookup() {}
}
