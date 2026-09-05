package io.github.hectorvent.floci.services.acm;

import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X962Parameters;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.util.io.pem.PemObject;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

@ApplicationScoped
public class CertificateGenerator {

    private static final Logger LOG = Logger.getLogger(CertificateGenerator.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final String PBE_ALGORITHM = "PBEWithHmacSHA256AndAES_256";
    private static final int PBE_SALT_BYTES = 16;
    private static final int PBE_ITERATIONS = 4096;

    /**
     * Pattern matching IPv4 addresses (e.g. 192.168.1.100) and IPv6 addresses
     * (bracketed like [::1] or raw like ::1, fe80::1).
     */
    private static final Pattern IP_ADDRESS_PATTERN = Pattern.compile(
            "^\\[?([0-9a-fA-F:]+)]?$|^(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})$"
    );

    public record GeneratedCertificate(
        String certificatePem,
        String privateKeyPem,
        String serial,
        Instant notBefore,
        Instant notAfter,
        String subject,
        String issuer,
        String signatureAlgorithm
    ) {}

    /**
     * What an issued leaf is for. Decides the Extended Key Usage. A CA passes {@code null}.
     * {@code SERVER} carries both serverAuth and clientAuth, as ACM-issued certificates do (and as
     * {@code DescribeCertificate} already advertises); {@code CLIENT} is clientAuth only.
     */
    public enum LeafUsage { SERVER, CLIENT }

    /** A signer: the issuer's certificate (for its DN) and its private key. */
    public record Issuer(X509Certificate certificate, PrivateKey key) {}

    /**
     * Generates a genuinely self-signed certificate (issuer == subject, marked as a CA) suitable
     * for use as a <em>trust anchor</em>: a client that adds this certificate to its CA store can
     * verify a TLS connection that presents it. Used for Floci's own HTTPS server certificate so
     * that containers (e.g. Lambdas making CDK {@code cfn-response} callbacks over HTTPS) can trust
     * Floci once the certificate is installed in their CA bundle.
     */
    public GeneratedCertificate generateSelfSignedCertificate(String domainName, List<String> sans, KeyAlgorithm keyAlgorithm) {
        return buildSelfSignedCertificate(domainName, sans, keyAlgorithm, null);
    }

    /**
     * Same as {@link #generateSelfSignedCertificate(String, List, KeyAlgorithm)}, but signs the
     * certificate with a caller-supplied key pair instead of minting a new one. Use this to reissue
     * a trust anchor with an updated SAN list without changing its key: a client that already
     * trusts a certificate sharing this key will still validate the new one, since the reissued
     * certificate's signature verifies against the same public key.
     */
    public GeneratedCertificate generateSelfSignedCertificate(String domainName, List<String> sans,
                                                              KeyAlgorithm keyAlgorithm, KeyPair keyPair) {
        return buildSelfSignedCertificate(domainName, sans, keyAlgorithm, keyPair);
    }

    /**
     * A root CA: self-signed, {@code cA=true}, {@code keyCertSign}, ten years. RSA 2048 because
     * every client in the emulator's reach accepts it and it keeps key generation under a second.
     */
    public GeneratedCertificate generateCaCertificate(String commonName) {
        return generateCaCertificate(commonName, Instant.now().plus(3650, ChronoUnit.DAYS));
    }

    /** Same as {@link #generateCaCertificate(String)} with an explicit end of validity. */
    public GeneratedCertificate generateCaCertificate(String commonName, Instant notAfter) {
        try {
            KeyPair keyPair = generateKeyPair(KeyAlgorithm.RSA_2048);
            X500Name dn = new X500Name("CN=" + commonName);
            X509Certificate cert = signCertificate(dn, keyPair.getPublic(), dn, keyPair.getPrivate(),
                    List.of(), true, null, Instant.now(), notAfter);
            return toGenerated(cert, keyPair.getPrivate(), dn.toString(), dn.toString());
        } catch (Exception e) {
            LOG.error("Failed to generate CA certificate", e);
            throw new CertificateGenerationException("CA generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * A leaf signed by {@code issuer}. {@code subjectKeyPair} may be {@code null} to mint a new
     * one of {@code keyAlgorithm}; pass the previous pair to reissue with an updated SAN list and
     * an unchanged public key, in which case it must be of {@code keyAlgorithm}. The result is
     * checked before it is returned: the leaf must verify against the issuer's certificate, and a
     * supplied key pair must be a pair of the requested algorithm, so a caller can never get back
     * a certificate its own issuer rejects, a private key that does not fit it, or a key type it
     * did not ask for.
     */
    public GeneratedCertificate generateIssuedCertificate(String domainName, List<String> sans,
                                                          KeyAlgorithm keyAlgorithm, KeyPair subjectKeyPair,
                                                          Issuer issuer, LeafUsage usage) {
        return generateIssuedCertificate(domainName, sans, keyAlgorithm, subjectKeyPair, issuer, usage,
                Instant.now().plus(365, ChronoUnit.DAYS));
    }

    /** Same as {@link #generateIssuedCertificate(String, List, KeyAlgorithm, KeyPair, Issuer, LeafUsage)} with an explicit end of validity. */
    public GeneratedCertificate generateIssuedCertificate(String domainName, List<String> sans,
                                                          KeyAlgorithm keyAlgorithm, KeyPair subjectKeyPair,
                                                          Issuer issuer, LeafUsage usage, Instant notAfter) {
        try {
            if (subjectKeyPair != null && !isOfAlgorithm(subjectKeyPair.getPublic(), keyAlgorithm)) {
                throw new IllegalArgumentException("supplied key pair is not the requested " + keyAlgorithm);
            }
            if (subjectKeyPair != null && !isPair(subjectKeyPair.getPrivate(), subjectKeyPair.getPublic())) {
                throw new IllegalArgumentException("supplied private key does not match its public key");
            }
            KeyPair keyPair = subjectKeyPair != null ? subjectKeyPair : generateKeyPair(keyAlgorithm);
            X500Name subject = new X500Name("CN=" + domainName);
            X500Name issuerDn = X500Name.getInstance(issuer.certificate().getSubjectX500Principal().getEncoded());
            X509Certificate cert = signCertificate(subject, keyPair.getPublic(), issuerDn, issuer.key(),
                    withDomainFirst(domainName, sans), false, usage, Instant.now(), notAfter);
            cert.verify(issuer.certificate().getPublicKey());
            return toGenerated(cert, keyPair.getPrivate(), subject.toString(),
                    issuer.certificate().getSubjectX500Principal().getName());
        } catch (Exception e) {
            LOG.error("Failed to generate issued certificate", e);
            throw new CertificateGenerationException("Certificate generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * True when {@code key} is exactly what {@code keyAlgorithm} names: the RSA size, or for EC the
     * named curve read from the key's encoding, so a curve that only shares a field size
     * (secp256k1 for {@code EC_prime256v1}) does not pass.
     */
    boolean isOfAlgorithm(PublicKey key, KeyAlgorithm keyAlgorithm) {
        if ("EC".equals(keyAlgorithm.getAlgorithm())) {
            if (!(key instanceof ECKey)) {
                return false;
            }
            X962Parameters parameters = X962Parameters.getInstance(
                    SubjectPublicKeyInfo.getInstance(key.getEncoded()).getAlgorithm().getParameters());
            return parameters.isNamedCurve()
                    && ECNamedCurveTable.getOID(keyAlgorithm.getCurveName()).equals(parameters.getParameters());
        }
        return !(key instanceof ECKey) && detectKeyAlgorithm(key) == keyAlgorithm;
    }

    /** True when {@code privateKey} signs what {@code publicKey} verifies. */
    public static boolean isPair(PrivateKey privateKey, PublicKey publicKey) throws Exception {
        String algorithm = privateKey instanceof ECKey ? "SHA256withECDSA" : "SHA256withRSA";
        byte[] probe = "floci".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(privateKey);
        signer.update(probe);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(publicKey);
        verifier.update(probe);
        return verifier.verify(signature);
    }

    private GeneratedCertificate buildSelfSignedCertificate(String domainName, List<String> sans,
                                                            KeyAlgorithm keyAlgorithm, KeyPair suppliedKeyPair) {
        try {
            KeyPair keyPair = suppliedKeyPair != null ? suppliedKeyPair : generateKeyPair(keyAlgorithm);
            String dn = "CN=" + domainName;
            // Signed with the subject's own key: issuer == subject, and a CA so it can be a trust anchor.
            X509Certificate cert = signCertificate(new X500Name(dn), keyPair.getPublic(),
                    new X500Name(dn), keyPair.getPrivate(), withDomainFirst(domainName, sans), true, null, 365);
            return toGenerated(cert, keyPair.getPrivate(), dn, dn);
        } catch (Exception e) {
            LOG.error("Failed to generate certificate", e);
            throw new CertificateGenerationException("Certificate generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * The one place a certificate is signed. Everything else in this class, and
     * {@code CloudHsmV2Service}, goes through here.
     *
     * @param sans  DNS names or IP addresses, written once each in order; empty for a CA
     * @param asCa  sets {@code BasicConstraints(cA)}, {@code keyCertSign} and {@code cRLSign}
     * @param usage EKU for a leaf, {@code null} for a CA or a leaf without one
     */
    public X509Certificate signCertificate(X500Name subject, PublicKey subjectKey, X500Name issuerDn,
                                           PrivateKey issuerKey, List<String> sans, boolean asCa,
                                           LeafUsage usage, int validityDays) throws Exception {
        Instant now = Instant.now();
        return signCertificate(subject, subjectKey, issuerDn, issuerKey, sans, asCa, usage, now,
                now.plus(validityDays, ChronoUnit.DAYS));
    }

    /** Same as {@link #signCertificate(X500Name, PublicKey, X500Name, PrivateKey, List, boolean, LeafUsage, int)} with an explicit validity window. */
    public X509Certificate signCertificate(X500Name subject, PublicKey subjectKey, X500Name issuerDn,
                                           PrivateKey issuerKey, List<String> sans, boolean asCa,
                                           LeafUsage usage, Instant notBefore, Instant notAfter) throws Exception {
        BigInteger serial = new BigInteger(128, SECURE_RANDOM);
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuerDn, serial, Date.from(notBefore), Date.from(notAfter), subject, subjectKey);

        if (sans != null && !sans.isEmpty()) {
            List<GeneralName> sanList = new ArrayList<>();
            for (String san : new LinkedHashSet<>(sans)) {
                sanList.add(toGeneralName(san));
            }
            certBuilder.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(sanList.toArray(new GeneralName[0])));
        }

        // keyEncipherment is an RSA key-transport bit; EC keys sign (and agree), they never encipher.
        int keyUsageBits = KeyUsage.digitalSignature;
        if ("RSA".equals(subjectKey.getAlgorithm())) {
            keyUsageBits |= KeyUsage.keyEncipherment;
        }
        if (asCa) {
            keyUsageBits |= KeyUsage.keyCertSign | KeyUsage.cRLSign;
        }
        certBuilder.addExtension(Extension.keyUsage, true, new KeyUsage(keyUsageBits));
        certBuilder.addExtension(Extension.basicConstraints, true, new BasicConstraints(asCa));
        if (usage == LeafUsage.SERVER) {
            certBuilder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(
                    new KeyPurposeId[] {KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
        } else if (usage == LeafUsage.CLIENT) {
            certBuilder.addExtension(Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
        }

        String signatureAlgorithm = issuerKey instanceof ECKey ? "SHA512withECDSA" : "SHA512WithRSA";
        ContentSigner signer = new JcaContentSignerBuilder(signatureAlgorithm).build(issuerKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static List<String> withDomainFirst(String domainName, List<String> sans) {
        List<String> allSans = new ArrayList<>();
        allSans.add(domainName);
        if (sans != null) {
            allSans.addAll(sans);
        }
        return allSans;
    }

    /**
     * Metadata comes from the certificate, not from the arguments: the signature algorithm is the
     * issuer's (an RSA CA signing an EC leaf yields SHA512WITHRSA, spelled in upper case as ACM
     * does), and ACM shows the serial as colon-separated hex ({@code 07:71:71:f4:...}).
     */
    private GeneratedCertificate toGenerated(X509Certificate cert, PrivateKey key, String subjectDn,
                                             String issuerDn) throws Exception {
        return new GeneratedCertificate(
                toPem(cert),
                toPem(key),
                colonHex(cert.getSerialNumber()),
                cert.getNotBefore().toInstant(),
                cert.getNotAfter().toInstant(),
                subjectDn,
                issuerDn,
                cert.getSigAlgName().toUpperCase(Locale.ROOT));
    }

    static String colonHex(BigInteger serial) {
        String hex = serial.toString(16);
        if (hex.length() % 2 == 1) {
            hex = "0" + hex;
        }
        return String.join(":", hex.split("(?<=\\G..)"));
    }

    private KeyPair generateKeyPair(KeyAlgorithm keyAlgorithm) throws Exception {
        KeyPairGenerator keyGen;
        if ("EC".equals(keyAlgorithm.getAlgorithm())) {
            keyGen = KeyPairGenerator.getInstance("EC");
            keyGen.initialize(new ECGenParameterSpec(keyAlgorithm.getCurveName()), SECURE_RANDOM);
        } else {
            keyGen = KeyPairGenerator.getInstance("RSA");
            keyGen.initialize(keyAlgorithm.getKeySize(), SECURE_RANDOM);
        }
        return keyGen.generateKeyPair();
    }

    /**
     * Creates the appropriate {@link GeneralName} for a SAN entry.
     * IP addresses (IPv4 and IPv6) use {@code GeneralName.iPAddress},
     * all other values use {@code GeneralName.dNSName}.
     */
    private static GeneralName toGeneralName(String san) {
        if (isIpAddress(san)) {
            try {
                // Strip brackets from IPv6 if present (e.g. [::1] → ::1)
                String raw = san.startsWith("[") && san.endsWith("]")
                        ? san.substring(1, san.length() - 1)
                        : san;
                byte[] addr = InetAddress.getByName(raw).getAddress();
                return new GeneralName(GeneralName.iPAddress,
                        new org.bouncycastle.asn1.DEROctetString(addr));
            } catch (Exception e) {
                // Fallback to DNS name if IP parsing fails
                LOG.debugv("Could not parse '{0}' as IP address, treating as DNS name", san);
                return new GeneralName(GeneralName.dNSName, san);
            }
        }
        return new GeneralName(GeneralName.dNSName, san);
    }

    /**
     * Checks whether a SAN value looks like an IP address (IPv4 or IPv6).
     * Wildcard entries (e.g. *.localhost) are never IP addresses.
     */
    static boolean isIpAddress(String value) {
        if (value == null || value.isBlank() || value.startsWith("*")) {
            return false;
        }
        return IP_ADDRESS_PATTERN.matcher(value).matches();
    }

    /**
     * PEM for a certificate or key. A JDK EC private key goes out as PKCS#8 ({@code PRIVATE KEY}):
     * {@link JcaPEMWriter} would write it as a bare SEC1 structure without the curve, which neither
     * OpenSSL nor {@link #parsePrivateKey} can read. RSA keys stay PKCS#1 ({@code RSA PRIVATE KEY}),
     * the form AWS IoT hands out.
     */
    public String toPem(Object obj) throws Exception {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
            if (obj instanceof PrivateKey key && key instanceof ECKey) {
                pemWriter.writeObject(new PemObject("PRIVATE KEY", key.getEncoded()));
            } else {
                pemWriter.writeObject(obj);
            }
        }
        return sw.toString();
    }

    /**
     * Encrypts a private key using AES-256-CBC (replacing deprecated Triple-DES).
     *
     * @param privateKeyPem The private key in PEM format
     * @param passphrase The passphrase for encryption
     * @return Encrypted private key in PEM format
     */
    public String encryptPrivateKey(String privateKeyPem, String passphrase) {
        try {
            PrivateKey privateKey = parsePrivateKey(privateKeyPem);

            // PBES2 with AES-256-CBC, run through the JDK so no JCE provider has to be
            // registered. The algorithm name fixes the PBKDF2 PRF to HMAC-SHA256, and the
            // salt and iteration count are passed explicitly so the output does not depend
            // on provider defaults. The ASN.1 below only wraps the result, so it needs no
            // provider either.
            var secretKey = SecretKeyFactory.getInstance(PBE_ALGORITHM)
                .generateSecret(new PBEKeySpec(passphrase.toCharArray()));
            var salt = new byte[PBE_SALT_BYTES];
            SECURE_RANDOM.nextBytes(salt);
            var cipher = Cipher.getInstance(PBE_ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new PBEParameterSpec(salt, PBE_ITERATIONS));
            var ciphertext = cipher.doFinal(privateKey.getEncoded());

            var scheme = new AlgorithmIdentifier(
                PKCSObjectIdentifiers.id_PBES2,
                ASN1Primitive.fromByteArray(cipher.getParameters().getEncoded()));
            var encryptedInfo =
                new PKCS8EncryptedPrivateKeyInfo(new EncryptedPrivateKeyInfo(scheme, ciphertext));

            StringWriter sw = new StringWriter();
            try (JcaPEMWriter pemWriter = new JcaPEMWriter(sw)) {
                pemWriter.writeObject(encryptedInfo);
            }
            return sw.toString();

        } catch (Exception e) {
            LOG.error("Failed to encrypt private key", e);
            throw new CertificateGenerationException("Private key encryption failed: " + e.getMessage(), e);
        }
    }

    public X509Certificate parseCertificate(String certPem) {
        try (PEMParser parser = new PEMParser(new StringReader(certPem))) {
            Object obj = parser.readObject();
            if (obj instanceof X509CertificateHolder holder) {
                return new JcaX509CertificateConverter()
                    .getCertificate(holder);
            }
            throw new IllegalArgumentException("Invalid certificate PEM format");
        } catch (Exception e) {
            LOG.error("Failed to parse certificate", e);
            throw new CertificateGenerationException("Certificate parsing failed: " + e.getMessage(), e);
        }
    }

    public PrivateKey parsePrivateKey(String keyPem) {
        try (PEMParser parser = new PEMParser(new StringReader(keyPem))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter();

            if (obj instanceof org.bouncycastle.openssl.PEMKeyPair pemKeyPair) {
                // Only the private half is needed, and a SEC1 key may carry no public half at all.
                return converter.getPrivateKey(pemKeyPair.getPrivateKeyInfo());
            } else if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo pkInfo) {
                return converter.getPrivateKey(pkInfo);
            }
            throw new IllegalArgumentException("Invalid private key PEM format");
        } catch (Exception e) {
            LOG.error("Failed to parse private key", e);
            throw new CertificateGenerationException("Private key parsing failed: " + e.getMessage(), e);
        }
    }

    public void validateCertificate(X509Certificate cert) {
        try {
            cert.checkValidity();
        } catch (Exception e) {
            throw new IllegalArgumentException("Certificate validation failed: " + e.getMessage(), e);
        }
    }

    public KeyAlgorithm detectKeyAlgorithm(PublicKey publicKey) {
        String algorithm = publicKey.getAlgorithm();
        if ("RSA".equals(algorithm)) {
            try {
                java.security.interfaces.RSAPublicKey rsaKey = (java.security.interfaces.RSAPublicKey) publicKey;
                int keySize = rsaKey.getModulus().bitLength();
                return switch (keySize) {
                    case 1024 -> KeyAlgorithm.RSA_1024;
                    case 3072 -> KeyAlgorithm.RSA_3072;
                    case 4096 -> KeyAlgorithm.RSA_4096;
                    default -> KeyAlgorithm.RSA_2048;
                };
            } catch (Exception e) {
                return KeyAlgorithm.RSA_2048;
            }
        } else if ("EC".equals(algorithm)) {
            try {
                java.security.interfaces.ECPublicKey ecKey = (java.security.interfaces.ECPublicKey) publicKey;
                int fieldSize = ecKey.getParams().getCurve().getField().getFieldSize();
                return switch (fieldSize) {
                    case 384 -> KeyAlgorithm.EC_secp384r1;
                    case 521 -> KeyAlgorithm.EC_secp521r1;
                    default -> KeyAlgorithm.EC_prime256v1;
                };
            } catch (Exception e) {
                return KeyAlgorithm.EC_prime256v1;
            }
        }
        return KeyAlgorithm.RSA_2048;
    }
}
