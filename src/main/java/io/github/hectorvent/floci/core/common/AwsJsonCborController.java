package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import io.github.hectorvent.floci.services.cloudwatch.metrics.CloudWatchMetricsJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbJsonHandler;
import io.github.hectorvent.floci.services.dynamodb.DynamoDbStreamsJsonHandler;
import io.github.hectorvent.floci.services.kinesis.KinesisJsonHandler;
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
 * Generic dispatcher for all AWS services that use the application/cbor protocol,
 * via either smithy-rpc-v2-cbor path routing or the legacy X-Amz-Target header.
 * <p>
 * Currently supported services: DynamoDB (incl. Streams), SQS, SNS, Kinesis,
 * Step Functions, and CloudWatch Metrics.
 */
@Path("/")
public class AwsJsonCborController {

    private static final Logger LOG = Logger.getLogger(AwsJsonCborController.class);
    private static final ObjectMapper CBOR_MAPPER = new ObjectMapper(new CBORFactory());
    private static final String GENERIC_CBOR_MEDIA_TYPE = "application/cbor";
    private static final String AWS_CBOR_1_1_MEDIA_TYPE = "application/x-amz-cbor-1.1";

    private final ObjectMapper objectMapper;
    private final ResolvedServiceCatalog catalog;
    private final RegionResolver regionResolver;
    private final DynamoDbJsonHandler dynamoDbJsonHandler;
    private final DynamoDbStreamsJsonHandler dynamoDbStreamsJsonHandler;
    private final SqsJsonHandler sqsJsonHandler;
    private final SnsJsonHandler snsJsonHandler;
    private final KinesisJsonHandler kinesisJsonHandler;
    private final StepFunctionsJsonHandler sfnJsonHandler;
    private final CloudWatchMetricsJsonHandler cloudWatchMetricsJsonHandler;

    @Inject
    public AwsJsonCborController(ObjectMapper objectMapper, ResolvedServiceCatalog catalog,
                                 RegionResolver regionResolver,
                                 DynamoDbJsonHandler dynamoDbJsonHandler,
                                 DynamoDbStreamsJsonHandler dynamoDbStreamsJsonHandler,
                                 SqsJsonHandler sqsJsonHandler, SnsJsonHandler snsJsonHandler,
                                 KinesisJsonHandler kinesisJsonHandler,
                                 StepFunctionsJsonHandler sfnJsonHandler,
                                 CloudWatchMetricsJsonHandler cloudWatchMetricsJsonHandler) {
        this.objectMapper = objectMapper;
        this.catalog = catalog;
        this.regionResolver = regionResolver;
        this.dynamoDbJsonHandler = dynamoDbJsonHandler;
        this.dynamoDbStreamsJsonHandler = dynamoDbStreamsJsonHandler;
        this.sqsJsonHandler = sqsJsonHandler;
        this.snsJsonHandler = snsJsonHandler;
        this.kinesisJsonHandler = kinesisJsonHandler;
        this.sfnJsonHandler = sfnJsonHandler;
        this.cloudWatchMetricsJsonHandler = cloudWatchMetricsJsonHandler;
    }


    /**
     * Serializes a JsonNode to CBOR bytes, encoding timestamp shapes with CBOR tag 1
     * (epoch seconds, RFC 8949 section 3.4.2) as required by the smithy-rpc-v2-cbor
     * protocol specification.
     * <p>
     * Package-private so the timestamp-tagging behaviour can be unit-tested directly
     * without booting Quarkus.
     */
    static byte[] nodeToSmithyCbor(JsonNode node) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CBORFactory factory = (CBORFactory) CBOR_MAPPER.getFactory();
        try (CBORGenerator gen = factory.createGenerator(out)) {
            writeNodeToCbor(gen, node, false);
        }
        return out.toByteArray();
    }

    /**
     * Recursively writes a JsonNode to CBOR. The {@code numberIsTimestamp} flag carries
     * timestamp context down the tree (this serializer is schema-less, so it cannot look
     * up the Smithy shape): when set and the node is a number, it is emitted as a CBOR
     * tag(1) timestamp. The flag is set by {@link #isTimestampField(String)} for matching
     * object fields and is propagated into array elements, so both scalar timestamps and
     * elements of timestamp lists are tagged.
     */
    private static void writeNodeToCbor(CBORGenerator gen, JsonNode node, boolean numberIsTimestamp) throws Exception {
        if (node.isObject()) {
            gen.writeStartObject();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                gen.writeFieldName(entry.getKey());
                writeNodeToCbor(gen, entry.getValue(), isTimestampField(entry.getKey()));
            }
            gen.writeEndObject();
        } else if (node.isArray()) {
            gen.writeStartArray();
            for (JsonNode item : node) {
                // Propagate the timestamp context so every element of a timestamp list
                // (e.g. CloudWatch GetMetricData MetricDataResults[].Timestamps) is tagged.
                writeNodeToCbor(gen, item, numberIsTimestamp);
            }
            gen.writeEndArray();
        } else if (numberIsTimestamp && node.isNumber()) {
            writeCborTimestamp(gen, node);
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
     * Name-based heuristic for detecting timestamp shapes. Since this serializer is
     * schema-less it cannot consult the Smithy model, so it matches field names by their
     * conventional suffix: AWS timestamp members end in {@code "Timestamp"} (e.g.
     * GetMetricStatistics' scalar {@code Timestamp}, or alarm fields such as
     * {@code StateUpdatedTimestamp}), and list-of-timestamp members end in
     * {@code "Timestamps"} (e.g. CloudWatch GetMetricData's
     * {@code MetricDataResults[].Timestamps}). The flag is propagated into array elements,
     * so both scalar timestamps and timestamp lists are tagged.
     * <p>
     * This is a pragmatic heuristic; a shape carrying a differently-named time value would
     * need to be driven from the Smithy model instead.
     */
    private static boolean isTimestampField(String fieldName) {
        return fieldName.endsWith("Timestamp") || fieldName.endsWith("Timestamps");
    }

    /**
     * Writes a numeric node as a CBOR tag(1) epoch-seconds timestamp, preserving
     * integer-ness. Integral epoch seconds are emitted as a tag(1) integer and
     * fractional values as a tag(1) float; both are spec-valid (RFC 8949 section 3.4.2)
     * and accepted by AWS SDK decoders.
     */
    private static void writeCborTimestamp(CBORGenerator gen, JsonNode node) throws Exception {
        gen.writeTag(1);
        if (node.isIntegralNumber()) {
            gen.writeNumber(node.longValue());
        } else {
            gen.writeNumber(node.doubleValue());
        }
    }

    /**
     * Handles AWS smithy-rpc-v2-cbor protocol requests:
     * POST /service/{serviceName}/operation/{operation} with a CBOR content type,
     * the smithy-protocol header, and no X-Amz-Target. The {serviceName} segment
     * is the Smithy service shape name — for AWS services the X-Amz-Target prefix
     * without its trailing dot (e.g. DynamoDB_20120810, GraniteServiceVersion20100801).
     */
    @POST
    @Path("service/{serviceId}/operation/{operation}")
    @Consumes({GENERIC_CBOR_MEDIA_TYPE, AWS_CBOR_1_1_MEDIA_TYPE})
    @Produces({GENERIC_CBOR_MEDIA_TYPE, AWS_CBOR_1_1_MEDIA_TYPE})
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
            String responseContentType = responseContentType(httpHeaders);
            return Response.status(delegated.getStatus())
                    .header("smithy-protocol", "rpc-v2-cbor")
                    .type(responseContentType)
                    .entity(cborBytes)
                    .build();
        } catch (AwsException e) {
            return cborErrorResponse(e, "smithy-protocol", responseContentType(httpHeaders));
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
    @Consumes({GENERIC_CBOR_MEDIA_TYPE, AWS_CBOR_1_1_MEDIA_TYPE})
    @Produces({GENERIC_CBOR_MEDIA_TYPE, AWS_CBOR_1_1_MEDIA_TYPE})
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
                case "kinesis" -> kinesisJsonHandler.handle(action, request, region);
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
            String responseContentType = responseContentType(httpHeaders);
            return Response.status(delegated.getStatus())
                    .header("smithy-protocol", "rpc-v2-cbor")
                    .type(responseContentType)
                    .entity(cborBytes)
                    .build();
        } catch (AwsException e) {
            return cborErrorResponse(e, "smithy-protocol", responseContentType(httpHeaders));
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
                if ("DynamoDB Streams".equals(serviceId) || serviceId.startsWith("DynamoDBStreams")) {
                    yield dynamoDbStreamsJsonHandler.handle(operation, request, region);
                }
                yield dynamoDbJsonHandler.handle(operation, request, region);
            }
            case "sqs" -> sqsJsonHandler.handle(operation, request, region);
            case "sns" -> snsJsonHandler.handle(operation, request, region);
            case "kinesis" -> kinesisJsonHandler.handle(operation, request, region);
            case "states" -> sfnJsonHandler.handle(operation, request, region);
            case "monitoring" -> cloudWatchMetricsJsonHandler.handle(operation, request, region);
            default -> null;
        };
    }

    private Response cborErrorResponse(AwsException e, String protocolHeader, String mediaType) {
        try {
            byte[] errBytes = CBOR_MAPPER.writeValueAsBytes(
                    new AwsErrorResponse(e.jsonType(), e.getMessage()));
            String queryErrorFault = (e.getHttpStatus() < 500) ? "Sender" : "Receiver";
            return Response.status(e.getHttpStatus())
                    .header(protocolHeader, "rpc-v2-cbor")
                    .header("x-amzn-query-error", e.getErrorCode() + ";" + queryErrorFault)
                    .type(mediaType)
                    .entity(errBytes)
                    .build();
        } catch (Exception ex) {
            return Response.status(e.getHttpStatus()).build();
        }
    }

    private String responseContentType(HttpHeaders httpHeaders) {
        String requestContentType = httpHeaders.getHeaderString(AwsCborContentTypeFilter.ORIGINAL_CONTENT_TYPE_HEADER);
        if (requestContentType == null) {
            requestContentType = httpHeaders.getHeaderString("Content-Type");
        }
        if (requestContentType != null && requestContentType.contains("x-amz-cbor")) {
            return AWS_CBOR_1_1_MEDIA_TYPE;
        }
        return GENERIC_CBOR_MEDIA_TYPE;
    }
}
