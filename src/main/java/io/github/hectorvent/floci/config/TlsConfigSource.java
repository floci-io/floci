package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.security.Security;

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
 * When TLS is enabled with self-signed mode, the certificate is generated
 * using the ACM {@link CertificateGenerator} and persisted under
 * {@code {persistent-path}/tls/} for reuse across restarts.
 *
 * <p>
 * Both HTTP and HTTPS are served simultaneously (LocalStack parity).
 */
public class TlsConfigSource implements ConfigSource {

    private static final Logger LOG = Logger.getLogger(TlsConfigSource.class);

    private static final String SELF_SIGNED_CERT_NAME = "floci-selfsigned.crt";
    private static final String SELF_SIGNED_KEY_NAME = "floci-selfsigned.key";
    private static final String SELF_SIGNED_METADATA_NAME = "floci-selfsigned.metadata.json";
    private static final String TLS_DIR = "tls";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Map<String, String> properties = new HashMap<>();

    public TlsConfigSource() {
        String enabled = resolveProperty("floci.tls.enabled", "false");
        if (!"true".equalsIgnoreCase(enabled)) {
            LOG.debug("TLS disabled — TlsConfigSource inactive");
            return;
        }

        String certPath = resolveProperty("floci.tls.cert-path", "");
        String keyPath = resolveProperty("floci.tls.key-path", "");
        String selfSigned = resolveProperty("floci.tls.self-signed", "true");
        String persistentPath = resolveProperty("floci.storage.persistent-path", "./data");

        if (!certPath.isBlank() && !keyPath.isBlank()) {
            validateFileExists(certPath, "TLS certificate");
            validateFileExists(keyPath, "TLS private key");
            LOG.infov("TLS: using user-provided certificate: {0}", certPath);
        } else if ("true".equalsIgnoreCase(selfSigned)) {
            Path tlsDir = Path.of(persistentPath, TLS_DIR);
            Path certFile = tlsDir.resolve(SELF_SIGNED_CERT_NAME);
            Path keyFile = tlsDir.resolve(SELF_SIGNED_KEY_NAME);

            // Check if certificate files exist and configuration hasn't changed
            if (Files.exists(certFile) && Files.exists(keyFile)) {
                // Extract current hostname configuration
                List<String> customHostnames = extractCustomHostnames();
                List<String> currentHostnames = new ArrayList<>();
                currentHostnames.addAll(List.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost"));
                currentHostnames.addAll(customHostnames);
                
                // Check if hostname configuration has changed
                if (hostnameConfigChanged(tlsDir, currentHostnames)) {
                    // Configuration changed or metadata missing - regenerate certificate
                    generateSelfSignedCert(tlsDir, certFile, keyFile);
                } else {
                    // Configuration unchanged - reuse existing certificate
                    LOG.infov("TLS: reusing existing self-signed certificate: {0}", certFile);
                }
            } else {
                // Certificate files don't exist - generate new certificate
                generateSelfSignedCert(tlsDir, certFile, keyFile);
            }

            certPath = certFile.toAbsolutePath().toString();
            keyPath = keyFile.toAbsolutePath().toString();
        } else {
            throw new IllegalStateException(
                    "TLS enabled but no certificate provided and self-signed generation disabled. "
                            + "Set FLOCI_TLS_CERT_PATH + FLOCI_TLS_KEY_PATH, or enable FLOCI_TLS_SELF_SIGNED.");
        }

        properties.put("quarkus.http.ssl.certificate.files", certPath);
        properties.put("quarkus.http.ssl.certificate.key-files", keyPath);
        properties.put("quarkus.http.insecure-requests", "enabled");

        LOG.infov("TLS: HTTPS enabled (HTTP+HTTPS dual mode), cert={0}", certPath);
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

    private void generateSelfSignedCert(Path tlsDir, Path certFile, Path keyFile) {
        try {
            Files.createDirectories(tlsDir);

            // BouncyCastle may not be registered yet — ConfigSource runs before CDI
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }

            // Extract custom hostnames and combine with defaults
            List<String> customHostnames = extractCustomHostnames();
            List<String> allSans = new ArrayList<>();
            allSans.addAll(List.of("localhost", "127.0.0.1", "0.0.0.0", "*.localhost"));
            allSans.addAll(customHostnames);

            CertificateGenerator gen = new CertificateGenerator();
            CertificateGenerator.GeneratedCertificate generated = gen.generateCertificate(
                    "localhost",
                    allSans,
                    KeyAlgorithm.RSA_2048);

            Files.writeString(certFile, generated.certificatePem());
            Files.writeString(keyFile, generated.privateKeyPem());

            LOG.infov("TLS: generated self-signed certificate: {0}", certFile);

            // Persist metadata for change detection on restart
            persistMetadata(tlsDir, allSans);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write self-signed TLS certificate", e);
        }
    }

    private static void validateFileExists(String path, String description) {
        if (!Files.isReadable(Path.of(path))) {
            throw new IllegalStateException(
                    description + " file not found or not readable: " + path);
        }
    }

    /**
     * Extracts custom hostnames from FLOCI_HOSTNAME and FLOCI_BASE_URL configuration.
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
     * Writes metadata to {tls-dir}/floci-selfsigned.metadata.json.
     * 
     * @param tlsDir The TLS directory where metadata should be written
     * @param hostnames List of hostnames included in the certificate SANs
     */
    private void persistMetadata(Path tlsDir, List<String> hostnames) {
        Path metadataFile = tlsDir.resolve(SELF_SIGNED_METADATA_NAME);
        try {
            String version = resolveFlociVersion();
            CertificateMetadata metadata = CertificateMetadata.create(hostnames, version);
            
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
        Path metadataFile = tlsDir.resolve(SELF_SIGNED_METADATA_NAME);
        
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
