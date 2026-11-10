package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.CustomDomain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
 * The Route 53 hosted zone a regional custom domain reports: the General Reference row for its
 * region, or the invented placeholder in a region the table does not list.
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
            "us-east-1,      Z1UJRXOUMOOFQ8",
            "eu-west-1,      ZLY8HYME6SFDD",
            "us-gov-west-1,  Z1K6XKP9SAGWDV",
            "us-gov-east-1,  Z3SE9ATJYCRCZJ"})
    void regionalDomainReportsTheRegionsPublishedZone(String region, String hostedZoneId) {
        assertEquals(hostedZoneId, createRegionalDomain(region).getRegionalHostedZoneId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ap-southeast-6", "cn-north-1", "eusc-de-east-1", "us-iso-east-1"})
    void regionalDomainReportsThePlaceholderWhereNoZoneIsPublished(String region) {
        assertEquals("Z2FDTNDATAQYL2", createRegionalDomain(region).getRegionalHostedZoneId());
    }

    private CustomDomain createRegionalDomain(String region) {
        return service.createDomainName(region, Map.of(
                "domainName", "api." + region + ".example.com",
                "endpointConfiguration", Map.of("types", List.of("REGIONAL"))));
    }
}
