package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution.ActionExecution;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes the {@code aws.codepipeline} state-change events (pipeline, stage, and action)
 * to the default EventBridge bus, and the SNS approval-needed notification, matching the
 * shapes real CodePipeline emits. All publishing is best-effort: a failing delivery never
 * fails the pipeline execution.
 */
@ApplicationScoped
public class CodePipelineEventPublisher {

    private static final Logger LOG = Logger.getLogger(CodePipelineEventPublisher.class);
    private static final int MAX_SNS_SUBJECT_LENGTH = 100;
    private static final String CONSOLE_URL = "https://console.aws.amazon.com/codepipeline/home";
    /** The approval message's {@code expires} is to the minute, e.g. 2016-07-07T20:22Z. */
    private static final DateTimeFormatter MINUTE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'Z'").withZone(ZoneOffset.UTC);

    private final EventBridgeService eventBridgeService;
    private final SnsService snsService;
    private final ObjectMapper mapper;

    @Inject
    public CodePipelineEventPublisher(EventBridgeService eventBridgeService,
                                      SnsService snsService, ObjectMapper mapper) {
        this.eventBridgeService = eventBridgeService;
        this.snsService = snsService;
        this.mapper = mapper;
    }

    public void pipelineStateChange(CodePipelineExecution execution, String state) {
        ObjectNode detail = baseDetail(execution);
        detail.put("state", state);
        detail.put("execution-mode", execution.getExecutionMode());
        if (execution.getStartTime() != null) {
            detail.put("start-time", isoTimestamp(execution.getStartTime()));
        }
        publish(execution, "CodePipeline Pipeline Execution State Change", detail);
    }

    public void stageStateChange(CodePipelineExecution execution, String stageName, String state) {
        ObjectNode detail = baseDetail(execution);
        detail.put("stage", stageName);
        detail.put("state", state);
        publish(execution, "CodePipeline Stage Execution State Change", detail);
    }

    public void actionStateChange(CodePipelineExecution execution, ActionExecution action, String state) {
        ObjectNode detail = baseDetail(execution);
        detail.put("stage", action.getStageName());
        detail.put("action", action.getActionName());
        detail.put("state", state);
        detail.put("region", execution.getRegion());
        detail.putObject("type")
                .put("owner", action.getOwner())
                .put("provider", action.getProvider())
                .put("category", action.getCategory())
                .put("version", "1");
        if (action.getExternalExecutionId() != null || action.getSummary() != null
                || action.getErrorDetails() != null) {
            ObjectNode result = detail.putObject("execution-result");
            if (action.getExternalExecutionId() != null) {
                result.put("external-execution-id", action.getExternalExecutionId());
            }
            if (action.getSummary() != null) {
                result.put("external-execution-summary", action.getSummary());
            }
            if (action.getErrorDetails() != null) {
                Object code = action.getErrorDetails().get("code");
                if (code != null) {
                    result.put("error-code", code.toString());
                }
            }
        }
        publish(execution, "CodePipeline Action Execution State Change", detail);
    }

    /**
     * The SNS notification a Manual approval action sends when it starts waiting.
     *
     * <p>Runs on a worker thread with no request context, so the publish is wrapped in
     * {@link RequestScopes#runAs} to resolve the topic in the pipeline's own account.
     * The subject is truncated to the 100 characters SNS allows.</p>
     */
    public void approvalNeeded(CodePipelineExecution execution, ActionExecution action,
                               String notificationArn, String customData,
                               String externalEntityLink, double expires) {
        try {
            ObjectNode message = mapper.createObjectNode();
            String consoleLink = CONSOLE_URL + "?region=" + execution.getRegion()
                    + "#/view/" + execution.getPipelineName();
            message.put("region", execution.getRegion());
            message.put("consoleLink", consoleLink);
            ObjectNode approval = message.putObject("approval");
            approval.put("pipelineName", execution.getPipelineName());
            approval.put("stageName", action.getStageName());
            approval.put("actionName", action.getActionName());
            approval.put("token", action.getToken());
            approval.put("expires", MINUTE_FORMAT.format(Instant.ofEpochSecond((long) expires)));
            approval.put("approvalReviewLink", consoleLink + "/" + action.getStageName()
                    + "/" + action.getActionName() + "/approve/" + action.getToken());
            if (customData != null && !customData.isBlank()) {
                approval.put("customData", customData);
            }
            if (externalEntityLink != null && !externalEntityLink.isBlank()) {
                approval.put("externalEntityLink", externalEntityLink);
            }
            String subject = "APPROVAL NEEDED: AWS CodePipeline " + execution.getPipelineName()
                    + " for stage " + action.getStageName()
                    + " action " + action.getActionName();
            String truncated = subject.length() > MAX_SNS_SUBJECT_LENGTH
                    ? subject.substring(0, MAX_SNS_SUBJECT_LENGTH) : subject;
            RequestScopes.runAs(execution.getAccountId(), () -> snsService.publish(
                    notificationArn, null, message.toString(), truncated, execution.getRegion()));
        } catch (Exception e) {
            LOG.warnv("CodePipeline approval notification to {0} skipped: {1}",
                    notificationArn, e.getMessage());
        }
    }

    /** Epoch seconds (with fraction) as the ISO-8601 UTC timestamp CodePipeline emits, e.g. 2023-10-26T13:31:39.604Z. */
    private static String isoTimestamp(double epochSeconds) {
        return Instant.ofEpochMilli(Math.round(epochSeconds * 1000)).toString();
    }

    private ObjectNode baseDetail(CodePipelineExecution execution) {
        ObjectNode detail = mapper.createObjectNode();
        detail.put("pipeline", execution.getPipelineName());
        detail.put("execution-id", execution.getPipelineExecutionId());
        if (execution.getPipelineVersion() != null) {
            detail.put("version", execution.getPipelineVersion());
        }
        return detail;
    }

    private void publish(CodePipelineExecution execution, String detailType, ObjectNode detail) {
        try {
            Map<String, Object> entry = new HashMap<>();
            entry.put("Source", "aws.codepipeline");
            entry.put("DetailType", detailType);
            entry.put("Detail", detail.toString());
            entry.put("EventBusName", "default");
            entry.put("Region", execution.getRegion());
            // Always emit Resources (possibly empty): EventBridge pattern matching reads the
            // key unconditionally for rules with a "resources" filter.
            ArrayNode resources = mapper.createArrayNode();
            resources.add(AwsArnUtils.Arn.of("codepipeline", execution.getRegion(),
                    execution.getAccountId(), execution.getPipelineName()).toString());
            entry.put("Resources", resources);
            // This runs on a pipeline-execution worker thread with no request context, so the
            // two-argument overload would fall back to the default (management) account and a
            // member account's EventBridge rules would never see the event. Name the bus's
            // account explicitly.
            eventBridgeService.putEvents(List.of(entry), execution.getRegion(),
                    execution.getAccountId());
        } catch (Exception e) {
            LOG.warnv("CodePipeline event {0} not published: {1}", detailType, e.getMessage());
        }
    }
}
