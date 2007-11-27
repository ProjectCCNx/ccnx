package test.ccn.security.crypto.certificates;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.Security;
import java.security.SignatureException;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidParameterSpecException;
import java.util.Calendar;
import java.util.Date;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.security.auth.x500.X500Principal;

import junit.framework.Assert;
import junit.framework.TestCase;

import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1StreamParser;
import org.bouncycastle.asn1.DEREncodable;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x509.X509CertificateStructure;
import org.bouncycastle.asn1.x509.X509Name;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jce.provider.X509CertificateObject;

import com.parc.ccn.security.crypto.SignatureHelper;
import com.parc.ccn.security.crypto.certificates.BCX509CertificateGenerator;

/**
 * @author smetters
 *
 * To change this generated comment edit the template variable "typecomment":
 * Window>Preferences>Java>Templates.
 * To enable and disable the creation of type comments go to
 * Window>Preferences>Java>Code Generation.
 */
public class BCX509CertificateGeneratorTest extends TestCase {

	/**
	 * Constructor for BCX509CertificateGeneratorTest.
	 * @param arg0
	 */
	public BCX509CertificateGeneratorTest(String arg0) {
		super(arg0);
	}

	public static void main(String[] args) {
	}
	
	static KeyPair testPair;
	static KeyPair testPair2;
	static KeyPair testPair3;
	static SecureRandom random = new SecureRandom();
	static final String issuerDN = "CN=An Issuer,O=A Company,C=US";
	static final String subjectDN1 = "CN=A Subject,O=A Company,C=US";
	static final String subjectDN2 = "CN=Evil Minion,O=A Company,C=US";
	static final String rootDN = "C=US,O=Organization,OU=Organizational Unit,CN=Issuer";
	static final String intDN = "CN=Intermediate,OU=Int OU,O=Int Org,C=US,E=int@int.org";
	static final String endDN = "C=US,O=Final Org,L=Locality,CN=Fred Jones,E=fred@final.org";
	static final Date start = new Date(); 
	static final Date end = new Date(start.getTime() + (60*60*24*365));
	static final String s_emailAddress = "minion@company.com";
	static final String s_ipAddress = "13.13.13.13";
	static final String s_dnsName = "machine.company.com";
	
	static {
		try {
			Security.addProvider(new BouncyCastleProvider());
			
			// generate key pair
			KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
			kpg.initialize(512); // go for fast
			testPair = kpg.generateKeyPair();
			testPair2 = kpg.generateKeyPair();
			testPair3 = kpg.generateKeyPair();
		} catch (Exception ex) {
			handleException(ex);
			System.out.println("Unable To Initialize Test!!!");
		}	
	}

	public void testAddEKUExtension() throws Exception {
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
					issuerDN,
					subjectDN1,
					null,
					start,
					end,
					null);

			String [] purposes = new String[] {
											BCX509CertificateGenerator.id_kp_clientAuth,
											BCX509CertificateGenerator.id_kp_ipsec,
											BCX509CertificateGenerator.id_kp_registrationAgent};
											
			gen.addEKUExtension(false, purposes);
																			
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testAddEKUExtension.der", cert);
			
			X509Certificate cert2 = inputCert("testAddEKUExtension.der");
			// DKS
			cert2.verify(testPair.getPublic());
			cert.verify(testPair.getPublic());
			
			if (!BCX509CertificateGenerator.hasExtendedKeyUsage(
							cert, BCX509CertificateGenerator.id_kp_clientAuth) ||
				!BCX509CertificateGenerator.hasExtendedKeyUsage(
				 			cert, BCX509CertificateGenerator.id_kp_ipsec) ||
				 !BCX509CertificateGenerator.hasExtendedKeyUsage(
				 			cert, BCX509CertificateGenerator.id_kp_registrationAgent)) {
				throw new Exception("Failed -- no EKU!");
			}
																																
		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
	}

	public void testAddExtendedKeyUsage() throws Exception {
		
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
																																
			gen.addClientAuthenticationEKU();
			gen.addExtendedKeyUsage(BCX509CertificateGenerator.id_kp_ipsec);
			gen.addExtendedKeyUsage(BCX509CertificateGenerator.id_kp_registrationAgent);
																			
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testAddEKU.der", cert);
			X509Certificate cert2 = inputCert("testAddEKU.der");
			// DKS
			System.out.println("Class of generated cert: " + cert.getClass().getName());
			System.out.println("Class of read cert: " + cert2.getClass().getName());
			cert2.verify(testPair.getPublic(), "BC");
			cert.verify(testPair.getPublic());
			
			Assert.assertTrue(BCX509CertificateGenerator.hasExtendedKeyUsage(
											cert, BCX509CertificateGenerator.id_kp_clientAuth));
			Assert.assertTrue(BCX509CertificateGenerator.hasExtendedKeyUsage(
				 							cert, BCX509CertificateGenerator.id_kp_ipsec));
			Assert.assertTrue(BCX509CertificateGenerator.hasExtendedKeyUsage(
				 				cert, BCX509CertificateGenerator.id_kp_registrationAgent));
			Set criticalExtensions = cert.getCriticalExtensionOIDs();
			Assert.assertTrue(!criticalExtensions.contains(BCX509CertificateGenerator.id_ce_extendedKeyUsage));
																																
		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
	}

	public void testRemoveExtendedKeyUsage() throws Exception {
		
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
																																
			gen.addClientAuthenticationEKU();
			gen.addExtendedKeyUsage(BCX509CertificateGenerator.id_kp_ipsec);
			gen.addExtendedKeyUsage(BCX509CertificateGenerator.id_kp_registrationAgent);
			
			gen.removeExtendedKeyUsage(BCX509CertificateGenerator.id_kp_registrationAgent);

			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testRemoveEKU.der", cert);
			cert.verify(testPair.getPublic());
			
			Assert.assertTrue(BCX509CertificateGenerator.hasExtendedKeyUsage(
											cert, BCX509CertificateGenerator.id_kp_clientAuth));
			Assert.assertTrue(BCX509CertificateGenerator.hasExtendedKeyUsage(
				 							cert, BCX509CertificateGenerator.id_kp_ipsec));
			Assert.assertTrue(!BCX509CertificateGenerator.hasExtendedKeyUsage(
				 				cert, BCX509CertificateGenerator.id_kp_registrationAgent));
		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
	}

	public void testHasExtendedKeyUsage() {
	}

	public void testSetServerAuthenticationUsage() {
	}

	public void testAddServerAuthenticationEKU() throws Exception {
		
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
																																
			gen.addServerAuthenticationEKU();
																	
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testAddServerAuthenticationEKU.der", cert);
			cert.verify(testPair.getPublic());
			
			if (!BCX509CertificateGenerator.hasExtendedKeyUsage(
							cert, BCX509CertificateGenerator.id_kp_serverAuth)) {
				throw new Exception("Failed -- no server auth EKU!");
			}
																																
		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
	}

	public void testAddClientAuthenticationEKU() throws Exception {
		
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
																																
			gen.addClientAuthenticationEKU();
																	
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testAddClientAuthenticationEKU.der", cert);
			cert.verify(testPair.getPublic());
			
			if (!BCX509CertificateGenerator.hasExtendedKeyUsage(
							cert, BCX509CertificateGenerator.id_kp_clientAuth)) {
				throw new Exception("Failed -- no client auth EKU!");
			}
																																
		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
	}

	public void testSetSecureEmailUsage() {
	}

	public void testAddSecureEmailEKU() throws Exception {
		
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
																																
			gen.addSecureEmailEKU();
																	
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testAddSecureEmailEKU.der", cert);
			cert.verify(testPair.getPublic());
			
			if (!BCX509CertificateGenerator.hasExtendedKeyUsage(
							cert, BCX509CertificateGenerator.id_kp_emailProtection)) {
				throw new Exception("Failed -- no secure email EKU!");
			}
																																
		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
	}

	public void testAddSubjectAltName() throws Exception {
		
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
																																
			gen.addSubjectAlternativeName(false, BCX509CertificateGenerator.DNSNAMEAlternativeNameType,
																	s_dnsName);
			gen.addSubjectAlternativeName(false, BCX509CertificateGenerator.IPADDRESSAlternativeNameType,
																	s_ipAddress);
			gen.addSubjectAlternativeName(false, BCX509CertificateGenerator.RFC822AlternativeNameType,
																	s_emailAddress);
																	
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testSubjectAltName.der", cert);
			cert.verify(testPair.getPublic());
			
			if (null == cert.getExtensionValue(BCX509CertificateGenerator.id_ce_subjectAltName)) {
				throw new Exception("Failed -- no subjectAltName extension!");
			}
																																
		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
																															
	}

	public void testAddCRLDistributionPoints() throws Exception {
		
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
															
			String [] strDistPoints = new String[]{"http://ca.parc.xerox.com/crl?ParcCA.crl",
																	  "ldap://ca.parc.xerox.com/X509Entity/CRL/ParcCA",
																	  "https://ca.parc.xerox.com/crl?ParcCA.crl"};																	
			gen.addCRLDistributionPointsExtension(false, strDistPoints);
																																
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testCRLDistributionPointsExtension.der", cert);
			cert.verify(testPair.getPublic());
			
			if (null == cert.getExtensionValue(BCX509CertificateGenerator.id_ce_crlDistributionPoints)) {
				throw new Exception("Failed -- no CRLDistributionPoints extension!");
			}
																																
			BCX509CertificateGenerator gen2 = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
															
			String [] strDistPoints2 = new String[]{"http://ca.parc.xerox.com/crl?ParcCA.crl"};
			gen2.addCRLDistributionPointsExtension(false, strDistPoints2);
																																
			X509Certificate cert2 = gen2.sign(null, testPair.getPrivate());
			outputCert("testCRLDistributionPointsExtension2.der", cert2);
			cert2.verify(testPair.getPublic());
			
			if (null == cert2.getExtensionValue(BCX509CertificateGenerator.id_ce_crlDistributionPoints)) {
				throw new Exception("Failed -- no CRLDistributionPoints extension!");
			}

			BCX509CertificateGenerator gen3 = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
															
			String [] strDistPoints3 = new String[]{"http://ca.parc.xerox.com/crl?ParcCA.crl",
																	  "ldap://ca.parc.xerox.com/X509Entity/CRL/ParcCA"};
			gen3.addCRLDistributionPointsExtension(false, strDistPoints3);
																																
			X509Certificate cert3 = gen3.sign(null, testPair.getPrivate());
			outputCert("testCRLDistributionPointsExtension3.der", cert3);
			cert3.verify(testPair.getPublic());
			
			if (null == cert3.getExtensionValue(BCX509CertificateGenerator.id_ce_crlDistributionPoints)) {
				throw new Exception("Failed -- no CRLDistributionPoints extension!");
			}

		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
																															
	}

	public void testAddBasicConstraints() throws Exception {

		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
			gen.addBasicConstraints(true, true);
																	
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testAddBasicConstraintsCAnopath.der", cert);
			cert.verify(testPair.getPublic());

			Assert.assertEquals(cert.getBasicConstraints(), Integer.MAX_VALUE);

			BCX509CertificateGenerator gen2 = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
			gen2.addBasicConstraints(true, true, 0);
																	
			X509Certificate cert2 = gen2.sign(null, testPair.getPrivate());
			outputCert("testAddBasicConstraintsCA.der", cert2);
			Assert.assertEquals(cert2.getBasicConstraints(), 0);
			cert2.verify(testPair.getPublic());

			BCX509CertificateGenerator gen3 = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
			gen3.addBasicConstraints(false, false);
																	
			X509Certificate cert3 = gen3.sign(null, testPair.getPrivate());
			outputCert("testAddBasicConstraintsnonCA.der", cert3);
			cert3.verify(testPair.getPublic());
			Assert.assertEquals(cert3.getBasicConstraints(), -1);
		} catch (Exception ex) {
			throw ex;
		}
	}

	public void testIsCACertificate() {
	}

	public void testGetPathLenConstraint() {
	}

	public void testAddKeyUsage() throws Exception {
		
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);
			boolean [] keyUsageBits = new boolean[] {true, true, false, false, false, true, true, false, false};
			gen.addKeyUsage(false, keyUsageBits);
																	
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testAddKeyUsage.der", cert);
			cert.verify(testPair.getPublic());

			if (!BCX509CertificateGenerator.hasKeyUsage(
							cert, BCX509CertificateGenerator.DigitalSignatureKeyUsageBit)) {
				throw new Exception("Failed -- no digital signature key usage!");
			}
																																
			boolean [] keyUsageBitsAll = new boolean[] {true, true, true, true, true, true,true, true, true};
			gen.addKeyUsage(false, keyUsageBitsAll);
																	
			X509Certificate certAll = gen.sign(null, testPair.getPrivate());
			outputCert("testAddKeyUsageAll.der", certAll);
			certAll.verify(testPair.getPublic());

			for (int i=0; i < BCX509CertificateGenerator.NUM_KEY_USAGE_BITS; ++i) {
				if (!BCX509CertificateGenerator.hasKeyUsage(certAll, i)) {
					throw new Exception("Failed -- no key usage " + i + "!");
				}
			}

			boolean [] keyUsageBitsFirst = new boolean[] {true, false, false, false, false, false, false, false, false};
			gen.addKeyUsage(false, keyUsageBitsFirst);
																	
			X509Certificate certFirst = gen.sign(null, testPair.getPrivate());
			outputCert("testAddKeyUsageFirst.der", certFirst);
			certFirst.verify(testPair.getPublic());
			Assert.assertTrue("Failed -- no digital signature key usage in first-bit only cert!",
										BCX509CertificateGenerator.hasKeyUsage(
											certFirst, 
											BCX509CertificateGenerator.DigitalSignatureKeyUsageBit));

			boolean [] keyUsageBitsLast = new boolean[] {false, false, false, false, false, false, false, false, true};
			gen.addKeyUsage(false, keyUsageBitsLast);
																	
			X509Certificate certLast = gen.sign(null, testPair.getPrivate());
			outputCert("testAddKeyUsageLast.der", certLast);
			certLast.verify(testPair.getPublic());
			Assert.assertTrue("No DecipherOnly key usage in last-bit only cert!",
										 BCX509CertificateGenerator.hasKeyUsage(
											certLast, 
											BCX509CertificateGenerator.DecipherOnlyKeyUsageBit));

		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
	}

	public void testAddKeyIdentifiers() throws Exception {
		
		try {
			BCX509CertificateGenerator gen = new BCX509CertificateGenerator(testPair.getPublic(),
																																issuerDN,
																																subjectDN1,
																																null,
																																start,
																																end,
																																null);


			gen.addSubjectKeyIdentifierExtension(false);
			gen.addAuthorityKeyIdentifierExtension(false,
																	BCX509CertificateGenerator.generateKeyID(testPair.getPublic()),
																	issuerDN,
																	BigInteger.valueOf(37));
																	
			X509Certificate cert = gen.sign(null, testPair.getPrivate());
			outputCert("testAddKeyIDs.der", cert);
			cert.verify(testPair.getPublic());

			if (null == cert.getExtensionValue(BCX509CertificateGenerator.id_ce_subjectKeyIdentifier)) {
				throw new Exception("Failed -- no subjectKeyIdentifier extension!");
			}
			if (null == cert.getExtensionValue(BCX509CertificateGenerator.id_ce_authorityKeyIdentifier)) {
				throw new Exception("Failed -- no authorityKeyIdentifier extension!");
			}
			
			cert.verify(testPair.getPublic());
																																
		} catch (Exception ex) {
			handleException(ex);
			throw ex;
		}
	}

	/*
	 * Test for X509Certificate GenerateX509Certificate
	 */
	public void testGenerateX509Certificate() 
		throws InvalidAlgorithmParameterException,
				InvalidParameterSpecException, NoSuchAlgorithmException,
				 InvalidKeyException, IOException, CertificateEncodingException, 
				 SignatureException, NoSuchProviderException, CertificateException {
		
		/** Test making a 3-chain **/
		String rootDN = "E=ca@parc.com, CN=PARC Root Certification Authority, O=PARC, C=US";
		String intDN = "CN=PARC Network Certification Authority, O=PARC, C=US";
		String endDN = "CN=Bob Smeltz, O=PARC, C=US, EMAILADDRESS=smetters@parc.com";
		String endDN2 = "CN=PARC\\smetters, O=PARC, C=US, EMAILADDRESS=smetters@parc.com";
		
		X509Certificate rootCert = 
				BCX509CertificateGenerator.GenerateX509Certificate(testPair, rootDN, 10000);
		
		outputCert("chainTestRootCert.der", rootCert);
		rootCert.verify(testPair.getPublic());

		BCX509CertificateGenerator intGen = 
				new BCX509CertificateGenerator(testPair2.getPublic(), rootDN, intDN,
																			 null, null);
		intGen.setDuration(10000);
		intGen.addBasicConstraints(true, true);
		intGen.addSubjectKeyIdentifierExtension(false);
		byte [] issuerKeyID = BCX509CertificateGenerator.getKeyIDFromCertificate(rootCert);
		if (null == issuerKeyID) {
			issuerKeyID = BCX509CertificateGenerator.generateKeyID(rootCert.getPublicKey());
		}
		intGen.addAuthorityKeyIdentifierExtension(false, issuerKeyID);
		
		X509Certificate intCert = intGen.sign(null, testPair.getPrivate());
		outputCert("chainTestIntCert.der",intCert);
		intCert.verify(testPair.getPublic());

		BCX509CertificateGenerator endGen = 
				new BCX509CertificateGenerator(testPair3.getPublic(), intDN, endDN,
																			 null, null);
		endGen.setDuration(10000);
		endGen.addSubjectKeyIdentifierExtension(false);
		endGen.addAuthorityKeyIdentifierExtension(false, 
							BCX509CertificateGenerator.getKeyIDFromCertificate(intCert));
		boolean [] keyUsageBits = new boolean[] {true, false, true, true, true, false, false, false, false};
		endGen.addKeyUsage(false, keyUsageBits);
		endGen.setSecureEmailUsage("smetters@parc.com");
		
		X509Certificate endCert = endGen.sign(null, testPair2.getPrivate());
		outputCert("chainTestEndCert.der", endCert);
		endCert.verify(testPair2.getPublic());

		BCX509CertificateGenerator endGen2 = 
				new BCX509CertificateGenerator(testPair3.getPublic(), intDN, endDN2,
																			 null, null);
		endGen2.setDuration(10000);
		endGen2.addSubjectKeyIdentifierExtension(false);
		endGen2.addAuthorityKeyIdentifierExtension(false, 
							BCX509CertificateGenerator.getKeyIDFromCertificate(intCert));
		endGen2.addKeyUsage(false, keyUsageBits);
		endGen2.addEmailSubjectAltName(false, "smetters@parc.com");
		endGen2.addIPAddressSubjectAltName(false, "13.2.116.90");
		endGen2.addDNSNameSubjectAltName(false, "playfair-wireless.parc.com");
		
		X509Certificate endCert2 = endGen2.sign(null, testPair2.getPrivate());
		outputCert("chainTestEnd2Cert.der", endCert2);
		endCert2.verify(testPair2.getPublic());
	}

	/*
	 * Test for X509Certificate GenerateX509Certificate
	 */
	public void testX509CertificateNameChaining() 
		throws InvalidAlgorithmParameterException,
				InvalidParameterSpecException, NoSuchAlgorithmException,
				 InvalidKeyException, IOException, CertificateEncodingException, 
				 SignatureException, NoSuchProviderException, CertificateException {
		
    	BCX509CertificateGenerator generator =
            new BCX509CertificateGenerator(testPair.getPublic(),
                                         rootDN, rootDN,
                                         null,
                                         null, null, null);
		generator.setDuration(10000);
		generator.addBasicConstraints(true, true, 0);
		boolean [] keyUsageBits = new boolean[] {true, true, false, false, false, true, true, false, false};
		generator.addKeyUsage(false, keyUsageBits);
		generator.addSubjectKeyIdentifierExtension(false);
		X509Certificate rootCert = generator.sign(null, testPair.getPrivate());
		
		outputCert("nameChainTestRootCert.der", rootCert);
		rootCert.verify(testPair.getPublic());

		BCX509CertificateGenerator intGen = 
				new BCX509CertificateGenerator(testPair2.getPublic(), rootCert.getSubjectDN().toString(),
																			 intDN,
																			 null, null);
		intGen.setDuration(10000);
		intGen.addBasicConstraints(true, true);
		intGen.addSubjectKeyIdentifierExtension(false);
		byte [] issuerKeyID = BCX509CertificateGenerator.getKeyIDFromCertificate(rootCert);
		if (null == issuerKeyID) {
			issuerKeyID = BCX509CertificateGenerator.generateKeyID(rootCert.getPublicKey());
		}
		intGen.addAuthorityKeyIdentifierExtension(false, issuerKeyID);
		
		X509Certificate intCert = intGen.sign(null, testPair.getPrivate());
		outputCert("nameChainTestIntCert.der",intCert);
		intCert.verify(testPair.getPublic());

		BCX509CertificateGenerator endGen = 
				new BCX509CertificateGenerator(testPair3.getPublic(), intCert.getSubjectDN().toString(), endDN,
																			 null, null);
		endGen.setDuration(10000);
		endGen.addSubjectKeyIdentifierExtension(false);
		endGen.addAuthorityKeyIdentifierExtension(false, 
							BCX509CertificateGenerator.getKeyIDFromCertificate(intCert));
		boolean [] endKeyUsageBits = new boolean[] {true, false, true, true, true, false, false, false, false};
		endGen.addKeyUsage(false, endKeyUsageBits);
		endGen.setSecureEmailUsage("smetters@parc.com");
		
		X509Certificate endCert = endGen.sign(null, testPair2.getPrivate());
		outputCert("nameChainEndCert.der", endCert);
		endCert.verify(testPair2.getPublic());
	}

	public void testGetSignatureAlgorithmName() {
		
		String sha1wrsa =  SignatureHelper.getSignatureAlgorithmName("SHA1", "RSA");
		System.out.println("Got: " + sha1wrsa);
		String sha_1wrsa = SignatureHelper.getSignatureAlgorithmName("SHA-1", "RSA");
		System.out.println("Got: " + sha_1wrsa);
		String shawrsa = SignatureHelper.getSignatureAlgorithmName("SHA", "RSA");
		System.out.println("Got: " + shawrsa);
		String shawdsa = SignatureHelper.getSignatureAlgorithmName("SHA", "DSA");
		System.out.println("Got: " + shawdsa);
		String sha1wdsa =  SignatureHelper.getSignatureAlgorithmName("SHA1", "DSA");
		System.out.println("Got: " + sha1wdsa);
		String sha_1wdsa = SignatureHelper.getSignatureAlgorithmName("SHA-1", "DSA");
		System.out.println("Got: " + sha_1wdsa);
		String md5wrsa =  SignatureHelper.getSignatureAlgorithmName("MD5", "RSA");
		System.out.println("Got: " + md5wrsa);
		String sha1wecdsa =  SignatureHelper.getSignatureAlgorithmName("SHA1", "ECDSA");
		System.out.println("Got: " + sha1wecdsa);
		
	}

	public void testProperties() {
			Enumeration e;
			Provider[] provider;
			String k; // key
			String v; // value
			String s; // string
			String p; // previous mapping
			Map map = new HashMap();
			int i;
			int j;

			provider = Security.getProviders();
			
			/* We start from the last provider and work our
			 * way to the first one such that aliases of
			 * preferred providers overwrite entries of
			 * less favoured providers.
			 */
			for (i=provider.length-1; i>=0; i--) {
				System.out.println("Provider: " + provider[i].getName());
				 e = provider[i].propertyNames();
				 while (e.hasMoreElements())
				  {
					  k = (String)e.nextElement();
					  v = provider[i].getProperty(k);
					  
					  System.out.println("    " + k + ": " + v);
					  
					  if (!k.startsWith("Alg.Alias."))
						  continue;

					  /* Truncate k to <engine>.<alias>
					   */
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

					  /* If <alias> starts with a digit then we
					   * assume it is an OID. OIDs are uniquely
					   * defined, hence we ommit <engine> in the
					   * oid mappings. But we also include the
					   * alias mapping for this oid.
					   */
					  if (Character.isDigit(k.charAt(0)))
					   {
						   p = (String)map.get("oid."+k);
						   if (p != null && p.length() >= v.length())
							   continue;

						   map.put("oid."+k,v);
						   map.put(s+"."+k,v);
					   }
					  /* If <alias> starts with the string "OID."
					   * then we found a reverse mapping. In that
					   * case we swap <alias> and the value of the
					   * mapping, and make an entry of the form
					   * "oid."+<value> = <oid>
					   */
					  else if (k.startsWith("oid."))
					   {
						   k = k.substring(4);
						   v = v.toLowerCase();
						   map.put("oid."+v, k);
					   }
					  /* In all other cases we make an entry of the
					   * form <engine>+"."+<alias> = <value> as is
					   * defined in the providers.
					   */
					  else
						  map.put(s+"."+k, v);
				  }
			 }
			return;
	}
	
	public void testParsing() throws Exception {
		String filename = "testAddEKUExtension.der";
		InputStream inStream = new FileInputStream(filename);

		ASN1StreamParser ain = new ASN1StreamParser(inStream);
		DEREncodable out = ain.readObject();
		
		System.out.println("Read file: " + filename + " got object of type: " + out.getClass().getName());
		System.out.println("Which is: " + out.getDERObject().getClass().getName());
		System.out.println("And looks like: " + out.getDERObject().toString());
		X509CertificateStructure structure = new X509CertificateStructure((DERSequence)out.getDERObject());
		System.out.println("Got structure.");
		Enumeration extoids = structure.getTBSCertificate().getExtensions().oids();

		System.out.println("Got extensions:");
		while (extoids.hasMoreElements()) {
			System.out.println("OID: " + extoids.nextElement().toString());
		}
	}
	
	public void testNames() throws Exception {
		// issuerDN, subjectDN1, subjectDN2 are strings with CN first
		// rootDN and endDN are strings with C first
		// intDN has CN first
		X509Certificate rootCert = 
			BCX509CertificateGenerator.GenerateRootCertificate(
				testPair,
				rootDN,
				(BigInteger)null,
				start,
				end);

		BCX509CertificateGenerator gen = 
			new BCX509CertificateGenerator(
				testPair2.getPublic(),
				rootDN,
				intDN,
				null,
				start,
				end,
				null);

		X509Certificate intCert = gen.sign(null, testPair.getPrivate());

		X509Certificate endCert = 
			BCX509CertificateGenerator.GenerateX509Certificate(
				testPair3.getPublic(),
				intDN,
				endDN,
				null,
				start,
				end,
				null,
				testPair2.getPrivate(),
				null);
		
		outputCert("testNamesRoot.der", rootCert);
		X509Certificate rootCert2 = inputCert("testNamesRoot.der");
		outputCert("testNamesInt.der", intCert);
		X509Certificate intCert2 = inputCert("testNamesInt.der");
		outputCert("testNamesEnd.der", endCert);
		X509Certificate endCert2 = inputCert("testNamesEnd.der");
		
		rootCert.verify(testPair.getPublic());
		rootCert2.verify(testPair.getPublic());
		intCert.verify(testPair.getPublic());
		intCert2.verify(testPair.getPublic());
		endCert.verify(testPair2.getPublic());
		endCert2.verify(testPair2.getPublic());
		
		outputNames("Root certificate (i=C,i=C)", rootCert);
		outputNames("Intermediate certificate (i=C,s=CN)",intCert);
		outputNames("End certificate (i=CN, s=C)", endCert);
	}
	
	public void testRollover() throws Exception {
		// Test PARC's cert rollover problem. Build
		// hierarchy here because it's easy, test it outside.
		
		// Generate two hierarchies using same keys.
		KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
		kpg.initialize(1024); // go for fast
		KeyPair rootPair = kpg.generateKeyPair();
		KeyPair intPair = kpg.generateKeyPair();
		KeyPair clientPair = kpg.generateKeyPair();
		KeyPair serverPair = kpg.generateKeyPair();
		
		GregorianCalendar now = new GregorianCalendar();
		printDate("Now", now);
		// Old Intermediate root start
		GregorianCalendar threeYearsAgo = ((GregorianCalendar)now.clone());
		threeYearsAgo.add(GregorianCalendar.YEAR, -3);
		printDate("Three years ago", threeYearsAgo);
		// Old Intermediate root end
		GregorianCalendar hourFromNow = ((GregorianCalendar)now.clone());
		hourFromNow.add(Calendar.HOUR_OF_DAY, 1);
		printDate("An hour from now", hourFromNow);
		// Old root start -- three years ago minus a bit
		GregorianCalendar aBitOverThreeYearsAgo = ((GregorianCalendar)threeYearsAgo.clone());
		aBitOverThreeYearsAgo.add(Calendar.HOUR_OF_DAY, -1);
		printDate("Just over three years ago", aBitOverThreeYearsAgo);
		// Old root end
		GregorianCalendar threeYearsFromNow = ((GregorianCalendar)now.clone());
		threeYearsFromNow.add(GregorianCalendar.YEAR, 3);
		printDate("Three years from now", threeYearsFromNow);
		
		// New root start - now minus a bit
		GregorianCalendar anHourAgo = ((GregorianCalendar)now.clone());
		anHourAgo.add(Calendar.HOUR_OF_DAY, -1);
		printDate("An hour ago", anHourAgo);
		// new root end
		GregorianCalendar tenYearsFromNow = ((GregorianCalendar)now.clone());
		tenYearsFromNow.add(Calendar.YEAR, 10);
		printDate("Ten years from now", tenYearsFromNow);
		
		// New intermediate start -- now
		// new intermediate end - just before root ends
		GregorianCalendar almostTenYearsFromNow = ((GregorianCalendar)tenYearsFromNow.clone());
		almostTenYearsFromNow.add(Calendar.HOUR_OF_DAY, -1);
		printDate("Almost ten years from now", almostTenYearsFromNow);
		
		// Client start
		GregorianCalendar oneYearAgo = ((GregorianCalendar)now.clone());
		oneYearAgo.add(GregorianCalendar.YEAR, -1);
		printDate("One year ago", oneYearAgo);
		GregorianCalendar oneYearFromNow = ((GregorianCalendar)now.clone());
		oneYearFromNow.add(GregorianCalendar.YEAR, 1);
		printDate("One year from now", oneYearFromNow);

		String prootDN = "CN=PARC Root Certification Authority,O=PARC,C=US";
		String pintDN = "CN=PARC Wireless Network Certification Authority,O=PARC,C=US";
		String pendDN = "CN=roo-wlan.parc.com,O=PARC,C=US";
		String pserverDN = "CN=radius.parc.com,O=PARC,C=US";

		X509Certificate parcRootCertOld = 
			BCX509CertificateGenerator.GenerateRootCertificate(
				rootPair,
				prootDN,
				(BigInteger)null,
				aBitOverThreeYearsAgo.getTime(),
				threeYearsFromNow.getTime());
		outputCert("parcOldRootTest.der", parcRootCertOld);

		X509Certificate parcRootCertNew = 
			BCX509CertificateGenerator.GenerateRootCertificate(
				rootPair,
				prootDN,
				(BigInteger)null,
				anHourAgo.getTime(),
				tenYearsFromNow.getTime());
		outputCert("parcNewRootTest.der", parcRootCertNew);
		
		outputKey("parcRootTestKey.der", rootPair);

		BCX509CertificateGenerator genOld = 
			new BCX509CertificateGenerator(
				intPair.getPublic(),
				prootDN,
				pintDN,
				null,
				threeYearsAgo.getTime(),
				hourFromNow.getTime(),
				null);
		genOld.addAuthorityKeyIdentifierExtension(false, BCX509CertificateGenerator.generateKeyID(parcRootCertOld.getPublicKey()));
		genOld.addBasicConstraints(true, true);

		X509Certificate parcIntCertOld = genOld.sign(null, rootPair.getPrivate());
		outputCert("parcIntRootTestOld.der", parcIntCertOld);
		
		BCX509CertificateGenerator genNew = 
			new BCX509CertificateGenerator(
				intPair.getPublic(),
				prootDN,
				pintDN,
				null,
				anHourAgo.getTime(),
				almostTenYearsFromNow.getTime(),
				null);
		genNew.addAuthorityKeyIdentifierExtension(false, BCX509CertificateGenerator.generateKeyID(parcRootCertNew.getPublicKey()));
		genNew.addBasicConstraints(true, true);
		
		X509Certificate parcIntCertNew = genNew.sign(null, rootPair.getPrivate());
		outputCert("parcIntRootTestNew.der", parcIntCertNew);
		outputKey("parcIntTestKey.der", intPair);
		
		BCX509CertificateGenerator clientGen =
			 new BCX509CertificateGenerator(
				clientPair.getPublic(),
				pintDN,
				pendDN,
				null,
				oneYearAgo.getTime(),
				oneYearFromNow.getTime(),
				null);
		
		clientGen.addAuthorityKeyIdentifierExtension(false, BCX509CertificateGenerator.getKeyIDFromCertificate(parcIntCertOld));
		clientGen.addClientAuthenticationEKU();
		clientGen.addBasicConstraints(true, false);
		clientGen.addDNSNameSubjectAltName(false, "roo-wlan.parc.com");
		clientGen.addEmailSubjectAltName(false, "smetters@parc.com");
		
		X509Certificate parcClientCert = clientGen.sign(null, intPair.getPrivate());
		outputCert("parcClientTest.der", parcClientCert);
		outputKey("parcClientTestKey.der", clientPair);
		
		BCX509CertificateGenerator serverGen =
			 new BCX509CertificateGenerator(
				serverPair.getPublic(),
				pintDN,
				pserverDN,
				null,
				oneYearAgo.getTime(),
				oneYearFromNow.getTime(),
				null);
		
		serverGen.addAuthorityKeyIdentifierExtension(false, BCX509CertificateGenerator.getKeyIDFromCertificate(parcIntCertOld));
		serverGen.setServerAuthenticationUsage("radius.parc.com");
		serverGen.addBasicConstraints(true, false);
		
		X509Certificate parcServerCert = serverGen.sign(null, intPair.getPrivate());
		outputCert("parcServerTest.der", parcServerCert);
		outputKey("parcServerTestKey.der", serverPair);

	}

	static void outputNames(String message, X509Certificate cert) throws Exception {
		System.out.println("Outputting certificate names: " + message);
		
		X509Certificate javaCert = null;
		X509Certificate bcCert = null;
		if (cert instanceof X509CertificateObject) {
			bcCert = cert;
			javaCert = BCX509CertificateGenerator.convert(bcCert, BCX509CertificateGenerator.SUN_PROVIDER);
		} else {
			javaCert = cert;
			bcCert = BCX509CertificateGenerator.convert(javaCert, "BC");
		}
				
		System.out.println("Java certificate: issuerDN.getName: " + javaCert.getIssuerDN().getName());
		System.out.println("Java certificate: issuerDN.toString: " + javaCert.getIssuerDN().toString());
		System.out.println("Java certificate: issuerX500Principal.getName: " + javaCert.getIssuerX500Principal().getName());
		System.out.println("Java certificate: issuerX500Principal.toString: " + javaCert.getIssuerX500Principal().toString());
		System.out.println("Java certificate: issuerX500Principal.getName(CANONICAL): " + javaCert.getIssuerX500Principal().getName(X500Principal.CANONICAL));
		System.out.println("Java certificate: issuerX500Principal.getName(RFC1779): " + javaCert.getIssuerX500Principal().getName(X500Principal.RFC1779));
		System.out.println("Java certificate: issuerX500Principal.getName(RFC2253): " + javaCert.getIssuerX500Principal().getName(X500Principal.RFC2253));
		byte [] encodedIssuer = javaCert.getIssuerX500Principal().getEncoded();
		ASN1InputStream as = new ASN1InputStream(encodedIssuer);
		DERObject obj = as.readObject();
		X509Name x509Name = new X509Name((ASN1Sequence)obj);
		System.out.println("Java certificate: issuerX500Principal decoded: " + x509Name.toString());
		System.out.println("Java certificate: subjectDN.getName: " + javaCert.getSubjectDN().getName());
		System.out.println("Java certificate: subjectDN.toString: " + javaCert.getSubjectDN().toString());
		System.out.println("Java certificate: subjectX500Principal.getName: " + javaCert.getSubjectX500Principal().getName());
		System.out.println("Java certificate: subjectX500Principal.toString: " + javaCert.getSubjectX500Principal().toString());
		System.out.println("");
		System.out.println("BC certificate: issuerDN.getName: " + bcCert.getIssuerDN().getName());
		System.out.println("BC certificate: issuerDN.toString: " + bcCert.getIssuerDN().toString());
		System.out.println("BC certificate: issuerX500Principal.getName: " + bcCert.getIssuerX500Principal().getName());
		System.out.println("BC certificate: issuerX500Principal.toString: " + bcCert.getIssuerX500Principal().toString());
		System.out.println("BC certificate: subjectDN.getName: " + bcCert.getSubjectDN().getName());
		System.out.println("BC certificate: subjectDN.toString: " + bcCert.getSubjectDN().toString());
		System.out.println("BC certificate: subjectX500Principal.getName: " + bcCert.getSubjectX500Principal().getName());
		System.out.println("BC certificate: subjectX500Principal.toString: " + bcCert.getSubjectX500Principal().toString());
		byte [] encodedIssuerBC = javaCert.getIssuerX500Principal().getEncoded();
		ASN1InputStream asBC = new ASN1InputStream(encodedIssuerBC);
		DERObject objBC = asBC.readObject();
		X509Name x509NameBC = new X509Name((ASN1Sequence)objBC);
		System.out.println("BC certificate: issuerX500Principal decoded: " + x509NameBC.toString());
		System.out.println("");
	}
	
	static void handleException(Exception ex) {
		System.out.println("Got exception of type: " + ex.getClass().getName() + " message: " +
										ex.getMessage());
		ex.printStackTrace();
	}
	
	static void outputCert(String file, X509Certificate cert) 
			throws CertificateEncodingException, FileNotFoundException, IOException {
		byte [] encoded = cert.getEncoded();
		FileOutputStream fos = new FileOutputStream(file);
		fos.write(encoded);
		fos.flush();
		fos.close();
	}
	
	static X509Certificate inputCert(String file) throws IOException, CertificateException {
		InputStream inStream = new FileInputStream(file);
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		X509Certificate cert = (X509Certificate)cf.generateCertificate(inStream);
		inStream.close();
		return cert;
	}

	static void outputKey(String file, KeyPair pair) throws IOException {
		// get private key out eventually to PEM format
		// start with pkcs8
		byte [] encoded = pair.getPrivate().getEncoded();
		FileOutputStream fos = new FileOutputStream(file);
		fos.write(encoded);
		fos.flush();
		fos.close();
	}
	
	static void printDate(String label, Calendar date) {
		System.out.println(label + ": " + date.getTime());
	}
}
