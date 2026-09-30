package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayResourceProviderTest {

    private static final String REGION = "us-east-1";
    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String CALLING_ACCOUNT = "111111111111";

    @Test
    void resourcesReportTheCallingAccountAsOwner() {
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any()))
                .thenAnswer(invocation -> AccountAwareStorageBackend.inMemory(DEFAULT_ACCOUNT));
        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.defaultAccountId()).thenReturn(DEFAULT_ACCOUNT);
        ApiGatewayService service = new ApiGatewayService(storageFactory, config,
                mock(TlsCertificateManager.class), new RegionResolver(REGION, CALLING_ACCOUNT));
        service.createRestApi(REGION, Map.of("name", "owned", "tags", Map.of("k", "v")));

        List<ExplorerResource> resources = service.getResources();

        assertEquals(1, resources.size());
        assertEquals(CALLING_ACCOUNT, resources.getFirst().owningAccountId());
    }
}
