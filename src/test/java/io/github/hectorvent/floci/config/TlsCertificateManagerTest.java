package io.github.hectorvent.floci.config;

import io.quarkus.tls.CertificateUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reissue, reload, persistence, reset, failure handling and concurrency. Name rules are next door. */
class TlsCertificateManagerTest extends TlsCertificateManagerFixture {

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
    void reissuedLeafKeepsTheShapeOfTheBootLeaf() throws Exception {
        X509Certificate before = read("floci-server.crt");

        manager().ensureHost(NEW_HOST);

        X509Certificate after = read("floci-server.crt");
        assertEquals(before.getSubjectX500Principal(), after.getSubjectX500Principal(), "subject unchanged");
        assertEquals(ca.certificate().getSubjectX500Principal(), after.getIssuerX500Principal(), "issued by the local CA");
        assertTrue(ca.isIssuedByUs(after));
        assertNotEquals(before.getSerialNumber(), after.getSerialNumber(), "a reissue is a new certificate");
        assertEquals(-1, after.getBasicConstraints(), "a server leaf, not a CA");
        assertEquals(List.of("1.3.6.1.5.5.7.3.1", "1.3.6.1.5.5.7.3.2"), after.getExtendedKeyUsage(),
                "serverAuth and clientAuth, as an ACM-issued certificate carries");
        after.checkValidity();
        assertTrue(after.getNotAfter().toInstant().isBefore(Instant.now().plus(366, ChronoUnit.DAYS)),
                "validity is not extended beyond a normal leaf");
        assertTrue(after.getNotAfter().toInstant().isAfter(Instant.now().plus(364, ChronoUnit.DAYS)));

        List<String> ordered = new ArrayList<>();
        for (List<?> entry : after.getSubjectAlternativeNames()) {
            ordered.add(String.valueOf(entry.get(1)));
        }
        assertEquals(CONFIGURED, ordered.subList(0, CONFIGURED.size()), "configured names first, in their order");
        assertEquals(NEW_HOST, ordered.get(ordered.size() - 1), "learned names appended");
    }

    @Test
    void secondCallForTheSameHostDoesNotReissue() throws Exception {
        TlsCertificateManager m = manager();
        m.ensureHost(NEW_HOST);
        byte[] certAfterFirst = servedCertificate();

        m.ensureHost(NEW_HOST);
        m.ensureHost(NEW_HOST.toUpperCase());

        assertArrayEquals(certAfterFirst, servedCertificate());
        verify(defaultTls, times(1)).reload();
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
        byte[] certBefore = servedCertificate();

        manager().clear();

        assertArrayEquals(certBefore, servedCertificate());
        verify(defaultTls, never()).reload();
    }

    @Test
    void doesNothingWhenTlsIsOff() throws Exception {
        when(config.tls().enabled()).thenReturn(false);
        byte[] certBefore = servedCertificate();

        TlsCertificateManager m = manager();
        m.ensureHost(NEW_HOST);
        m.clear();

        assertArrayEquals(certBefore, servedCertificate());
        assertEquals(Set.of(), m.knownHostnames());
        verify(defaultTls, never()).reload();
    }

    @Test
    void doesNothingWithAUserProvidedCertificate() throws Exception {
        when(config.tls().certPath()).thenReturn(Optional.of("/etc/floci/user.crt"));
        byte[] certBefore = servedCertificate();

        manager().ensureHost(NEW_HOST);

        assertArrayEquals(certBefore, servedCertificate());
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
    void aFailedReloadIsRetriedByTheNextCall() throws Exception {
        when(defaultTls.reload()).thenReturn(false);
        TlsCertificateManager m = manager();

        m.ensureHost(NEW_HOST);

        assertTrue(sans(read("floci-server.crt")).contains(NEW_HOST), "the file is still written for the next boot");
        verify(events, never()).fire(any());
        assertFalse(m.knownHostnames().contains(NEW_HOST), "a name the listener does not serve is not known");

        when(defaultTls.reload()).thenReturn(true);
        m.ensureHost(NEW_HOST);

        verify(defaultTls, times(2)).reload();
        verify(events, times(1)).fire(any());
        assertTrue(m.knownHostnames().contains(NEW_HOST));
        assertEquals(List.of(NEW_HOST), readMetadata().getLearnedHostnames());
    }

    @Test
    void aNameWhoseReloadFailedIsCarriedIntoTheNextReissue() throws Exception {
        when(defaultTls.reload()).thenReturn(false);
        TlsCertificateManager m = manager();
        m.ensureHost(NEW_HOST);
        assertFalse(m.knownHostnames().contains(NEW_HOST));

        when(defaultTls.reload()).thenReturn(true);
        m.ensureHost("auth.example.localhost.floci.io");

        Set<String> sans = sans(read("floci-server.crt"));
        assertTrue(sans.contains(NEW_HOST), "the name written to disk is not dropped by the next reissue: " + sans);
        assertTrue(sans.contains("auth.example.localhost.floci.io"), sans.toString());
        assertTrue(m.knownHostnames().containsAll(List.of(NEW_HOST, "auth.example.localhost.floci.io")));
        assertEquals(List.of(NEW_HOST, "auth.example.localhost.floci.io"), readMetadata().getLearnedHostnames());
        verify(defaultTls, times(2)).reload();
        verify(events, times(1)).fire(any());
    }

    @Test
    void aResetWhoseReloadFailedIsRetriedByTheNextReset() throws Exception {
        TlsCertificateManager m = manager();
        m.ensureHost(NEW_HOST);

        when(defaultTls.reload()).thenReturn(false);
        m.clear();
        assertEquals(List.of(), readMetadata().getLearnedHostnames(), "the files are reset");
        assertTrue(m.knownHostnames().contains(NEW_HOST), "the listener still serves the name");

        when(defaultTls.reload()).thenReturn(true);
        m.clear();

        verify(defaultTls, times(3)).reload();
        assertFalse(m.knownHostnames().contains(NEW_HOST));
        assertFalse(sans(read("floci-server.crt")).contains(NEW_HOST));
    }

    @Test
    void aMissingDefaultTlsConfigurationIsRetriedByTheNextCall() throws Exception {
        when(registry.getDefault()).thenReturn(Optional.empty());
        TlsCertificateManager m = manager();

        m.ensureHost(NEW_HOST);

        verify(events, never()).fire(any());
        assertFalse(m.knownHostnames().contains(NEW_HOST));

        when(registry.getDefault()).thenReturn(Optional.of(defaultTls));
        m.ensureHost(NEW_HOST);

        verify(defaultTls, times(1)).reload();
        verify(events, times(1)).fire(any());
        assertTrue(m.knownHostnames().contains(NEW_HOST));
    }

    @Test
    void aFailingListenerIsRetriedByTheNextCall() throws Exception {
        doThrow(new IllegalStateException("observer failed")).when(events).fire(any());
        TlsCertificateManager m = manager();

        m.ensureHost(NEW_HOST);
        assertFalse(m.knownHostnames().contains(NEW_HOST));

        doNothing().when(events).fire(any());
        m.ensureHost(NEW_HOST);

        verify(defaultTls, times(2)).reload();
        assertTrue(m.knownHostnames().contains(NEW_HOST));
    }

    @Test
    void aFailedReissueLeavesTheServedFilesIntactAndDoesNotThrow() throws Exception {
        FlociCertificateAuthority broken = spy(ca);
        doThrow(new IllegalStateException("boom")).when(broken).issueServerCertificate(anyString(), anyList(), any(), any());
        byte[] certBefore = servedCertificate();
        String metadataBefore = Files.readString(tlsDir.resolve("floci-server.metadata.json"));

        TlsCertificateManager m = new TlsCertificateManager(config, broken, registry, events);
        m.ensureHost(NEW_HOST);

        assertArrayEquals(certBefore, servedCertificate());
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
}
