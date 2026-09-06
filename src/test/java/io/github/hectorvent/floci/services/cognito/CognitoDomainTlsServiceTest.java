package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.acm.model.Certificate;
import io.github.hectorvent.floci.services.acm.model.CertificateStatus;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The contract between CreateUserPoolDomain and the TLS certificate manager: a custom domain is
 * handed over once, after it is stored and with no service lock held; a prefix domain, a rejected
 * request and every later operation register nothing. The test-only constructor without a manager keeps working. A
 * certificate ACM does not know, or has not issued, is refused before the hook, as on AWS.
 */
class CognitoDomainTlsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CERTIFICATE_ARN = "arn:aws:acm:us-east-1:000000000000:certificate/abc";
    private static final Map<String, Object> CUSTOM = Map.of("CertificateArn", CERTIFICATE_ARN);

    private final RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
    private final TlsCertificateManager certificateManager = mock(TlsCertificateManager.class);
    private final AcmService acmService = mock(AcmService.class);
    private CognitoService service;
    private String poolId;

    @BeforeEach
    void setUp() {
        // Every certificate exists and is issued unless a test says otherwise.
        when(acmService.describeCertificate(anyString(), eq(REGION))).thenAnswer(invocation -> {
            Certificate certificate = new Certificate();
            certificate.setArn(invocation.getArgument(0));
            certificate.setStatus(CertificateStatus.ISSUED);
            return certificate;
        });
        service = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", regionResolver, null, acmService, null, null, certificateManager);
        poolId = service.createUserPool(Map.of("PoolName", "tls-pool"), REGION).getId();
    }

    @Test
    void customDomainIsHandedOverOnce() {
        UserPoolDomain created = service.createUserPoolDomain("auth.dev.localhost.floci.io", poolId, CUSTOM, null);

        assertTrue(created.isCustomDomain());
        verify(certificateManager).ensureHost("auth.dev.localhost.floci.io");
        verifyNoMoreInteractions(certificateManager);
    }

    @Test
    void prefixDomainRegistersNothing() {
        service.createUserPoolDomain("my-prefix", poolId, null, null);

        verifyNoInteractions(certificateManager);
    }

    @Test
    void customDomainWithoutACertificateIsRejectedBeforeTheHook() {
        Map<String, Object> noCertificate = new HashMap<>();
        noCertificate.put("CertificateArn", null);

        AwsException failure = assertThrows(AwsException.class,
                () -> service.createUserPoolDomain("auth.dev.localhost.floci.io", poolId, noCertificate, null));

        assertEquals("InvalidParameterException", failure.getErrorCode());
        verifyNoInteractions(certificateManager);
    }

    @Test
    void certificateUnknownToAcmIsRejectedBeforeTheHook() {
        when(acmService.describeCertificate(anyString(), eq(REGION)))
                .thenThrow(new AwsException("ResourceNotFoundException", "no such certificate", 400));

        AwsException failure = assertThrows(AwsException.class,
                () -> service.createUserPoolDomain("auth.dev.localhost.floci.io", poolId, CUSTOM, null));

        assertEquals("InvalidParameterException", failure.getErrorCode());
        verifyNoInteractions(certificateManager);
    }

    @Test
    void certificateNotYetIssuedIsRejectedBeforeTheHook() {
        when(acmService.describeCertificate(anyString(), eq(REGION))).thenAnswer(invocation -> {
            Certificate pending = new Certificate();
            pending.setArn(invocation.getArgument(0));
            pending.setStatus(CertificateStatus.PENDING_VALIDATION);
            return pending;
        });

        assertThrows(AwsException.class,
                () -> service.createUserPoolDomain("auth.dev.localhost.floci.io", poolId, CUSTOM, null));

        verifyNoInteractions(certificateManager);
    }

    @Test
    void unknownPoolIsRejectedBeforeTheHook() {
        assertThrows(AwsException.class,
                () -> service.createUserPoolDomain("auth.dev.localhost.floci.io", "us-east-1_missing", CUSTOM, null));

        verifyNoInteractions(certificateManager);
    }

    @Test
    void duplicateDomainIsRejectedBeforeTheHook() {
        service.createUserPoolDomain("auth.dev.localhost.floci.io", poolId, CUSTOM, null);
        String otherPool = service.createUserPool(Map.of("PoolName", "other-pool"), REGION).getId();

        AwsException failure = assertThrows(AwsException.class,
                () -> service.createUserPoolDomain("auth.dev.localhost.floci.io", otherPool, CUSTOM, null));

        assertEquals("InvalidParameterException", failure.getErrorCode());
        verify(certificateManager).ensureHost("auth.dev.localhost.floci.io");
        verifyNoMoreInteractions(certificateManager);
    }

    @Test
    void domainIsStoredBeforeTheCertificateIsExtended() {
        doAnswer(invocation -> {
            assertNotNull(service.describeUserPoolDomain("auth.dev.localhost.floci.io"),
                    "DescribeUserPoolDomain must already find the domain while the certificate reloads");
            return null;
        }).when(certificateManager).ensureHost(anyString());

        service.createUserPoolDomain("auth.dev.localhost.floci.io", poolId, CUSTOM, null);

        verify(certificateManager).ensureHost("auth.dev.localhost.floci.io");
    }

    @Test
    void laterOperationsOnTheDomainRegisterNothingAgain() {
        service.createUserPoolDomain("auth.dev.localhost.floci.io", poolId, CUSTOM, null);

        service.updateUserPoolDomain("auth.dev.localhost.floci.io", poolId,
                Map.of("CertificateArn", CERTIFICATE_ARN + "-renewed"), 2);
        service.describeUserPoolDomain("auth.dev.localhost.floci.io");
        service.deleteUserPoolDomain("auth.dev.localhost.floci.io", poolId);

        verify(certificateManager).ensureHost("auth.dev.localhost.floci.io");
        verifyNoMoreInteractions(certificateManager);
    }

    /** The reissue blocks on the HTTPS listener; a concurrent create must not queue behind it. */
    @Test
    void noServiceLockIsHeldWhileTheCertificateReloads() throws Exception {
        String otherPool = service.createUserPool(Map.of("PoolName", "other-pool"), REGION).getId();
        AtomicBoolean nested = new AtomicBoolean();
        Thread[] concurrent = new Thread[1];
        doAnswer(invocation -> {
            if (nested.compareAndSet(false, true)) {
                concurrent[0] = new Thread(() -> service.createUserPoolDomain("other.dev.localhost.floci.io", otherPool, CUSTOM, null));
                concurrent[0].start();
                concurrent[0].join(5000);
                assertFalse(concurrent[0].isAlive(), "a create must not wait for another create's TLS reload");
            }
            return null;
        }).when(certificateManager).ensureHost(anyString());

        service.createUserPoolDomain("auth.dev.localhost.floci.io", poolId, CUSTOM, null);

        verify(certificateManager).ensureHost("auth.dev.localhost.floci.io");
        verify(certificateManager).ensureHost("other.dev.localhost.floci.io");
        assertNotNull(service.describeUserPoolDomain("other.dev.localhost.floci.io"));
    }

    @Test
    void constructorWithoutAManagerStillCreatesCustomDomains() {
        CognitoService bare = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), "http://localhost:4566", regionResolver, null, acmService);
        String pool = bare.createUserPool(Map.of("PoolName", "bare-pool"), REGION).getId();

        UserPoolDomain created = bare.createUserPoolDomain("auth.dev.localhost.floci.io", pool, CUSTOM, null);

        assertTrue(created.isCustomDomain());
        assertNotNull(created.getCloudFrontDistribution());
    }
}
