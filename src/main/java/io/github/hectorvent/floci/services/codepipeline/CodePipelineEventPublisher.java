package io.github.hectorvent.floci.services.codepipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution;
import io.github.hectorvent.floci.services.codepipeline.model.CodePipelineExecution.ActionExecution;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.sns.SnsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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
            detail.put("start-time", execution.getStartTime());
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
        if (action.getExternalExecutionId() != null || action.getErrorDetails() != null) {
            ObjectNode result = detail.putObject("execution-result");
            if (action.getExternalExecutionId() != null) {
                result.put("external-execution-id", action.getExternalExecutionId());
            }
            if (action.getSummary() != null) {
                result.put("external-execution-summary", action.getSummary());
            }
            if (action.getErrorDetails() != null) {
                result.put("error-code", String.valueOf(action.getErrorDetails().get("code")));
            }
        }
        publish(execution, "CodePipeline Action Execution State Change", detail);
    }

    /** The SNS notification a Manual approval action sends when it starts waiting. */
    public void approvalNeeded(CodePipelineExecution execution, ActionExecution action,
                               String notificationArn, String customData, double expires) {
        try {
            ObjectNode message = mapper.createObjectNode();
            message.put("region", execution.getRegion());
            message.put("consoleLink", "http://localhost:4566/_floci/codepipeline/"
                    + execution.getPipelineName());
            ObjectNode approval = message.putObject("approval");
            approval.put("pipelineName", execution.getPipelineName());
            approval.put("stageName", action.getStageName());
            approval.put("actionName", action.getActionName());
            approval.put("token", action.getToken());
            approval.put("expires", expires);
            if (customData != null && !customData.isBlank()) {
                approval.put("customData", customData);
            }
            approval.put("approvalReviewLink", "http://localhost:4566/_floci/codepipeline/"
                    + execution.getPipelineName() + "/approvals");
            String subject = "APPROVAL NEEDED: AWS CodePipeline " + execution.getPipelineName()
                    + " for stage " + action.getStageName()
                    + " action " + action.getActionName();
            snsService.publish(notificationArn, null, message.toString(), subject, execution.getRegion());
        } catch (Exception e) {
            LOG.warnf("CodePipeline approval notification to %s skipped: %s",
                    notificationArn, e.getMessage());
        }
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
            resources.add("arn:aws:codepipeline:" + execution.getRegion() + ":"
                    + execution.getAccountId() + ":" + execution.getPipelineName());
            entry.put("Resources", resources);
            eventBridgeService.putEvents(List.of(entry), execution.getRegion());
        } catch (Exception e) {
            LOG.warnf("CodePipeline event %s not published: %s", detailType, e.getMessage());
        }
    }
}
