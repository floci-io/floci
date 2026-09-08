package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The contract between CreateDomainConfiguration and the TLS certificate manager: a
 * customer-managed domain name is handed over once, after the configuration is stored and with
 * no service lock held; an ENDPOINT configuration, the AWS-managed configurations, a rejected
 * request and every later operation register nothing.
 */
class IotDomainConfigurationTlsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-1111-1111-1111-111111111111";

    private final ObjectMapper mapper = new ObjectMapper();
    private final TlsCertificateManager certificateManager = mock(TlsCertificateManager.class);
    private final IotDomainConfigurationService service = new IotDomainConfigurationService(
            new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"), endpointConfig(), certificateManager);

    private static EmulatorConfig endpointConfig() {
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.iotEndpointAddress()).thenReturn("localhost:4566");
        return config;
    }

    private ObjectNode customDomain(String domainName) {
        ObjectNode request = mapper.createObjectNode().put("domainName", domainName);
        request.putArray("serverCertificateArns").add(CERTIFICATE_ARN);
        return request;
    }

    @Test
    void customerManagedDomainIsHandedOverOnce() {
        service.createDomainConfiguration("iot", customDomain("iot.dev.localhost.floci.io"), REGION);

        verify(certificateManager).ensureHost("iot.dev.localhost.floci.io");
        verifyNoMoreInteractions(certificateManager);
    }

    @Test
    void endpointConfigurationRegistersNothing() {
        service.createDomainConfiguration("endpoint", mapper.createObjectNode(), REGION);
        service.createDomainConfiguration("null-body", null, REGION);

        verifyNoInteractions(certificateManager);
    }

    @Test
    void awsManagedConfigurationsRegisterNothing() {
        assertEquals("AWS_MANAGED", service.describeDomainConfiguration("iot:Data-ATS", REGION).getDomainType());

        verifyNoInteractions(certificateManager);
    }

    @Test
    void domainWithoutACertificateIsRejectedBeforeTheHook() {
        AwsException failure = assertThrows(AwsException.class, () -> service.createDomainConfiguration(
                "no-cert", mapper.createObjectNode().put("domainName", "iot.dev.localhost.floci.io"), REGION));

        assertEquals("InvalidRequestException", failure.getErrorCode());
        verifyNoInteractions(certificateManager);
    }

    @Test
    void duplicateNameIsRejectedBeforeTheHook() {
        service.createDomainConfiguration("iot", customDomain("iot.dev.localhost.floci.io"), REGION);

        AwsException failure = assertThrows(AwsException.class,
                () -> service.createDomainConfiguration("iot", customDomain("again.dev.localhost.floci.io"), REGION));

        assertEquals("ResourceAlreadyExistsException", failure.getErrorCode());
        verify(certificateManager).ensureHost("iot.dev.localhost.floci.io");
        verifyNoMoreInteractions(certificateManager);
    }

    @Test
    void configurationIsStoredBeforeTheCertificateIsExtended() {
        doAnswer(invocation -> {
            assertNotNull(service.describeDomainConfiguration("iot", REGION),
                    "DescribeDomainConfiguration must already find the configuration while the certificate reloads");
            return null;
        }).when(certificateManager).ensureHost(anyString());

        service.createDomainConfiguration("iot", customDomain("iot.dev.localhost.floci.io"), REGION);

        verify(certificateManager).ensureHost("iot.dev.localhost.floci.io");
    }

    @Test
    void laterOperationsOnTheConfigurationRegisterNothingAgain() {
        service.createDomainConfiguration("iot", customDomain("iot.dev.localhost.floci.io"), REGION);

        service.updateDomainConfiguration("iot",
                mapper.createObjectNode().put("domainConfigurationStatus", "DISABLED"), REGION);
        service.describeDomainConfiguration("iot", REGION);
        service.deleteDomainConfiguration("iot", REGION);

        verify(certificateManager).ensureHost("iot.dev.localhost.floci.io");
        verifyNoMoreInteractions(certificateManager);
    }

    /** The reissue blocks on the HTTPS listener; a concurrent create must not queue behind it. */
    @Test
    void noServiceLockIsHeldWhileTheCertificateReloads() throws Exception {
        AtomicBoolean nested = new AtomicBoolean();
        Thread[] concurrent = new Thread[1];
        doAnswer(invocation -> {
            if (nested.compareAndSet(false, true)) {
                concurrent[0] = new Thread(() -> service.createDomainConfiguration(
                        "other", customDomain("other.dev.localhost.floci.io"), REGION));
                concurrent[0].start();
                concurrent[0].join(5000);
                assertFalse(concurrent[0].isAlive(), "a create must not wait for another create's TLS reload");
            }
            return null;
        }).when(certificateManager).ensureHost(anyString());

        service.createDomainConfiguration("iot", customDomain("iot.dev.localhost.floci.io"), REGION);

        verify(certificateManager).ensureHost("iot.dev.localhost.floci.io");
        verify(certificateManager).ensureHost("other.dev.localhost.floci.io");
        assertNotNull(service.describeDomainConfiguration("other", REGION));
    }
}
