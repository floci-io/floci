package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbResponses;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamsJsonHandler;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsJsonHandler;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Generic dispatcher for all AWS services that use the application/x-amz-json-1.0 protocol.
 * Routes requests to the appropriate service handler based on the X-Amz-Target header prefix.
 * <p>
 * Currently supported services:
 * - DynamoDB (DynamoDB_20120810.*)
 * - SQS (AmazonSQS.*)
 */
@Path("/")
public class AwsJsonController {

    private static final Logger LOG = Logger.getLogger(AwsJsonController.class);

    private static final String DYNAMODB_TARGET_PREFIX = "DynamoDB_20120810.";
    private static final String DYNAMODB_STREAMS_TARGET_PREFIX = "DynamoDBStreams_20120810.";
    private static final String SQS_TARGET_PREFIX = "AmazonSQS.";
    private static final String SNS_TARGET_PREFIX = "SNS_20100331.";
    private static final String STEPFUNCTIONS_TARGET_PREFIX = "AWSStepFunctions.";
    private static final String CLOUDWATCH_TARGET_PREFIX = "GraniteServiceVersion20100801.";

    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;
    private final DynamoDbJsonHandler dynamoDbJsonHandler;
    private final DynamoDbStreamsJsonHandler dynamoDbStreamsJsonHandler;
    private final SqsJsonHandler sqsJsonHandler;
    private final SnsJsonHandler snsJsonHandler;
    private final StepFunctionsJsonHandler sfnJsonHandler;
    private final CloudWatchMetricsJsonHandler cloudWatchMetricsJsonHandler;

    @Inject
    public AwsJsonController(ObjectMapper objectMapper, RegionResolver regionResolver,
                             DynamoDbJsonHandler dynamoDbJsonHandler,
                             DynamoDbStreamsJsonHandler dynamoDbStreamsJsonHandler,
                             SqsJsonHandler sqsJsonHandler, SnsJsonHandler snsJsonHandler,
                             StepFunctionsJsonHandler sfnJsonHandler,
                             CloudWatchMetricsJsonHandler cloudWatchMetricsJsonHandler) {
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
        this.dynamoDbJsonHandler = dynamoDbJsonHandler;
        this.dynamoDbStreamsJsonHandler = dynamoDbStreamsJsonHandler;
        this.sqsJsonHandler = sqsJsonHandler;
        this.snsJsonHandler = snsJsonHandler;
        this.sfnJsonHandler = sfnJsonHandler;
        this.cloudWatchMetricsJsonHandler = cloudWatchMetricsJsonHandler;
    }

    @POST
    @Consumes("application/x-amz-json-1.0")
    @Produces("application/x-amz-json-1.0")
    public Response handleJsonRequest(
            @HeaderParam("X-Amz-Target") String target,
            @Context HttpHeaders httpHeaders,
            String body) {

        if (target == null) {
            return null;
        }

        String prefix;
        String action;
        String serviceName;

        if (target.startsWith(DYNAMODB_STREAMS_TARGET_PREFIX)) {
            prefix = DYNAMODB_STREAMS_TARGET_PREFIX;
            serviceName = "DynamoDBStreams";
        } else if (target.startsWith(DYNAMODB_TARGET_PREFIX)) {
            prefix = DYNAMODB_TARGET_PREFIX;
            serviceName = "DynamoDB";
        } else if (target.startsWith(SQS_TARGET_PREFIX)) {
            prefix = SQS_TARGET_PREFIX;
            serviceName = "SQS";
        } else if (target.startsWith(SNS_TARGET_PREFIX)) {
            prefix = SNS_TARGET_PREFIX;
            serviceName = "SNS";
        } else if (target.startsWith(STEPFUNCTIONS_TARGET_PREFIX)) {
            prefix = STEPFUNCTIONS_TARGET_PREFIX;
            serviceName = "StepFunctions";
        } else if (target.startsWith(CLOUDWATCH_TARGET_PREFIX)) {
            prefix = CLOUDWATCH_TARGET_PREFIX;
            serviceName = "CloudWatch";
        } else {
            return JsonErrorResponseUtils.createUnknownOperationErrorResponse(target);
        }

        action = target.substring(prefix.length());
        LOG.debugv("{0} JSON action: {1}", serviceName, action);

        Response response;
        try {
            JsonNode request = objectMapper.readTree(body);
            String region = regionResolver.resolveRegion(httpHeaders);

            response = switch (serviceName) {
                case "DynamoDB" -> dynamoDbJsonHandler.handle(action, request, region);
                case "DynamoDBStreams" -> dynamoDbStreamsJsonHandler.handle(action, request, region);
                case "SQS" -> sqsJsonHandler.handle(action, request, region);
                case "SNS" -> snsJsonHandler.handle(action, request, region);
                case "StepFunctions" -> sfnJsonHandler.handle(action, request, region);
                case "CloudWatch" -> cloudWatchMetricsJsonHandler.handle(action, request, region);
                default -> null;
            };
        } catch (AwsException e) {
            response = JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            LOG.error("Error processing " + serviceName + " JSON request", e);
            response = JsonErrorResponseUtils.createErrorResponse(e);
        }

        // Real AWS DynamoDB attaches X-Amz-Crc32 to every response. The Go SDK DynamoDB
        // client verifies this header on body Close() and logs "failed to close HTTP
        // response body" when the header is missing — attach it here at the JSON protocol
        // boundary so other callers of DynamoDbJsonHandler (CBOR, API Gateway proxy,
        // Step Functions tasks) keep their original ObjectNode entity.
        if ("DynamoDB".equals(serviceName) || "DynamoDBStreams".equals(serviceName)) {
            return DynamoDbResponses.withCrc32(response, objectMapper);
        }
        return response;
    }
}
