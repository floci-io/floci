package io.github.hectorvent.floci.services.elasticache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.elasticache.model.CacheParameterGroup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write paths of a cache parameter group: only one create of a name can win, a modify cannot
 * restore a group deleted alongside it, and a stored group survives being written and read back.
 */
@QuarkusTest
class CacheParameterGroupConcurrencyTest {

    @Inject
    ElastiCacheService service;

    @Test
    void onlyOneConcurrentCreateOfANameSucceeds() {
        // Without a lock held across the existence check and the write, both creates see no group
        // and both succeed, so a duplicate never reports one.
        String name = "concurrent-create-pg";
        var pool = Executors.newFixedThreadPool(8);
        var start = new CountDownLatch(1);
        var created = new AtomicInteger();
        var rejected = new AtomicInteger();
        try {
            List<Future<Object>> attempts = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> pool.submit(() -> {
                        start.await();
                        try {
                            service.createCacheParameterGroup(name, "redis7", "concurrent", Map.of());
                            created.incrementAndGet();
                        } catch (AwsException e) {
                            assertEquals("CacheParameterGroupAlreadyExists", e.getErrorCode());
                            rejected.incrementAndGet();
                        }
                        return null;
                    }))
                    .toList();
            start.countDown();
            for (Future<Object> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new AssertionError(e);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, created.get(), "more than one create of the same name was accepted");
        assertEquals(7, rejected.get());
        service.deleteCacheParameterGroup(name);
    }

    @Test
    void aModifyCannotRestoreAGroupThatWasDeleted() throws Exception {
        // The modify reads its record inside the lock, so a delete that got there first leaves it
        // with nothing to write back.
        String name = "modify-delete-race-pg";
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 40; attempt++) {
                service.createCacheParameterGroup(name, "redis7", "race", Map.of());
                var start = new CountDownLatch(1);

                Future<?> modify = pool.submit(() -> {
                    start.await();
                    try {
                        service.modifyCacheParameterGroup(name, Map.of("maxmemory-policy", "allkeys-lru"));
                    } catch (AwsException e) {
                        // Losing the race is a legitimate outcome, but only with this error: any
                        // other one would be a real failure hiding inside the tolerated case.
                        assertEquals("CacheParameterGroupNotFound", e.getErrorCode(),
                                "modify losing the race to delete reported " + e.getErrorCode()
                                        + ": " + e.getMessage());
                    }
                    return null;
                });
                Future<?> delete = pool.submit(() -> {
                    start.await();
                    service.deleteCacheParameterGroup(name);
                    return null;
                });

                start.countDown();
                modify.get(30, TimeUnit.SECONDS);
                delete.get(30, TimeUnit.SECONDS);

                AwsException gone = assertThrows(AwsException.class,
                        () -> service.requireParameterGroup(name),
                        "the deleted parameter group was restored by a concurrent modify");
                assertEquals("CacheParameterGroupNotFound", gone.getErrorCode());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void modifyReportsWhichThingIsActuallyWrong() {
        // Absent from the store covers two different situations, and the race test only sees the
        // second one when it happens to lose the race, so both are asserted directly here.
        service.createCacheParameterGroup("gone-pg", "redis7", "deleted below", Map.of());
        service.deleteCacheParameterGroup("gone-pg");

        AwsException deleted = assertThrows(AwsException.class,
                () -> service.modifyCacheParameterGroup("gone-pg", Map.of("maxmemory-policy", "noeviction")));
        assertEquals("CacheParameterGroupNotFound", deleted.getErrorCode(),
                "a group that was deleted must not be reported as a default group");

        AwsException published = assertThrows(AwsException.class,
                () -> service.modifyCacheParameterGroup("default.redis7", Map.of("maxmemory-policy", "noeviction")));
        assertEquals("InvalidParameterValue", published.getErrorCode());
        assertTrue(published.getMessage().contains("default group"));
    }

    @Test
    void aGroupSurvivesBeingWrittenAndReadBack() throws Exception {
        // The records are persisted as JSON, so what a restart reloads has to carry the parameters
        // and tags that were set — and a record written before either field existed still has to
        // load.
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        CacheParameterGroup group = new CacheParameterGroup("persisted-pg", "redis7", "persisted");
        group.setParameters(new java.util.LinkedHashMap<>(Map.of("maxmemory-policy", "allkeys-lru")));
        group.setTags(new java.util.LinkedHashMap<>(Map.of("env", "prod")));

        Map<String, CacheParameterGroup> reloaded = mapper.readValue(
                mapper.writeValueAsString(Map.of("persisted-pg", group)),
                new TypeReference<Map<String, CacheParameterGroup>>() {});

        CacheParameterGroup restored = reloaded.get("persisted-pg");
        assertEquals("redis7", restored.getFamily());
        assertEquals("allkeys-lru", restored.getParameters().get("maxmemory-policy"));
        assertEquals("prod", restored.getTags().get("env"));

        CacheParameterGroup legacy = mapper.readValue(
                "{\"name\":\"old-pg\",\"family\":\"redis7\"}", CacheParameterGroup.class);
        assertTrue(legacy.getParameters().isEmpty(), "a record without parameters must load with none");
        assertTrue(legacy.getTags().isEmpty(), "a record without tags must load with none");
    }
}
