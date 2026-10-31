package io.github.hectorvent.floci.services.lambdamicrovms;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LambdaMicrovmsServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT_ID = "000000000000";

    @Test
    void concurrentConnectorUpdatesEachMakeTheirOwnVersion() throws Exception {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        LambdaMicrovmsService service = new LambdaMicrovmsService(regionResolver, mock(StorageFactory.class));
        LambdaMicrovmsService.NetworkConnector created = service.createConnector(REGION, ACCOUNT_ID, "concurrent",
                List.of("subnet-00000000000000001"), List.of("sg-00000000000000000"),
                "arn:aws:iam::000000000000:role/operator", "token", List.of("WebFunction"), "IPv4");

        int updates = 16;
        ExecutorService executor = Executors.newFixedThreadPool(updates);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<LambdaMicrovmsService.NetworkConnector>> results = new ArrayList<>();
        for (int i = 1; i <= updates; i++) {
            List<String> groups = List.of(String.format("sg-%017d", i));
            results.add(executor.submit(() -> {
                start.await();
                return service.updateConnector(REGION, created.id, null, groups, null, null, null);
            }));
        }
        start.countDown();
        Set<Long> versions = new HashSet<>();
        for (Future<LambdaMicrovmsService.NetworkConnector> result : results) {
            LambdaMicrovmsService.NetworkConnector updated = result.get(10, TimeUnit.SECONDS);
            versions.add(updated.latestVersion);
            LambdaMicrovmsService.ConnectorVersion named =
                    service.getConnectorVersion(REGION, created.id + ":" + updated.latestVersion);
            assertEquals(updated.securityGroupIds, named.version().securityGroupIds);
        }
        executor.shutdown();

        assertEquals(updates, versions.size());
        assertEquals(updates + 1, service.getConnector(REGION, created.id).latestVersion);
        assertEquals(updates + 1, service.listConnectorVersions(REGION, created.id).size());
    }
}
