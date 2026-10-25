package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreMemoryService.MemoryDeleted;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.Memory;
import io.github.hectorvent.floci.testing.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockAgentCoreMemoryServiceTest {

    private static final String REGION = "us-east-1";
    private static final String KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/mem-key";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/mem-role";

    private final MutableClock clock = new MutableClock();
    private BedrockAgentCoreMemoryService service;

    @BeforeEach
    void setUp() {
        service = new BedrockAgentCoreMemoryService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"), clock);
    }

    private Memory create(String name) {
        return service.create(name, 30, "desc", KEY_ARN, ROLE_ARN,
                Map.of("team", "core"), null, REGION);
    }

    @Test
    void createPersistsAllFields() {
        Memory memory = create("myMemory");
        assertTrue(memory.getMemoryId().matches("myMemory-[a-zA-Z0-9]{10}"), memory.getMemoryId());
        assertEquals(KEY_ARN, memory.getEncryptionKeyArn());
        assertEquals(ROLE_ARN, memory.getMemoryExecutionRoleArn());
        assertEquals("core", memory.getTags().get("team"));

        Memory fetched = service.get(memory.getMemoryId(), REGION);
        assertEquals(KEY_ARN, fetched.getEncryptionKeyArn());
        assertEquals(ROLE_ARN, fetched.getMemoryExecutionRoleArn());
        assertEquals("core", fetched.getTags().get("team"));
    }

    @Test
    void clientTokenReplayReturnsOriginalAndIgnoresNewFields() {
        Memory first = service.create("tokMem", 30, null, null, null, null, "tok-1", REGION);
        Memory replay = service.create("otherName", 60, null, KEY_ARN, ROLE_ARN,
                Map.of("late", "tag"), "tok-1", REGION);
        assertEquals(first.getMemoryId(), replay.getMemoryId());
        assertEquals("tokMem", replay.getName());
        assertNull(replay.getEncryptionKeyArn());
        assertTrue(replay.getTags().isEmpty());
    }

    @Test
    void updateAppliesFieldsAndLeavesNullsUntouched() {
        Memory memory = create("updMemory");
        service.update(memory.getMemoryId(), null, 90, null, REGION);

        Memory after = service.get(memory.getMemoryId(), REGION);
        assertEquals(90, after.getEventExpiryDuration());
        assertEquals("desc", after.getDescription());
        assertEquals(ROLE_ARN, after.getMemoryExecutionRoleArn());

        service.update(memory.getMemoryId(), "new desc", null,
                "arn:aws:iam::000000000000:role/mem-role-2", REGION);
        after = service.get(memory.getMemoryId(), REGION);
        assertEquals("new desc", after.getDescription());
        assertEquals(90, after.getEventExpiryDuration());
        assertEquals("arn:aws:iam::000000000000:role/mem-role-2", after.getMemoryExecutionRoleArn());
    }

    @Test
    void updateReplacingOmittedClearsNullDescriptionAndRole() {
        Memory memory = create("replMemory");
        service.update(memory.getMemoryId(), null, 45, null, true, REGION);

        Memory after = service.get(memory.getMemoryId(), REGION);
        assertEquals(45, after.getEventExpiryDuration());
        assertNull(after.getDescription());
        assertNull(after.getMemoryExecutionRoleArn());
        assertEquals(KEY_ARN, after.getEncryptionKeyArn(), "the encryption key is not updatable, so it stays");
    }

    @Test
    void updateRejectsOutOfRangeExpiry() {
        Memory memory = create("badUpd");
        for (int v : new int[]{2, 366}) {
            AwsException e = assertThrows(AwsException.class,
                    () -> service.update(memory.getMemoryId(), null, v, null, REGION));
            assertEquals(400, e.getHttpStatus());
        }
        assertEquals(30, service.get(memory.getMemoryId(), REGION).getEventExpiryDuration());
    }

    @Test
    void rejectedUpdateAppliesNothing() {
        Memory memory = create("atomicUpd");
        AwsException e = assertThrows(AwsException.class, () -> service.update(
                memory.getMemoryId(), "should-not-stick", 999,
                "arn:aws:iam::000000000000:role/should-not-stick", REGION));
        assertEquals(400, e.getHttpStatus());

        Memory after = service.get(memory.getMemoryId(), REGION);
        assertEquals("desc", after.getDescription());
        assertEquals(30, after.getEventExpiryDuration());
        assertEquals(ROLE_ARN, after.getMemoryExecutionRoleArn());
    }

    @Test
    void deleteIsIdempotentByClientToken() {
        Memory memory = create("myMemory");
        String id = memory.getMemoryId();
        assertEquals("DELETING", service.delete(id, "del-1", REGION).getStatus());
        // Replayed delete with the same token succeeds instead of 404ing.
        assertEquals("DELETING", service.delete(id, "del-1", REGION).getStatus());
        // A different token against the now-missing id still 404s.
        assertEquals(404, assertThrows(AwsException.class,
                () -> service.delete(id, "other", REGION)).getHttpStatus());
    }

    @Test
    void deleteAnnouncesTheMemoryOnceAndNotOnReplay() {
        List<MemoryDeleted> deleted = new ArrayList<>();
        service = new BedrockAgentCoreMemoryService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"), clock, deleted::add);
        String id = create("myMemory").getMemoryId();
        service.delete(id, "del-1", REGION);
        service.delete(id, "del-1", REGION);
        assertEquals(List.of(new MemoryDeleted(id)), deleted);
    }

    @Test
    void failedEventCleanupLeavesTheMemoryForARetry() {
        AtomicBoolean cleanupFails = new AtomicBoolean(true);
        List<MemoryDeleted> deleted = new ArrayList<>();
        service = new BedrockAgentCoreMemoryService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"), clock, event -> {
                    if (cleanupFails.get()) {
                        throw new IllegalStateException("events file write failed");
                    }
                    deleted.add(event);
                });
        String id = create("myMemory").getMemoryId();

        assertThrows(IllegalStateException.class, () -> service.delete(id, "del-1", REGION));
        assertEquals(id, service.get(id, REGION).getMemoryId());

        cleanupFails.set(false);
        assertEquals("DELETING", service.delete(id, "del-1", REGION).getStatus());
        assertEquals(List.of(new MemoryDeleted(id)), deleted);
    }

    @Test
    @Timeout(10)
    void deleteWaitsForAnEventWriteInProgress() throws InterruptedException {
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        service = new BedrockAgentCoreMemoryService(
                new InMemoryStorage<>(), new RegionResolver(REGION, "000000000000"), clock,
                event -> order.add("cleanup"));
        String id = create("myMemory").getMemoryId();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread writer = new Thread(() -> service.writeEvent(id, REGION, () -> {
            writing.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.add("write");
        }));
        writer.start();
        writing.await();

        Thread deleter = new Thread(() -> service.delete(id, null, REGION));
        deleter.start();
        while (deleter.isAlive() && deleter.getState() != Thread.State.WAITING) {
            Thread.onSpinWait();
        }
        release.countDown();
        writer.join();
        deleter.join();

        assertEquals(List.of("write", "cleanup"), order);
    }

    @Test
    void deleteTokenExpiresAfterWindowReturns404() {
        Memory memory = create("myMemory");
        String id = memory.getMemoryId();
        assertEquals("DELETING", service.delete(id, "del-1", REGION).getStatus());

        clock.advance(DeletedTokenLedger.TTL.plusSeconds(1));

        assertEquals(404, assertThrows(AwsException.class,
                () -> service.delete(id, "del-1", REGION)).getHttpStatus());
    }

    @Test
    void tagOperationsRoundTripThroughTheArn() {
        Memory memory = create("tagMemory");
        String arn = service.arn(memory, REGION);

        assertEquals(Map.of("team", "core"), service.getTagsByArn(REGION, arn));

        service.tagByArn(REGION, arn, Map.of("env", "prod"));
        assertEquals("prod", service.getTagsByArn(REGION, arn).get("env"));
        assertEquals("core", service.getTagsByArn(REGION, arn).get("team"));

        service.untagByArn(REGION, arn, List.of("env"));
        assertNull(service.getTagsByArn(REGION, arn).get("env"));
        assertEquals("core", service.getTagsByArn(REGION, arn).get("team"));
    }

    @Test
    void findByArnRejectsNonMemoryArnAndUnknownId() {
        AwsException wrongType = assertThrows(AwsException.class, () -> service.getTagsByArn(REGION,
                "arn:aws:bedrock-agentcore:us-east-1:000000000000:gateway/gw-abc1234567"));
        assertEquals(400, wrongType.getHttpStatus());

        AwsException unknown = assertThrows(AwsException.class, () -> service.getTagsByArn(REGION,
                "arn:aws:bedrock-agentcore:us-east-1:000000000000:memory/nosuchmem-abc1234567"));
        assertEquals(404, unknown.getHttpStatus());
    }
}
