package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An edge-optimized custom domain exists only in the commercial partition, so it cannot be created
 * or switched to elsewhere. A domain stored before that rule still has to take updates
 * that leave its endpoint type alone.
 */
class ApiGatewayEdgeDomainPartitionTest {

    private static final String GOV_REGION = "us-gov-west-1";

    private final Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
    private ApiGatewayService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(1, String.class),
                        ignored -> AccountAwareStorageBackend.inMemory("000000000000")));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudfront().domainSuffix()).thenReturn("cloudfront.net");
        service = new ApiGatewayService(storageFactory, config, mock(TlsCertificateManager.class));
    }

    @SuppressWarnings("unchecked")
    private AccountAwareStorageBackend<CustomDomain> domainStore() {
        return (AccountAwareStorageBackend<CustomDomain>) stores.get("apigateway-domains.json");
    }

    @Test
    void aStoredEdgeDomainTakesUpdatesThatLeaveItsTypeAlone() {
        CustomDomain legacy = new CustomDomain();
        legacy.setDomainName("legacy.example.com");
        legacy.setEndpointConfigurationType("EDGE");
        legacy.setCertificateArn("arn:aws-us-gov:acm:us-gov-west-1:000000000000:certificate/old");
        domainStore().put(GOV_REGION + "::legacy.example.com", legacy);

        CustomDomain updated = service.updateDomainName(GOV_REGION, "legacy.example.com",
                List.of(Map.of("op", "replace", "path", "/certificateArn",
                        "value", "arn:aws-us-gov:acm:us-gov-west-1:000000000000:certificate/new")));

        assertEquals("arn:aws-us-gov:acm:us-gov-west-1:000000000000:certificate/new", updated.getCertificateArn());
        assertEquals("EDGE", updated.getEndpointConfigurationType());
    }

    @Test
    void switchingADomainToEdgeIsRefusedWhereThePartitionHasNoCloudFront() {
        service.createDomainName(GOV_REGION, Map.of("domainName", "regional.example.com",
                "regionalCertificateArn", "arn:aws-us-gov:acm:us-gov-west-1:000000000000:certificate/r",
                "endpointConfiguration", Map.of("types", List.of("REGIONAL"))));

        AwsException error = assertThrows(AwsException.class, () -> service.updateDomainName(GOV_REGION,
                "regional.example.com", List.of(Map.of("op", "replace",
                        "path", "/endpointConfiguration/types/REGIONAL", "value", "EDGE"))));

        assertEquals("BadRequestException", error.getErrorCode());
    }

    /** China has CloudFront but no edge-optimized API Gateway. */
    @Test
    void anEdgeDomainIsRefusedInChina() {
        AwsException error = assertThrows(AwsException.class, () -> service.createDomainName("cn-north-1",
                Map.of("domainName", "edge.example.cn",
                        "certificateArn", "arn:aws-cn:acm:cn-north-1:000000000000:certificate/e",
                        "endpointConfiguration", Map.of("types", List.of("EDGE")))));

        assertEquals("BadRequestException", error.getErrorCode());
    }
}
