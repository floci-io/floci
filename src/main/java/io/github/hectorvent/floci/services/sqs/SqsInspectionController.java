package io.github.hectorvent.floci.services.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * LocalStack-compatible REST endpoint for inspecting SQS queue contents.
 * Provides non-destructive peek of all messages (visible and in-flight) for test helpers.
 */
@Path("/_aws/sqs/messages")
@Produces(MediaType.APPLICATION_JSON)
public class SqsInspectionController {

    private final SqsService sqsService;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public SqsInspectionController(SqsService sqsService, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.sqsService = sqsService;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @GET
    public Response getMessages(@Context HttpHeaders headers, @QueryParam("QueueUrl") String queueUrl) {
        if (queueUrl == null || queueUrl.isBlank()) {
            return Response.status(400)
                    .entity(objectMapper.createObjectNode().put("message", "QueueUrl query parameter is required"))
                    .build();
        }

        List<Message> messages = sqsService.peekMessages(queueUrl, regionResolver.resolveRegion(headers));

        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (Message msg : messages) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("MessageId", msg.getMessageId());
            node.put("MD5OfBody", msg.getMd5OfBody());
            node.put("Body", msg.getBody());

            if (msg.getReceiptHandle() != null) {
                node.put("ReceiptHandle", msg.getReceiptHandle());
            } else {
                node.putNull("ReceiptHandle");
            }

            ObjectNode attributes = node.putObject("Attributes");
            if (msg.getSentTimestamp() != null) {
                attributes.put("SentTimestamp", String.valueOf(msg.getSentTimestamp().toEpochMilli()));
            }
            attributes.put("ApproximateReceiveCount", String.valueOf(msg.getReceiveCount()));
            if (msg.getMessageGroupId() != null) {
                attributes.put("MessageGroupId", msg.getMessageGroupId());
            }
            if (msg.getMessageDeduplicationId() != null) {
                attributes.put("MessageDeduplicationId", msg.getMessageDeduplicationId());
            }
            if (msg.getSequenceNumber() != 0) {
                attributes.put("SequenceNumber", String.valueOf(msg.getSequenceNumber()));
            }

            ObjectNode messageAttributes = node.putObject("MessageAttributes");
            if (msg.getMessageAttributes() != null) {
                for (Map.Entry<String, MessageAttributeValue> entry : msg.getMessageAttributes().entrySet()) {
                    ObjectNode attrNode = messageAttributes.putObject(entry.getKey());
                    MessageAttributeValue val = entry.getValue();
                    attrNode.put("DataType", val.getDataType());
                    if (val.getStringValue() != null) {
                        attrNode.put("StringValue", val.getStringValue());
                    }
                    if (val.getBinaryValue() != null) {
                        attrNode.put("BinaryValue", val.getBinaryValue());
                    }
                }
            }

            messagesArray.add(node);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("messages", messagesArray);
        return Response.ok(result).build();
    }

    @DELETE
    public Response purgeQueue(@Context HttpHeaders headers, @QueryParam("QueueUrl") String queueUrl) {
        if (queueUrl == null || queueUrl.isBlank()) {
            return Response.status(400)
                    .entity(objectMapper.createObjectNode().put("message", "QueueUrl query parameter is required"))
                    .build();
        }

        sqsService.purgeQueue(queueUrl, regionResolver.resolveRegion(headers));
        return Response.ok().build();
    }
}
