package io.github.hectorvent.floci.services.cognito;

import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * The contract between CreateUserPoolDomain and the TLS certificate manager: a custom domain is
 * handed over once, after it is stored; a prefix domain, a rejected request and every later
 * operation register nothing. The test-only constructor without a manager keeps working.
 */
class CognitoDomainTlsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CERTIFICATE_ARN = "arn:aws:acm:us-east-1:000000000000:certificate/abc";
    private static final Map<String, Object> CUSTOM = Map.of("CertificateArn", CERTIFICATE_ARN);

    private final RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
    private final TlsCertificateManager certificateManager = mock(TlsCertificateManager.class);
    private CognitoService service;
    private String poolId;

    @BeforeEach
    void setUp() {
        service = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", regionResolver, null, null, null, certificateManager);
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

    @Test
    void constructorWithoutAManagerStillCreatesCustomDomains() {
        CognitoService bare = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), "http://localhost:4566", regionResolver, null);
        String pool = bare.createUserPool(Map.of("PoolName", "bare-pool"), REGION).getId();

        UserPoolDomain created = bare.createUserPoolDomain("auth.dev.localhost.floci.io", pool, CUSTOM, null);

        assertTrue(created.isCustomDomain());
        assertNotNull(created.getCloudFrontDistribution());
    }
}
