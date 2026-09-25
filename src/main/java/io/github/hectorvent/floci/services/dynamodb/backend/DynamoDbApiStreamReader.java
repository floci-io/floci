package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Call;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Reply;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link DynamoDbStreamReader} that speaks the DynamoDB Streams wire API through
 * {@link DynamoDbOperations}, so it reads whichever engine is selected. It parses AWS JSON only.
 */
public final class DynamoDbApiStreamReader implements DynamoDbStreamReader {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final DynamoDbOperations operations;
    private final CheckpointLifetime checkpointLifetime;

    public DynamoDbApiStreamReader(DynamoDbOperations operations, CheckpointLifetime checkpointLifetime) {
        this.operations = operations;
        this.checkpointLifetime = checkpointLifetime;
    }

    @Override
    public ShardsPage describeStream(Stream stream, String exclusiveStartShardId, Integer limit) {
        ObjectNode body = JSON.objectNode().put("StreamArn", stream.arn());
        if (exclusiveStartShardId != null) {
            body.put("ExclusiveStartShardId", exclusiveStartShardId);
        }
        if (limit != null) {
            body.put("Limit", limit);
        }
        JsonNode description = call(stream, "DescribeStream", body).path("StreamDescription");
        List<Shard> shards = new ArrayList<>();
        for (JsonNode shard : description.path("Shards")) {
            JsonNode range = shard.path("SequenceNumberRange");
            shards.add(new Shard(text(shard, "ShardId"), text(shard, "ParentShardId"),
                    text(range, "StartingSequenceNumber"), text(range, "EndingSequenceNumber")));
        }
        return new ShardsPage(stream, List.copyOf(shards), text(description, "LastEvaluatedShardId"));
    }

    @Override
    public Cursor getShardIterator(Stream stream, String shardId, Position position, String sequenceNumber) {
        ObjectNode body = JSON.objectNode()
                .put("StreamArn", stream.arn())
                .put("ShardId", shardId)
                .put("ShardIteratorType", position.name());
        if (sequenceNumber != null) {
            body.put("SequenceNumber", sequenceNumber);
        }
        return new Cursor(stream, shardId, text(call(stream, "GetShardIterator", body), "ShardIterator"));
    }

    @Override
    public RecordsPage getRecords(Cursor cursor, int limit) {
        ObjectNode body = JSON.objectNode().put("ShardIterator", cursor.token()).put("Limit", limit);
        JsonNode reply = call(cursor.stream(), "GetRecords", body);
        List<Record> records = new ArrayList<>();
        for (JsonNode awsRecord : reply.path("Records")) {
            records.add(new Record(text(awsRecord.path("dynamodb"), "SequenceNumber"), awsRecord));
        }
        String next = text(reply, "NextShardIterator");
        return new RecordsPage(List.copyOf(records),
                next == null ? null : new Cursor(cursor.stream(), cursor.shardId(), next));
    }

    @Override
    public CheckpointLifetime checkpointLifetime() {
        return checkpointLifetime;
    }

    private JsonNode call(Stream stream, String action, ObjectNode body) {
        Reply reply;
        try {
            reply = operations.execute(new Call(stream.scope(), Api.DYNAMODB_STREAMS, action, body));
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        JsonNode replyBody = reply.body() == null ? JSON.missingNode() : reply.body();
        if (reply.status() >= 300) {
            String type = text(replyBody, "__type");
            String code = type == null ? "InternalServerError" : type.substring(type.lastIndexOf('#') + 1);
            String message = text(replyBody, "message");
            throw new AwsException(code, message != null ? message : text(replyBody, "Message"), reply.status());
        }
        return replyBody;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
