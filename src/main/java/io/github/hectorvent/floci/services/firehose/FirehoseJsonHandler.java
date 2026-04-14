package io.github.hectorvent.floci.services.firehose;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
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

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateDeliveryStream" -> {
                String name = request.get("DeliveryStreamName").asText();
                String arn = firehoseService.createDeliveryStream(name);
                yield Response.ok(Map.of("DeliveryStreamARN", arn)).build();
            }
            case "DescribeDeliveryStream" -> {
                String name = request.get("DeliveryStreamName").asText();
                var desc = firehoseService.describeDeliveryStream(name);
                yield Response.ok(Map.of("DeliveryStreamDescription", desc)).build();
            }
            case "ListDeliveryStreams" -> {
                yield Response.ok(Map.of("DeliveryStreamNames", firehoseService.listDeliveryStreams(), "HasMoreDeliveryStreams", false)).build();
            }
            case "DeleteDeliveryStream" -> {
                // TODO: delete implementation
                yield Response.ok().build();
            }
            case "PutRecord" -> {
                String name = request.get("DeliveryStreamName").asText();
                Record record = mapper.treeToValue(request.get("Record"), Record.class);
                firehoseService.putRecord(name, record);
                yield Response.ok(Map.of("RecordId", UUID.randomUUID().toString())).build();
            }
            case "PutRecordBatch" -> {
                String name = request.get("DeliveryStreamName").asText();
                List<Map<String, String>> responseEntries = new ArrayList<>();
                for (JsonNode recordNode : request.get("Records")) {
                    Record record = mapper.treeToValue(recordNode, Record.class);
                    firehoseService.putRecord(name, record);
                    responseEntries.add(Map.of("RecordId", UUID.randomUUID().toString()));
                }
                yield Response.ok(Map.of("FailedPutCount", 0, "RequestResponses", responseEntries)).build();
            }
            default -> throw new AwsException("InvalidAction", "Action " + action + " is not supported", 400);
        };
    }
}
