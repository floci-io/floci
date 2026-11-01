package io.github.hectorvent.floci.config;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.openssl.PEMParser;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
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
 * only applies to AWS SDK and CLI traffic, so both are harmless with the same file. The JVM reads none
 * of them, so the same certificates also go in a PKCS12 trust store, which goes in beside the bundle for a
 * container whose workload may be Java, its JVMs pointed at it through {@code JAVA_TOOL_OPTIONS}.
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

    public static final String JAVA_TRUST_STORE_FILE_NAME = "floci-ca-bundle.p12";
    public static final String JAVA_TRUST_STORE_PATH = CONTAINER_DIR + "/" + JAVA_TRUST_STORE_FILE_NAME;

    private static final String JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";

    /**
     * The JVM option that makes a JVM trust what the trust store at {@link #JAVA_TRUST_STORE_PATH} holds: its
     * path alone, as the store needs no password and the JVM reads it whatever store type it is told. A store a
     * job or an image names then replaces Floci's whole, and its own type and password, or none, apply to it.
     */
    public static final String JAVA_TRUST_OPTIONS = "-Djavax.net.ssl.trustStore=" + JAVA_TRUST_STORE_PATH;

    /**
     * A shell line after which every JVM the shell starts trusts the bundle, through {@code JAVA_TOOL_OPTIONS},
     * when the trust store is in the container, and only then, so a container without it keeps the image's
     * trust. Options already in {@code JAVA_TOOL_OPTIONS} come after Floci's, so a trust store of their own
     * wins. For a container the trust store is copied into on its own rather than with the bundle, such as one
     * kept as an image, which would keep a {@code JAVA_TOOL_OPTIONS} given at its creation.
     */
    public static final String JAVA_TRUST_SHELL_LINE = "if [ -r " + JAVA_TRUST_STORE_PATH + " ]; then"
            + " export JAVA_TOOL_OPTIONS=\"" + JAVA_TRUST_OPTIONS + "${JAVA_TOOL_OPTIONS:+ $JAVA_TOOL_OPTIONS}\"; fi";

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
        Path target = replace(tlsDir.resolve(FILE_NAME), pem.toString().getBytes(StandardCharsets.UTF_8));
        LOG.debugv("TLS: container CA bundle {0} holds {1} public roots and {2} Floci anchor(s)",
                target, roots.size(), anchors.size());
        return target;
    }

    /**
     * Writes the certificates of {@code bundle}, the file {@link #write} returned, to a PKCS12 trust store
     * beside it, {@code floci-ca-bundle.p12}, which a JVM given {@link #JAVA_TRUST_OPTIONS} trusts, swapped in
     * as the bundle is. It has no password, so its certificates are neither encrypted nor under a MAC, and a
     * JVM reads them with no password or any.
     *
     * @throws CertificateException when the bundle holds no certificate
     */
    public static Path writeJavaTrustStore(Path bundle) throws IOException, GeneralSecurityException {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        trustStore(bundle).store(content, null);
        return replace(bundle.resolveSibling(JAVA_TRUST_STORE_FILE_NAME), content.toByteArray());
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
                LOG.warnv("TLS is enabled but {0} is missing; containers launched now will not trust Floci HTTPS,"
                        + " and JWT authorizers will refuse an issuer Floci serves", bundle);
            } else {
                LOG.debugv("Container CA bundle {0} still missing", bundle);
            }
            return Optional.empty();
        }
        MISSING_BUNDLE_WARNED.set(false);
        return Optional.of(bundle);
    }

    /**
     * The trust store {@link #writeJavaTrustStore} wrote on the Floci host, or empty when TLS is off or the
     * boot did not manage to write it.
     */
    public static Optional<Path> javaTrustStoreHostPath(EmulatorConfig config) {
        if (!config.tls().enabled()) {
            return Optional.empty();
        }
        Path trustStore = FlociCertificateAuthorityProducer.tlsDir(config).resolve(JAVA_TRUST_STORE_FILE_NAME);
        return Files.isReadable(trustStore) ? Optional.of(trustStore) : Optional.empty();
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

    /**
     * {@code env} with {@code JAVA_TOOL_OPTIONS} opening with {@link #JAVA_TRUST_OPTIONS}: ahead of the value
     * {@code env} gives it, or, when it gives none, ahead of the value in {@code imageEnv}, the image's own,
     * which a container's value replaces. Options after Floci's win, so a trust store they name replaces it.
     */
    public static List<String> appendJavaTrustEnv(List<String> env, List<String> imageEnv) {
        List<String> merged = new ArrayList<>(env);
        boolean set = false;
        for (int i = 0; i < merged.size(); i++) {
            if (isJavaToolOptions(merged.get(i))) {
                merged.set(i, javaToolOptionsAfterFloci(merged.get(i)));
                set = true;
            }
        }
        if (!set) {
            // The image's last entry is the one its containers get.
            merged.add(javaToolOptionsAfterFloci(imageEnv.stream()
                    .filter(ContainerCaBundle::isJavaToolOptions)
                    .reduce((earlier, later) -> later)
                    .orElse(JAVA_TOOL_OPTIONS)));
        }
        return merged;
    }

    private static boolean isJavaToolOptions(String entry) {
        return entry.equals(JAVA_TOOL_OPTIONS) || entry.startsWith(JAVA_TOOL_OPTIONS + "=");
    }

    // The JAVA_TOOL_OPTIONS entry with Floci's option ahead of the options in entry, which a bare key gives none.
    private static String javaToolOptionsAfterFloci(String entry) {
        String options = entry.length() > JAVA_TOOL_OPTIONS.length()
                ? entry.substring(JAVA_TOOL_OPTIONS.length() + 1) : "";
        return JAVA_TOOL_OPTIONS + "=" + JAVA_TRUST_OPTIONS + (options.isBlank() ? "" : " " + options);
    }

    /**
     * An SSL context that trusts what the bundle carries: the JVM's roots and Floci's own trust
     * anchor. For Floci's own outbound HTTPS to a URL Floci serves, such as a Cognito pool's issuer,
     * which on AWS is a public endpoint every client already trusts. Empty when there is no bundle,
     * or when it cannot be read, and the JVM's roots alone are trusted then.
     */
    public static Optional<SSLContext> sslContext(EmulatorConfig config) {
        Optional<Path> bundle = hostPath(config);
        if (bundle.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(sslContext(bundle.get()));
        } catch (IOException | GeneralSecurityException e) {
            LOG.warnv(e, "Cannot read {0}; Floci's own HTTPS clients trust the JVM's roots only", bundle.get());
            return Optional.empty();
        }
    }

    /**
     * An SSL context that trusts exactly the certificates in {@code bundle}.
     *
     * @throws CertificateException when the bundle holds no certificate: a context over it would
     *                              trust nothing, public endpoints included
     */
    public static SSLContext sslContext(Path bundle) throws IOException, GeneralSecurityException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers(bundle), null);
        return context;
    }

    /** The trust managers of {@link #sslContext(Path)}, over exactly the certificates in {@code bundle}. */
    static TrustManager[] trustManagers(Path bundle) throws IOException, GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore(bundle));
        return factory.getTrustManagers();
    }

    /** A PKCS12 key store of exactly the certificates in {@code bundle}, each a trusted entry. */
    private static KeyStore trustStore(Path bundle) throws IOException, GeneralSecurityException {
        List<X509Certificate> certificates = parseCertificates(Files.readString(bundle));
        if (certificates.isEmpty()) {
            throw new CertificateException(bundle + " holds no certificate");
        }
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        for (int i = 0; i < certificates.size(); i++) {
            trustStore.setCertificateEntry("anchor-" + i, certificates.get(i));
        }
        return trustStore;
    }

    /** Puts {@code content} at {@code target} through a temporary file swapped in, as {@link #write} says. */
    private static Path replace(Path target, byte[] content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temp, content);
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    /** Every trust anchor the running JVM accepts by default. */
    private static List<X509Certificate> systemRoots() throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        List<X509Certificate> roots = new ArrayList<>();
        for (TrustManager manager : factory.getTrustManagers()) {
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
