package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
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

import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Map;

/**
 * Generic dispatcher for all AWS services that use the application/cbor protocol.
 * Routes requests to the appropriate service handler based on the X-Amz-Target header prefix.
 * <p>
 * Currently supported services:
 * - DynamoDB (DynamoDB_20120810.*)
 * - SQS (AmazonSQS.*)
 */
@Path("/")
public class AwsJsonCborController {

    private static final Logger LOG = Logger.getLogger(AwsJsonCborController.class);
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());

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
    public AwsJsonCborController(ObjectMapper objectMapper, ResolvedServiceCatalog catalog,
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
            // Smithy rpc-v2-cbor timestamps are epoch seconds encoded as tagged floating-point numbers.
            gen.writeNumber(node.doubleValue());
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

            JsonNode responseNode = delegated.getEntity() instanceof JsonNode
                    ? (JsonNode) delegated.getEntity()
                    : objectMapper.valueToTree(delegated.getEntity());
            byte[] cborBytes = nodeToSmithyCbor(responseNode);
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

        // Upstream CBOR behavior is to return null for targets this controller
        // does not dispatch (JAX-RS then serves 204). The JSON 1.0/1.1
        // controllers return UnknownOperationException instead; CBOR stays on
        // null here to preserve pre-refactor semantics.
        ServiceCatalog.TargetMatch targetMatch = catalog.matchTarget(target).orElse(null);
        if (targetMatch == null) {
            return null;
        }

        String serviceKey = targetMatch.descriptor().externalKey();
        String action = targetMatch.action();
        LOG.debugv("{0} CBOR action: {1}", serviceKey, action);

        try {
            JsonNode request = (body != null && body.length > 0)
                    ? CBOR_MAPPER.readTree(body)
                    : objectMapper.createObjectNode();
            String region = regionResolver.resolveRegion(httpHeaders);

            Response delegated = switch (serviceKey) {
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
            if (delegated == null) {
                return null;
            }

            JsonNode responseNode = delegated.getEntity() instanceof JsonNode
                    ? (JsonNode) delegated.getEntity()
                    : objectMapper.valueToTree(delegated.getEntity());
            byte[] cborBytes = nodeToSmithyCbor(responseNode);
            return Response.status(delegated.getStatus())
                    .header("smithy-protocol", "rpc-v2-cbor")
                    .type("application/cbor")
                    .entity(cborBytes)
                    .build();
        } catch (AwsException e) {
            return cborErrorResponse(e, "smithy-protocol");
        } catch (Exception e) {
            LOG.error("Error processing CBOR request: " + serviceKey + "." + action, e);
            return Response.status(500).build();
        }
    }

    /**
     * Dispatches a CBOR request to the appropriate service handler by SDK service ID.
     */
    private Response dispatchCbor(String serviceId, String operation, JsonNode request, String region) throws Exception {
        ServiceDescriptor descriptor = catalog.byCborSdkServiceId(serviceId).orElse(null);
        if (descriptor == null) {
            return null;
        }
        return switch (descriptor.externalKey()) {
            case "dynamodb" -> {
                if ("DynamoDB Streams".equals(serviceId)) {
                    yield dynamoDbStreamsJsonHandler.handle(operation, request, region);
                }
                yield dynamoDbJsonHandler.handle(operation, request, region);
            }
            case "sqs" -> sqsJsonHandler.handle(operation, request, region);
            case "sns" -> snsJsonHandler.handle(operation, request, region);
            case "states" -> sfnJsonHandler.handle(operation, request, region);
            case "monitoring" -> cloudWatchMetricsJsonHandler.handle(operation, request, region);
            default -> null;
        };
    }

    private Response cborErrorResponse(AwsException e, String protocolHeader) {
        try {
            byte[] errBytes = CBOR_MAPPER.writeValueAsBytes(
                    new AwsErrorResponse(e.jsonType(), e.getMessage()));
            String queryErrorFault = (e.getHttpStatus() < 500) ? "Sender" : "Receiver";
            return Response.status(e.getHttpStatus())
                    .header(protocolHeader, "rpc-v2-cbor")
                    .header("x-amzn-query-error", e.getErrorCode() + ";" + queryErrorFault)
                    .type("application/cbor")
                    .entity(errBytes)
                    .build();
        } catch (Exception ex) {
            return Response.status(e.getHttpStatus()).build();
        }
    }
}
