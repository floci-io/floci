package io.floci.aws.services.bedrockagentcorecontrol;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntimeEndpoint;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreConcurrencyFixture.REGION;
import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreConcurrencyFixture.assertBlocked;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockAgentCoreEndpointConcurrencyTest {

    @Test
    void concurrentClientTokenReplayWaitsForPublication() throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = new BedrockAgentCoreConcurrencyFixture();
        fixture.storage.parkNextWrite();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentRuntimeEndpoint> first = executor.submit(() -> createEndpoint(fixture, "token"));
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> secondThread = new AtomicReference<>();
                Future<AgentRuntimeEndpoint> second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return createEndpoint(fixture, "token");
                });
                assertBlocked(secondThread, second);
                fixture.storage.release();
                assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                assertEquals(1, namedEndpointCount(fixture));
            } finally {
                fixture.storage.release();
            }
        }
    }

    @Test
    void concurrentSameNameGetsConflictAfterPublication() throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = new BedrockAgentCoreConcurrencyFixture();
        fixture.storage.parkNextWrite();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentRuntimeEndpoint> first = executor.submit(() -> createEndpoint(fixture, null));
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> secondThread = new AtomicReference<>();
                Future<AwsException> second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return assertThrows(AwsException.class, () -> createEndpoint(fixture, null));
                });
                assertBlocked(secondThread, second);
                fixture.storage.release();
                first.get(5, TimeUnit.SECONDS);
                assertEquals("ConflictException", second.get(5, TimeUnit.SECONDS).getErrorCode());
                assertEquals(1, namedEndpointCount(fixture));
            } finally {
                fixture.storage.release();
            }
        }
    }

    @Test
    void deletingRuntimeCannotBeUndoneByEndpointPublication() throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = new BedrockAgentCoreConcurrencyFixture();
        fixture.storage.parkNextWrite();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentRuntimeEndpoint> first = executor.submit(() -> createEndpoint(fixture, null));
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> deleteThread = new AtomicReference<>();
                Future<AgentRuntime> deletion = executor.submit(() -> {
                    deleteThread.set(Thread.currentThread());
                    return fixture.delete();
                });
                assertBlocked(deleteThread, deletion);
                fixture.storage.release();
                first.get(5, TimeUnit.SECONDS);
                deletion.get(5, TimeUnit.SECONDS);
                assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                        () -> fixture.service.getAgentRuntime(fixture.runtimeId, REGION)).getErrorCode());
            } finally {
                fixture.storage.release();
            }
        }
    }

    private static AgentRuntimeEndpoint createEndpoint(BedrockAgentCoreConcurrencyFixture fixture,
                                                       String clientToken) {
        return fixture.service.createEndpoint(fixture.runtimeId, "shared", null, null, clientToken, REGION);
    }

    private static long namedEndpointCount(BedrockAgentCoreConcurrencyFixture fixture) {
        return fixture.service.getAgentRuntime(fixture.runtimeId, REGION).getEndpoints().stream()
                .filter(endpoint -> "shared".equals(endpoint.getName())).count();
    }
}
