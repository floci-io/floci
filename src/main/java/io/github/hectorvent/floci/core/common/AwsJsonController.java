package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamsJsonHandler;
import io.github.hectorvent.floci.services.sns.SnsJsonHandler;
import io.github.hectorvent.floci.services.sqs.SqsJsonHandler;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Map;

import io.github.hectorvent.floci.services.stepfunctions.StepFunctionsJsonHandler;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
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
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

    private static final String DYNAMODB_TARGET_PREFIX = "DynamoDB_20120810.";
    private static final String DYNAMODB_STREAMS_TARGET_PREFIX = "DynamoDBStreams_20120810.";
    private static final String SQS_TARGET_PREFIX = "AmazonSQS.";
    private static final String SNS_TARGET_PREFIX = "SNS_20100331.";
    private static final String STEPFUNCTIONS_TARGET_PREFIX = "AWSStepFunctions.";
    private static final String CLOUDWATCH_TARGET_PREFIX = "GraniteServiceVersion20100801.";
    private static final String CLOUDWATCH_SERVICE_ID = "GraniteServiceVersion20100801";

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
    @Produces(MediaType.APPLICATION_JSON)
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
            action = target.substring(prefix.length());
            serviceName = "DynamoDBStreams";
        } else if (target.startsWith(DYNAMODB_TARGET_PREFIX)) {
            prefix = DYNAMODB_TARGET_PREFIX;
            action = target.substring(prefix.length());
            serviceName = "DynamoDB";
        } else if (target.startsWith(SQS_TARGET_PREFIX)) {
            prefix = SQS_TARGET_PREFIX;
            action = target.substring(prefix.length());
            serviceName = "SQS";
        } else if (target.startsWith(SNS_TARGET_PREFIX)) {
            prefix = SNS_TARGET_PREFIX;
            action = target.substring(prefix.length());
            serviceName = "SNS";
        } else if (target.startsWith(STEPFUNCTIONS_TARGET_PREFIX)) {
            prefix = STEPFUNCTIONS_TARGET_PREFIX;
            action = target.substring(prefix.length());
            serviceName = "StepFunctions";
        } else if (target.startsWith(CLOUDWATCH_TARGET_PREFIX)) {
            prefix = CLOUDWATCH_TARGET_PREFIX;
            action = target.substring(prefix.length());
            serviceName = "CloudWatch";
        } else {
            return Response.status(400)
                    .entity(new AwsErrorResponse("UnknownOperationException",
                            "Unknown operation: " + target))
                    .build();
        }

        LOG.debugv("{0} JSON action: {1}", serviceName, action);

        try {
            JsonNode request = objectMapper.readTree(body);
            String region = regionResolver.resolveRegion(httpHeaders);

            return switch (serviceName) {
                case "DynamoDB" -> dynamoDbJsonHandler.handle(action, request, region);
                case "DynamoDBStreams" -> dynamoDbStreamsJsonHandler.handle(action, request, region);
                case "SQS" -> sqsJsonHandler.handle(action, request, region);
                case "SNS" -> snsJsonHandler.handle(action, request, region);
                case "StepFunctions" -> sfnJsonHandler.handle(action, request, region);
                case "CloudWatch" -> cloudWatchMetricsJsonHandler.handle(action, request, region);
                default -> null;
            };
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus())
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AwsErrorResponse(e.jsonType(), e.getMessage()))
                    .build();
        } catch (Exception e) {
            LOG.error("Error processing " + serviceName + " JSON request", e);
            return Response.status(500)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new AwsErrorResponse("InternalServerError", e.getMessage()))
                    .build();
        }
    }

    /**
     * Serializes a JsonNode to CBOR bytes, encoding fields named "Timestamp" with CBOR tag 1
     * as required by the smithy-rpc-v2-cbor protocol specification.
     */
    private static byte[] nodeToSmithyCbor(JsonNode node) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORFactory factory = (CBORFactory) CBOR_MAPPER.getFactory();
        try (CBORGenerator gen = factory.createGenerator(out)) {
            writeNodeToCbor(gen, node, null);
        }
        return out.toByteArray();
    }

    private static void writeNodeToCbor(CBORGenerator gen, JsonNode node, String fieldName) throws Exception {
        if (node.isObject()) {
            gen.writeStartObject();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                gen.writeFieldName(entry.getKey());
                writeNodeToCbor(gen, entry.getValue(), entry.getKey());
            }
            gen.writeEndObject();
        } else if (node.isArray()) {
            gen.writeStartArray();
            for (JsonNode item : node) {
                writeNodeToCbor(gen, item, null);
            }
            gen.writeEndArray();
        } else if ("Timestamp".equals(fieldName) && node.isNumber()) {
            gen.writeTag(1);
            gen.writeNumber(node.longValue());
        } else if (node.isTextual()) {
            gen.writeString(node.textValue());
        } else if (node.isDouble() || node.isFloat()) {
            gen.writeNumber(node.doubleValue());
        } else if (node.isLong() || node.isInt()) {
            gen.writeNumber(node.longValue());
        } else if (node.isBoolean()) {
            gen.writeBoolean(node.booleanValue());
        } else if (node.isNull()) {
            gen.writeNull();
        } else {
            gen.writeString(node.asText());
        }
    }

    /**
     * Handles AWS smithy-rpc-v2-cbor protocol requests.
     * AWS SDK v2 sends to POST /service/{sdkId}/operation/{op}
     * with Content-Type: application/cbor and no X-Amz-Target header.
     * Supported services: DynamoDB, SQS, SNS, StepFunctions, CloudWatch.
     */
    @POST
    @Path("service/{serviceId}/operation/{operation}")
    @Consumes("application/cbor")
    @Produces("application/cbor")
    public Response handleSmithyRpcV2Cbor(
            @PathParam("serviceId") String serviceId,
            @PathParam("operation") String operation,
            @Context HttpHeaders httpHeaders,
            byte[] body) {

        LOG.debugv("Smithy RPC v2 CBOR: service={0}, operation={1}", serviceId, operation);

        try {
            JsonNode request = (body != null && body.length > 0)
                    ? CBOR_MAPPER.readTree(body)
                    : objectMapper.createObjectNode();
            String region = regionResolver.resolveRegion(httpHeaders);

            Response delegated = dispatchCbor(serviceId, operation, request, region);
            if (delegated == null) {
                return Response.status(404).build();
            }

            byte[] cborBytes = nodeToSmithyCbor((JsonNode) delegated.getEntity());
            return Response.status(delegated.getStatus())
                    .header("Smithy-Protocol", "rpc-v2-cbor")
                    .type("application/cbor")
                    .entity(cborBytes)
                    .build();
        } catch (AwsException e) {
            return cborErrorResponse(e, "Smithy-Protocol");
        } catch (Exception e) {
            LOG.error("Error processing Smithy CBOR request: " + serviceId + "." + operation, e);
            return Response.status(500).build();
        }
    }

    /**
     * Handles AWS services that migrated to the smithy-rpc-v2-cbor protocol at root path.
     * Fallback handler for X-Amz-Target based routing with CBOR body.
     */
    @POST
    @Consumes("application/cbor")
    @Produces("application/cbor")
    public Response handleCborRequest(
            @HeaderParam("X-Amz-Target") String target,
            @Context HttpHeaders httpHeaders,
            byte[] body) {

        if (target == null) {
            return null;
        }

        String prefix;
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
            return null;
        }

        String action = target.substring(prefix.length());
        LOG.debugv("{0} CBOR action: {1}", serviceName, action);

        try {
            JsonNode request = (body != null && body.length > 0)
                    ? CBOR_MAPPER.readTree(body)
                    : objectMapper.createObjectNode();
            String region = regionResolver.resolveRegion(httpHeaders);

            Response delegated = switch (serviceName) {
                case "DynamoDB" -> dynamoDbJsonHandler.handle(action, request, region);
                case "DynamoDBStreams" -> dynamoDbStreamsJsonHandler.handle(action, request, region);
                case "SQS" -> sqsJsonHandler.handle(action, request, region);
                case "SNS" -> snsJsonHandler.handle(action, request, region);
                case "StepFunctions" -> sfnJsonHandler.handle(action, request, region);
                case "CloudWatch" -> cloudWatchMetricsJsonHandler.handle(action, request, region);
                default -> null;
            };
            if (delegated == null) {
                return null;
            }

            byte[] cborBytes = nodeToSmithyCbor((JsonNode) delegated.getEntity());
            return Response.status(delegated.getStatus())
                    .header("smithy-protocol", "rpc-v2-cbor")
                    .type("application/cbor")
                    .entity(cborBytes)
                    .build();
        } catch (AwsException e) {
            return cborErrorResponse(e, "smithy-protocol");
        } catch (Exception e) {
            LOG.error("Error processing CBOR request: " + serviceName + "." + action, e);
            return Response.status(500).build();
        }
    }

    /** Dispatches a CBOR request to the appropriate service handler by SDK service ID. */
    private Response dispatchCbor(String serviceId, String operation, JsonNode request, String region) throws Exception {
        return switch (serviceId) {
            case "DynamoDB" -> dynamoDbJsonHandler.handle(operation, request, region);
            case "DynamoDB Streams" -> dynamoDbStreamsJsonHandler.handle(operation, request, region);
            case "SQS" -> sqsJsonHandler.handle(operation, request, region);
            case "SNS" -> snsJsonHandler.handle(operation, request, region);
            case "SFN" -> sfnJsonHandler.handle(operation, request, region);
            case CLOUDWATCH_SERVICE_ID -> cloudWatchMetricsJsonHandler.handle(operation, request, region);
            default -> null;
        };
    }

    private Response cborErrorResponse(AwsException e, String protocolHeader) {
        try {
            byte[] errBytes = CBOR_MAPPER.writeValueAsBytes(
                    new AwsErrorResponse(e.jsonType(), e.getMessage()));
            return Response.status(e.getHttpStatus())
                    .header(protocolHeader, "rpc-v2-cbor")
                    .type("application/cbor")
                    .entity(errBytes)
                    .build();
        } catch (Exception ex) {
            return Response.status(e.getHttpStatus()).build();
        }
    }
}
