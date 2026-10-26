package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ElastiCacheTlsCertificatesTest {
    @TempDir
    Path directory;

    @Test
    void concurrentHandshakesRenewAnExpiringLeafOnce() throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        CertificateGenerator generator = spy(new CertificateGenerator());
        CertificateGenerator.GeneratedCertificate expiring = new CertificateGenerator().generateIssuedCertificate(
                "localhost", List.of("localhost"), KeyAlgorithm.RSA_2048, null, authority.issuer(),
                CertificateGenerator.LeafUsage.SERVER, Instant.now().plusSeconds(120));
        doReturn(expiring).doCallRealMethod().when(generator)
                .generateIssuedCertificate(anyString(), anyList(), eq(KeyAlgorithm.RSA_2048), isNull(),
                        any(CertificateGenerator.Issuer.class), eq(CertificateGenerator.LeafUsage.SERVER), any(Instant.class));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(expiring.notAfter().minusSeconds(120));
        ElastiCacheTlsCertificates certificates = new ElastiCacheTlsCertificates(authority, config(), generator, clock);
        SSLContext original = certificates.sslContext();
        when(clock.instant()).thenReturn(expiring.notAfter().minusSeconds(61));
        assertSame(original, certificates.sslContext());

        when(clock.instant()).thenReturn(expiring.notAfter().minusSeconds(60));
        List<CompletableFuture<SSLContext>> contexts = IntStream.range(0, 8)
                .mapToObj(ignored -> CompletableFuture.supplyAsync(certificates::sslContext)).toList();
        SSLContext renewed = contexts.getFirst().get(10, TimeUnit.SECONDS);
        assertNotSame(original, renewed);
        for (CompletableFuture<SSLContext> context : contexts) {
            assertSame(renewed, context.get(10, TimeUnit.SECONDS));
        }
        assertSame(renewed, certificates.sslContext());
        verify(generator, times(2)).generateIssuedCertificate(anyString(), anyList(), eq(KeyAlgorithm.RSA_2048), isNull(),
                any(CertificateGenerator.Issuer.class), eq(CertificateGenerator.LeafUsage.SERVER), any(Instant.class));
    }

    @Test
    void failedIssuancePreservesTheCauseAndCanBeRetried() {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        CertificateGenerator generator = spy(new CertificateGenerator());
        IllegalStateException failure = new IllegalStateException("certificate setup failed");
        doThrow(failure).doCallRealMethod().when(generator)
                .generateIssuedCertificate(anyString(), anyList(), eq(KeyAlgorithm.RSA_2048), isNull(),
                        any(CertificateGenerator.Issuer.class), eq(CertificateGenerator.LeafUsage.SERVER), any(Instant.class));
        ElastiCacheTlsCertificates certificates = new ElastiCacheTlsCertificates(authority, config(), generator);

        assertSame(failure, assertThrows(IllegalStateException.class, certificates::sslContext).getCause());
        assertNotNull(certificates.sslContext());
        verify(generator, times(2)).generateIssuedCertificate(anyString(), anyList(), eq(KeyAlgorithm.RSA_2048), isNull(),
                any(CertificateGenerator.Issuer.class), eq(CertificateGenerator.LeafUsage.SERVER), any(Instant.class));
    }

    @Test
    void blankConfiguredHostnamesAreSkipped() {
        EmulatorConfig config = config();
        when(config.hostname()).thenReturn(Optional.of(" "));
        when(config.baseUrl()).thenReturn(" ");
        when(config.services().elasticache().clusterAnnounceHostname()).thenReturn(Optional.of(" "));
        ElastiCacheTlsCertificates certificates = new ElastiCacheTlsCertificates(
                FlociCertificateAuthority.loadOrCreate(directory), config, new CertificateGenerator());

        assertNotNull(certificates.sslContext());
    }

    private EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.hostname()).thenReturn(Optional.empty());
        when(config.services().elasticache().clusterAnnounceHostname()).thenReturn(Optional.empty());
        return config;
    }
}
