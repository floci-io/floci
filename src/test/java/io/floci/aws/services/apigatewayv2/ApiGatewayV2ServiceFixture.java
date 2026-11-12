package io.floci.aws.services.apigatewayv2;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.testing.InMemoryStorageFactory;

import java.util.Optional;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What a unit test needs to build a real {@link ApiGatewayV2Service} over in-memory stores: the
 * emulator config the service reads and the service itself.
 */
public final class ApiGatewayV2ServiceFixture {

    private ApiGatewayV2ServiceFixture() {
    }

    /** An emulator config with no CloudFront domain suffix; every other setting is a deep stub. */
    public static EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cloudfront().domainSuffix()).thenReturn(Optional.empty());
        return config;
    }

    public static ApiGatewayV2Service service(InMemoryStorageFactory storageFactory, String region,
                                              String accountId) {
        return new ApiGatewayV2Service(storageFactory, config(), new RegionResolver(region, accountId));
    }
}
