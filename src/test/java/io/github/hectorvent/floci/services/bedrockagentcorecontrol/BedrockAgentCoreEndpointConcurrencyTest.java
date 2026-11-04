package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.model.AgentRuntimeEndpoint;
import io.github.hectorvent.floci.testing.ParkingStore;
import io.github.hectorvent.floci.testing.ThreadStates;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockAgentCoreEndpointConcurrencyTest {

    private static final String REGION = "us-east-1";

    @Test
    void concurrentClientTokenReplayWaitsForPublication() throws Exception {
        Fixture fixture = new Fixture();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentRuntimeEndpoint> first = executor.submit(() -> fixture.create("token"));
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> secondThread = new AtomicReference<>();
                Future<AgentRuntimeEndpoint> second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return fixture.create("token");
                });
                ThreadStates.awaitBlockedOrDone(secondThread, second);
                assertNotNull(secondThread.get(), "the competing worker must start");
                assertEquals(Thread.State.BLOCKED, secondThread.get().getState(),
                        "the competing worker must overlap the parked publication");
                assertFalse(second.isDone(), "replay must await the original publication");
                fixture.storage.release();
                assertSame(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
                assertEquals(1, fixture.namedEndpointCount());
            } finally {
                fixture.storage.release();
            }
        }
    }

    @Test
    void concurrentSameNameGetsConflictAfterPublication() throws Exception {
        Fixture fixture = new Fixture();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentRuntimeEndpoint> first = executor.submit(() -> fixture.create(null));
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> secondThread = new AtomicReference<>();
                Future<AwsException> second = executor.submit(() -> {
                    secondThread.set(Thread.currentThread());
                    return assertThrows(AwsException.class, () -> fixture.create(null));
                });
                ThreadStates.awaitBlockedOrDone(secondThread, second);
                assertNotNull(secondThread.get(), "the competing worker must start");
                assertEquals(Thread.State.BLOCKED, secondThread.get().getState(),
                        "the competing worker must overlap the parked publication");
                assertFalse(second.isDone(), "the uniqueness decision must await publication");
                fixture.storage.release();
                first.get(5, TimeUnit.SECONDS);
                assertEquals("ConflictException", second.get(5, TimeUnit.SECONDS).getErrorCode());
                assertEquals(1, fixture.namedEndpointCount());
            } finally {
                fixture.storage.release();
            }
        }
    }

    @Test
    void deletingRuntimeCannotBeUndoneByEndpointPublication() throws Exception {
        Fixture fixture = new Fixture();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentRuntimeEndpoint> first = executor.submit(() -> fixture.create(null));
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> deleteThread = new AtomicReference<>();
                Future<AgentRuntime> deletion = executor.submit(() -> {
                    deleteThread.set(Thread.currentThread());
                    return fixture.service.deleteAgentRuntime(fixture.runtimeId, null, REGION);
                });
                ThreadStates.awaitBlockedOrDone(deleteThread, deletion);
                assertNotNull(deleteThread.get(), "the competing worker must start");
                assertEquals(Thread.State.BLOCKED, deleteThread.get().getState(),
                        "the competing worker must overlap the parked publication");
                assertFalse(deletion.isDone(), "deletion must wait for the in-flight publication");
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

    private static final class Fixture {
        private final ParkingStore<AgentRuntime> storage = new ParkingStore<>();
        private final BedrockAgentCoreControlService service = new BedrockAgentCoreControlService(
                storage, new RegionResolver(REGION, "000000000000"));
        private final String runtimeId;

        private Fixture() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode artifact = mapper.createObjectNode();
            artifact.putObject("containerConfiguration").put("containerUri", "example.invalid/probe:1");
            ObjectNode network = mapper.createObjectNode().put("networkMode", "PUBLIC");
            runtimeId = service.createAgentRuntime("probe", artifact, network,
                    "arn:aws:iam::000000000000:role/probe", null, null,
                    null, null, null, REGION).getAgentRuntimeId();
            storage.parkNextWrite();
        }

        private AgentRuntimeEndpoint create(String clientToken) {
            return service.createEndpoint(runtimeId, "shared", null, null, clientToken, REGION);
        }

        private long namedEndpointCount() {
            return service.getAgentRuntime(runtimeId, REGION).getEndpoints().stream()
                    .filter(endpoint -> "shared".equals(endpoint.getName())).count();
        }
    }

}
