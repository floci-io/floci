package io.github.hectorvent.floci.services.rds.proxy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.CertificateMetadata;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the RDS auth proxy's self-signed CA certificate: a single trust anchor shared by every
 * proxied Postgres/MySQL instance, cluster, and RDS Proxy target, whose Subject Alternative Names
 * grow to cover every advertised host Floci has handed out (Docker bridge IP, {@code
 * host.docker.internal}, a configured {@code rds.endpointHost}, etc.).
 *
 * <p>Persisted at {@code {persistent-path}/tls/rds-ca.{crt,key}} (plus a metadata file recording
 * the current SAN list) so the same root survives restarts — a client only has to trust it once
 * (e.g. {@code sslmode=verify-full sslrootcert=<path>}) to keep working across every local
 * database and every restart, as long as the advertised host doesn't change.
 */
@ApplicationScoped
public class RdsProxyTlsCertificates {

    private static final Logger LOG = Logger.getLogger(RdsProxyTlsCertificates.class);

    private static final List<String> DEFAULT_SANS = List.of("localhost", "127.0.0.1");
    private static final String CERT_NAME = "rds-ca.crt";
    private static final String KEY_NAME = "rds-ca.key";
    private static final String METADATA_NAME = "rds-ca.metadata.json";
    private static final String TLS_DIR = "tls";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final EmulatorConfig config;
    private final CertificateGenerator certificateGenerator;
    private final Object lock = new Object();

    private volatile SSLContext sslContext;
    private volatile Set<String> currentSans = Set.of();
    private volatile KeyPair currentKeyPair;

    @Inject
    public RdsProxyTlsCertificates(EmulatorConfig config, CertificateGenerator certificateGenerator) {
        this.config = config;
        this.certificateGenerator = certificateGenerator;
    }

    /**
     * Ensures {@code host} is covered by the current CA's SAN list, reissuing the certificate
     * (and re-persisting it) if it is a genuinely new host. Reissuing reuses the existing key pair
     * rather than minting a new one, so a client that already trusts a prior copy of this CA (e.g.
     * one it copied into its own trust store) keeps validating connections after the SAN list
     * grows — only the SAN extension and serial change, not the signing key. Cheap no-op once a
     * host is known.
     */
    public void ensureHost(String host) {
        if (host == null || host.isBlank()) {
            return;
        }
        ensureInitialized();
        if (currentSans.contains(host)) {
            return;
        }
        synchronized (lock) {
            if (currentSans.contains(host)) {
                return;
            }
            Set<String> updated = new LinkedHashSet<>(currentSans);
            updated.add(host);
            regenerate(updated);
        }
    }

    /**
     * Returns the current TLS server context. Handlers should call this per-handshake rather than
     * caching it themselves — {@link #ensureHost(String)} can swap it out at any time.
     */
    public SSLContext sslContext() {
        ensureInitialized();
        return sslContext;
    }

    private void ensureInitialized() {
        if (sslContext != null) {
            return;
        }
        synchronized (lock) {
            if (sslContext != null) {
                return;
            }
            Path tlsDir = tlsDir();
            Path certFile = tlsDir.resolve(CERT_NAME);
            Path keyFile = tlsDir.resolve(KEY_NAME);
            Path metadataFile = tlsDir.resolve(METADATA_NAME);
            if (Files.exists(certFile) && Files.exists(keyFile) && Files.exists(metadataFile)) {
                try {
                    List<String> persistedSans = readMetadata(metadataFile);
                    loadExisting(certFile, keyFile, persistedSans);
                    // Re-harden on every load: earlier Floci versions wrote this key world-readable,
                    // so an existing install must be tightened too, not just newly generated ones.
                    restrictToOwnerOnly(tlsDir, "rwx------");
                    restrictToOwnerOnly(keyFile, "rw-------");
                    LOG.infov("RDS proxy TLS: reusing existing CA cert: {0} (SANs: {1})",
                            certFile, persistedSans);
                    logTrustHint(certFile);
                    return;
                } catch (Exception e) {
                    LOG.warnv(e, "RDS proxy TLS: failed to load existing CA cert ({0}); regenerating",
                            e.getMessage());
                }
            }
            regenerate(new LinkedHashSet<>(DEFAULT_SANS));
        }
    }

    private void regenerate(Set<String> sans) {
        try {
            Path tlsDir = tlsDir();
            Files.createDirectories(tlsDir);
            restrictToOwnerOnly(tlsDir, "rwx------");
            List<String> sanList = new ArrayList<>(sans);

            KeyPair keyPair = this.currentKeyPair;
            CertificateGenerator.GeneratedCertificate generated = keyPair != null
                    ? certificateGenerator.generateSelfSignedCertificate(
                            "localhost", sanList, KeyAlgorithm.RSA_2048, keyPair)
                    : certificateGenerator.generateSelfSignedCertificate(
                            "localhost", sanList, KeyAlgorithm.RSA_2048);

            Path certFile = tlsDir.resolve(CERT_NAME);
            Path keyFile = tlsDir.resolve(KEY_NAME);
            Files.writeString(certFile, generated.certificatePem());
            Files.writeString(keyFile, generated.privateKeyPem());
            restrictToOwnerOnly(keyFile, "rw-------");
            writeMetadata(tlsDir.resolve(METADATA_NAME), sanList);

            X509Certificate certificate = certificateGenerator.parseCertificate(generated.certificatePem());
            PrivateKey privateKey = certificateGenerator.parsePrivateKey(generated.privateKeyPem());
            this.sslContext = buildSslContext(certificate, privateKey);
            this.currentSans = Set.copyOf(sans);
            this.currentKeyPair = new KeyPair(certificate.getPublicKey(), privateKey);

            LOG.infov("RDS proxy TLS: generated CA cert at {0}", certFile);
            logTrustHint(certFile);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RDS proxy TLS certificate", e);
        }
    }

    private void loadExisting(Path certFile, Path keyFile, List<String> sans) throws Exception {
        X509Certificate certificate = certificateGenerator.parseCertificate(Files.readString(certFile));
        PrivateKey privateKey = certificateGenerator.parsePrivateKey(Files.readString(keyFile));
        this.sslContext = buildSslContext(certificate, privateKey);
        this.currentSans = new LinkedHashSet<>(sans);
        this.currentKeyPair = new KeyPair(certificate.getPublicKey(), privateKey);
    }

    private SSLContext buildSslContext(X509Certificate certificate, PrivateKey privateKey) throws Exception {
        char[] password = new char[0];
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, password);
        keyStore.setKeyEntry("floci-rds-proxy", privateKey, password,
                new java.security.cert.Certificate[]{certificate});

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private Path tlsDir() {
        return Path.of(config.storage().persistentPath(), TLS_DIR);
    }

    /**
     * Restricts {@code path} to owner-only access. This CA's private key is meant to be trusted by
     * a developer's client tooling (e.g. {@code sslmode=verify-full}), so unlike Floci's other
     * generated keys — which only let you impersonate an emulated resource — anyone who can read
     * this one can mint a certificate for any host the developer's trust store now accepts.
     * No-ops on filesystems without POSIX permissions (e.g. Windows) rather than failing.
     */
    private static void restrictToOwnerOnly(Path path, String posixPermissions) {
        try {
            if (Files.getFileStore(path).supportsFileAttributeView(PosixFileAttributeView.class)) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posixPermissions));
            }
        } catch (IOException e) {
            LOG.warnv(e, "RDS proxy TLS: failed to restrict permissions on {0}", path);
        }
    }

    private void logTrustHint(Path certFile) {
        LOG.infov("RDS proxy TLS: for sslmode=verify-full set PGSSLROOTCERT={0}",
                certFile.toAbsolutePath());
    }

    private List<String> readMetadata(Path metadataFile) throws IOException {
        CertificateMetadata metadata = OBJECT_MAPPER.readValue(metadataFile.toFile(), CertificateMetadata.class);
        List<String> hostnames = metadata.getHostnames();
        if (hostnames == null || hostnames.isEmpty()) {
            throw new IOException("RDS proxy TLS metadata file has no hostnames: " + metadataFile);
        }
        return hostnames;
    }

    private void writeMetadata(Path metadataFile, List<String> sans) throws IOException {
        CertificateMetadata metadata = CertificateMetadata.create(sans, resolveFlociVersion());
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metadata);
        Files.writeString(metadataFile, json);
    }

    private static String resolveFlociVersion() {
        String env = System.getenv("FLOCI_VERSION");
        return (env != null && !env.isBlank()) ? env : "dev";
    }
}
