package io.github.hectorvent.floci.config;

import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateExpiredException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ServerCertificateRenewalTest {
    @TempDir
    Path directory;

    @Test
    void aLeafLastsAYearAndIsDueAMinuteBeforeItEnds() throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        // after the CA's creation: a CA signs nothing before its own validity starts
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now);
        ServerCertificateRenewal renewal = new ServerCertificateRenewal(authority, new CertificateGenerator(), clock);

        ServerCertificateRenewal.Leaf leaf = renewal.issue(List.of("localhost"));

        assertEquals(now.plus(365, ChronoUnit.DAYS), leaf.notAfter());
        assertTrue(renewal.due(null));
        when(clock.instant()).thenReturn(leaf.notAfter().minusSeconds(61));
        assertFalse(renewal.due(leaf));
        when(clock.instant()).thenReturn(leaf.notAfter().minusSeconds(60));
        assertTrue(renewal.due(leaf));
    }

    @Test
    void aLeafEndsWithTheCaAndIsNotDueAgainUntilTheCaEnds() throws Exception {
        CertificateGenerator.GeneratedCertificate ca = new CertificateGenerator().generateCaCertificate(
                "Short-lived CA", Instant.now().plus(1, ChronoUnit.DAYS));
        Files.writeString(directory.resolve(FlociCertificateAuthority.CA_CERT_NAME), ca.certificatePem());
        Files.writeString(directory.resolve(FlociCertificateAuthority.CA_KEY_NAME), ca.privateKeyPem());
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        Instant caNotAfter = authority.certificate().getNotAfter().toInstant();
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.now());
        ServerCertificateRenewal renewal = new ServerCertificateRenewal(authority, new CertificateGenerator(), clock);

        ServerCertificateRenewal.Leaf leaf = renewal.issue(List.of("localhost"));

        assertEquals(caNotAfter, leaf.notAfter());
        when(clock.instant()).thenReturn(caNotAfter.minusSeconds(1));
        assertFalse(renewal.due(leaf), "a new leaf could end no later, so renewing it would only churn");
        when(clock.instant()).thenReturn(caNotAfter);
        assertTrue(renewal.due(leaf));
        when(clock.instant()).thenReturn(caNotAfter.plusSeconds(1));
        assertThrows(CertificateExpiredException.class, () -> renewal.issue(List.of("localhost")));
    }
}
