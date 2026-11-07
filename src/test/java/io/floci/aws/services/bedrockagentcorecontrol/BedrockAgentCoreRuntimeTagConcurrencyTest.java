package io.floci.aws.services.bedrockagentcorecontrol;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreConcurrencyFixture.REGION;
import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreConcurrencyFixture.assertBlocked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockAgentCoreRuntimeTagConcurrencyTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionWaitsForTagPublication(boolean remove) throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        fixture.storage.parkNextWrite();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> tagging = executor.submit(() -> changeTags(fixture, remove));
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> deleteThread = new AtomicReference<>();
                Future<AgentRuntime> deletion = executor.submit(() -> {
                    deleteThread.set(Thread.currentThread());
                    return fixture.delete();
                });
                assertBlocked(deleteThread, deletion);
                fixture.storage.release();
                tagging.get(5, TimeUnit.SECONDS);
                deletion.get(5, TimeUnit.SECONDS);
                assertAbsent(fixture);
            } finally {
                fixture.storage.release();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagWriteRechecksExistenceAfterDeletion(boolean remove) throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        fixture.storage.parkNextRead();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentRuntime> deletion = executor.submit(fixture::delete);
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> tagThread = new AtomicReference<>();
                Future<AwsException> tagging = executor.submit(() -> {
                    tagThread.set(Thread.currentThread());
                    return assertThrows(AwsException.class, () -> changeTags(fixture, remove));
                });
                assertBlocked(tagThread, tagging);
                fixture.storage.release();
                deletion.get(5, TimeUnit.SECONDS);
                assertEquals("ResourceNotFoundException", tagging.get(5, TimeUnit.SECONDS).getErrorCode());
                assertAbsent(fixture);
            } finally {
                fixture.storage.release();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagChangesPreserveOtherTags(boolean remove) {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        changeTags(fixture, remove);
        assertEquals(remove ? Map.of("other", "retained") : Map.of("key", "new", "other", "retained"),
                fixture.service.getTagsByArn(REGION, fixture.arn));
        fixture.delete();
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> changeTags(fixture, remove)).getErrorCode());
        assertAbsent(fixture);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagReadWaitsForTagPublication(boolean remove) throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        fixture.storage.parkNextWrite();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> tagging = executor.submit(() -> changeTags(fixture, remove));
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> readThread = new AtomicReference<>();
                Future<Map<String, String>> reading = executor.submit(() -> {
                    readThread.set(Thread.currentThread());
                    return fixture.service.getTagsByArn(REGION, fixture.arn);
                });
                assertBlocked(readThread, reading);
                fixture.storage.release();
                tagging.get(5, TimeUnit.SECONDS);
                assertEquals(remove ? Map.of("other", "retained") : Map.of("key", "new", "other", "retained"),
                        reading.get(5, TimeUnit.SECONDS));
            } finally {
                fixture.storage.release();
            }
        }
    }

    @Test
    void tagReadRechecksExistenceAfterDeletion() throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        fixture.storage.parkNextRead();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentRuntime> deletion = executor.submit(fixture::delete);
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> readThread = new AtomicReference<>();
                Future<AwsException> reading = executor.submit(() -> {
                    readThread.set(Thread.currentThread());
                    return assertThrows(AwsException.class,
                            () -> fixture.service.getTagsByArn(REGION, fixture.arn));
                });
                assertBlocked(readThread, reading);
                fixture.storage.release();
                deletion.get(5, TimeUnit.SECONDS);
                assertEquals("ResourceNotFoundException", reading.get(5, TimeUnit.SECONDS).getErrorCode());
                assertAbsent(fixture);
            } finally {
                fixture.storage.release();
            }
        }
    }

    @Test
    void tagReadReturnsDetachedSnapshot() {
        BedrockAgentCoreConcurrencyFixture fixture = taggedRuntime();
        Map<String, String> tags = fixture.service.getTagsByArn(REGION, fixture.arn);
        changeTags(fixture, false);
        assertEquals(Map.of("key", "old", "other", "retained"), tags);
        tags.clear();
        assertEquals(Map.of("key", "new", "other", "retained"),
                fixture.service.getTagsByArn(REGION, fixture.arn));
    }

    private static BedrockAgentCoreConcurrencyFixture taggedRuntime() {
        BedrockAgentCoreConcurrencyFixture fixture = new BedrockAgentCoreConcurrencyFixture();
        fixture.service.tagByArn(REGION, fixture.arn, Map.of("key", "old", "other", "retained"));
        return fixture;
    }

    private static void changeTags(BedrockAgentCoreConcurrencyFixture fixture, boolean remove) {
        if (remove) {
            fixture.service.untagByArn(REGION, fixture.arn, List.of("key"));
        } else {
            fixture.service.tagByArn(REGION, fixture.arn, Map.of("key", "new"));
        }
    }

    private static void assertAbsent(BedrockAgentCoreConcurrencyFixture fixture) {
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.service.getAgentRuntime(fixture.runtimeId, REGION)).getErrorCode());
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.service.getTagsByArn(REGION, fixture.arn)).getErrorCode());
    }
}
