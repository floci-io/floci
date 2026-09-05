package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * Floci's one local root CA. Every certificate the emulator hands out that a client is expected
 * to validate (the HTTPS server leaf, and later ACM and IoT device certificates) chains to it, so
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
                    if (!keyMatches(key, cert.getPublicKey())) {
                        throw new IllegalStateException("private key does not match the certificate");
                    }
                    restrictToOwnerOnly(tlsDir, "rwx------");
                    restrictToOwnerOnly(keyFile, "rw-------");
                    LOG.infov("TLS: using local CA {0} ({1})", certFile, cert.getSubjectX500Principal().getName());
                    return new FlociCertificateAuthority(certFile, cert, key, pem, generator);
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
            LOG.infov("TLS: generated local CA {0}. Trust it once: GET /_floci/ca.pem", certFile);
            return new FlociCertificateAuthority(certFile,
                    generator.parseCertificate(generated.certificatePem()),
                    generator.parsePrivateKey(generated.privateKeyPem()),
                    generated.certificatePem(), generator);
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

    public CertificateGenerator.Issuer issuer() {
        return new CertificateGenerator.Issuer(certificate, key);
    }

    /**
     * A {@code serverAuth} leaf for {@code commonName} plus {@code sans}. {@code keyPair} null
     * mints a new key of {@code keyAlgorithm}; passing the current pair keeps the public key
     * across a SAN change (the algorithm is then ignored).
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

    private static boolean keyMatches(PrivateKey key, PublicKey publicKey) throws Exception {
        String algorithm = "EC".equals(key.getAlgorithm()) ? "SHA256withECDSA" : "SHA256withRSA";
        byte[] probe = "floci".getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance(algorithm);
        signer.initSign(key);
        signer.update(probe);
        byte[] signature = signer.sign();
        Signature verifier = Signature.getInstance(algorithm);
        verifier.initVerify(publicKey);
        verifier.update(probe);
        return verifier.verify(signature);
    }

    /**
     * Writes a private key so that no other user can read it at any point: the file is created
     * owner-only where the file system supports POSIX permissions, and re-tightened otherwise.
     */
    static void writePrivateKey(Path keyFile, String pem) throws IOException {
        Files.deleteIfExists(keyFile);
        if (Files.getFileAttributeView(keyFile.getParent(), PosixFileAttributeView.class) != null) {
            Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
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
