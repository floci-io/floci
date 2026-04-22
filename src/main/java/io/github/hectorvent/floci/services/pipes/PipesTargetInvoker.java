package io.github.hectorvent.floci.services.pipes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.pipes.model.Pipe;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class PipesTargetInvoker {

    private static final Logger LOG = Logger.getLogger(PipesTargetInvoker.class);

    private final LambdaService lambdaService;
    private final SqsService sqsService;
    private final SnsService snsService;
    private final EventBridgeService eventBridgeService;
    private final StepFunctionsService stepFunctionsService;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    @Inject
    public PipesTargetInvoker(LambdaService lambdaService,
                              SqsService sqsService,
                              SnsService snsService,
                              EventBridgeService eventBridgeService,
                              StepFunctionsService stepFunctionsService,
                              ObjectMapper objectMapper,
                              EmulatorConfig config) {
        this.lambdaService = lambdaService;
        this.sqsService = sqsService;
        this.snsService = snsService;
        this.eventBridgeService = eventBridgeService;
        this.stepFunctionsService = stepFunctionsService;
        this.objectMapper = objectMapper;
        this.baseUrl = config.effectiveBaseUrl();
    }

    public void invoke(Pipe pipe, String payload, String region) {
        String targetArn = pipe.getTarget();
        try {
            if (targetArn.contains(":lambda:") || targetArn.contains(":function:")) {
                invokeLambda(targetArn, payload, region);
            } else if (targetArn.contains(":sqs:")) {
                invokeSqs(targetArn, payload);
            } else if (targetArn.contains(":sns:")) {
                invokeSns(targetArn, payload, region);
            } else if (targetArn.contains(":events:")) {
                invokeEventBridge(targetArn, payload, region);
            } else if (targetArn.contains(":states:")) {
                invokeStepFunctions(targetArn, payload, region);
            } else {
                LOG.warnv("Pipe {0}: unsupported target ARN type: {1}", pipe.getName(), targetArn);
            }
        } catch (Exception e) {
            LOG.warnv("Pipe {0}: failed to deliver to target {1}: {2}",
                    pipe.getName(), targetArn, e.getMessage());
        }
    }

    private void invokeLambda(String arn, String payload, String region) {
        String fnName = arn.substring(arn.lastIndexOf(':') + 1);
        String fnRegion = extractRegionFromArn(arn, region);
        lambdaService.invoke(fnRegion, fnName, payload.getBytes(), InvocationType.RequestResponse);
        LOG.debugv("Pipe delivered to Lambda: {0}", arn);
    }

    private void invokeSqs(String arn, String payload) {
        String queueUrl = AwsArnUtils.arnToQueueUrl(arn, baseUrl);
        sqsService.sendMessage(queueUrl, payload, 0);
        LOG.debugv("Pipe delivered to SQS: {0}", arn);
    }

    private void invokeSns(String arn, String payload, String region) {
        String topicRegion = extractRegionFromArn(arn, region);
        snsService.publish(arn, null, payload, "Pipes", topicRegion);
        LOG.debugv("Pipe delivered to SNS: {0}", arn);
    }

    private void invokeEventBridge(String arn, String payload, String region) {
        String busName = arn.substring(arn.lastIndexOf('/') + 1);
        String ebRegion = extractRegionFromArn(arn, region);
        Map<String, Object> entry = Map.of(
                "EventBusName", busName,
                "Source", "aws.pipes",
                "DetailType", "PipeForwarded",
                "Detail", payload
        );
        eventBridgeService.putEvents(List.of(entry), ebRegion);
        LOG.debugv("Pipe delivered to EventBridge: {0}", arn);
    }

    private void invokeStepFunctions(String arn, String payload, String region) {
        String sfnRegion = extractRegionFromArn(arn, region);
        String executionName = "pipes-" + UUID.randomUUID();
        stepFunctionsService.startExecution(arn, executionName, payload, sfnRegion);
        LOG.debugv("Pipe delivered to Step Functions: {0}", arn);
    }

    private static String extractRegionFromArn(String arn, String defaultRegion) {
        String[] parts = arn.split(":");
        return parts.length >= 4 && !parts[3].isEmpty() ? parts[3] : defaultRegion;
    }
}
