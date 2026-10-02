package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.ecs.model.ServiceDeployment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class EcsResponseWriterDeploymentTest {

    @Test
    void deploymentIncludesRollbackAndTriggeredAlarms() {
        ServiceDeployment deployment = new ServiceDeployment();
        deployment.setRollbackTargetServiceRevisionArn("revision/previous");
        deployment.setRollbackReason("Deployment alarm triggered");
        deployment.setRollbackStartedAt(Instant.parse("2026-10-01T00:00:00Z"));
        deployment.setAlarmNames(List.of("cpu-high", "memory-high"));
        deployment.setTriggeredAlarmNames(List.of("cpu-high"));
        deployment.setAlarmStatus("TRIGGERED");

        JsonNode node = new EcsResponseWriter(mock(EcsService.class), new ObjectMapper())
                .serviceDeploymentNode(deployment);

        assertEquals("revision/previous", node.path("rollback").path("serviceRevisionArn").asText());
        assertEquals("Deployment alarm triggered", node.path("rollback").path("reason").asText());
        assertFalse(node.path("rollback").path("startedAt").isMissingNode());
        assertEquals("cpu-high", node.path("alarms").path("triggeredAlarmNames").get(0).asText());
        assertEquals(2, node.path("alarms").path("alarmNames").size());
        assertEquals("TRIGGERED", node.path("alarms").path("status").asText());
    }
}
