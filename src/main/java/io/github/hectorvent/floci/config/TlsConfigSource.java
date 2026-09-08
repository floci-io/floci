package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A MicroProfile {@link ConfigSource} that dynamically provides Quarkus TLS/SSL
 * configuration when {@code floci.tls.enabled=true}.
 *
 * <p>
 * This runs <em>before</em> the Quarkus HTTP server starts, which is critical
 * because Quarkus reads {@code quarkus.http.ssl.*} properties during server
 * initialization. A CDI {@code @Startup} bean or {@code StartupEvent} observer
 * would be too late.
 *
 * <p>
 * When TLS is enabled without a user certificate, the server certificate is a leaf issued by
 * {@link FlociCertificateAuthority} and persisted under {@code {persistent-path}/tls/}. Clients
 * trust the CA ({@code floci-root-ca.crt}, or {@code GET /_floci/ca.pem}), never the leaf.
 *
 * <p>
 * It also writes {@link ContainerCaBundle}, the trust bundle every container Floci launches
 * receives, next to the certificates.
 *
 * <p>
 * Both HTTP and HTTPS are served simultaneously (LocalStack parity).
 */
public class TlsConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(TlsConfigSource.class);

    private static final String SERVER_CERT_NAME = "floci-server.crt";
    private static final String SERVER_KEY_NAME = "floci-server.key";
    private static final String SERVER_METADATA_NAME = "floci-server.metadata.json";
    private static final String TLS_DIR = "tls";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // host.docker.internal: how Lambda containers reach Floci when it runs on the host (not in a container).
    private static final List<String> DEFAULT_SAN_HOSTNAMES = List.of(
            "localhost", "127.0.0.1", "0.0.0.0", "*.localhost",
            "localhost.floci.io", "*.localhost.floci.io",
            "*.execute-api.localhost.floci.io",
            "*.execute-api.localhost.localstack.cloud", "host.docker.internal");

    private static volatile Path resolvedTlsDir;

    private final Map<String, String> properties = new HashMap<>();

    /**
     * The TLS directory the most recent bootstrap used, or null when that bootstrap ran with TLS
     * off. With a user-provided certificate it holds the container CA bundle and, once
     * {@code GET /_floci/ca.pem} is called, the local CA; in self-signed mode the CA and the leaf
     * as well. Reset on every construction so a later boot in the same JVM never sees a stale
     * value.
     */
    static Path resolvedTlsDir() {
        return resolvedTlsDir;
    }

    public TlsConfigSource() {
        resolvedTlsDir = null;
        String enabled = resolveProperty("floci.tls.enabled", "false");
        if (!"true".equalsIgnoreCase(enabled)) {
            LOG.debug("TLS disabled, TlsConfigSource inactive");
            return;
        }

        String certPath = resolveProperty("floci.tls.cert-path", "");
        String keyPath = resolveProperty("floci.tls.key-path", "");
        String selfSigned = resolveProperty("floci.tls.self-signed", "true");
        String persistentPath = resolveProperty("floci.storage.persistent-path", "./data");
        Path tlsDir = Path.of(persistentPath, TLS_DIR);
        resolvedTlsDir = tlsDir;

        Path trustAnchor;
        if (!certPath.isBlank() && !keyPath.isBlank()) {
            validateFileExists(certPath, "TLS certificate");
            validateFileExists(keyPath, "TLS private key");
            LOG.infov("TLS: using user-provided certificate: {0}", certPath);
            trustAnchor = Path.of(certPath);
        } else if ("true".equalsIgnoreCase(selfSigned)) {
            Path certFile = tlsDir.resolve(SERVER_CERT_NAME);
            Path keyFile = tlsDir.resolve(SERVER_KEY_NAME);
            FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
            trustAnchor = ca.certificatePath();

            if (Files.exists(certFile) && Files.exists(keyFile)) {
                List<String> currentHostnames = new ArrayList<>(DEFAULT_SAN_HOSTNAMES);
                currentHostnames.addAll(extractCustomHostnames());

                // Regenerate when the hostname config changed, when the existing leaf was not
                // issued by the current CA (a pre-CA self-signed cert, or the CA was regenerated),
                // or when it is outside its validity window (the leaf lives 365 days, dev data
                // directories longer).
                if (hostnameConfigChanged(tlsDir, currentHostnames) || !isValidLocalLeaf(ca, certFile)) {
                    generateServerCert(tlsDir, certFile, keyFile, ca);
                } else {
                    LOG.infov("TLS: reusing existing server certificate: {0}", certFile);
                }
            } else {
                generateServerCert(tlsDir, certFile, keyFile, ca);
            }

            certPath = certFile.toAbsolutePath().toString();
            keyPath = keyFile.toAbsolutePath().toString();
        } else {
            throw new IllegalStateException(
                    "TLS enabled but no certificate provided and self-signed generation disabled. "
                            + "Set FLOCI_TLS_CERT_PATH + FLOCI_TLS_KEY_PATH, or enable FLOCI_TLS_SELF_SIGNED.");
        }

        writeContainerCaBundle(tlsDir, trustAnchor);

        // The default entry of the Quarkus TLS registry. The HTTP server reads it when no
        // quarkus.http.tls-configuration-name is set, and the registry can reload it at runtime.
        properties.put("quarkus.tls.key-store.pem.0.cert", certPath);
        properties.put("quarkus.tls.key-store.pem.0.key", keyPath);
        // When TLS is enabled, Quarkus HTTP and HTTPS run on internal ports.
        // A TlsProxyServer (NetServer) listens on the public Floci port (4566)
        // and does protocol detection to route HTTP and HTTPS to the correct backend.
        properties.put("quarkus.http.insecure-requests", "enabled");
        properties.put("quarkus.http.host", "127.0.0.1");
        properties.put("quarkus.http.port", "4510");
        properties.put("quarkus.http.ssl-port", "4511");

        LOG.infov("TLS: HTTPS enabled, proxy will listen on port {0} (HTTP+HTTPS), cert={1}",
                resolveProperty("floci.port", "4566"), certPath);
    }

    @Override
    public int getOrdinal() {
        // Higher than application.yml (250) so TLS properties take precedence
        return 300;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return "FlociTlsConfigSource";
    }

    /**
     * Resolves a property from system properties or environment variables.
     * Environment variable names follow the MicroProfile convention:
     * {@code floci.tls.enabled} → {@code FLOCI_TLS_ENABLED}.
     */
    private static String resolveProperty(String key, String defaultValue) {
        // 1. System property (highest priority)
        String value = System.getProperty(key);
        if (value != null && !value.isBlank())
            return value;

        // 2. Environment variable (underscore + uppercase)
        String envKey = key.replace('.', '_').replace('-', '_').toUpperCase();
        value = System.getenv(envKey);
        if (value != null && !value.isBlank())
            return value;

        return defaultValue;
    }

    private void generateServerCert(Path tlsDir, Path certFile, Path keyFile, FlociCertificateAuthority ca) {
        try {
            Files.createDirectories(tlsDir);
            List<String> configured = new ArrayList<>(DEFAULT_SAN_HOSTNAMES);
            configured.addAll(extractCustomHostnames());
            List<String> learned = readLearnedHostnames(tlsDir, certFile);
            List<String> allSans = new ArrayList<>(configured);
            for (String name : learned) {
                if (!allSans.contains(name)) {
                    allSans.add(name);
                }
            }

            CertificateGenerator.GeneratedCertificate generated = ca.issueServerCertificate("localhost", allSans,
                    KeyAlgorithm.RSA_2048, null);

            Files.writeString(certFile, generated.certificatePem());
            FlociCertificateAuthority.writePrivateKey(keyFile, generated.privateKeyPem());

            LOG.infov("TLS: generated server certificate {0} issued by {1}", certFile,
                    ca.certificate().getSubjectX500Principal().getName());
            persistMetadata(tlsDir, configured, learned);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write TLS server certificate", e);
        }
    }

    /**
     * Hostnames {@link TlsCertificateManager} added at runtime. A boot that regenerates the
     * certificate (changed configuration, expired or foreign leaf) must keep serving them. The
     * served certificate is the source of truth: a SAN it carries beyond the metadata's configured
     * list was learned by a reissue whose metadata write did not complete, and is kept too.
     */
    private List<String> readLearnedHostnames(Path tlsDir, Path certFile) {
        Path metadataFile = tlsDir.resolve(SERVER_METADATA_NAME);
        if (!Files.exists(metadataFile)) {
            return List.of();
        }
        try {
            CertificateMetadata metadata = OBJECT_MAPPER.readValue(Files.readString(metadataFile), CertificateMetadata.class);
            Set<String> learned = new LinkedHashSet<>(metadata.getLearnedHostnames());
            if (metadata.getHostnames() != null && Files.exists(certFile)) {
                Set<String> configured = new LinkedHashSet<>(metadata.getHostnames());
                for (String san : servedSans(certFile)) {
                    if (!configured.contains(san)) {
                        learned.add(san);
                    }
                }
            }
            return new ArrayList<>(learned);
        } catch (IOException e) {
            LOG.warnv("TLS: could not read learned hostnames from {0} ({1}); the new certificate starts without them",
                    metadataFile, e.getMessage());
            return List.of();
        }
    }

    private static List<String> servedSans(Path certFile) {
        List<String> sans = new ArrayList<>();
        try {
            X509Certificate cert = new CertificateGenerator().parseCertificate(Files.readString(certFile));
            if (cert.getSubjectAlternativeNames() != null) {
                for (List<?> entry : cert.getSubjectAlternativeNames()) {
                    sans.add(String.valueOf(entry.get(1)));
                }
            }
        } catch (Exception e) {
            LOG.debugv("TLS: could not read the SAN list of {0} ({1}); learned names come from the metadata only",
                    certFile, e.getMessage());
        }
        return sans;
    }

    /**
     * Containers Floci launches get this bundle copied in (Docker) or mounted (Kubernetes). A
     * failure here must not stop Floci: log it, and containers simply will not trust Floci HTTPS
     * until a boot manages to write it.
     */
    private static void writeContainerCaBundle(Path tlsDir, Path trustAnchor) {
        try {
            ContainerCaBundle.write(tlsDir, trustAnchor);
        } catch (Exception e) {
            LOG.warnv(e, "TLS: could not write the container CA bundle under {0}: {1}", tlsDir, e.getMessage());
        }
    }

    private static void validateFileExists(String path, String description) {
        if (!Files.isReadable(Path.of(path))) {
            throw new IllegalStateException(
                    description + " file not found or not readable: " + path);
        }
    }

    /**
     * Returns {@code true} if the certificate at {@code certFile} was issued by the current local
     * CA and is inside its validity window. A pre-CA self-signed leaf, a leaf from a regenerated
     * CA, an expired leaf or an unreadable file all return {@code false} and are replaced.
     */
    private boolean isValidLocalLeaf(FlociCertificateAuthority ca, Path certFile) {
        try {
            X509Certificate cert = new CertificateGenerator().parseCertificate(Files.readString(certFile));
            if (!ca.isIssuedByUs(cert)) {
                LOG.infov("TLS: existing server certificate was not issued by the local CA; regenerating");
                return false;
            }
            cert.checkValidity();
            return true;
        } catch (Exception e) {
            LOG.warnv("TLS: existing server certificate unusable ({0}); regenerating", e.getMessage());
            return false;
        }
    }

    /**
     * Extracts custom hostnames from FLOCI_HOSTNAME, FLOCI_BASE_URL and
     * FLOCI_SERVICES_IOT_ENDPOINT_ADDRESS configuration.
     * Filters out default values like "localhost" and "127.0.0.1".
     * Returns a deduplicated list of custom hostnames.
     *
     * @return List of custom hostnames (may be empty if no custom hostnames configured)
     */
    private List<String> extractCustomHostnames() {
        Set<String> hostnames = new LinkedHashSet<>();

        // Extract from FLOCI_HOSTNAME
        String hostname = resolveProperty("floci.hostname", "");
        if (!hostname.isBlank() && !isDefaultHostname(hostname)) {
            hostnames.add(hostname);
            LOG.debugv("TLS: extracted hostname from floci.hostname: {0}", hostname);
        }

        // Extract from FLOCI_BASE_URL
        String baseUrl = resolveProperty("floci.base-url", "http://localhost:4566");
        try {
            URI uri = new URI(baseUrl);
            String host = uri.getHost();
            if (host != null && !isDefaultHostname(host)) {
                hostnames.add(host);
                LOG.debugv("TLS: extracted hostname from floci.base-url: {0}", host);
            }
        } catch (URISyntaxException e) {
            LOG.warnv("TLS: failed to parse base URL for hostname extraction: {0}", baseUrl);
        }

        // Extract from FLOCI_SERVICES_IOT_ENDPOINT_ADDRESS: devices verify that name on 8883 and 443
        String iotEndpoint = resolveProperty("floci.services.iot.endpoint-address", "").strip();
        if (!iotEndpoint.isEmpty()) {
            try {
                // Anything beyond host[:port] is a typo: java.net.URI would read "https" as the host of a URL.
                URI uri = new URI("//" + iotEndpoint);
                String host = uri.getHost();
                if (host == null || uri.getUserInfo() != null || !iotEndpoint.equals(uri.getRawAuthority())) {
                    LOG.warnv("TLS: floci.services.iot.endpoint-address is not a host or host:port, not added to the certificate: {0}",
                            iotEndpoint);
                } else if (!isDefaultHostname(host)) {
                    hostnames.add(host);
                    LOG.debugv("TLS: extracted hostname from floci.services.iot.endpoint-address: {0}", host);
                }
            } catch (URISyntaxException e) {
                LOG.warnv("TLS: failed to parse floci.services.iot.endpoint-address for hostname extraction: {0}",
                        iotEndpoint);
            }
        }

        List<String> result = new ArrayList<>(hostnames);
        if (!result.isEmpty()) {
            LOG.infov("TLS: detected custom hostnames: {0}", result);
        }
        return result;
    }

    /**
     * Checks if a hostname is a default value that should be filtered out.
     *
     * @param hostname The hostname to check
     * @return true if the hostname is a default value (localhost, 127.0.0.1, 0.0.0.0)
     */
    private boolean isDefaultHostname(String hostname) {
        return hostname.equals("localhost") 
            || hostname.equals("127.0.0.1") 
            || hostname.equals("0.0.0.0");
    }

    /**
     * Persists certificate metadata to enable change detection on restart.
     * Writes metadata to {tls-dir}/floci-server.metadata.json.
     * 
     * @param tlsDir The TLS directory where metadata should be written
     * @param hostnames List of configured hostnames included in the certificate SANs
     * @param learnedHostnames Hostnames added at runtime, also in the SANs but kept apart so a
     *                         configuration change is still detected by comparing {@code hostnames}
     */
    private void persistMetadata(Path tlsDir, List<String> hostnames, List<String> learnedHostnames) {
        Path metadataFile = tlsDir.resolve(SERVER_METADATA_NAME);
        try {
            String version = resolveFlociVersion();
            CertificateMetadata metadata = CertificateMetadata.create(hostnames, version);
            metadata.setLearnedHostnames(learnedHostnames);

            String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
            
            Files.writeString(metadataFile, json);
            LOG.debugv("TLS: persisted certificate metadata: {0}", metadataFile);
        } catch (IOException e) {
            // Log warning but don't fail startup - metadata is for optimization, not critical
            LOG.warnv("TLS: failed to write certificate metadata (will regenerate on next restart): {0}", e.getMessage());
        }
    }

    /**
     * Resolves the Floci version from environment variable or defaults to "dev".
     * 
     * @return Floci version string
     */
    private static String resolveFlociVersion() {
        String env = System.getenv("FLOCI_VERSION");
        if (env != null && !env.isBlank()) {
            return env;
        }
        return "dev";
    }

    /**
     * Checks if the hostname configuration has changed since the last certificate generation.
     * Loads the metadata file and compares the hostnames with the current configuration.
     * 
     * @param tlsDir The TLS directory where metadata file is stored
     * @param currentHostnames List of hostnames from current configuration
     * @return true if configuration changed or metadata missing, false if same
     */
    private boolean hostnameConfigChanged(Path tlsDir, List<String> currentHostnames) {
        Path metadataFile = tlsDir.resolve(SERVER_METADATA_NAME);
        
        // If metadata doesn't exist, trigger regeneration
        if (!Files.exists(metadataFile)) {
            LOG.infov("TLS: metadata file missing, regenerating certificate to ensure correctness");
            return true;
        }
        
        try {
            // Load and parse metadata file
            String json = Files.readString(metadataFile);
            CertificateMetadata metadata = OBJECT_MAPPER.readValue(json, CertificateMetadata.class);
            
            List<String> previousHostnames = metadata.getHostnames();
            if (previousHostnames == null) {
                LOG.warnv("TLS: metadata file has no hostnames field, regenerating certificate");
                return true;
            }
            
            // Compare hostnames (order-independent)
            Set<String> previousSet = new LinkedHashSet<>(previousHostnames);
            Set<String> currentSet = new LinkedHashSet<>(currentHostnames);
            
            if (!previousSet.equals(currentSet)) {
                LOG.infov("TLS: hostname configuration changed, regenerating certificate");
                LOG.debugv("TLS: previous hostnames: {0}", previousHostnames);
                LOG.debugv("TLS: current hostnames: {0}", currentHostnames);
                return true;
            }
            
            // Configuration unchanged
            LOG.debugv("TLS: hostname configuration unchanged, reusing certificate");
            return false;
            
        } catch (IOException e) {
            // Handle read/parse failures gracefully - trigger regeneration
            LOG.warnv("TLS: failed to read metadata file (will regenerate certificate): {0}", e.getMessage());
            return true;
        }
    }
}
