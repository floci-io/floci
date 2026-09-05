package io.github.hectorvent.floci.config;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.jboss.logging.Logger;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The CA bundle Floci hands to every container it launches, so the workload inside can call Floci
 * over HTTPS (custom domains included) from any runtime without trusting anything by hand.
 *
 * <p>The bundle is the JVM's default trust anchors followed by Floci's own trust anchor: the local
 * CA, or the user-provided certificate file in custom-certificate mode. Carrying the public roots
 * is what makes it safe to point the <em>replacing</em> variables ({@code SSL_CERT_FILE},
 * {@code CURL_CA_BUNDLE}, {@code REQUESTS_CA_BUNDLE}) at it: the workload still reaches public
 * HTTPS endpoints. {@code NODE_EXTRA_CA_CERTS} appends to Node's roots and {@code AWS_CA_BUNDLE}
 * only applies to AWS SDK and CLI traffic, so both are harmless with the same file.
 *
 * <p>Written by {@link TlsConfigSource} at boot; copied into containers by the Docker lifecycle
 * manager and mounted from a ConfigMap by the Kubernetes Lambda launcher. Plain class because the
 * config source runs before CDI exists.
 */
public final class ContainerCaBundle {

    private static final Logger LOG = Logger.getLogger(ContainerCaBundle.class);

    public static final String FILE_NAME = "floci-ca-bundle.pem";
    public static final String CONTAINER_DIR = "/etc";
    public static final String CONTAINER_PATH = CONTAINER_DIR + "/" + FILE_NAME;

    static final List<String> ENV_KEYS = List.of(
            "SSL_CERT_FILE", "CURL_CA_BUNDLE", "REQUESTS_CA_BUNDLE", "NODE_EXTRA_CA_CERTS", "AWS_CA_BUNDLE");

    /** A missing bundle is a boot-time condition, so it is worth one WARN per process, not one per container. */
    private static final AtomicBoolean MISSING_BUNDLE_WARNED = new AtomicBoolean();

    private ContainerCaBundle() {
    }

    /**
     * Writes {@code tlsDir/floci-ca-bundle.pem}: the JVM's trust anchors, then every certificate in
     * {@code trustAnchorFile}. Only certificates are copied out of that file, so a combined
     * certificate-and-key file never leaks its key into a container. The file is swapped in
     * atomically from a uniquely named temporary file, so a container created during a boot never
     * reads a half-written bundle, even when two Floci processes share the directory.
     *
     * @throws IllegalArgumentException when the anchor file holds no certificate
     * @throws IllegalStateException    when the JVM has no trust anchors at all: a bundle without
     *                                  public roots would cut containers off from public HTTPS
     */
    public static Path write(Path tlsDir, Path trustAnchorFile) throws IOException, GeneralSecurityException {
        List<X509Certificate> anchors = parseCertificates(Files.readString(trustAnchorFile));
        if (anchors.isEmpty()) {
            throw new IllegalArgumentException(trustAnchorFile + " holds no certificate");
        }
        List<X509Certificate> roots = systemRoots();
        if (roots.isEmpty()) {
            throw new IllegalStateException("the JVM has no default trust anchors");
        }
        StringBuilder pem = new StringBuilder();
        for (X509Certificate root : roots) {
            pem.append(toPem(root));
        }
        for (X509Certificate anchor : anchors) {
            pem.append(toPem(anchor));
        }
        Files.createDirectories(tlsDir);
        Path target = tlsDir.resolve(FILE_NAME);
        Path temp = tlsDir.resolve(FILE_NAME + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(temp, pem.toString());
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        LOG.debugv("TLS: container CA bundle {0} holds {1} public roots and {2} Floci anchor(s)",
                target, roots.size(), anchors.size());
        return target;
    }

    /**
     * The bundle on the Floci host, or empty when TLS is off or the boot did not manage to write
     * it. Looks in the directory the bootstrap used, next to the CA.
     */
    public static Optional<Path> hostPath(EmulatorConfig config) {
        if (!config.tls().enabled()) {
            return Optional.empty();
        }
        Path bundle = FlociCertificateAuthorityProducer.tlsDir(config).resolve(FILE_NAME);
        if (!Files.isReadable(bundle)) {
            if (MISSING_BUNDLE_WARNED.compareAndSet(false, true)) {
                LOG.warnv("TLS is enabled but {0} is missing; containers launched now will not trust Floci HTTPS", bundle);
            } else {
                LOG.debugv("Container CA bundle {0} still missing", bundle);
            }
            return Optional.empty();
        }
        MISSING_BUNDLE_WARNED.set(false);
        return Optional.of(bundle);
    }

    /**
     * The certificate blocks of a PEM text, re-encoded one after the other; a private key or any
     * other PEM object in the text is dropped. What Floci hands out from a user-provided certificate
     * file, for the bundle and for {@code GET /_floci/ca.pem}, goes through here.
     */
    public static String certificatePem(String pem) throws IOException, GeneralSecurityException {
        StringBuilder certificates = new StringBuilder();
        for (X509Certificate certificate : parseCertificates(pem)) {
            certificates.append(toPem(certificate));
        }
        return certificates.toString();
    }

    /** The {@code KEY=value} entries that make each runtime read {@link #CONTAINER_PATH}. */
    public static List<String> env() {
        return appendEnv(null);
    }

    /**
     * {@code env} followed by {@link #env()}, skipping every key the caller already set (with or
     * without a value), so a value from a function or task definition always wins.
     */
    public static List<String> appendEnv(List<String> env) {
        List<String> merged = new ArrayList<>(env == null ? List.of() : env);
        for (String key : ENV_KEYS) {
            if (merged.stream().noneMatch(entry -> entry.equals(key) || entry.startsWith(key + "="))) {
                merged.add(key + "=" + CONTAINER_PATH);
            }
        }
        return merged;
    }

    /** Every trust anchor the running JVM accepts by default. */
    private static List<X509Certificate> systemRoots() throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        List<X509Certificate> roots = new ArrayList<>();
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) {
                roots.addAll(List.of(x509.getAcceptedIssuers()));
            }
        }
        return roots;
    }

    /** The certificates in a PEM text, in order; keys and any other PEM object are skipped. */
    private static List<X509Certificate> parseCertificates(String pem) throws IOException, GeneralSecurityException {
        List<X509Certificate> certificates = new ArrayList<>();
        JcaX509CertificateConverter converter = new JcaX509CertificateConverter();
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            for (Object object = parser.readObject(); object != null; object = parser.readObject()) {
                if (object instanceof X509CertificateHolder holder) {
                    certificates.add(converter.getCertificate(holder));
                }
            }
        }
        return certificates;
    }

    private static String toPem(X509Certificate certificate) throws CertificateEncodingException {
        return "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
    }
}
