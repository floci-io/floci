package io.floci.aws.services.apigateway;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.config.TlsCertificateManager;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.apigateway.model.VpcLink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayVpcLinkTaggingServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";

    private ApiGatewayService service;
    private VpcLink link;

    @BeforeEach
    void setUp() {
        Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(1, String.class),
                        ignored -> AccountAwareStorageBackend.inMemory(ACCOUNT)));
        service = new ApiGatewayService(storageFactory,
                mock(EmulatorConfig.class, RETURNS_DEEP_STUBS), mock(TlsCertificateManager.class),
                new RegionResolver(REGION, ACCOUNT));
        link = service.createVpcLink(REGION, Map.of("name", "tagged-link",
                "targetArns", List.of("target"), "tags", Map.of("owner", "network", "keep", "yes")));
    }

    @Test
    void tagWriteLeavesPreviouslyReturnedMapUnchanged() {
        Map<String, String> snapshot = service.getVpcLink(REGION, link.getId()).getTags();

        service.tagVpcLink(REGION, link.getId(), Map.of("owner", "platform", "env", "test"));

        assertEquals(Map.of("owner", "network", "keep", "yes"), snapshot);
        assertEquals(Map.of("owner", "platform", "keep", "yes", "env", "test"),
                service.getVpcLink(REGION, link.getId()).getTags());
    }

    @Test
    void untagWriteLeavesPreviouslyReturnedMapUnchanged() {
        Map<String, String> snapshot = service.getVpcLink(REGION, link.getId()).getTags();

        service.untagVpcLink(REGION, link.getId(), List.of("owner", "absent"));

        assertEquals(Map.of("owner", "network", "keep", "yes"), snapshot);
        assertEquals(Map.of("keep", "yes"), service.getVpcLink(REGION, link.getId()).getTags());
    }

    @Test
    void concurrentTagAndUntagCallsRetainEveryIndependentChange() throws Exception {
        Map<String, String> initial = new HashMap<>();
        Map<String, String> expected = new HashMap<>(Map.of("owner", "network", "keep", "yes"));
        for (int i = 0; i < 128; i++) {
            initial.put("remove-" + i, "old");
            expected.put("add-" + i, "new");
        }
        service.tagVpcLink(REGION, link.getId(), initial);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> writers = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int offset = worker * 16;
                writers.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int i = offset; i < offset + 16; i++) {
                        service.tagVpcLink(REGION, link.getId(), Map.of("add-" + i, "new"));
                        service.untagVpcLink(REGION, link.getId(), List.of("remove-" + i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(10, TimeUnit.SECONDS);
            }
        }

        assertEquals(expected, service.getVpcLink(REGION, link.getId()).getTags());
    }
}
