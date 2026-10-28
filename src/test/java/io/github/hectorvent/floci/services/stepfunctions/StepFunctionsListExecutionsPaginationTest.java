package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.PaginatedResult;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StepFunctionsListExecutionsPaginationTest {

    private static final String ARN = "arn:aws:states:us-east-1:000000000000:stateMachine:pagination";

    private final ObjectMapper mapper = new ObjectMapper();
    private StepFunctionsService service;
    private StepFunctionsJsonHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(StepFunctionsService.class);
        handler = new StepFunctionsJsonHandler(service, mapper, Clock.systemUTC());

        Execution newer = execution("newer", 2.0);
        when(service.listExecutions(ARN, null, 1, null)).thenReturn(
                new PaginatedResult<>(List.of(
                        new StepFunctionsService.ExecutionSnapshot(newer, "SUCCEEDED")), "continuation"));
    }

    @Test
    void maxResultsCapsThePageAndReturnsAContinuationToken() {
        ObjectNode request = mapper.createObjectNode()
                .put("stateMachineArn", ARN)
                .put("maxResults", 1);

        JsonNode response = (JsonNode) handler.handle("ListExecutions", request, "us-east-1").getEntity();

        assertEquals(1, response.path("executions").size());
        assertEquals("newer", response.path("executions").get(0).path("name").asText());
        assertNotNull(response.path("nextToken").textValue());
    }

    @Test
    void omitsNextTokenWhenTheServiceReturnsTheFinalPage() {
        when(service.listExecutions(ARN, "SUCCEEDED", null, "continuation"))
                .thenReturn(new PaginatedResult<>(List.of(), null));
        ObjectNode request = mapper.createObjectNode()
                .put("stateMachineArn", ARN)
                .put("statusFilter", "SUCCEEDED")
                .put("nextToken", "continuation");

        JsonNode response = (JsonNode) handler.handle("ListExecutions", request, "us-east-1").getEntity();

        assertEquals(0, response.path("executions").size());
        assertFalse(response.has("nextToken"));
    }

    private static Execution execution(String name, double startDate) {
        Execution execution = new Execution();
        execution.setExecutionArn(ARN + ":" + name);
        execution.setStateMachineArn(ARN);
        execution.setName(name);
        execution.setStatus("SUCCEEDED");
        execution.setStartDate(startDate);
        return execution;
    }
}
