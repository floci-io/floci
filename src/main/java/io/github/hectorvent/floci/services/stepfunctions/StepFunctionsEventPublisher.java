package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.stepfunctions.model.Execution;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes AWS-shaped {@code aws.states} "Step Functions Execution Status Change" events to the
 * default EventBridge bus. AWS emits them for STANDARD workflows only, one per status the
 * execution enters: RUNNING, SUCCEEDED, FAILED, TIMED_OUT and ABORTED.
 *
 * <p>The event's account is left to the caller's request context, which is the execution's own
 * account on every path that emits: the StartExecution and StopExecution requests, and the worker
 * thread that runs under the execution's account. The region is taken from the execution ARN.
 */
@ApplicationScoped
public class StepFunctionsEventPublisher {

    private static final Logger LOG = Logger.getLogger(StepFunctionsEventPublisher.class);
    static final String SOURCE = "aws.states";
    static final String DETAIL_TYPE = "Step Functions Execution Status Change";
    // AWS's documented EventBridge quota rule: when the escaped input and escaped output together
    // exceed 248 KiB the input is left out, and when the escaped output alone exceeds it the output
    // is left out too. inputDetails/outputDetails.included report which ones were.
    static final long MAX_PAYLOAD_BYTES = 248L * 1024;

    private final EventBridgeService eventBridgeService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public StepFunctionsEventPublisher(EventBridgeService eventBridgeService,
                                       RegionResolver regionResolver,
                                       ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Emits one status change event carrying the execution's current fields. Never throws: a
     * failure to publish is logged and the execution carries on.
     */
    public void emitStatusChange(Execution exec) {
        String executionArn = exec.getExecutionArn();
        try {
            ObjectNode detail = buildDetail(exec);
            ArrayNode resources = objectMapper.createArrayNode();
            if (executionArn != null) {
                resources.add(executionArn);
            }
            Map<String, Object> entry = new HashMap<>();
            entry.put("Source", SOURCE);
            entry.put("DetailType", DETAIL_TYPE);
            entry.put("Detail", objectMapper.writeValueAsString(detail));
            entry.put("Resources", resources);
            EventBridgeService.PutEventsResult result = eventBridgeService.putEvents(List.of(entry),
                    AwsArnUtils.regionOrDefault(executionArn, regionResolver.getDefaultRegion()));
            if (result != null && result.failedCount() > 0) {
                LOG.warnv("EventBridge rejected Step Functions Execution Status Change for {0}: {1}",
                        executionArn, result.entries());
            }
        } catch (Exception e) {
            LOG.warnv("Failed to emit Step Functions Execution Status Change for {0}: {1}",
                    executionArn, e.getMessage());
        }
    }

    /**
     * AWS emits status change events for STANDARD executions only. An EXPRESS execution's ARN is
     * minted under the {@code express:} resource namespace, a STANDARD one under {@code execution:}.
     */
    static boolean isStandard(Execution exec) {
        String arn = exec.getExecutionArn();
        if (arn == null || !AwsArnUtils.isArn(arn)) {
            return false;
        }
        return AwsArnUtils.parse(arn).resource().startsWith("execution:");
    }

    // The fields are read under the Execution's monitor, the one the worker and StopExecution take
    // for their terminal writes, so the event never pairs one status with another's stopDate.
    // Every key of the documented event is always present, null when it does not apply.
    private ObjectNode buildDetail(Execution exec) throws JsonProcessingException {
        ObjectNode detail = objectMapper.createObjectNode();
        synchronized (exec) {
            String status = exec.getStatus();
            boolean succeeded = "SUCCEEDED".equals(status);
            detail.put("executionArn", exec.getExecutionArn());
            detail.put("stateMachineArn", exec.getStateMachineArn());
            detail.put("name", exec.getName());
            detail.put("status", status);
            detail.put("startDate", toEpochMillis(exec.getStartDate()));
            if (exec.getStopDate() != null && !"RUNNING".equals(status)) {
                detail.put("stopDate", toEpochMillis(exec.getStopDate()));
            } else {
                detail.putNull("stopDate");
            }

            String output = succeeded ? exec.getOutput() : null;
            long outputSize = escapedSize(output);
            boolean outputIncluded = outputSize <= MAX_PAYLOAD_BYTES;
            boolean inputIncluded = escapedSize(exec.getInput()) + outputSize <= MAX_PAYLOAD_BYTES;
            detail.put("input", inputIncluded ? exec.getInput() : null);
            detail.putObject("inputDetails").put("included", inputIncluded);
            detail.put("output", outputIncluded ? output : null);
            if (succeeded) {
                detail.putObject("outputDetails").put("included", outputIncluded);
            } else {
                detail.putNull("outputDetails");
            }

            detail.put("stateMachineVersionArn", exec.getStateMachineVersionArn());
            detail.put("stateMachineAliasArn", exec.getStateMachineAliasArn());
            detail.put("redriveCount", 0);
            detail.putNull("redriveDate");
            detail.put("redriveStatus", redriveStatus(status));
            detail.put("redriveStatusReason", redriveStatusReason(status));
            detail.put("error", "TIMED_OUT".equals(status) && exec.getError() == null
                    ? "States.Timeout" : exec.getError());
            detail.put("cause", exec.getCause());
        }
        return detail;
    }

    /**
     * The size of {@code value} once escaped as a JSON string, the measure AWS applies its 248 KiB
     * rule to. A null value takes no room.
     */
    private long escapedSize(String value) throws JsonProcessingException {
        if (value == null) {
            return 0;
        }
        // writeValueAsBytes quotes the string; the quotes are not part of the payload.
        return objectMapper.writeValueAsBytes(value).length - 2L;
    }

    // Floci does not implement RedriveExecution, so these carry what AWS's documented events report
    // for an execution that was never redriven: FAILED and TIMED_OUT are redrivable, the rest are not.
    private static String redriveStatus(String status) {
        return "FAILED".equals(status) || "TIMED_OUT".equals(status) ? "REDRIVABLE" : "NOT_REDRIVABLE";
    }

    private static String redriveStatusReason(String status) {
        if (status == null) {
            return null;
        }
        return "FAILED".equals(status) || "TIMED_OUT".equals(status)
                ? "Execution is " + status + " and is redrivable"
                : "Execution is " + status + " and cannot be redriven";
    }

    private static long toEpochMillis(double epochSeconds) {
        return Math.round(epochSeconds * 1000.0);
    }
}
