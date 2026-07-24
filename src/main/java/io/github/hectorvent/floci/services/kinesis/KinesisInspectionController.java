package io.github.hectorvent.floci.services.kinesis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.kinesis.model.KinesisRecord;
import io.github.hectorvent.floci.services.kinesis.model.KinesisShard;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local inspection endpoint for the Floci UI and test helpers.
 *
 * <p>The AWS-compatible Kinesis API remains the JSON 1.1 endpoint on {@code /};
 * these {@code _aws} paths are read-only convenience views over local emulator
 * state.
 */
@Path("/_aws/kinesis")
@Produces(MediaType.APPLICATION_JSON)
public class KinesisInspectionController {

    private final KinesisService kinesisService;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public KinesisInspectionController(KinesisService kinesisService,
                                       ObjectMapper objectMapper,
                                       RegionResolver regionResolver) {
        this.kinesisService = kinesisService;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    @Path("/streams")
    public Response getStreams(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        ArrayNode streams = objectMapper.createArrayNode();
        for (KinesisStream stream : kinesisService.listStreamDetails(region)) {
            streams.add(streamNode(stream));
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("streams", streams);
        return Response.ok(result).build();
    }

    @GET
    @Path("/records")
    public Response getRecords(@Context HttpHeaders headers,
                               @QueryParam("StreamName") String streamName,
                               @QueryParam("ShardId") String shardId) {
        if (streamName == null || streamName.isBlank()) {
            return Response.status(400)
                    .entity(objectMapper.createObjectNode().put("message", "StreamName query parameter is required"))
                    .build();
        }

        String region = regionResolver.resolveRegion(headers);
        List<KinesisRecord> captured = kinesisService.peekRecords(streamName, shardId, region);
        ArrayNode records = objectMapper.createArrayNode();
        for (KinesisRecord record : captured) {
            records.add(recordNode(record));
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("records", records);
        return Response.ok(result).build();
    }

    private ObjectNode streamNode(KinesisStream stream) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("StreamName", stream.getStreamName());
        node.put("StreamARN", stream.getStreamArn());
        node.put("StreamStatus", stream.getStreamStatus());
        node.put("StreamMode", stream.getStreamMode());
        node.put("RetentionPeriodHours", stream.getRetentionPeriodHours());
        node.put("EncryptionType", stream.getEncryptionType());
        if (stream.getKeyId() != null) {
            node.put("KeyId", stream.getKeyId());
        } else {
            node.putNull("KeyId");
        }
        if (stream.getStreamCreationTimestamp() != null) {
            node.put("StreamCreationTimestamp", stream.getStreamCreationTimestamp().toString());
        }
        node.put("OpenShardCount", stream.getShards().stream().filter(s -> !s.isClosed()).count());
        node.put("RecordCount", stream.getShards().stream().mapToInt(s -> s.getRecords().size()).sum());
        ArrayNode enhancedMonitoring = node.putArray("EnhancedMonitoring");
        Set<String> metrics = stream.getEnhancedMonitoringMetrics();
        if (metrics != null) {
            metrics.stream().sorted().forEach(enhancedMonitoring::add);
        }

        ArrayNode shards = node.putArray("Shards");
        for (KinesisShard shard : stream.getShards()) {
            shards.add(shardNode(shard));
        }

        ObjectNode tags = node.putObject("Tags");
        Map<String, String> streamTags = stream.getTags();
        if (streamTags != null) {
            for (Map.Entry<String, String> entry : streamTags.entrySet()) {
                tags.put(entry.getKey(), entry.getValue());
            }
        }
        return node;
    }

    private ObjectNode shardNode(KinesisShard shard) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("ShardId", shard.getShardId());
        node.put("Closed", shard.isClosed());
        node.put("RecordCount", shard.getRecords().size());
        if (shard.getParentShardId() != null) {
            node.put("ParentShardId", shard.getParentShardId());
        }
        if (shard.getAdjacentParentShardId() != null) {
            node.put("AdjacentParentShardId", shard.getAdjacentParentShardId());
        }
        ObjectNode hashKeyRange = node.putObject("HashKeyRange");
        hashKeyRange.put("StartingHashKey", shard.getHashKeyRange().startingHashKey());
        hashKeyRange.put("EndingHashKey", shard.getHashKeyRange().endingHashKey());
        ObjectNode sequenceNumberRange = node.putObject("SequenceNumberRange");
        sequenceNumberRange.put("StartingSequenceNumber", shard.getSequenceNumberRange().startingSequenceNumber());
        if (shard.getSequenceNumberRange().endingSequenceNumber() != null) {
            sequenceNumberRange.put("EndingSequenceNumber", shard.getSequenceNumberRange().endingSequenceNumber());
        }
        return node;
    }

    private ObjectNode recordNode(KinesisRecord record) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("SequenceNumber", record.getSequenceNumber());
        node.put("PartitionKey", record.getPartitionKey());
        byte[] data = record.getData();
        node.put("Data", data == null ? "" : Base64.getEncoder().encodeToString(data));
        if (record.getApproximateArrivalTimestamp() != null) {
            node.put("ApproximateArrivalTimestamp", record.getApproximateArrivalTimestamp().toString());
        }
        return node;
    }
}
