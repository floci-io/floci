package io.github.hectorvent.floci.services.firehose;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ProcessingConfiguration;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Processor;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.ProcessorParameter;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The semantic checks AWS runs on a ProcessingConfiguration once its modeled
 * constraints hold, in the order and with the wording probed against real AWS
 * (us-west-2, 2026-09-10).
 *
 * Deliberately absent: AWS does not range-check NumberOfRetries at configuration
 * time, accepting 0 and 9 alike despite the documented 1 to 8, and it does not
 * check that the function the LambdaArn names exists, unlike the Glue table a
 * conversion schema names. Both were probed; neither is enforced here.
 */
final class ProcessingConfigurationValidator {

    /**
     * Lambda's own ARN shape rather than "anything without a colon": probed, AWS
     * answers "Invalid lambda ARN." for a function name carrying a space or slashes,
     * which a looser pattern would have stored.
     */
    private static final Pattern LAMBDA_ARN = Pattern.compile(
            "arn:aws[a-zA-Z-]*:lambda:[a-z0-9-]+:\\d{12}:function:[a-zA-Z0-9_-]{1,64}"
                    + "(:(\\$LATEST|[a-zA-Z0-9_-]{1,128}))?");

    private ProcessingConfigurationValidator() {}

    static void validateEffective(S3Destination config) {
        if (config == null || config.getProcessingConfiguration() == null) {
            return;
        }
        ProcessingConfiguration processing = config.getProcessingConfiguration();
        if (processing.getProcessors() == null) {
            return;
        }
        // Before anything a processor's own parameters say: a repeated name is rejected
        // even when the LambdaArn the processor needs is missing too (probed).
        int lambdaProcessors = 0;
        for (Processor processor : processing.getProcessors()) {
            if (processor == null) {
                continue;
            }
            rejectDuplicateParameters(processor.getParameters());
            if ("Lambda".equals(processor.getType())) {
                lambdaProcessors++;
            }
        }
        if (lambdaProcessors > 1) {
            throw invalidArgument("Cannot have more than 1 Lambda processor.");
        }
        for (Processor processor : processing.getProcessors()) {
            if (processor == null || !"Lambda".equals(processor.getType())) {
                continue;
            }
            validateLambdaProcessor(processor.getParameters());
        }
    }

    private static void rejectDuplicateParameters(List<ProcessorParameter> parameters) {
        if (parameters == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (ProcessorParameter parameter : parameters) {
            if (parameter != null && parameter.getParameterName() != null
                    && !seen.add(parameter.getParameterName())) {
                throw invalidArgument("Duplicate ProcessorParameter passed to ProcessingConfiguration.");
            }
        }
    }

    private static void validateLambdaProcessor(List<ProcessorParameter> parameters) {
        String lambdaArn = parameterValue(parameters, "LambdaArn");
        if (lambdaArn == null || lambdaArn.isEmpty()) {
            throw invalidArgument("LambdaArn is required when Lambda processor is used.");
        }
        if (!LAMBDA_ARN.matcher(lambdaArn).matches()) {
            throw invalidArgument("Invalid lambda ARN.");
        }
        boolean hasSize = parameterValue(parameters, "BufferSizeInMBs") != null;
        boolean hasInterval = parameterValue(parameters, "BufferIntervalInSeconds") != null;
        if (hasSize != hasInterval) {
            throw invalidArgument("Both BufferSizeInMBs and BufferIntervalInSeconds are required"
                    + " to configure buffering for lambda processor.");
        }
    }

    private static String parameterValue(List<ProcessorParameter> parameters, String name) {
        if (parameters == null) {
            return null;
        }
        for (ProcessorParameter parameter : parameters) {
            if (parameter != null && name.equals(parameter.getParameterName())) {
                return parameter.getParameterValue();
            }
        }
        return null;
    }

    private static AwsException invalidArgument(String message) {
        return new AwsException("InvalidArgumentException", message, 400);
    }
}
