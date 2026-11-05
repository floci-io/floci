package io.github.hectorvent.floci.services.lambdamicrovms;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.resource.ExplorerResource;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void listsTaggedImagesAndConnectorsAsResources() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        LambdaMicrovmsService service = new LambdaMicrovmsService(regionResolver, mock(StorageFactory.class));
        LambdaMicrovmsService.MicrovmImage image = service.createImage(REGION, ACCOUNT_ID, "tagged-image",
                "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1", "arn:aws:iam::000000000000:role/build",
                "s3://artifacts/app.zip", null);
        LambdaMicrovmsService.NetworkConnector connector = service.createConnector(REGION, ACCOUNT_ID, "egress",
                List.of("subnet-00000000000000001"), List.of("sg-00000000000000001"),
                "arn:aws:iam::000000000000:role/operator", "token", List.of("MicroVm"), "IPv4");
        service.tagResource(REGION, image.imageArn, Map.of("team", "sandbox"));
        service.tagResource(REGION, connector.arn, Map.of("team", "net"));

        Map<String, ExplorerResource> byType = new HashMap<>();
        service.getResources().forEach(resource -> byType.put(resource.resourceType(), resource));

        assertEquals(image.imageArn, byType.get("lambda:microvm-image").arn());
        assertEquals(Map.of("team", "sandbox"), byType.get("lambda:microvm-image").tags());
        assertEquals(REGION, byType.get("lambda:microvm-image").region());
        assertEquals(ACCOUNT_ID, byType.get("lambda:microvm-image").owningAccountId());
        assertEquals(connector.arn, byType.get("lambda:network-connector").arn());
        assertEquals(Map.of("team", "net"), byType.get("lambda:network-connector").tags());
        assertEquals(0, service.getSupportedResourceTypes().size());
    }

    @Test
    void aVersionQualifiedConnectorIsNotATagTarget() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn(ACCOUNT_ID);
        LambdaMicrovmsService service = new LambdaMicrovmsService(regionResolver, mock(StorageFactory.class));
        LambdaMicrovmsService.NetworkConnector created = service.createConnector(REGION, ACCOUNT_ID, "tagged",
                List.of("subnet-00000000000000001"), List.of("sg-00000000000000000"),
                "arn:aws:iam::000000000000:role/operator", "token", List.of("WebFunction"), "IPv4");
        String qualified = created.arn + ":1";

        assertEquals(Optional.of("network-connector:" + created.id), LambdaMicrovmsService.taggedResource(created.arn));
        assertEquals(Optional.empty(), LambdaMicrovmsService.taggedResource(qualified));
        AwsException refused = assertThrows(AwsException.class,
                () -> service.tagResource(REGION, qualified, Map.of("team", "a")));
        assertEquals("ValidationException", refused.getErrorCode());
        assertEquals(Map.of(), service.listTags(REGION, created.arn));
    }
}
