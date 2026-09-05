package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.pkcs.RSAPublicKey;
import org.bouncycastle.asn1.sec.SECObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X962Parameters;
import org.bouncycastle.asn1.x9.X9ObjectIdentifiers;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Floci's one local root CA. Every certificate the emulator hands out that a client is expected
 * to validate (the HTTPS server leaf, ACM certificates and, later, IoT device certificates) chains to it, so
 * a developer trusts one file once: {@code {persistent-path}/tls/floci-root-ca.crt}, also served
 * at {@code GET /_floci/ca.pem}.
 *
 * <p>Plain class on purpose: {@link TlsConfigSource} needs it before CDI exists. Beans get the
 * same pair through {@link FlociCertificateAuthorityProducer}.
 */
public final class FlociCertificateAuthority {

    private static final Logger LOG = Logger.getLogger(FlociCertificateAuthority.class);

    public static final String CA_CERT_NAME = "floci-root-ca.crt";
    public static final String CA_KEY_NAME = "floci-root-ca.key";
    static final String COMMON_NAME = "Floci Local CA";

    private final Path certificatePath;
    private final X509Certificate certificate;
    private final PrivateKey key;
    private final String caPem;
    private final CertificateGenerator generator;

    private FlociCertificateAuthority(Path certificatePath, X509Certificate certificate, PrivateKey key,
                                      String caPem, CertificateGenerator generator) {
        this.certificatePath = certificatePath;
        this.certificate = certificate;
        this.key = key;
        this.caPem = caPem;
        this.generator = generator;
    }

    /**
     * Loads the CA from {@code tlsDir}, or creates one when missing or unusable. A corrupt,
     * expired or mismatched pair is regenerated with a WARN: every leaf issued before that moment
     * stops validating, which is the correct outcome for a trust anchor nobody can use.
     */
    public static FlociCertificateAuthority loadOrCreate(Path tlsDir) {
        CertificateGenerator generator = new CertificateGenerator();
        Path certFile = tlsDir.resolve(CA_CERT_NAME);
        Path keyFile = tlsDir.resolve(CA_KEY_NAME);
        try {
            if (Files.exists(certFile) && Files.exists(keyFile)) {
                try {
                    String pem = Files.readString(certFile);
                    X509Certificate cert = generator.parseCertificate(pem);
                    PrivateKey key = generator.parsePrivateKey(Files.readString(keyFile));
                    if (cert.getBasicConstraints() < 0) {
                        throw new IllegalStateException("not a CA certificate (BasicConstraints cA=false)");
                    }
                    cert.verify(cert.getPublicKey());
                    cert.checkValidity();
                    if (!CertificateGenerator.isPair(key, cert.getPublicKey())) {
                        throw new IllegalStateException("private key does not match the certificate");
                    }
                    restrictToOwnerOnly(tlsDir, "rwx------");
                    restrictToOwnerOnly(keyFile, "rw-------");
                    FlociCertificateAuthority ca = new FlociCertificateAuthority(certFile, cert, key, pem, generator);
                    LOG.infov("TLS: using local CA {0} ({1}), SHA256 fingerprint {2}", certFile,
                            cert.getSubjectX500Principal().getName(), ca.fingerprint());
                    return ca;
                } catch (Exception e) {
                    LOG.warnv(e, "TLS: local CA at {0} is unusable ({1}); generating a new one. Clients that trusted "
                            + "the old CA must re-import {2}", tlsDir, e.getMessage(), CA_CERT_NAME);
                }
            }
            Files.createDirectories(tlsDir);
            restrictToOwnerOnly(tlsDir, "rwx------");
            CertificateGenerator.GeneratedCertificate generated = generator.generateCaCertificate(COMMON_NAME);
            Files.writeString(certFile, generated.certificatePem());
            writePrivateKey(keyFile, generated.privateKeyPem());
            FlociCertificateAuthority ca = new FlociCertificateAuthority(certFile,
                    generator.parseCertificate(generated.certificatePem()),
                    generator.parsePrivateKey(generated.privateKeyPem()),
                    generated.certificatePem(), generator);
            LOG.infov("TLS: generated local CA {0}, SHA256 fingerprint {1}. Trust it once: GET /_floci/ca.pem",
                    certFile, ca.fingerprint());
            return ca;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create local CA under " + tlsDir, e);
        }
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public PrivateKey key() {
        return key;
    }

    /** PEM of the CA certificate, exactly the bytes on disk. */
    public String caPem() {
        return caPem;
    }

    public Path certificatePath() {
        return certificatePath;
    }

    /**
     * SHA-256 fingerprint of the CA certificate in the form {@code openssl x509 -fingerprint -sha256}
     * prints, so a copy obtained out of band can be checked against the startup log.
     */
    public String fingerprint() {
        try {
            return HexFormat.ofDelimiter(":").withUpperCase()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint the CA certificate", e);
        }
    }

    public CertificateGenerator.Issuer issuer() {
        return new CertificateGenerator.Issuer(certificate, key);
    }

    /**
     * A {@code serverAuth} leaf for {@code commonName} plus {@code sans}. {@code keyPair} null
     * mints a new key of {@code keyAlgorithm}; passing the current pair keeps the public key
     * across a SAN change, and it must be of {@code keyAlgorithm}.
     */
    public CertificateGenerator.GeneratedCertificate issueServerCertificate(String commonName, List<String> sans,
                                                                            KeyAlgorithm keyAlgorithm, KeyPair keyPair) {
        return generator.generateIssuedCertificate(commonName, sans, keyAlgorithm, keyPair, issuer(),
                CertificateGenerator.LeafUsage.SERVER);
    }

    /** A {@code clientAuth} leaf, for IoT device certificates. Always a fresh RSA 2048 key pair. */
    public CertificateGenerator.GeneratedCertificate issueClientCertificate(String commonName) {
        return generator.generateIssuedCertificate(commonName, List.of(), KeyAlgorithm.RSA_2048, null, issuer(),
                CertificateGenerator.LeafUsage.CLIENT);
    }

    /**
     * Signs an external PKCS#10 request as a {@code clientAuth} leaf. The subject DN comes from
     * the CSR; requested extensions are ignored (AWS IoT ignores them too). Throws
     * {@link IllegalArgumentException} when the PEM is not a CSR or its self-signature fails.
     */
    public X509Certificate signClientCsr(String csrPem) {
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            Object parsed = parser.readObject();
            if (!(parsed instanceof PKCS10CertificationRequest csr)) {
                throw new IllegalArgumentException("certificateSigningRequest is not a PEM CERTIFICATE REQUEST");
            }
            requireAcceptedKey(csr.getSubjectPublicKeyInfo());
            PublicKey subjectKey = new JcaPKCS10CertificationRequest(csr).getPublicKey();
            if (!csr.isSignatureValid(new JcaContentVerifierProviderBuilder().build(subjectKey))) {
                throw new IllegalArgumentException("certificateSigningRequest signature does not verify");
            }
            X500Name issuerDn = X500Name.getInstance(certificate.getSubjectX500Principal().getEncoded());
            return generator.signCertificate(csr.getSubject(), subjectKey, issuerDn, key, List.of(), false,
                    CertificateGenerator.LeafUsage.CLIENT, 365);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("certificateSigningRequest could not be parsed: " + e.getMessage(), e);
        }
    }

    /** True when {@code cert} names this CA as issuer and its signature checks against our key. */
    public boolean isIssuedByUs(X509Certificate cert) {
        if (!certificate.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) {
            return false;
        }
        try {
            cert.verify(certificate.getPublicKey());
            return true;
        } catch (Exception e) {
            LOG.debugv("TLS: certificate names our CA but does not verify: {0}", e.getMessage());
            return false;
        }
    }

    private static final Set<ASN1ObjectIdentifier> ACCEPTED_CURVES = Set.of(
            SECObjectIdentifiers.secp256r1, SECObjectIdentifiers.secp384r1, SECObjectIdentifiers.secp521r1);

    /**
     * AWS IoT accepts an RSA key of at least 2048 bits or an EC key on NIST P-256, P-384 or P-521.
     * The curve is read from the request's named-curve identifier, so a curve that merely shares a
     * field size (secp256k1) or explicit curve parameters are refused as well.
     */
    private static void requireAcceptedKey(SubjectPublicKeyInfo key) throws Exception {
        ASN1ObjectIdentifier algorithm = key.getAlgorithm().getAlgorithm();
        if (PKCSObjectIdentifiers.rsaEncryption.equals(algorithm)
                && RSAPublicKey.getInstance(key.parsePublicKey()).getModulus().bitLength() >= 2048) {
            return;
        }
        if (X9ObjectIdentifiers.id_ecPublicKey.equals(algorithm)) {
            X962Parameters parameters = X962Parameters.getInstance(key.getAlgorithm().getParameters());
            if (parameters.isNamedCurve() && ACCEPTED_CURVES.contains((ASN1ObjectIdentifier) parameters.getParameters())) {
                return;
            }
        }
        throw new IllegalArgumentException("certificateSigningRequest key must be RSA of at least 2048 bits "
                + "or EC on NIST P-256, P-384 or P-521");
    }

    /**
     * Writes a private key so that, where the file system has POSIX permissions, no other user can
     * read it at any point: the file is created owner-only before the first byte is written. A
     * file system without POSIX permissions gets the platform default and a WARN.
     */
    static void writePrivateKey(Path keyFile, String pem) throws IOException {
        Files.deleteIfExists(keyFile);
        if (Files.getFileAttributeView(keyFile.getParent(), PosixFileAttributeView.class) != null) {
            Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            LOG.warnv("TLS: {0} is written with the file system's default permissions because it has no POSIX "
                    + "permissions; restrict it to your own account yourself", keyFile);
        }
        Files.writeString(keyFile, pem);
        restrictToOwnerOnly(keyFile, "rw-------");
    }

    static void restrictToOwnerOnly(Path path, String posixPerms) {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view == null) {
            return;
        }
        try {
            view.setPermissions(PosixFilePermissions.fromString(posixPerms));
        } catch (IOException e) {
            LOG.warnv("TLS: could not restrict permissions on {0}: {1}", path, e.getMessage());
        }
    }
}
