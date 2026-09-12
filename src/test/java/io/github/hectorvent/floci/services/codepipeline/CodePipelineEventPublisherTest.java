package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution.ActionExecution;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link CodePipelineEventPublisher#actionStateChange}'s execution-result
 * shape, pinned at the entry handed to {@code eventBridgeService.putEvents} so a regression
 * in the optional-field guards is caught even though the event delivery itself is best-effort.
 */
@ExtendWith(MockitoExtension.class)
class CodePipelineEventPublisherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private EventBridgeService eventBridgeService;
    @Mock private SnsService snsService;

    @Test
    @SuppressWarnings("unchecked")
    void summaryAloneStillProducesAnExecutionResultBlock() throws Exception {
        CodePipelineEventPublisher publisher =
                new CodePipelineEventPublisher(eventBridgeService, snsService, MAPPER);

        CodePipelineExecution execution = new CodePipelineExecution();
        execution.setRegion("us-east-1");
        ActionExecution action = new ActionExecution();
        action.setStageName("Build");
        action.setActionName("Compile");
        action.setOwner("AWS");
        action.setProvider("CodeBuild");
        action.setCategory("Build");
        action.setSummary("Build succeeded.");

        publisher.actionStateChange(execution, action, "SUCCEEDED");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(captor.capture(), any(), any());
        JsonNode detail = MAPPER.readTree((String) captor.getValue().get(0).get("Detail"));
        assertEquals("Build succeeded.",
                detail.path("execution-result").path("external-execution-summary").asText());
        assertFalse(detail.path("execution-result").has("external-execution-id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingErrorCodeIsOmittedRatherThanTheLiteralStringNull() throws Exception {
        CodePipelineEventPublisher publisher =
                new CodePipelineEventPublisher(eventBridgeService, snsService, MAPPER);

        CodePipelineExecution execution = new CodePipelineExecution();
        execution.setRegion("us-east-1");
        ActionExecution action = new ActionExecution();
        action.setStageName("Build");
        action.setActionName("Compile");
        action.setOwner("AWS");
        action.setProvider("CodeBuild");
        action.setCategory("Build");
        action.setErrorDetails(Map.of("message", "boom"));

        publisher.actionStateChange(execution, action, "FAILED");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(captor.capture(), any(), any());
        JsonNode detail = MAPPER.readTree((String) captor.getValue().get(0).get("Detail"));
        assertFalse(detail.path("execution-result").has("error-code"));
    }
}
