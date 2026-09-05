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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TlsCertificateManagerTest {

    private static final List<String> CONFIGURED = List.of("localhost", "127.0.0.1", "*.localhost.floci.io", "localhost.floci.io");
    private static final String NEW_HOST = "api.example.localhost.floci.io";

    @TempDir
    Path tempDir;

    private Path tlsDir;
    private FlociCertificateAuthority ca;
    private EmulatorConfig config;
    private TlsConfigurationRegistry registry;
    private TlsConfiguration defaultTls;
    @SuppressWarnings("unchecked")
    private final Event<CertificateUpdatedEvent> events = mock(Event.class);

    @BeforeAll
    static void bouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void serverLeafOnDisk() throws Exception {
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

    private TlsCertificateManager manager() {
        return new TlsCertificateManager(config, ca, registry, events);
    }

    @Test
    void newHostIsAppendedWithTheSameKeyAndReloaded() throws Exception {
        byte[] keyBefore = Files.readAllBytes(tlsDir.resolve("floci-server.key"));
        X509Certificate before = read("floci-server.crt");

        manager().ensureHost(NEW_HOST);

        X509Certificate after = read("floci-server.crt");
        assertTrue(sans(after).contains(NEW_HOST), sans(after).toString());
        assertTrue(sans(after).containsAll(CONFIGURED));
        assertEquals(before.getPublicKey(), after.getPublicKey(), "same key pair");
        assertArrayEquals(keyBefore, Files.readAllBytes(tlsDir.resolve("floci-server.key")), "key file never rewritten");
        after.verify(ca.certificate().getPublicKey());

        CertificateMetadata metadata = readMetadata();
        assertEquals(CONFIGURED, metadata.getHostnames(), "configured list untouched");
        assertEquals(List.of(NEW_HOST), metadata.getLearnedHostnames());

        verify(defaultTls).reload();
        ArgumentCaptor<CertificateUpdatedEvent> event = ArgumentCaptor.forClass(CertificateUpdatedEvent.class);
        verify(events).fire(event.capture());
        assertEquals("<default>", event.getValue().name());
        assertEquals(defaultTls, event.getValue().tlsConfiguration());
        assertFalse(Files.exists(tlsDir.resolve("floci-server.crt.tmp")), "temporary file renamed away");
        assertFalse(Files.exists(tlsDir.resolve("floci-server.metadata.json.tmp")), "temporary file renamed away");
    }

    @Test
    void knownOrWildcardCoveredHostIsANoOp() throws Exception {
        byte[] certBefore = Files.readAllBytes(tlsDir.resolve("floci-server.crt"));

        TlsCertificateManager m = manager();
        m.ensureHost("localhost");
        m.ensureHost("LOCALHOST.floci.io.");
        m.ensureHost("one-label.localhost.floci.io");
        m.ensureHost(" 127.0.0.1 ");

        assertArrayEquals(certBefore, Files.readAllBytes(tlsDir.resolve("floci-server.crt")));
        verify(defaultTls, never()).reload();
        verify(events, never()).fire(any());
    }

    @Test
    void secondCallForTheSameHostDoesNotReissue() throws Exception {
        TlsCertificateManager m = manager();
        m.ensureHost(NEW_HOST);
        byte[] certAfterFirst = Files.readAllBytes(tlsDir.resolve("floci-server.crt"));

        m.ensureHost(NEW_HOST);
        m.ensureHost(NEW_HOST.toUpperCase());

        assertArrayEquals(certAfterFirst, Files.readAllBytes(tlsDir.resolve("floci-server.crt")));
        verify(defaultTls, times(1)).reload();
    }

    @Test
    void hostOutsideTheAllowListIsRefused() throws Exception {
        byte[] certBefore = Files.readAllBytes(tlsDir.resolve("floci-server.crt"));

        TlsCertificateManager m = manager();
        m.ensureHost("accounts.google.com");
        m.ensureHost("evil-localhost.floci.io");
        m.ensureHost("localhost.floci.io.attacker.example");
        m.ensureHost("floci.example");

        assertArrayEquals(certBefore, Files.readAllBytes(tlsDir.resolve("floci-server.crt")));
        verify(defaultTls, never()).reload();
        assertEquals(List.of(), readMetadata().getLearnedHostnames());
    }

    @Test
    void wildcardIsAcceptedOnlyAsAWholeLeftmostLabel() throws Exception {
        TlsCertificateManager m = manager();

        m.ensureHost("*.api.example.localhost.floci.io");
        assertTrue(sans(read("floci-server.crt")).contains("*.api.example.localhost.floci.io"),
                "one leading *. label is a valid SAN");
        verify(defaultTls, times(1)).reload();

        m.ensureHost("*");
        m.ensureHost("*.");
        m.ensureHost("api*.example.localhost.floci.io");
        m.ensureHost("*.*.example.localhost.floci.io");
        m.ensureHost("example.*.localhost.floci.io");
        verify(defaultTls, times(1)).reload();
        Set<String> starred = new TreeSet<>(sans(read("floci-server.crt")));
        starred.removeIf(n -> !n.contains("*"));
        assertEquals(Set.of("*.localhost.floci.io", "*.api.example.localhost.floci.io"), starred,
                "malformed wildcards must not reach the certificate");
    }

    @Test
    void namesThatAreNotHostnamesAreRefused() throws Exception {
        byte[] certBefore = Files.readAllBytes(tlsDir.resolve("floci-server.crt"));

        TlsCertificateManager m = manager();
        m.ensureHost(null);
        m.ensureHost("");
        m.ensureHost("   ");
        m.ensureHost(".");
        m.ensureHost("api.example.localhost.floci.io:443");
        m.ensureHost("https://api.example.localhost.floci.io");
        m.ensureHost("api example.localhost.floci.io");
        m.ensureHost("api_1.example.localhost.floci.io");
        m.ensureHost("api..example.localhost.floci.io");
        m.ensureHost(".api.example.localhost.floci.io");
        m.ensureHost("-api.example.localhost.floci.io");
        m.ensureHost("api-.example.localhost.floci.io");
        m.ensureHost("a".repeat(64) + ".localhost.floci.io");
        m.ensureHost("a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(63) + ".localhost.floci.io");

        assertArrayEquals(certBefore, Files.readAllBytes(tlsDir.resolve("floci-server.crt")));
        verify(defaultTls, never()).reload();
        verify(events, never()).fire(any());
    }

    @Test
    void allowListIncludesConfiguredHostnameBaseUrlAndExtraSuffixes() throws Exception {
        when(config.baseUrl()).thenReturn("https://Floci.Corp.Example:4566");
        TlsCertificateManager m = manager();

        m.ensureHost("api.floci");
        m.ensureHost("floci");
        m.ensureHost("iot.example.internal");
        m.ensureHost("data.localhost.localstack.cloud");
        m.ensureHost("api.floci.corp.example");

        Set<String> sans = sans(read("floci-server.crt"));
        assertTrue(sans.containsAll(List.of("api.floci", "floci", "iot.example.internal",
                "data.localhost.localstack.cloud", "api.floci.corp.example")), sans.toString());
    }

    @Test
    void unusableBaseUrlAndAbsentOptionalConfigStillAllowBuiltinSuffixes() throws Exception {
        when(config.baseUrl()).thenReturn("not a url");
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.dns().extraSuffixes()).thenReturn(Optional.empty());

        manager().ensureHost(NEW_HOST);

        assertTrue(sans(read("floci-server.crt")).contains(NEW_HOST));
    }

    @Test
    void learnedHostsSurviveANewManagerInstance() throws Exception {
        manager().ensureHost(NEW_HOST);

        TlsCertificateManager fresh = manager();
        fresh.ensureHost(NEW_HOST);

        verify(defaultTls, times(1)).reload();
        assertTrue(fresh.knownHostnames().contains(NEW_HOST));
        assertTrue(fresh.knownHostnames().containsAll(CONFIGURED));
    }

    @Test
    void aSecondLearnedHostKeepsTheFirst() throws Exception {
        manager().ensureHost(NEW_HOST);

        manager().ensureHost("auth.example.localhost.floci.io");

        Set<String> sans = sans(read("floci-server.crt"));
        assertTrue(sans.containsAll(List.of(NEW_HOST, "auth.example.localhost.floci.io")), sans.toString());
        assertEquals(List.of(NEW_HOST, "auth.example.localhost.floci.io"), readMetadata().getLearnedHostnames());
    }

    @Test
    void clearDropsLearnedHostsAndKeepsConfiguredOnes() throws Exception {
        TlsCertificateManager m = manager();
        m.ensureHost(NEW_HOST);

        m.clear();

        Set<String> sans = sans(read("floci-server.crt"));
        assertFalse(sans.contains(NEW_HOST));
        assertTrue(sans.containsAll(CONFIGURED));
        assertEquals(List.of(), readMetadata().getLearnedHostnames());
        assertEquals(CONFIGURED, readMetadata().getHostnames());
        assertFalse(m.knownHostnames().contains(NEW_HOST));
        verify(defaultTls, times(2)).reload();
    }

    @Test
    void clearWithNothingLearnedTouchesNothing() throws Exception {
        byte[] certBefore = Files.readAllBytes(tlsDir.resolve("floci-server.crt"));

        manager().clear();

        assertArrayEquals(certBefore, Files.readAllBytes(tlsDir.resolve("floci-server.crt")));
        verify(defaultTls, never()).reload();
    }

    @Test
    void doesNothingWhenTlsIsOff() throws Exception {
        when(config.tls().enabled()).thenReturn(false);
        byte[] certBefore = Files.readAllBytes(tlsDir.resolve("floci-server.crt"));

        TlsCertificateManager m = manager();
        m.ensureHost(NEW_HOST);
        m.clear();

        assertArrayEquals(certBefore, Files.readAllBytes(tlsDir.resolve("floci-server.crt")));
        assertEquals(Set.of(), m.knownHostnames());
        verify(defaultTls, never()).reload();
    }

    @Test
    void doesNothingWithAUserProvidedCertificate() throws Exception {
        when(config.tls().certPath()).thenReturn(Optional.of("/etc/floci/user.crt"));
        byte[] certBefore = Files.readAllBytes(tlsDir.resolve("floci-server.crt"));

        manager().ensureHost(NEW_HOST);

        assertArrayEquals(certBefore, Files.readAllBytes(tlsDir.resolve("floci-server.crt")));
        verify(defaultTls, never()).reload();
    }

    @Test
    void doesNothingWithoutAServerLeafOnDisk() throws Exception {
        Files.delete(tlsDir.resolve("floci-server.crt"));

        TlsCertificateManager m = manager();
        m.ensureHost(NEW_HOST);

        assertFalse(Files.exists(tlsDir.resolve("floci-server.crt")));
        assertEquals(Set.of(), m.knownHostnames());
        verify(defaultTls, never()).reload();
    }

    @Test
    void reloadReturningFalseFiresNoEvent() throws Exception {
        when(defaultTls.reload()).thenReturn(false);

        manager().ensureHost(NEW_HOST);

        assertTrue(sans(read("floci-server.crt")).contains(NEW_HOST), "the file is still written for the next boot");
        verify(events, never()).fire(any());
    }

    @Test
    void noDefaultTlsConfigurationFiresNoEvent() throws Exception {
        when(registry.getDefault()).thenReturn(Optional.empty());

        manager().ensureHost(NEW_HOST);

        assertTrue(sans(read("floci-server.crt")).contains(NEW_HOST));
        verify(events, never()).fire(any());
    }

    @Test
    void aFailedReissueLeavesTheServedFilesIntactAndDoesNotThrow() throws Exception {
        FlociCertificateAuthority broken = spy(ca);
        doThrow(new IllegalStateException("boom")).when(broken).issueServerCertificate(anyString(), anyList(), any(), any());
        byte[] certBefore = Files.readAllBytes(tlsDir.resolve("floci-server.crt"));
        String metadataBefore = Files.readString(tlsDir.resolve("floci-server.metadata.json"));

        TlsCertificateManager m = new TlsCertificateManager(config, broken, registry, events);
        m.ensureHost(NEW_HOST);

        assertArrayEquals(certBefore, Files.readAllBytes(tlsDir.resolve("floci-server.crt")));
        assertEquals(metadataBefore, Files.readString(tlsDir.resolve("floci-server.metadata.json")));
        assertFalse(m.knownHostnames().contains(NEW_HOST), "a name that was never served is not known");
        verify(defaultTls, never()).reload();

        doCallRealMethod().when(broken).issueServerCertificate(anyString(), anyList(), any(), any());
        m.ensureHost("auth.example.localhost.floci.io");
        Set<String> sans = sans(read("floci-server.crt"));
        assertTrue(sans.contains("auth.example.localhost.floci.io"), sans.toString());
        assertFalse(sans.contains(NEW_HOST), "the failed name is not carried into later reissues");
        assertEquals(List.of("auth.example.localhost.floci.io"), readMetadata().getLearnedHostnames());
    }

    @Test
    void concurrentCallsForDistinctHostsAllLandInTheCertificate() throws Exception {
        TlsCertificateManager m = manager();
        List<String> hosts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            hosts.add("svc" + i + ".example.localhost.floci.io");
        }

        runConcurrently(hosts, m);

        Set<String> sans = sans(read("floci-server.crt"));
        assertTrue(sans.containsAll(hosts), sans.toString());
        assertTrue(sans.containsAll(CONFIGURED));
        assertEquals(new TreeSet<>(hosts), new TreeSet<>(readMetadata().getLearnedHostnames()));
        assertTrue(m.knownHostnames().containsAll(hosts));
        verify(defaultTls, times(8)).reload();
    }

    @Test
    void concurrentCallsForTheSameHostReissueOnce() throws Exception {
        TlsCertificateManager m = manager();
        List<String> hosts = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            hosts.add(NEW_HOST);
        }

        runConcurrently(hosts, m);

        assertEquals(List.of(NEW_HOST), readMetadata().getLearnedHostnames());
        verify(defaultTls, times(1)).reload();
    }

    private static void runConcurrently(List<String> hosts, TlsCertificateManager m) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(hosts.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (String host : hosts) {
                futures.add(pool.submit(() -> {
                    start.await();
                    m.ensureHost(host);
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private X509Certificate read(String name) throws Exception {
        return new CertificateGenerator().parseCertificate(Files.readString(tlsDir.resolve(name)));
    }

    private CertificateMetadata readMetadata() throws Exception {
        return new ObjectMapper().readValue(tlsDir.resolve("floci-server.metadata.json").toFile(), CertificateMetadata.class);
    }

    private static Set<String> sans(X509Certificate cert) throws Exception {
        Set<String> out = new TreeSet<>();
        for (List<?> entry : cert.getSubjectAlternativeNames()) {
            out.add(String.valueOf(entry.get(1)));
        }
        return out;
    }
}
