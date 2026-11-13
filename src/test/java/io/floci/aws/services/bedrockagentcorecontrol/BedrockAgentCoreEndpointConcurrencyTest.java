package io.floci.aws.services.bedrockagentcorecontrol;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.bedrockagentcorecontrol.model.AgentRuntimeEndpoint;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static io.floci.aws.services.bedrockagentcorecontrol.BedrockAgentCoreConcurrencyFixture.REGION;
import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BedrockAgentCoreEndpointConcurrencyTest {

    @Test
    void concurrentClientTokenReplayWaitsForPublication() throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = new BedrockAgentCoreConcurrencyFixture();
        AtomicReference<AgentRuntimeEndpoint> first = new AtomicReference<>();
        AtomicReference<AgentRuntimeEndpoint> second = new AtomicReference<>();
        fixture.storage.parkNextWrite();
        assertOperationWaitsForParkedStore(fixture.storage,
                () -> first.set(createEndpoint(fixture, "token")),
                () -> second.set(createEndpoint(fixture, "token")));
        assertSame(first.get(), second.get());
        assertEquals(1, namedEndpointCount(fixture));
    }

    @Test
    void concurrentSameNameGetsConflictAfterPublication() throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = new BedrockAgentCoreConcurrencyFixture();
        fixture.storage.parkNextWrite();
        assertOperationWaitsForParkedStore(fixture.storage,
                () -> createEndpoint(fixture, null),
                () -> {
                    AwsException failure = assertThrows(AwsException.class, () -> createEndpoint(fixture, null));
                    assertEquals("ConflictException", failure.getErrorCode());
                });
        assertEquals(1, namedEndpointCount(fixture));
    }

    @Test
    void deletingRuntimeCannotBeUndoneByEndpointPublication() throws Exception {
        BedrockAgentCoreConcurrencyFixture fixture = new BedrockAgentCoreConcurrencyFixture();
        fixture.storage.parkNextWrite();
        assertOperationWaitsForParkedStore(fixture.storage,
                () -> createEndpoint(fixture, null), fixture::delete);
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                () -> fixture.service.getAgentRuntime(fixture.runtimeId, REGION)).getErrorCode());
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
