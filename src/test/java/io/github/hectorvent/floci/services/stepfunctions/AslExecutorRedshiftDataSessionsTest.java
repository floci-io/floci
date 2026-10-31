package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.ServiceRegistry;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataJsonHandler;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import io.github.hectorvent.floci.services.stepfunctions.model.StateMachine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AslExecutorRedshiftDataSessionsTest {
    @Test
    void listSessionsRendersEveryLifecycleTimestampWithoutChangingTheWireResponse() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        ServiceRegistry registry = mock(ServiceRegistry.class);
        when(registry.isServiceEnabled("redshift-data")).thenReturn(true);
        RedshiftDataService service = mock(RedshiftDataService.class);
        ObjectNode response = mapper.createObjectNode();
        ObjectNode session = response.putArray("Sessions").addObject();
        session.put("SessionId", "session-id");
        session.put("Status", "AVAILABLE");
        session.put("CreatedAt", 1_700_000_000.5);
        session.put("UpdatedAt", 1_700_000_001.0);
        session.put("SessionTtl", 1_700_000_060.25);
        session.put("SessionAliveSeconds", 60);
        when(service.listSessions(any(), eq("us-east-1"))).thenReturn(response);
        AslExecutor executor = AslExecutorFixture.builder()
                .objectMapper(mapper)
                .config(config)
                .customResourceLiveness(null)
                .redshiftDataHandler(new RedshiftDataJsonHandler(service))
                .serviceRegistry(registry)
                .build();
        StateMachine machine = new StateMachine();
        machine.setStateMachineArn("arn:aws:states:us-east-1:000000000000:stateMachine:list-sessions");
        machine.setDefinition("""
                {"StartAt":"List","States":{"List":{"Type":"Task",
                  "Resource":"arn:aws:states:::aws-sdk:redshiftdata:listSessions","End":true}}}
                """);
        Execution execution = new Execution();
        execution.setExecutionArn("arn:aws:states:us-east-1:000000000000:execution:list-sessions:run");
        execution.setStateMachineArn(machine.getStateMachineArn());
        execution.setInput("{}");
        try {
            executor.executeSync(machine, execution, new ArrayList<>(), (updated, events) -> { });

            assertEquals("SUCCEEDED", execution.getStatus(), execution.getCause());
            JsonNode rendered = mapper.readTree(execution.getOutput()).path("Sessions").get(0);
            assertEquals("2023-11-14T22:13:20.500Z", rendered.path("CreatedAt").asText());
            assertEquals("2023-11-14T22:13:21.000Z", rendered.path("UpdatedAt").asText());
            assertEquals("2023-11-14T22:14:20.250Z", rendered.path("SessionTtl").asText());
            assertEquals(60, rendered.path("SessionAliveSeconds").asInt());
            assertTrue(session.path("CreatedAt").isNumber());
            assertTrue(session.path("UpdatedAt").isNumber());
            assertTrue(session.path("SessionTtl").isNumber());
            verify(service).listSessions(any(), eq("us-east-1"));
        } finally {
            executor.stop();
        }
    }
}
