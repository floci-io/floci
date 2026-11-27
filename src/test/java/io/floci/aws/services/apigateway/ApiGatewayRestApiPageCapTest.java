package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.PaginatedResult;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.RestApi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayRestApiPageCapTest {

    private static final String REGION = "us-east-1";

    private ApiGatewayService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory("000000000000"));
        service = new ApiGatewayService(storageFactory, mock(EmulatorConfig.class),
                mock(TlsCertificateManager.class), new RegionResolver(REGION, "000000000000"));
        for (int i = 0; i < 501; i++) {
            service.createRestApi(REGION, Map.of("name", "capped-" + i));
        }
    }

    @Test
    void aLimitAboveTheCapReturnsFiveHundredAndAUsablePosition() {
        PaginatedResult<RestApi> first = service.getRestApiPage(REGION, 501, null);
        assertEquals(500, first.items().size());
        assertNotNull(first.nextToken());

        PaginatedResult<RestApi> last = service.getRestApiPage(REGION, 501, first.nextToken());
        assertEquals(1, last.items().size());
        assertNull(last.nextToken());
    }

    @Test
    void theLargestIntegerLimitIsCappedTheSameWay() {
        PaginatedResult<RestApi> first = service.getRestApiPage(REGION, Integer.MAX_VALUE, null);
        assertEquals(500, first.items().size());
        assertNotNull(first.nextToken());
    }
}
