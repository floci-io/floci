package io.github.hectorvent.floci.services.kinesis;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.kinesis.model.KinesisConsumer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@ApplicationScoped
public class KinesisJsonHandler {

    private final KinesisService service;
    private final ObjectMapper objectMapper;

    @Inject
    public KinesisJsonHandler(KinesisService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "CreateStream" -> handleCreateStream(request, region);
            case "DeleteStream" -> handleDeleteStream(request, region);
            case "ListStreams" -> handleListStreams(request, region);
            case "DescribeStream" -> handleDescribeStream(request, region);
            case "DescribeStreamSummary" -> handleDescribeStreamSummary(request, region);
            case "RegisterStreamConsumer" -> handleRegisterStreamConsumer(request, region);
            case "DeregisterStreamConsumer" -> handleDeregisterStreamConsumer(request, region);
            case "DescribeStreamConsumer" -> handleDescribeStreamConsumer(request, region);
            case "ListStreamConsumers" -> handleListStreamConsumers(request, region);
            case "SubscribeToShard" -> handleSubscribeToShard(request, region);
            case "AddTagsToStream" -> handleAddTagsToStream(request, region);
            case "RemoveTagsFromStream" -> handleRemoveTagsFromStream(request, region);
            case "ListTagsForStream" -> handleListTagsForStream(request, region);
            case "StartStreamEncryption" -> handleStartStreamEncryption(request, region);
            case "StopStreamEncryption" -> handleStopStreamEncryption(request, region);
            case "SplitShard" -> handleSplitShard(request, region);
            case "MergeShards" -> handleMergeShards(request, region);
            case "PutRecord" -> handlePutRecord(request, region);
            case "PutRecords" -> handlePutRecords(request, region);
            case "GetShardIterator" -> handleGetShardIterator(request, region);
            case "GetRecords" -> handleGetRecords(request, region);
            case "ListShards" -> handleListShards(request, region);
            case "IncreaseStreamRetentionPeriod" -> handleIncreaseStreamRetentionPeriod(request, region);
            case "DecreaseStreamRetentionPeriod" -> handleDecreaseStreamRetentionPeriod(request, region);
            case "EnableEnhancedMonitoring" -> handleEnableEnhancedMonitoring(request, region);
            case "DisableEnhancedMonitoring" -> handleDisableEnhancedMonitoring(request, region);
            case "UpdateStreamMode" -> handleUpdateStreamMode(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private String resolveStreamName(JsonNode request) {
        String streamName = request.path("StreamName").asText(null);
        if (streamName != null && !streamName.isBlank()) {
            return streamName;
        }

        String streamArn = request.path("StreamARN").asText(null);
        if (streamArn != null) {
            String name = parseStreamNameFromArn(streamArn);
            if (name != null) {
                return name;
            }
        }

        throw new AwsException("InvalidArgumentException",
                "StreamName or valid StreamARN must be provided", 400);
    }

    private String parseStreamNameFromArn(String streamArn) {
        int streamIdx = streamArn.indexOf(":stream/");
        if (streamIdx < 0) {
            return null;
        }
        String after = streamArn.substring(streamIdx + 8);
        int slash = after.indexOf('/');
        String name = slash >= 0 ? after.substring(0, slash) : after;
        return name.isBlank() ? null : name;
    }

    private Response handleCreateStream(JsonNode request, String region) {
        String streamName = request.path("StreamName").asText();
        int shardCount = request.path("ShardCount").asInt(1);
        String streamMode = null;
        JsonNode modeDetails = request.path("StreamModeDetails");
        if (modeDetails.isObject()) {
            String mode = modeDetails.path("StreamMode").asText(null);
            if (mode != null && !mode.isBlank()) {
                streamMode = mode;
            }
        }
        service.createStream(streamName, shardCount, streamMode, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUpdateStreamMode(JsonNode request, String region) {
        // UpdateStreamMode accepts only StreamARN per the AWS API; StreamName is not valid.
        String streamArn = request.path("StreamARN").asText(null);
        if (streamArn == null || streamArn.isBlank()) {
            throw new AwsException("InvalidArgumentException", "StreamARN is required", 400);
        }
        JsonNode modeDetails = request.path("StreamModeDetails");
        if (!modeDetails.isObject()) {
            throw new AwsException("InvalidArgumentException", "StreamModeDetails is required", 400);
        }
        String streamMode = modeDetails.path("StreamMode").asText(null);
        if (streamMode == null || streamMode.isBlank()) {
            throw new AwsException("InvalidArgumentException", "StreamModeDetails.StreamMode is required", 400);
        }
        String streamName = extractStreamNameFromArn(streamArn);
        service.updateStreamMode(streamName, streamMode, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private String extractStreamNameFromArn(String streamArn) {
        String name = parseStreamNameFromArn(streamArn);
        if (name == null) {
            throw new AwsException("InvalidArgumentException",
                    "StreamARN does not contain a valid stream name: " + streamArn, 400);
        }
        return name;
    }

    private Response handleDeleteStream(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        service.deleteStream(streamName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListStreams(JsonNode request, String region) {
        List<String> streamNames = service.listStreams(region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode names = response.putArray("StreamNames");
        streamNames.forEach(names::add);
        response.put("HasMoreStreams", false);
        return Response.ok(response).build();
    }

    private Response handleDescribeStream(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        KinesisStream stream = service.describeStream(streamName, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode desc = response.putObject("StreamDescription");
        desc.put("StreamName", stream.getStreamName());
        desc.put("StreamARN", stream.getStreamArn());
        desc.put("StreamStatus", stream.getStreamStatus());
        desc.put("HasMoreShards", false);
        desc.put("RetentionPeriodHours", stream.getRetentionPeriodHours());
        desc.put("StreamCreationTimestamp", stream.getStreamCreationTimestamp().toEpochMilli() / 1000.0);
        desc.put("EncryptionType", stream.getEncryptionType());
        if (stream.getKeyId() != null) {
            desc.put("KeyId", stream.getKeyId());
        }
        addStreamModeDetailsNode(desc, stream);

        addEnhancedMonitoringNode(desc, stream);

        ArrayNode shards = desc.putArray("Shards");
        for (KinesisShard shard : stream.getShards()) {
            ObjectNode sNode = shards.addObject();
            sNode.put("ShardId", shard.getShardId());
            if (shard.getParentShardId() != null) {
                sNode.put("ParentShardId", shard.getParentShardId());
            }
            if (shard.getAdjacentParentShardId() != null) {
                sNode.put("AdjacentParentShardId", shard.getAdjacentParentShardId());
            }
            sNode.putObject("HashKeyRange")
                    .put("StartingHashKey", shard.getHashKeyRange().startingHashKey())
                    .put("EndingHashKey", shard.getHashKeyRange().endingHashKey());
            ObjectNode seqRange = sNode.putObject("SequenceNumberRange");
            seqRange.put("StartingSequenceNumber", shard.getSequenceNumberRange().startingSequenceNumber());
            if (shard.getSequenceNumberRange().endingSequenceNumber() != null) {
                seqRange.put("EndingSequenceNumber", shard.getSequenceNumberRange().endingSequenceNumber());
            }
        }
        return Response.ok(response).build();
    }

    private Response handleDescribeStreamSummary(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        KinesisStream stream = service.describeStream(streamName, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode summary = response.putObject("StreamDescriptionSummary");
        summary.put("StreamName", stream.getStreamName());
        summary.put("StreamARN", stream.getStreamArn());
        summary.put("StreamStatus", stream.getStreamStatus());
        summary.put("RetentionPeriodHours", stream.getRetentionPeriodHours());
        summary.put("StreamCreationTimestamp", stream.getStreamCreationTimestamp().toEpochMilli() / 1000.0);
        summary.put("OpenShardCount", (int) stream.getShards().stream().filter(s -> !s.isClosed()).count());
        summary.put("EncryptionType", stream.getEncryptionType());
        if (stream.getKeyId() != null) {
            summary.put("KeyId", stream.getKeyId());
        }
        addStreamModeDetailsNode(summary, stream);

        addEnhancedMonitoringNode(summary, stream);

        return Response.ok(response).build();
    }

    private void addEnhancedMonitoringNode(ObjectNode parent, KinesisStream stream) {
        ArrayNode shardLevelMetrics = parent.putArray("EnhancedMonitoring").addObject().putArray("ShardLevelMetrics");
        stream.getEnhancedMonitoringMetrics().stream().sorted().forEach(shardLevelMetrics::add);
    }

    private void addStreamModeDetailsNode(ObjectNode parent, KinesisStream stream) {
        parent.putObject("StreamModeDetails").put("StreamMode", stream.getStreamMode());
    }

    private Response handleRegisterStreamConsumer(JsonNode request, String region) {
        String streamArn = request.path("StreamARN").asText();
        String consumerName = request.path("ConsumerName").asText();
        var consumer = service.registerStreamConsumer(streamArn, consumerName, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("Consumer", consumerToNode(consumer));
        return Response.ok(response).build();
    }

    private Response handleDeregisterStreamConsumer(JsonNode request, String region) {
        String streamArn = request.has("StreamARN") ? request.path("StreamARN").asText() : null;
        String consumerName = request.has("ConsumerName") ? request.path("ConsumerName").asText() : null;
        String consumerArn = request.has("ConsumerARN") ? request.path("ConsumerARN").asText() : null;
        service.deregisterStreamConsumer(streamArn, consumerName, consumerArn, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDescribeStreamConsumer(JsonNode request, String region) {
        String streamArn = request.has("StreamARN") ? request.path("StreamARN").asText() : null;
        String consumerName = request.has("ConsumerName") ? request.path("ConsumerName").asText() : null;
        String consumerArn = request.has("ConsumerARN") ? request.path("ConsumerARN").asText() : null;
        var consumer = service.describeStreamConsumer(streamArn, consumerName, consumerArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("ConsumerDescription", consumerToNode(consumer));
        return Response.ok(response).build();
    }

    private Response handleListStreamConsumers(JsonNode request, String region) {
        String streamArn = request.path("StreamARN").asText();
        var consumers = service.listStreamConsumers(streamArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = response.putArray("Consumers");
        consumers.forEach(c -> array.add(consumerToNode(c)));
        return Response.ok(response).build();
    }

    private Response handleSubscribeToShard(JsonNode request, String region) {
        ObjectNode response = objectMapper.createObjectNode();
        return Response.ok(response).build();
    }

    private ObjectNode consumerToNode(KinesisConsumer c) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ConsumerName", c.getConsumerName());
        node.put("ConsumerARN", c.getConsumerArn());
        node.put("ConsumerStatus", c.getConsumerStatus());
        node.put("ConsumerCreationTimestamp", c.getConsumerCreationTimestamp().toEpochMilli() / 1000.0);
        if (c.getStreamArn() != null) {
            node.put("StreamARN", c.getStreamArn());
        }
        return node;
    }

    private Response handleAddTagsToStream(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        Map<String, String> tags = new HashMap<>();
        request.path("Tags").fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        service.addTagsToStream(streamName, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleRemoveTagsFromStream(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        java.util.List<String> tagKeys = new java.util.ArrayList<>();
        request.path("TagKeys").forEach(node -> tagKeys.add(node.asText()));
        service.removeTagsFromStream(streamName, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListTagsForStream(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        Map<String, String> tags = service.listTagsForStream(streamName, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode tagsArray = response.putArray("Tags");
        tags.forEach((k, v) -> {
            ObjectNode tagNode = tagsArray.addObject();
            tagNode.put("Key", k);
            tagNode.put("Value", v);
        });
        response.put("HasMoreTags", false);
        return Response.ok(response).build();
    }

    private Response handleStartStreamEncryption(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        String type = request.path("EncryptionType").asText();
        String keyId = request.path("KeyId").asText();
        service.startStreamEncryption(streamName, type, keyId, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleStopStreamEncryption(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        service.stopStreamEncryption(streamName, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSplitShard(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        String shardId = request.path("ShardToSplit").asText();
        String newStart = request.path("NewStartingHashKey").asText();
        service.splitShard(streamName, shardId, newStart, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleMergeShards(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        String shard1 = request.path("ShardToMerge").asText();
        String shard2 = request.path("AdjacentShardToMerge").asText();
        service.mergeShards(streamName, shard1, shard2, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handlePutRecord(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        byte[] data = Base64.getDecoder().decode(request.path("Data").asText());
        String partitionKey = request.path("PartitionKey").asText();

        KinesisService.PutRecordResult result = service.putRecordWithShardId(streamName, data, partitionKey, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("SequenceNumber", result.sequenceNumber());
        response.put("ShardId", result.shardId());
        return Response.ok(response).build();
    }

    private Response handlePutRecords(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        JsonNode recordsNode = request.path("Records");
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode results = response.putArray("Records");
        int failed = 0;

        for (JsonNode node : recordsNode) {
            try {
                byte[] data = Base64.getDecoder().decode(node.path("Data").asText());
                String partitionKey = node.path("PartitionKey").asText();
                KinesisService.PutRecordResult result = service.putRecordWithShardId(streamName, data, partitionKey, region);
                results.addObject()
                        .put("SequenceNumber", result.sequenceNumber())
                        .put("ShardId", result.shardId());
            } catch (Exception e) {
                failed++;
                results.addObject()
                        .put("ErrorCode", "InternalFailure")
                        .put("ErrorMessage", e.getMessage());
            }
        }
        response.put("FailedRecordCount", failed);
        return Response.ok(response).build();
    }

    private Response handleGetShardIterator(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        String shardId = request.path("ShardId").asText();
        String type = request.path("ShardIteratorType").asText();
        String seq = request.has("StartingSequenceNumber") ? request.path("StartingSequenceNumber").asText() : null;
        // AWS sends Timestamp as epoch seconds (double with fractional ms).
        // Convert to long millis at the boundary; the emulator stores time in ms everywhere.
        // Use Math.round to avoid 1ms drift from FP multiplication (e.g. X.999...).
        Long timestampMillis = null;
        if (request.has("Timestamp") && !request.path("Timestamp").isNull()) {
            JsonNode tsNode = request.path("Timestamp");
            if (!tsNode.isNumber()) {
                throw new io.github.hectorvent.floci.core.common.AwsException("InvalidArgumentException",
                        "Timestamp must be a number (epoch seconds)", 400);
            }
            timestampMillis = Math.round(tsNode.asDouble() * 1000);
        }
        if ("AT_TIMESTAMP".equals(type) && timestampMillis == null) {
            throw new io.github.hectorvent.floci.core.common.AwsException("InvalidArgumentException",
                    "ShardIteratorType AT_TIMESTAMP requires a Timestamp", 400);
        }

        String iterator = service.getShardIterator(streamName, shardId, type, seq, timestampMillis, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("ShardIterator", iterator);
        return Response.ok(response).build();
    }

    @SuppressWarnings("unchecked")
    private Response handleGetRecords(JsonNode request, String region) {
        String iterator = request.path("ShardIterator").asText();
        Integer limit = request.has("Limit") ? request.path("Limit").asInt() : null;

        Map<String, Object> result = service.getRecords(iterator, limit, region);
        List<KinesisRecord> records = (List<KinesisRecord>) result.get("Records");

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode recordsArray = response.putArray("Records");
        for (KinesisRecord rec : records) {
            ObjectNode rNode = recordsArray.addObject();
            rNode.put("Data", Base64.getEncoder().encodeToString(rec.getData()));
            rNode.put("PartitionKey", rec.getPartitionKey());
            rNode.put("SequenceNumber", rec.getSequenceNumber());
            rNode.put("ApproximateArrivalTimestamp", rec.getApproximateArrivalTimestamp().toEpochMilli() / 1000.0);
        }
        response.put("NextShardIterator", (String) result.get("NextShardIterator"));
        response.put("MillisBehindLatest", ((Number) result.get("MillisBehindLatest")).longValue());
        return Response.ok(response).build();
    }

    private Response handleIncreaseStreamRetentionPeriod(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        int retentionPeriodHours = request.path("RetentionPeriodHours").asInt();
        service.increaseStreamRetentionPeriod(streamName, retentionPeriodHours, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDecreaseStreamRetentionPeriod(JsonNode request, String region) {
        String streamName = resolveStreamName(request);
        int retentionPeriodHours = request.path("RetentionPeriodHours").asInt();
        service.decreaseStreamRetentionPeriod(streamName, retentionPeriodHours, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListShards(JsonNode request, String region) {
        String resolvedStreamName = resolveStreamName(request);
        KinesisStream stream = service.describeStream(resolvedStreamName, region);

        List<KinesisShard> shards = stream.getShards();
        if (request.has("ShardFilter")) {
            JsonNode filter = request.path("ShardFilter");
            String filterType = filter.path("Type").asText(null);
            if ("AT_LATEST".equals(filterType)) {
                shards = shards.stream().filter(s -> !s.isClosed()).toList();
            }
        }

        int maxResults = request.has("MaxResults") ? request.path("MaxResults").asInt(1000) : 1000;
        List<KinesisShard> page = shards.size() > maxResults ? shards.subList(0, maxResults) : shards;

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode shardsArray = response.putArray("Shards");
        for (KinesisShard shard : page) {
            ObjectNode sNode = shardsArray.addObject();
            sNode.put("ShardId", shard.getShardId());
            if (shard.getParentShardId() != null) {
                sNode.put("ParentShardId", shard.getParentShardId());
            }
            if (shard.getAdjacentParentShardId() != null) {
                sNode.put("AdjacentParentShardId", shard.getAdjacentParentShardId());
            }
            sNode.putObject("HashKeyRange")
                    .put("StartingHashKey", shard.getHashKeyRange().startingHashKey())
                    .put("EndingHashKey", shard.getHashKeyRange().endingHashKey());
            ObjectNode seqRange = sNode.putObject("SequenceNumberRange");
            seqRange.put("StartingSequenceNumber", shard.getSequenceNumberRange().startingSequenceNumber());
            if (shard.getSequenceNumberRange().endingSequenceNumber() != null) {
                seqRange.put("EndingSequenceNumber", shard.getSequenceNumberRange().endingSequenceNumber());
            }
        }

        response.putNull("NextToken");

        return Response.ok(response).build();
    }

    private Response handleEnableEnhancedMonitoring(JsonNode request, String region) {
        String streamName = resolveStreamName(request);

        List<String> metrics = new ArrayList<>();
        request.path("ShardLevelMetrics").forEach(m -> metrics.add(m.asText()));

        Set<String> currentMetrics = service.enableEnhancedMonitoring(streamName, metrics, region);
        KinesisStream updated = service.describeStream(streamName, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("StreamName", streamName);
        response.put("StreamARN", updated.getStreamArn());
        ArrayNode current = response.putArray("CurrentShardLevelMetrics");
        currentMetrics.stream().sorted().forEach(current::add);
        ArrayNode desired = response.putArray("DesiredShardLevelMetrics");
        updated.getEnhancedMonitoringMetrics().stream().sorted().forEach(desired::add);
        return Response.ok(response).build();
    }

    private Response handleDisableEnhancedMonitoring(JsonNode request, String region) {
        String streamName = resolveStreamName(request);

        List<String> metrics = new ArrayList<>();
        request.path("ShardLevelMetrics").forEach(m -> metrics.add(m.asText()));

        Set<String> currentMetrics = service.disableEnhancedMonitoring(streamName, metrics, region);
        KinesisStream updated = service.describeStream(streamName, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("StreamName", streamName);
        response.put("StreamARN", updated.getStreamArn());
        ArrayNode current = response.putArray("CurrentShardLevelMetrics");
        currentMetrics.stream().sorted().forEach(current::add);
        ArrayNode desired = response.putArray("DesiredShardLevelMetrics");
        updated.getEnhancedMonitoringMetrics().stream().sorted().forEach(desired::add);
        return Response.ok(response).build();
    }

}
