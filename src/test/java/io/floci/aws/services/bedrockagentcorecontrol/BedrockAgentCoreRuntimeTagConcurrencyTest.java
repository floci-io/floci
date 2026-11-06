package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.floci.aws.testing.ParkingStore;
import io.floci.aws.testing.ThreadStates;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockAgentCoreRuntimeTagConcurrencyTest {

    private static final String REGION = "us-east-1";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deletionWaitsForTagPublication(boolean remove) throws Exception {
        Fixture fixture = new Fixture();
        fixture.storage.parkNextWrite();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> tagging = executor.submit(() -> fixture.changeTags(remove));
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
                fixture.assertAbsent();
            } finally {
                fixture.storage.release();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagWriteRechecksExistenceAfterDeletion(boolean remove) throws Exception {
        Fixture fixture = new Fixture();
        fixture.storage.parkNextRead();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AgentRuntime> deletion = executor.submit(fixture::delete);
            try {
                assertTrue(fixture.storage.awaitParked());
                AtomicReference<Thread> tagThread = new AtomicReference<>();
                Future<AwsException> tagging = executor.submit(() -> {
                    tagThread.set(Thread.currentThread());
                    return assertThrows(AwsException.class, () -> fixture.changeTags(remove));
                });
                assertBlocked(tagThread, tagging);
                fixture.storage.release();
                deletion.get(5, TimeUnit.SECONDS);
                assertEquals("ResourceNotFoundException", tagging.get(5, TimeUnit.SECONDS).getErrorCode());
                fixture.assertAbsent();
            } finally {
                fixture.storage.release();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagChangesPreserveOtherTags(boolean remove) {
        Fixture fixture = new Fixture();
        fixture.changeTags(remove);
        assertEquals(remove ? Map.of("other", "retained") : Map.of("key", "new", "other", "retained"),
                fixture.service.getTagsByArn(REGION, fixture.arn));
        fixture.delete();
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.changeTags(remove)).getErrorCode());
        fixture.assertAbsent();
    }

    private static void assertBlocked(AtomicReference<Thread> worker, Future<?> task) throws InterruptedException {
        ThreadStates.awaitBlockedOrDone(worker, task);
        assertNotNull(worker.get(), "the competing worker must start");
        assertEquals(Thread.State.BLOCKED, worker.get().getState(),
                "the competing worker must overlap the parked storage operation");
        assertFalse(task.isDone());
    }

    private static final class Fixture {
        private final ParkingStore<AgentRuntime> storage = new ParkingStore<>();
        private final BedrockAgentCoreControlService service = new BedrockAgentCoreControlService(
                storage, new RegionResolver(REGION, "000000000000"));
        private final String runtimeId;
        private final String arn;

        private Fixture() {
            ObjectMapper mapper = new ObjectMapper();
            ObjectNode artifact = mapper.createObjectNode();
            artifact.putObject("containerConfiguration").put("containerUri", "example.invalid/probe:1");
            ObjectNode network = mapper.createObjectNode().put("networkMode", "PUBLIC");
            AgentRuntime runtime = service.createAgentRuntime("probe", artifact, network,
                    "arn:aws:iam::000000000000:role/probe", null, null, null, null, null, REGION);
            runtimeId = runtime.getAgentRuntimeId();
            arn = service.arn(runtime, "1", REGION);
            service.tagByArn(REGION, arn, Map.of("key", "old", "other", "retained"));
        }

        private void changeTags(boolean remove) {
            if (remove) {
                service.untagByArn(REGION, arn, List.of("key"));
            } else {
                service.tagByArn(REGION, arn, Map.of("key", "new"));
            }
        }

        private AgentRuntime delete() {
            return service.deleteAgentRuntime(runtimeId, null, REGION);
        }

        private void assertAbsent() {
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> service.getAgentRuntime(runtimeId, REGION)).getErrorCode());
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> service.getTagsByArn(REGION, arn)).getErrorCode());
        }
    }
}
