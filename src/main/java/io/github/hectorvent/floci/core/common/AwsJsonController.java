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

    private final ObjectMapper objectMapper;
    private final ResolvedServiceCatalog catalog;
    private final RegionResolver regionResolver;
    private final DynamoDbJsonHandler dynamoDbJsonHandler;
    private final DynamoDbStreamsJsonHandler dynamoDbStreamsJsonHandler;
    private final SqsJsonHandler sqsJsonHandler;
    private final SnsJsonHandler snsJsonHandler;
    private final StepFunctionsJsonHandler sfnJsonHandler;
    private final CloudWatchMetricsJsonHandler cloudWatchMetricsJsonHandler;

    @Inject
    public AwsJsonController(ObjectMapper objectMapper, ResolvedServiceCatalog catalog,
                             RegionResolver regionResolver,
                             DynamoDbJsonHandler dynamoDbJsonHandler,
                             DynamoDbStreamsJsonHandler dynamoDbStreamsJsonHandler,
                             SqsJsonHandler sqsJsonHandler, SnsJsonHandler snsJsonHandler,
                             StepFunctionsJsonHandler sfnJsonHandler,
                             CloudWatchMetricsJsonHandler cloudWatchMetricsJsonHandler) {
        this.objectMapper = objectMapper;
        this.catalog = catalog;
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

        ServiceCatalog.TargetMatch targetMatch = catalog.matchTarget(target).orElse(null);
        if (targetMatch == null) {
            return JsonErrorResponseUtils.createUnknownOperationErrorResponse(target);
        }

        String serviceKey = targetMatch.descriptor().externalKey();
        String action = targetMatch.action();
        LOG.debugv("{0} JSON action: {1}", serviceKey, action);

        Response response;
        try {
            JsonNode request = objectMapper.readTree(body);
            String region = regionResolver.resolveRegion(httpHeaders);

            response = switch (serviceKey) {
                case "dynamodb" -> {
                    if (targetMatch.prefix().startsWith("DynamoDBStreams_")) {
                        yield dynamoDbStreamsJsonHandler.handle(action, request, region);
                    }
                    yield dynamoDbJsonHandler.handle(action, request, region);
                }
                case "sqs" -> sqsJsonHandler.handle(action, request, region);
                case "sns" -> snsJsonHandler.handle(action, request, region);
                case "states" -> sfnJsonHandler.handle(action, request, region);
                case "monitoring" -> cloudWatchMetricsJsonHandler.handle(action, request, region);
                default -> null;
            };
            // catalog.matchTarget is protocol-agnostic: a JSON 1.1 target
            // (e.g. AmazonSSM.*) can match here under @Consumes json-1.0.
            // Return the AWS-style unknown-operation error rather than null.
            if (response == null) {
                return JsonErrorResponseUtils.createUnknownOperationErrorResponse(target);
            }
        } catch (AwsException e) {
            response = JsonErrorResponseUtils.createErrorResponse(e);
        } catch (Exception e) {
            LOG.error("Error processing " + serviceKey + " JSON request", e);
            response = JsonErrorResponseUtils.createErrorResponse(e);
        }

        // Real AWS DynamoDB attaches X-Amz-Crc32 to every response. The Go SDK DynamoDB
        // client verifies this header on body Close() and logs "failed to close HTTP
        // response body" when the header is missing — attach it here at the JSON protocol
        // boundary so other callers of DynamoDbJsonHandler (CBOR, API Gateway proxy,
        // Step Functions tasks) keep their original ObjectNode entity.
        if ("dynamodb".equals(serviceKey)) {
            return DynamoDbResponses.withCrc32(response, objectMapper);
        }
        return response;
    }
}
