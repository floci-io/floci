package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The contract between CreateDomainName and the TLS certificate manager: the exact domain name
 * is handed over once, after the domain is stored and with no service lock held, and never for a
 * request AWS would reject. Later operations on the domain do not register anything again.
 */
class ApiGatewayDomainTlsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CERTIFICATE_ARN = "arn:aws:acm:us-east-1:000000000000:certificate/abc";

    private TlsCertificateManager certificateManager;
    private ApiGatewayService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        certificateManager = mock(TlsCertificateManager.class);
        service = new ApiGatewayService(storageFactory, mock(EmulatorConfig.class), certificateManager);
    }

    private static Map<String, Object> regional(String domain) {
        return Map.of("domainName", domain, "regionalCertificateArn", CERTIFICATE_ARN,
                "endpointConfiguration", Map.of("types", List.of("REGIONAL")));
    }

    @Test
    void createHandsTheExactDomainNameOverOnce() {
        service.createDomainName(REGION, regional("api.dev.localhost.floci.io"));

        verify(certificateManager).ensureHost("api.dev.localhost.floci.io");
        verifyNoMoreInteractions(certificateManager);
    }

    @Test
    void wildcardDomainIsHandedOverAsApiGatewaySpellsIt() {
        service.createDomainName(REGION, regional("*.dev.localhost.floci.io"));

        verify(certificateManager).ensureHost("*.dev.localhost.floci.io");
    }

    @Test
    void edgeDomainIsHandedOverToo() {
        service.createDomainName(REGION, Map.of("domainName", "edge.dev.localhost.floci.io",
                "certificateArn", CERTIFICATE_ARN, "endpointConfiguration", Map.of("types", List.of("EDGE"))));

        verify(certificateManager).ensureHost("edge.dev.localhost.floci.io");
    }

    @Test
    void domainIsStoredBeforeTheCertificateIsExtended() {
        doAnswer(invocation -> {
            assertNotNull(service.getDomainName(REGION, "api.dev.localhost.floci.io"),
                    "GetDomainName must already find the domain while the certificate reloads");
            return null;
        }).when(certificateManager).ensureHost(anyString());

        service.createDomainName(REGION, regional("api.dev.localhost.floci.io"));

        verify(certificateManager).ensureHost("api.dev.localhost.floci.io");
    }

    @Test
    void duplicateNameIsRejectedBeforeTheHook() {
        service.createDomainName(REGION, regional("api.dev.localhost.floci.io"));

        AwsException failure = assertThrows(AwsException.class,
                () -> service.createDomainName("eu-west-1", regional("api.dev.localhost.floci.io")));

        assertEquals("BadRequestException", failure.getErrorCode());
        verify(certificateManager).ensureHost("api.dev.localhost.floci.io");
        verifyNoMoreInteractions(certificateManager);
    }

    @Test
    void privateEndpointTypeIsRejectedBeforeTheHook() {
        assertThrows(AwsException.class, () -> service.createDomainName(REGION, Map.of(
                "domainName", "private.dev.localhost.floci.io",
                "endpointConfiguration", Map.of("types", List.of("PRIVATE")))));

        verifyNoInteractions(certificateManager);
    }

    @Test
    void laterOperationsOnTheDomainRegisterNothingAgain() {
        service.createDomainName(REGION, regional("api.dev.localhost.floci.io"));

        service.updateDomainName(REGION, "api.dev.localhost.floci.io",
                List.of(Map.of("op", "replace", "path", "/securityPolicy", "value", "TLS_1_0")));
        service.getDomainName(REGION, "api.dev.localhost.floci.io");
        service.deleteDomainName(REGION, "api.dev.localhost.floci.io");

        verify(certificateManager).ensureHost("api.dev.localhost.floci.io");
        verifyNoMoreInteractions(certificateManager);
    }

    /**
     * The reissue blocks until the HTTPS listener has switched certificates. A second create that
     * arrives meanwhile must not queue behind it, so the hook runs outside the uniqueness lock.
     */
    @Test
    void noServiceLockIsHeldWhileTheCertificateReloads() throws Exception {
        AtomicBoolean nested = new AtomicBoolean();
        Thread[] concurrent = new Thread[1];
        doAnswer(invocation -> {
            if (nested.compareAndSet(false, true)) {
                concurrent[0] = new Thread(() -> service.createDomainName(REGION, regional("other.dev.localhost.floci.io")));
                concurrent[0].start();
                concurrent[0].join(5000);
                assertFalse(concurrent[0].isAlive(), "a create must not wait for another create's TLS reload");
            }
            return null;
        }).when(certificateManager).ensureHost(anyString());

        service.createDomainName(REGION, regional("api.dev.localhost.floci.io"));

        verify(certificateManager).ensureHost("api.dev.localhost.floci.io");
        verify(certificateManager).ensureHost("other.dev.localhost.floci.io");
        assertNotNull(service.getDomainName(REGION, "other.dev.localhost.floci.io"));
    }
}
