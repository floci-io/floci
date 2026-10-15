package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution.ActionExecution;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link CodePipelineEventPublisher#actionStateChange}'s execution-result
 * shape, pinned at the entry handed to {@code eventBridgeService.putEvents} so a regression
 * in the optional-field guards is caught even though the event delivery itself is best-effort,
 * and for the approval notification's console links, pinned at the message handed to
 * {@code snsService.publish}.
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

    /**
     * The console host follows the pipeline's partition where a user guide states one; EUSC and
     * ISO have no published host and keep the commercial one.
     */
    @ParameterizedTest
    @CsvSource({
            "us-east-1,       console.aws.amazon.com",
            "cn-north-1,      console.amazonaws.cn",
            "us-gov-west-1,   console.amazonaws-us-gov.com",
            "eusc-de-east-1,  console.aws.amazon.com",
            "us-iso-east-1,   console.aws.amazon.com"})
    void approvalLinksUseTheConsoleHostOfThePipelinesPartition(String region, String host) throws Exception {
        CodePipelineEventPublisher publisher =
                new CodePipelineEventPublisher(eventBridgeService, snsService, MAPPER);

        CodePipelineExecution execution = new CodePipelineExecution();
        execution.setRegion(region);
        execution.setAccountId("000000000000");
        execution.setPipelineName("release");
        ActionExecution action = new ActionExecution();
        action.setStageName("Gate");
        action.setActionName("Approve");
        action.setToken("token-1");

        publisher.approvalNeeded(execution, action, "topic-arn", null, null, 1700000000);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(snsService).publish(eq("topic-arn"), isNull(), captor.capture(), any(), eq(region));
        JsonNode message = MAPPER.readTree(captor.getValue());
        String consoleLink = "https://" + host + "/codepipeline/home?region=" + region + "#/view/release";
        assertEquals(consoleLink, message.path("consoleLink").asText());
        assertEquals(consoleLink + "/Gate/Approve/approve/token-1",
                message.path("approval").path("approvalReviewLink").asText());
    }

    @Test
    @SuppressWarnings("unchecked")
    void startTimeKeepsTheMillisecondsOfTheStoredStartTime() throws Exception {
        // Catches: start-time truncated to whole seconds, up to 999 ms before the stored start.
        CodePipelineEventPublisher publisher =
                new CodePipelineEventPublisher(eventBridgeService, snsService, MAPPER);

        CodePipelineExecution execution = new CodePipelineExecution();
        execution.setRegion("us-east-1");
        execution.setStartTime(1700000000.604);

        publisher.pipelineStateChange(execution, "STARTED");

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(eventBridgeService).putEvents(captor.capture(), any(), any());
        JsonNode detail = MAPPER.readTree((String) captor.getValue().get(0).get("Detail"));
        assertEquals("2023-11-14T22:13:20.604Z", detail.path("start-time").asText());
    }
}
