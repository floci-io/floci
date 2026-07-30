package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.S3Destination;
import io.github.hectorvent.floci.services.firehose.model.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class FirehoseJsonHandler {

    private final FirehoseService firehoseService;
    private final ObjectMapper mapper;

    @Inject
    public FirehoseJsonHandler(FirehoseService firehoseService, ObjectMapper mapper) {
        this.firehoseService = firehoseService;
        this.mapper = mapper;
    }

    private String getDeliveryStreamName(JsonNode request) {
        if (request == null || !request.has("DeliveryStreamName") || request.get("DeliveryStreamName").isNull()) {
            throw new AwsException("InvalidArgumentException", "Delivery stream name must not be null or empty.", 400);
        }
        String name = request.get("DeliveryStreamName").asText();
        if (name.isEmpty() || name.length() > 64 || !name.matches("[a-zA-Z0-9_.-]+")) {
            throw new AwsException("InvalidArgumentException",
                    "Delivery stream name must be between 1 and 64 characters and contain only letters, numbers, underscores, hyphens, or periods.", 400);
        }
        return name;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateDeliveryStream" -> {
                String name = getDeliveryStreamName(request);
                S3Destination s3 = null;
                if (request.has("S3DestinationConfiguration")) {
                    s3 = mapper.treeToValue(request.get("S3DestinationConfiguration"), S3Destination.class);
                } else if (request.has("ExtendedS3DestinationConfiguration")) {
                    s3 = mapper.treeToValue(request.get("ExtendedS3DestinationConfiguration"), S3Destination.class);
                }
                List<io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Tag> tags = new ArrayList<>();
                if (request.has("Tags")) {
                    for (JsonNode tNode : request.get("Tags")) {
                        tags.add(mapper.treeToValue(tNode, io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Tag.class));
                    }
                }
                String deliveryStreamType = request.has("DeliveryStreamType")
                        ? request.get("DeliveryStreamType").asText() : null;
                String arn = firehoseService.createDeliveryStream(name, s3, tags, deliveryStreamType);
                yield Response.ok(Map.of("DeliveryStreamARN", arn)).build();
            }
            case "UpdateDestination" -> {
                String name = getDeliveryStreamName(request);
                String currentVersionId = request.has("CurrentDeliveryStreamVersionId") ? request.get("CurrentDeliveryStreamVersionId").asText() : "";
                String destinationId = request.has("DestinationId") ? request.get("DestinationId").asText() : "";
                S3Destination update = null;
                if (request.has("ExtendedS3DestinationUpdate")) {
                    update = mapper.treeToValue(request.get("ExtendedS3DestinationUpdate"), S3Destination.class);
                } else if (request.has("S3DestinationUpdate")) {
                    update = mapper.treeToValue(request.get("S3DestinationUpdate"), S3Destination.class);
                }
                firehoseService.updateDestination(name, currentVersionId, destinationId, update);
                yield Response.ok(Map.of()).build();
            }
            case "DescribeDeliveryStream" -> {
                String name = getDeliveryStreamName(request);
                var desc = firehoseService.describeDeliveryStream(name);
                yield Response.ok(Map.of("DeliveryStreamDescription", desc)).build();
            }
            case "ListDeliveryStreams" -> {
                yield Response.ok(Map.of(
                        "DeliveryStreamNames", firehoseService.listDeliveryStreams(),
                        "HasMoreDeliveryStreams", false)).build();
            }
            case "DeleteDeliveryStream" -> {
                String name = getDeliveryStreamName(request);
                firehoseService.deleteDeliveryStream(name);
                yield Response.ok(Map.of()).build();
            }
            case "PutRecord" -> {
                String name = getDeliveryStreamName(request);
                Record record = mapper.treeToValue(request.get("Record"), Record.class);
                firehoseService.putRecord(name, record);
                yield Response.ok(Map.of("RecordId", UUID.randomUUID().toString())).build();
            }
            case "PutRecordBatch" -> {
                String name = getDeliveryStreamName(request);
                List<Record> records = new ArrayList<>();
                for (JsonNode recordNode : request.get("Records")) {
                    records.add(mapper.treeToValue(recordNode, Record.class));
                }
                firehoseService.putRecordBatch(name, records);
                List<Map<String, String>> responses = records.stream()
                        .map(r -> Map.of("RecordId", UUID.randomUUID().toString()))
                        .toList();
                yield Response.ok(Map.of("FailedPutCount", 0, "RequestResponses", responses)).build();
            }
            case "TagDeliveryStream" -> {
                String name = getDeliveryStreamName(request);
                List<io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Tag> tags = new ArrayList<>();
                if (request.has("Tags")) {
                    for (JsonNode tNode : request.get("Tags")) {
                        tags.add(mapper.treeToValue(tNode, io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Tag.class));
                    }
                }
                firehoseService.tagDeliveryStream(name, tags);
                yield Response.ok(Map.of()).build();
            }
            case "UntagDeliveryStream" -> {
                String name = getDeliveryStreamName(request);
                List<String> keys = new ArrayList<>();
                if (request.has("TagKeys")) {
                    for (JsonNode kNode : request.get("TagKeys")) {
                        keys.add(kNode.asText());
                    }
                }
                firehoseService.untagDeliveryStream(name, keys);
                yield Response.ok(Map.of()).build();
            }
            case "ListTagsForDeliveryStream" -> {
                String name = getDeliveryStreamName(request);
                String exclusiveStartTagKey = request.has("ExclusiveStartTagKey") ? request.get("ExclusiveStartTagKey").asText() : null;
                Integer limit = request.has("Limit") ? request.get("Limit").asInt() : null;

                List<io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription.Tag> tags = firehoseService.listTagsForDeliveryStream(name, exclusiveStartTagKey, limit);
                boolean hasMore = false;
                var allTags = firehoseService.describeDeliveryStream(name).getTags();
                if (!tags.isEmpty() && !allTags.isEmpty()) {
                    String lastKey = tags.get(tags.size() - 1).getKey();
                    int idx = -1;
                    for (int i = 0; i < allTags.size(); i++) {
                        if (allTags.get(i).getKey().equals(lastKey)) {
                            idx = i;
                            break;
                        }
                    }
                    if (idx >= 0 && idx < allTags.size() - 1) {
                        hasMore = true;
                    }
                }
                yield Response.ok(Map.of(
                        "Tags", tags,
                        "HasMoreTags", hasMore
                )).build();
            }
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }
}
