package io.floci.aws.services.bedrockagentcorecontrol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntime;
import io.floci.aws.testing.ParkingStore;
import io.floci.aws.testing.ThreadStates;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class BedrockAgentCoreConcurrencyFixture {

    static final String REGION = "us-east-1";

    final ParkingStore<AgentRuntime> storage = new ParkingStore<>();
    final BedrockAgentCoreControlService service = new BedrockAgentCoreControlService(
            storage, new RegionResolver(REGION, "000000000000"));
    final String runtimeId;
    final String arn;

    BedrockAgentCoreConcurrencyFixture() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode artifact = mapper.createObjectNode();
        artifact.putObject("containerConfiguration").put("containerUri", "example.invalid/probe:1");
        ObjectNode network = mapper.createObjectNode().put("networkMode", "PUBLIC");
        AgentRuntime runtime = service.createAgentRuntime("probe", artifact, network,
                "arn:aws:iam::000000000000:role/probe", null, null, null, null, null, REGION);
        runtimeId = runtime.getAgentRuntimeId();
        arn = service.arn(runtime, REGION);
    }

    AgentRuntime delete() {
        return service.deleteAgentRuntime(runtimeId, null, REGION);
    }

    static void assertBlocked(AtomicReference<Thread> worker, Future<?> task) throws InterruptedException {
        ThreadStates.awaitBlockedOrDone(worker, task);
        assertNotNull(worker.get(), "the competing worker must start");
        assertEquals(Thread.State.BLOCKED, worker.get().getState(),
                "the competing worker must overlap the parked storage operation");
        assertFalse(task.isDone());
    }
}
