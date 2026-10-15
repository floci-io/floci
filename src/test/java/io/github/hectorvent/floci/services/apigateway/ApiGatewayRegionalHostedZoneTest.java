package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.CustomDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Route 53 hosted zone a regional custom domain reports. The GovCloud user guide states it for
 * its two regions; every other region has no source and keeps the value it always reported.
 */
class ApiGatewayRegionalHostedZoneTest {

    private ApiGatewayService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudfront().domainSuffix()).thenReturn(Optional.empty());
        service = new ApiGatewayService(storageFactory, config, mock(TlsCertificateManager.class),
                new RegionResolver("us-east-1", "000000000000"));
    }

    @ParameterizedTest
    @CsvSource({
            "us-gov-west-1,  Z1K6XKP9SAGWDV",
            "us-gov-east-1,  Z3SE9ATJYCRCZJ",
            "us-east-1,      Z2FDTNDATAQYL2",
            "cn-north-1,     Z2FDTNDATAQYL2",
            "eusc-de-east-1, Z2FDTNDATAQYL2"})
    void regionalDomainReportsTheRegionsPublishedZone(String region, String hostedZoneId) {
        CustomDomain domain = service.createDomainName(region, Map.of(
                "domainName", "api." + region + ".example.com",
                "endpointConfiguration", Map.of("types", List.of("REGIONAL"))));

        assertEquals(hostedZoneId, domain.getRegionalHostedZoneId());
    }
}
