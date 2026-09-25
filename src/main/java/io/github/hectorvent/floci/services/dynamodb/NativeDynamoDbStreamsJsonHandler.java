package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.model.DynamoDbStreamRecord;
import io.github.hectorvent.floci.services.dynamodb.model.KeySchemaElement;
import io.github.hectorvent.floci.services.dynamodb.model.StreamDescription;
import io.github.hectorvent.floci.services.dynamodb.model.TableDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * DynamoDB Streams JSON protocol handler of the native engine, reached through the native backend.
 * Handles requests with X-Amz-Target prefix {@code DynamoDBStreams_20120810.}.
 */
@ApplicationScoped
public class NativeDynamoDbStreamsJsonHandler {

    private static final Logger LOG = Logger.getLogger(NativeDynamoDbStreamsJsonHandler.class);

    private final DynamoDbStreamService streamService;
    private final DynamoDbService dynamoDbService;
    private final ObjectMapper objectMapper;

    @Inject
    public NativeDynamoDbStreamsJsonHandler(DynamoDbStreamService streamService, DynamoDbService dynamoDbService,
                                            ObjectMapper objectMapper) {
        this.streamService = streamService;
        this.dynamoDbService = dynamoDbService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "ListStreams" -> handleListStreams(request, region);
            case "DescribeStream" -> handleDescribeStream(request, region);
            case "GetShardIterator" -> handleGetShardIterator(request, region);
            case "GetRecords" -> handleGetRecords(request, region);
            default -> {
                yield Response.status(400)
                        .entity(new AwsErrorResponse("UnknownOperationException", "Operation " + action + " is not supported."))
                        .build();
            }
        };
    }

    private Response handleListStreams(JsonNode request, String region) {
        String tableNameFilter = request.has("TableName") ? request.get("TableName").asText() : null;

        List<StreamDescription> streams = streamService.listStreams(tableNameFilter, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode streamList = objectMapper.createArrayNode();
        for (StreamDescription sd : streams) {
            ObjectNode entry = objectMapper.createObjectNode();
            entry.put("StreamArn", sd.getStreamArn());
            entry.put("StreamLabel", sd.getStreamLabel());
            entry.put("TableName", sd.getTableName());
            streamList.add(entry);
        }
        response.set("Streams", streamList);
        return Response.ok(response).build();
    }

    private Response handleDescribeStream(JsonNode request, String region) {
        String streamArn = request.path("StreamArn").asText();
        StreamDescription sd = streamService.describeStream(streamArn);

        // Fetch key schema from the table
        List<KeySchemaElement> keySchema = List.of();
        try {
            TableDefinition table = dynamoDbService.describeTable(sd.getTableName(), region);
            keySchema = table.getKeySchema();
        } catch (AwsException e) {
            if (!"ResourceNotFoundException".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Table {0} of stream {1} not found, describing the stream without a KeySchema",
                    sd.getTableName(), streamArn);
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("StreamDescription", describeStreamToNode(sd, keySchema));
        return Response.ok(response).build();
    }

    private ObjectNode describeStreamToNode(StreamDescription sd, List<KeySchemaElement> keySchema) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("StreamArn", sd.getStreamArn());
        node.put("StreamLabel", sd.getStreamLabel());
        node.put("StreamStatus", sd.getStreamStatus());
        node.put("StreamViewType", sd.getStreamViewType());
        node.put("TableName", sd.getTableName());
        node.put("CreationRequestDateTime", sd.getCreationDateTime().getEpochSecond());

        ArrayNode keySchemaArray = objectMapper.createArrayNode();
        for (KeySchemaElement ks : keySchema) {
            ObjectNode ksNode = objectMapper.createObjectNode();
            ksNode.put("AttributeName", ks.getAttributeName());
            ksNode.put("KeyType", ks.getKeyType());
            keySchemaArray.add(ksNode);
        }
        node.set("KeySchema", keySchemaArray);

        ArrayNode shards = objectMapper.createArrayNode();
        ObjectNode shard = objectMapper.createObjectNode();
        shard.put("ShardId", DynamoDbStreamService.SHARD_ID);
        ObjectNode seqRange = objectMapper.createObjectNode();
        seqRange.put("StartingSequenceNumber", sd.getStartingSequenceNumber());
        shard.set("SequenceNumberRange", seqRange);
        shards.add(shard);
        node.set("Shards", shards);

        node.putNull("LastEvaluatedShardId");
        return node;
    }

    private Response handleGetShardIterator(JsonNode request, String region) {
        String streamArn = request.path("StreamArn").asText();
        String shardId = request.path("ShardId").asText();
        String iteratorType = request.path("ShardIteratorType").asText();
        String sequenceNumber = request.has("SequenceNumber")
                ? request.get("SequenceNumber").asText() : null;

        String iterator = streamService.getShardIterator(streamArn, shardId, iteratorType, sequenceNumber);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ShardIterator", iterator);
        return Response.ok(response).build();
    }

    private Response handleGetRecords(JsonNode request, String region) {
        String shardIterator = request.path("ShardIterator").asText();
        Integer limit = request.has("Limit") ? request.get("Limit").asInt() : null;

        DynamoDbStreamService.GetRecordsResult result = streamService.getRecords(shardIterator, limit);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode recordsArray = objectMapper.createArrayNode();
        for (DynamoDbStreamRecord record : result.records()) {
            recordsArray.add(recordToNode(record));
        }
        response.set("Records", recordsArray);
        response.put("NextShardIterator", result.nextShardIterator());
        
        return Response.ok(response).build();
    }

    private ObjectNode recordToNode(DynamoDbStreamRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("eventID", record.getEventId());
        node.put("eventName", record.getEventName());
        node.put("eventVersion", record.getEventVersion());
        node.put("eventSource", record.getEventSource());
        node.put("awsRegion", record.getAwsRegion());

        ObjectNode dynamodb = objectMapper.createObjectNode();
        dynamodb.put("ApproximateCreationDateTime", record.getApproximateCreationDateTime());
        if (record.getKeys() != null) {
            dynamodb.set("Keys", record.getKeys());
        }
        if (record.getNewImage() != null) {
            dynamodb.set("NewImage", record.getNewImage());
        }
        if (record.getOldImage() != null) {
            dynamodb.set("OldImage", record.getOldImage());
        }
        dynamodb.put("SequenceNumber", record.getSequenceNumber());
        dynamodb.put("SizeBytes", 100);
        dynamodb.put("StreamViewType", record.getStreamViewType());
        node.set("dynamodb", dynamodb);

        return node;
    }
}
