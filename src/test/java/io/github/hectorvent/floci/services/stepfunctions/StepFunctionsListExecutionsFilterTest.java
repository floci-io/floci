package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StepFunctionsListExecutionsFilterTest {

    private static final String ARN = "arn:aws:states:us-east-2:000000000000:stateMachine:filter";
    private final ObjectMapper mapper = new ObjectMapper();
    private StepFunctionsService service;
    private StepFunctionsJsonHandler handler;

    @BeforeEach
    void setUp() {
        service = mock(StepFunctionsService.class);
        handler = new StepFunctionsJsonHandler(service, mapper);
        when(service.listExecutions(ARN)).thenReturn(
                List.of("RUNNING", "SUCCEEDED", "FAILED", "TIMED_OUT", "ABORTED").stream()
                        .map(status -> {
                            Execution execution = new Execution();
                            execution.setExecutionArn(ARN + ":" + status);
                            execution.setStateMachineArn(ARN);
                            execution.setName(status);
                            execution.setStatus(status);
                            return execution;
                        }).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RUNNING", "SUCCEEDED", "FAILED", "TIMED_OUT", "ABORTED"})
    void onlyMatchingCurrentStatusesAreReturned(String status) {
        JsonNode executions = list(request().put("statusFilter", status));
        assertEquals(1, executions.size());
        assertEquals(status, executions.get(0).path("status").asText());
        assertEquals(ARN + ":" + status, executions.get(0).path("executionArn").asText());
    }

    @Test
    void omittedAndNullFiltersKeepAllStatuses() {
        assertEquals(5, list(request()).size());
        assertEquals(5, list(request().putNull("statusFilter")).size());
    }

    @Test
    void absentMatchesReturnAnEmptyArray() {
        Execution succeeded = new Execution();
        succeeded.setStatus("SUCCEEDED");
        when(service.listExecutions(ARN)).thenReturn(List.of(succeeded));
        assertEquals(0, list(request().put("statusFilter", "RUNNING")).size());
    }

    @Test
    void emittedStatusIsTheSameSnapshotThatMatchedTheFilter() {
        Execution execution = mock(Execution.class);
        when(execution.getStatus()).thenReturn("RUNNING", "SUCCEEDED");
        when(service.listExecutions(ARN)).thenReturn(List.of(execution));
        JsonNode executions = list(request().put("statusFilter", "RUNNING"));
        assertEquals(1, executions.size());
        assertEquals("RUNNING", executions.get(0).path("status").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "running", "UNKNOWN", "PENDING_REDRIVE"})
    void invalidStateMachineFiltersFailBeforeReadingExecutions(String status) {
        assertInvalid(request().put("statusFilter", status));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "succeeded", "UNKNOWN"})
    void invalidEnumValuesUseSmithyValidationWording(String status) {
        AwsException error = assertThrows(AwsException.class,
                () -> list(request().put("statusFilter", status)));
        assertEquals("ValidationException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("1 validation error detected: Value '" + status
                + "' at 'statusFilter' failed to satisfy constraint: Member must satisfy enum value set: "
                + "[SUCCEEDED, TIMED_OUT, PENDING_REDRIVE, ABORTED, FAILED, RUNNING]", error.getMessage());
        verifyNoInteractions(service);
    }

    @Test
    void pendingRedriveIsRejectedWithoutClaimingMapRunSupport() {
        assertInvalid(mapper.createObjectNode().put("statusFilter", "PENDING_REDRIVE"));
        assertInvalid(mapper.createObjectNode().put("statusFilter", "PENDING_REDRIVE")
                .put("mapRunArn", ARN + ":map-run"));
    }

    @Test
    void nonStringFiltersAreRejected() {
        assertInvalid(request().put("statusFilter", true));
        assertInvalid(request().put("statusFilter", 1));
        ObjectNode request = request();
        request.putArray("statusFilter").add("RUNNING");
        assertInvalid(request);
    }

    private void assertInvalid(ObjectNode request) {
        AwsException error = assertThrows(AwsException.class, () -> list(request));
        assertEquals("ValidationException", error.getErrorCode());
        verifyNoInteractions(service);
    }

    private ObjectNode request() {
        return mapper.createObjectNode().put("stateMachineArn", ARN);
    }

    private JsonNode list(ObjectNode request) {
        return ((JsonNode) handler.handle("ListExecutions", request, "us-east-2").getEntity())
                .path("executions");
    }
}
