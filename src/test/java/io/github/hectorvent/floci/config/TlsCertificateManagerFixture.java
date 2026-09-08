package io.github.hectorvent.floci.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.tls.CertificateUpdatedEvent;
import io.quarkus.tls.TlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import jakarta.enterprise.event.Event;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A server leaf on disk as {@code TlsConfigSource} leaves it, a mocked configuration, TLS registry
 * and event, for the {@link TlsCertificateManager} unit tests.
 */
abstract class TlsCertificateManagerFixture {

    static final List<String> CONFIGURED = List.of("localhost", "127.0.0.1", "*.localhost.floci.io", "localhost.floci.io");
    static final String NEW_HOST = "api.example.localhost.floci.io";

    @TempDir
    Path tempDir;

    Path tlsDir;
    FlociCertificateAuthority ca;
    EmulatorConfig config;
    TlsConfigurationRegistry registry;
    TlsConfiguration defaultTls;
    @SuppressWarnings("unchecked")
    final Event<CertificateUpdatedEvent> events = mock(Event.class);

    @BeforeAll
    static void bouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void serverLeafOnDisk() throws Exception {
        forgetBootstrapTlsDir();
        tlsDir = Files.createDirectories(tempDir.resolve("tls"));
        ca = FlociCertificateAuthority.loadOrCreate(tlsDir);
        var leaf = ca.issueServerCertificate("localhost", CONFIGURED, KeyAlgorithm.RSA_2048, null);
        Files.writeString(tlsDir.resolve("floci-server.crt"), leaf.certificatePem());
        Files.writeString(tlsDir.resolve("floci-server.key"), leaf.privateKeyPem());
        Files.writeString(tlsDir.resolve("floci-server.metadata.json"),
                new ObjectMapper().writeValueAsString(CertificateMetadata.create(CONFIGURED, "dev")));

        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().enabled()).thenReturn(true);
        when(config.tls().certPath()).thenReturn(Optional.empty());
        when(config.storage().persistentPath()).thenReturn(tempDir.toString());
        when(config.hostname()).thenReturn(Optional.of("floci"));
        when(config.baseUrl()).thenReturn("http://localhost:4566");
        when(config.dns().extraSuffixes()).thenReturn(Optional.of(List.of("example.internal")));

        registry = mock(TlsConfigurationRegistry.class);
        defaultTls = mock(TlsConfiguration.class);
        when(registry.getDefault()).thenReturn(Optional.of(defaultTls));
        when(defaultTls.reload()).thenReturn(true);
    }

    TlsCertificateManager manager() {
        return new TlsCertificateManager(config, ca, registry, events);
    }

    /**
     * The manager resolves the TLS directory like every CDI consumer: the one the config
     * bootstrap laid down, else the configured persistent path. Another test class in this JVM
     * may have run a TLS-on bootstrap; a TLS-off bootstrap clears that static, as a real boot does.
     */
    private static void forgetBootstrapTlsDir() {
        System.setProperty("floci.tls.enabled", "false");
        try {
            new TlsConfigSource();
        } finally {
            System.clearProperty("floci.tls.enabled");
        }
        assertNull(TlsConfigSource.resolvedTlsDir());
    }

    X509Certificate read(String name) throws Exception {
        return new CertificateGenerator().parseCertificate(Files.readString(tlsDir.resolve(name)));
    }

    byte[] servedCertificate() throws Exception {
        return Files.readAllBytes(tlsDir.resolve("floci-server.crt"));
    }

    CertificateMetadata readMetadata() throws Exception {
        return new ObjectMapper().readValue(tlsDir.resolve("floci-server.metadata.json").toFile(), CertificateMetadata.class);
    }

    static Set<String> sans(X509Certificate cert) throws Exception {
        Set<String> out = new TreeSet<>();
        for (List<?> entry : cert.getSubjectAlternativeNames()) {
            out.add(String.valueOf(entry.get(1)));
        }
        return out;
    }
}
