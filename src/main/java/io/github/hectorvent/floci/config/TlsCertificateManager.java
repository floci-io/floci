package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.Resettable;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.quarkus.tls.runtime.config.TlsConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Grows the HTTPS server certificate's SAN list at runtime so a custom domain created through
 * API Gateway, IoT or Cognito is served under its exact name in the same call.
 *
 * <p>A reissue keeps the key pair: only the SAN extension and the serial change, and the same
 * local CA signs it, so anyone who trusts {@code floci-root-ca.crt} keeps validating. The key
 * file is never rewritten. Certificate and metadata are replaced by atomic renames, then the
 * default entry of the Quarkus TLS registry is reloaded and {@link CertificateUpdatedEvent} is
 * fired, which is what {@code HttpCertificateUpdateEventListener} waits for before calling
 * {@code HttpServer.updateSSLOptions}: the next handshake sees the new certificate.
 *
 * <p>Only names under a local suffix are accepted. Floci verifies no request signature, so a
 * certificate covering an arbitrary public name would let any local process impersonate that
 * name to every client trusting the Floci CA.
 */
@ApplicationScoped
public class TlsCertificateManager implements Resettable {

    private static final Logger LOG = Logger.getLogger(TlsCertificateManager.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final List<String> BUILTIN_SUFFIXES = List.of("localhost", "localhost.floci.io", "localhost.localstack.cloud");
    private static final String LABEL = "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?";
    /** RFC 1123 labels, optionally one leading wildcard label (RFC 6125 section 6.4.3). */
    private static final Pattern HOSTNAME = Pattern.compile("(?:\\*\\.)?" + LABEL + "(?:\\." + LABEL + ")*");
    private static final int MAX_HOSTNAME_LENGTH = 253;

    static final String CERT_NAME = "floci-server.crt";
    static final String KEY_NAME = "floci-server.key";
    static final String METADATA_NAME = "floci-server.metadata.json";

    private final EmulatorConfig config;
    private final FlociCertificateAuthority ca;
    private final TlsConfigurationRegistry registry;
    private final Event<CertificateUpdatedEvent> certificateUpdated;
    private final CertificateGenerator generator = new CertificateGenerator();
    private final Object lock = new Object();

    private Set<String> knownHostnames = Set.of();
    private List<String> configuredHostnames = List.of();
    private Set<String> learnedHostnames = new LinkedHashSet<>();
    private KeyPair keyPair;
    private KeyAlgorithm keyAlgorithm;
    private boolean loaded;
    /** True between a successful file write and a successful listener swap. */
    private boolean listenerBehindFiles;

    @Inject
    public TlsCertificateManager(EmulatorConfig config, FlociCertificateAuthority ca,
                                 TlsConfigurationRegistry registry, Event<CertificateUpdatedEvent> certificateUpdated) {
        this.config = config;
        this.ca = ca;
        this.registry = registry;
        this.certificateUpdated = certificateUpdated;
    }

    /**
     * Makes sure {@code host} is covered by the server certificate. Idempotent, wildcard-aware,
     * and never throws: a failure is logged and the caller's AWS operation still succeeds.
     *
     * <p>Blocking: the Quarkus listener waits for every HTTP server to swap its SSL options, so
     * call this from a worker thread (a JAX-RS handler), never from a Vert.x event loop.
     */
    public void ensureHost(String host) {
        String name = normalize(host);
        if (name == null || !managesServerCertificate()) {
            return;
        }
        synchronized (lock) {
            if (!loadOnce() || covers(name)) {
                return;
            }
            if (!isAllowed(name)) {
                LOG.warnv("TLS: refusing to add {0} to the server certificate: not under a local suffix "
                        + "(localhost, localhost.floci.io, localhost.localstack.cloud, floci.hostname, "
                        + "the floci.base-url host, floci.dns.extra-suffixes)", name);
                return;
            }
            Set<String> learned = new LinkedHashSet<>(learnedHostnames);
            learned.add(name);
            try {
                reissue(learned);
                LOG.infov("TLS: server certificate now covers {0}", name);
            } catch (Exception e) {
                LOG.warnv(e, "TLS: could not extend the server certificate with {0}: {1}", name, e.getMessage());
            }
        }
    }

    /** Every SAN on the served leaf, configured and learned, lower-cased. Empty when not managed. */
    public Set<String> knownHostnames() {
        if (!managesServerCertificate()) {
            return Set.of();
        }
        synchronized (lock) {
            loadOnce();
            return knownHostnames;
        }
    }

    /** Reset: forget the learned names, keep the configured ones. */
    @Override
    public void clear() {
        if (!managesServerCertificate()) {
            return;
        }
        synchronized (lock) {
            if (!loadOnce() || (learnedHostnames.isEmpty() && !listenerBehindFiles)) {
                return;
            }
            try {
                reissue(new LinkedHashSet<>());
                LOG.info("TLS: server certificate reset to the configured hostnames");
            } catch (Exception e) {
                LOG.warnv(e, "TLS: could not reset the server certificate: {0}", e.getMessage());
            }
        }
    }

    /** False with TLS off or a user-provided certificate, whose SAN list is not ours to change. */
    private boolean managesServerCertificate() {
        return config.tls().enabled() && config.tls().certPath().filter(p -> !p.isBlank()).isEmpty();
    }

    private boolean loadOnce() {
        if (loaded) {
            return true;
        }
        Path dir = tlsDir();
        Path certFile = dir.resolve(CERT_NAME);
        Path keyFile = dir.resolve(KEY_NAME);
        if (!Files.exists(certFile) || !Files.exists(keyFile)) {
            LOG.debugv("TLS: no server certificate under {0}; nothing to extend", dir);
            return false;
        }
        try {
            X509Certificate cert = generator.parseCertificate(Files.readString(certFile));
            keyPair = new KeyPair(cert.getPublicKey(), generator.parsePrivateKey(Files.readString(keyFile)));
            keyAlgorithm = generator.detectKeyAlgorithm(cert.getPublicKey());
            Set<String> sans = sansOf(cert);

            Path metadataFile = dir.resolve(METADATA_NAME);
            CertificateMetadata metadata = Files.exists(metadataFile)
                    ? OBJECT_MAPPER.readValue(Files.readString(metadataFile), CertificateMetadata.class)
                    : null;
            configuredHostnames = metadata == null || metadata.getHostnames() == null
                    ? List.copyOf(sans)
                    : List.copyOf(metadata.getHostnames());
            learnedHostnames = new LinkedHashSet<>();
            if (metadata != null) {
                metadata.getLearnedHostnames().forEach(n -> learnedHostnames.add(n.toLowerCase(Locale.ROOT)));
            }
            // A SAN the metadata does not list was learned by a reissue whose metadata write did
            // not complete; keep serving it rather than dropping it on the next reissue.
            Set<String> configuredLower = new LinkedHashSet<>();
            configuredHostnames.forEach(n -> configuredLower.add(n.toLowerCase(Locale.ROOT)));
            sans.stream().filter(n -> !configuredLower.contains(n)).forEach(learnedHostnames::add);

            knownHostnames = Set.copyOf(sans);
            loaded = true;
            return true;
        } catch (Exception e) {
            LOG.warnv(e, "TLS: could not read the server certificate under {0}: {1}", dir, e.getMessage());
            return false;
        }
    }

    private void reissue(Set<String> learned) throws Exception {
        List<String> sans = new ArrayList<>(new LinkedHashSet<>(configuredHostnames));
        for (String name : learned) {
            if (!sans.contains(name)) {
                sans.add(name);
            }
        }
        CertificateGenerator.GeneratedCertificate issued = ca.issueServerCertificate("localhost", sans, keyAlgorithm, keyPair);

        Path dir = tlsDir();
        CertificateMetadata metadata = CertificateMetadata.create(configuredHostnames, resolveFlociVersion());
        metadata.setLearnedHostnames(new ArrayList<>(learned));
        writeAtomically(dir.resolve(CERT_NAME), issued.certificatePem());
        writeAtomically(dir.resolve(METADATA_NAME), OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(metadata));
        // The learned list follows the files, so a name whose reload fails below is still carried
        // by every later reissue; the known set follows the listener, so that name is retried by
        // its next ensureHost call instead of being reported as served.
        learnedHostnames = new LinkedHashSet<>(learned);
        listenerBehindFiles = true;
        reloadServer();
        listenerBehindFiles = false;
        knownHostnames = sansOf(generator.parseCertificate(issued.certificatePem()));
    }

    /** Re-reads the files into the registry and swaps the listener; throws when either did not happen. */
    private void reloadServer() {
        TlsConfiguration cfg = registry.getDefault().orElseThrow(() -> new IllegalStateException(
                "no default TLS configuration is registered; the new server certificate applies on the next restart"));
        if (!cfg.reload()) {
            throw new IllegalStateException(
                    "the TLS registry could not reload the server certificate; the next call retries");
        }
        certificateUpdated.fire(new CertificateUpdatedEvent(TlsConfig.DEFAULT_NAME, cfg));
    }

    private boolean covers(String name) {
        if (knownHostnames.contains(name)) {
            return true;
        }
        int dot = name.indexOf('.');
        return dot > 0 && knownHostnames.contains("*" + name.substring(dot));
    }

    private boolean isAllowed(String name) {
        List<String> suffixes = new ArrayList<>(BUILTIN_SUFFIXES);
        config.hostname().filter(h -> !h.isBlank()).ifPresent(h -> suffixes.add(h.toLowerCase(Locale.ROOT)));
        try {
            String baseHost = URI.create(config.baseUrl()).getHost();
            if (baseHost != null) {
                suffixes.add(baseHost.toLowerCase(Locale.ROOT));
            }
        } catch (RuntimeException e) {
            LOG.debugv("TLS: floci.base-url {0} has no usable host: {1}", config.baseUrl(), e.getMessage());
        }
        config.dns().extraSuffixes().ifPresent(list -> list.forEach(s -> suffixes.add(s.toLowerCase(Locale.ROOT))));
        String bare = name.startsWith("*.") ? name.substring(2) : name;
        for (String suffix : suffixes) {
            if (bare.equals(suffix) || bare.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lower-cased, trailing dot dropped, and checked to be a host name: RFC 1123 labels and at
     * most one leading wildcard label, which is how API Gateway spells a wildcard custom domain
     * and the only wildcard form X.509 name matching honours. Anything else yields null.
     */
    private static String normalize(String host) {
        if (host == null) {
            return null;
        }
        String name = host.strip().toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) {
            name = name.substring(0, name.length() - 1);
        }
        if (name.length() > MAX_HOSTNAME_LENGTH || !HOSTNAME.matcher(name).matches()) {
            return null;
        }
        return name;
    }

    private static Set<String> sansOf(X509Certificate cert) throws CertificateParsingException {
        Set<String> sans = new LinkedHashSet<>();
        if (cert.getSubjectAlternativeNames() != null) {
            for (List<?> entry : cert.getSubjectAlternativeNames()) {
                sans.add(String.valueOf(entry.get(1)).toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(sans);
    }

    private Path tlsDir() {
        return FlociCertificateAuthorityProducer.tlsDir(config);
    }

    private static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static String resolveFlociVersion() {
        String env = System.getenv("FLOCI_VERSION");
        return env == null || env.isBlank() ? "dev" : env;
    }
}
