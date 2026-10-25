package io.github.hectorvent.floci.services.apigateway;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.apigateway.model.UsagePlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiGatewayUsagePlanServiceTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final int REPLACEMENTS = 200_000;

    private static ApiGatewayService service() {
        Map<String, AccountAwareStorageBackend<?>> stores = new HashMap<>();
        StorageFactory storageFactory = mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(invocation ->
                stores.computeIfAbsent(invocation.getArgument(1, String.class),
                        ignored -> AccountAwareStorageBackend.inMemory(ACCOUNT)));
        return new ApiGatewayService(storageFactory, mock(EmulatorConfig.class, RETURNS_DEEP_STUBS),
                mock(TlsCertificateManager.class), new RegionResolver(REGION, ACCOUNT));
    }

    // Every patch writes back the stages it read, so without serialising them concurrent adds drop each other.
    @Test
    void concurrentStagePatchesKeepEveryStage() throws Exception {
        ApiGatewayService service = service();
        String planId = service.createUsagePlan(REGION, Map.of("name", "concurrent")).getId();
        Set<String> expected = new HashSet<>();
        for (int i = 0; i < 128; i++) {
            expected.add("api:s" + i);
        }
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            List<Future<?>> writers = new ArrayList<>();
            for (int worker = 0; worker < 8; worker++) {
                int offset = worker * 16;
                writers.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    for (int i = offset; i < offset + 16; i++) {
                        service.updateUsagePlan(REGION, planId,
                                List.of(Map.of("op", "add", "path", "/apiStages", "value", "api:s" + i)));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> writer : writers) {
                writer.get(10, TimeUnit.SECONDS);
            }
        }

        Set<String> stages = new HashSet<>();
        for (UsagePlan.ApiStage stage : service.getUsagePlan(REGION, planId).getApiStages()) {
            stages.add(stage.apiId() + ":" + stage.stage());
        }
        assertEquals(expected, stages);
    }

    // applyLimits writes stages, throttle and quota one after another, so an unserialised read can mix two revisions.
    @Test
    void limitsReadDuringAReplaceAreOneRevision() throws Exception {
        UsagePlan.Limits a = new UsagePlan.Limits(
                List.of(new UsagePlan.ApiStage("api", "v1", Map.of("/pets/GET", new UsagePlan.Throttle(10, 5.0)))),
                new UsagePlan.Throttle(20, 10.0), new UsagePlan.Quota(1000, 0, "MONTH"));
        UsagePlan.Limits b = new UsagePlan.Limits(List.of(new UsagePlan.ApiStage("api", "v2")), null, null);
        UsagePlan plan = new UsagePlan();
        plan.applyLimits(a);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch written = new CountDownLatch(1);
        AtomicLong mixed = new AtomicLong();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> writer = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                try {
                    for (int i = 0; i < REPLACEMENTS; i++) {
                        plan.applyLimits(i % 2 == 0 ? b : a);
                    }
                } finally {
                    written.countDown();
                }
                return null;
            });
            Future<List<UsagePlan.Limits>> reader = executor.submit(() -> {
                List<UsagePlan.Limits> samples = new ArrayList<>();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                while (written.getCount() > 0) {
                    UsagePlan.Limits seen = plan.limits();
                    if (!seen.equals(a) && !seen.equals(b)) {
                        mixed.incrementAndGet();
                        if (samples.size() < 3) {
                            samples.add(seen);
                        }
                    }
                }
                return samples;
            });
            start.countDown();
            writer.get(60, TimeUnit.SECONDS);
            List<UsagePlan.Limits> samples = reader.get(60, TimeUnit.SECONDS);
            assertEquals(0L, mixed.get(), "limits mixing two revisions, for example " + samples);
        }
    }

    // A patch writes back the plan it read, so without the lock a delete between the two is undone.
    @Test
    void aDeleteDuringAPatchStaysDeleted() throws Exception {
        ApiGatewayService service = service();
        List<Map<String, String>> patch = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            patch.add(Map.of("op", "add", "path", "/apiStages", "value", "api:s" + i));
        }
        int resurrected = 0;
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            for (int round = 0; round < 200; round++) {
                String planId = service.createUsagePlan(REGION, Map.of("name", "delete-race")).getId();
                CountDownLatch start = new CountDownLatch(1);
                Future<?> patcher = executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try {
                        service.updateUsagePlan(REGION, planId, patch);
                    } catch (AwsException expected) {
                        // The delete went first, so the patch finds no plan.
                        assertEquals("NotFoundException", expected.getErrorCode());
                    }
                    return null;
                });
                Future<?> deleter = executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    service.deleteUsagePlan(REGION, planId);
                    return null;
                });
                start.countDown();
                patcher.get(10, TimeUnit.SECONDS);
                deleter.get(10, TimeUnit.SECONDS);
                if (service.getUsagePlans(REGION).stream().anyMatch(plan -> plan.getId().equals(planId))) {
                    resurrected++;
                }
            }
        }
        assertEquals(0, resurrected, "plans a patch wrote back after they were deleted");
    }
}
