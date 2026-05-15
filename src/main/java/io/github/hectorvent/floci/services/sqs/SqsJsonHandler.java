package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SQS JSON protocol handler (application/x-amz-json-1.0).
 * Called by the DynamoDB controller's JSON 1.0 endpoint for SQS-targeted requests.
 */
@ApplicationScoped
public class SqsJsonHandler {

    private final SqsService sqsService;
    private final ObjectMapper objectMapper;

    @Inject
    public SqsJsonHandler(SqsService sqsService, ObjectMapper objectMapper) {
        this.sqsService = sqsService;
        this.objectMapper = objectMapper;
    }

    public Response handle(String action, JsonNode request, String region) throws Exception {
        return switch (action) {
            case "CreateQueue" -> handleCreateQueue(request, region);
            case "DeleteQueue" -> handleDeleteQueue(request, region);
            case "ListQueues" -> handleListQueues(request, region);
            case "GetQueueUrl" -> handleGetQueueUrl(request, region);
            case "GetQueueAttributes" -> handleGetQueueAttributes(request, region);
            case "SendMessage" -> handleSendMessage(request, region);
            case "ReceiveMessage" -> handleReceiveMessage(request, region);
            case "DeleteMessage" -> handleDeleteMessage(request, region);
            case "DeleteMessageBatch" -> handleDeleteMessageBatch(request, region);
            case "SendMessageBatch" -> handleSendMessageBatch(request, region);
            case "ChangeMessageVisibility" -> handleChangeMessageVisibility(request, region);
            case "ChangeMessageVisibilityBatch" -> handleChangeMessageVisibilityBatch(request, region);
            case "SetQueueAttributes" -> handleSetQueueAttributes(request, region);
            case "TagQueue" -> handleTagQueue(request, region);
            case "UntagQueue" -> handleUntagQueue(request, region);
            case "ListQueueTags" -> handleListQueueTags(request, region);
            case "PurgeQueue" -> handlePurgeQueue(request, region);
            case "ListDeadLetterSourceQueues" -> handleListDeadLetterSourceQueues(request, region);
            case "StartMessageMoveTask" -> handleStartMessageMoveTask(request, region);
            case "ListMessageMoveTasks" -> handleListMessageMoveTasks(request, region);
            case "AddPermission" -> handleAddPermission(request, region);
            case "RemovePermission" -> handleRemovePermission(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleAddPermission(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        String label = request.path("Label").asText(null);
        List<String> accountIds = jsonNodeToList(request.path("AWSAccountIds"));
        List<String> actions = jsonNodeToList(request.path("Actions"));
        sqsService.addPermission(queueUrl, label, accountIds, actions, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleRemovePermission(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        String label = request.path("Label").asText(null);
        sqsService.removePermission(queueUrl, label, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private void writeSystemAttributesJson(ObjectNode msgNode, Message msg,
                                           java.util.Set<String> requested, String senderId) {
        if (requested.isEmpty()) {
            return;
        }
        boolean all = requested.contains("All");
        ObjectNode attrs = objectMapper.createObjectNode();
        if ((all || requested.contains("SenderId")) && senderId != null) {
            attrs.put("SenderId", senderId);
        }
        if (all || requested.contains("SentTimestamp")) {
            attrs.put("SentTimestamp", String.valueOf(msg.getSentTimestamp().toEpochMilli()));
        }
        if (all || requested.contains("ApproximateReceiveCount")) {
            attrs.put("ApproximateReceiveCount", String.valueOf(msg.getReceiveCount()));
        }
        if ((all || requested.contains("ApproximateFirstReceiveTimestamp"))
                && msg.getFirstReceiveTimestamp() != null) {
            attrs.put("ApproximateFirstReceiveTimestamp",
                    String.valueOf(msg.getFirstReceiveTimestamp().toEpochMilli()));
        }
        if (msg.getMessageGroupId() != null && (all || requested.contains("MessageGroupId"))) {
            attrs.put("MessageGroupId", msg.getMessageGroupId());
        }
        if (msg.getSequenceNumber() > 0 && (all || requested.contains("SequenceNumber"))) {
            attrs.put("SequenceNumber", String.valueOf(msg.getSequenceNumber()));
        }
        if (msg.getMessageDeduplicationId() != null
                && (all || requested.contains("MessageDeduplicationId"))) {
            attrs.put("MessageDeduplicationId", msg.getMessageDeduplicationId());
        }
        if (!attrs.isEmpty()) {
            msgNode.set("Attributes", attrs);
        }
    }

    private List<String> jsonNodeToList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private Response handleCreateQueue(JsonNode request, String region) {
        String queueName = request.path("QueueName").asText(null);
        Map<String, String> attributes = jsonNodeToMap(request.path("Attributes"));
        Map<String, String> tags = jsonNodeToMap(request.path("tags"));
        Queue queue = sqsService.createQueue(queueName, attributes, tags, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("QueueUrl", queue.getQueueUrl());
        return Response.ok(response).build();
    }

    private Response handleDeleteQueue(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        sqsService.deleteQueue(queueUrl, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListQueues(JsonNode request, String region) {
        String prefix = request.path("QueueNamePrefix").asText(null);
        List<Queue> queues = sqsService.listQueues(prefix, region);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode urls = response.putArray("QueueUrls");
        for (Queue q : queues) {
            urls.add(q.getQueueUrl());
        }
        return Response.ok(response).build();
    }

    private Response handleGetQueueUrl(JsonNode request, String region) {
        String queueName = request.path("QueueName").asText(null);
        String queueUrl = sqsService.getQueueUrl(queueName, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("QueueUrl", queueUrl);
        return Response.ok(response).build();
    }

    private Response handleGetQueueAttributes(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        List<String> attributeNames = new ArrayList<>();
        JsonNode namesNode = request.path("AttributeNames");
        if (namesNode.isArray()) {
            for (JsonNode n : namesNode) {
                attributeNames.add(n.asText());
            }
        }
        if (attributeNames.isEmpty()) {
            attributeNames.add("All");
        }

        Map<String, String> attributes = sqsService.getQueueAttributes(queueUrl, attributeNames, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode attrsNode = response.putObject("Attributes");
        for (var entry : attributes.entrySet()) {
            attrsNode.put(entry.getKey(), entry.getValue());
        }
        return Response.ok(response).build();
    }

    private Response handleSendMessage(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        String messageBody = request.path("MessageBody").asText(null);
        int delaySeconds = request.path("DelaySeconds").asInt(0);
        String messageGroupId = request.path("MessageGroupId").asText(null);
        String messageDeduplicationId = request.path("MessageDeduplicationId").asText(null);

        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        JsonNode attrsNode = request.path("MessageAttributes");
        if (attrsNode.isObject()) {
            attrsNode.fields().forEachRemaining(entry -> {
                String name = entry.getKey();
                String dataType = entry.getValue().path("DataType").asText(null);
                String stringValue = entry.getValue().path("StringValue").asText(null);
                String binaryValueBase64 = entry.getValue().path("BinaryValue").asText(null);
                if (dataType != null) {
                    if (binaryValueBase64 != null) {
                        byte[] binaryValue = Base64.getDecoder().decode(binaryValueBase64);
                        messageAttributes.put(name, new MessageAttributeValue(binaryValue, dataType));
                    } else if (stringValue != null) {
                        messageAttributes.put(name, new MessageAttributeValue(stringValue, dataType));
                    }
                }
            });
        }

        Message msg = sqsService.sendMessage(queueUrl, messageBody, delaySeconds,
                messageGroupId, messageDeduplicationId, messageAttributes, region);

        ObjectNode response = objectMapper.createObjectNode();
        response.put("MessageId", msg.getMessageId());
        response.put("MD5OfMessageBody", msg.getMd5OfBody());
        if (msg.getMd5OfMessageAttributes() != null) {
            response.put("MD5OfMessageAttributes", msg.getMd5OfMessageAttributes());
        }
        if (msg.getSequenceNumber() > 0) {
            response.put("SequenceNumber", String.valueOf(msg.getSequenceNumber()));
        }
        return Response.ok(response).build();
    }

    private Response handleReceiveMessage(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        int maxMessages = request.path("MaxNumberOfMessages").asInt(1);
        int visibilityTimeout = request.path("VisibilityTimeout").asInt(-1);
        int waitTimeSeconds = request.path("WaitTimeSeconds").asInt(0);

        java.util.Set<String> requestedAttrs = new java.util.LinkedHashSet<>();
        requestedAttrs.addAll(jsonNodeToList(request.path("AttributeNames")));
        requestedAttrs.addAll(jsonNodeToList(request.path("MessageSystemAttributeNames")));

        List<Message> messages = sqsService.receiveMessage(queueUrl, maxMessages,
                visibilityTimeout, waitTimeSeconds, region);
        String senderId = sqsService.senderIdFor(queueUrl);

        ObjectNode response = objectMapper.createObjectNode();
        // Match AWS: omit the Messages field entirely when no messages are
        // available, so SDK clients with InitializeCollections=false see null
        // instead of an empty list.
        if (messages.isEmpty()) {
            return Response.ok(response).build();
        }
        ArrayNode messagesArray = response.putArray("Messages");
        for (Message msg : messages) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msgNode.put("MessageId", msg.getMessageId());
            msgNode.put("ReceiptHandle", msg.getReceiptHandle());
            msgNode.put("MD5OfBody", msg.getMd5OfBody());
            if (msg.getMd5OfMessageAttributes() != null) {
                msgNode.put("MD5OfMessageAttributes", msg.getMd5OfMessageAttributes());
            }
            msgNode.put("Body", msg.getBody());

            writeSystemAttributesJson(msgNode, msg, requestedAttrs, senderId);

            if (msg.getMessageAttributes() != null && !msg.getMessageAttributes().isEmpty()) {
                ObjectNode msgAttrs = msgNode.putObject("MessageAttributes");
                for (var entry : msg.getMessageAttributes().entrySet()) {
                    ObjectNode valNode = msgAttrs.putObject(entry.getKey());
                    valNode.put("DataType", entry.getValue().getDataType());
                    if (entry.getValue().getBinaryValue() != null) {
                        valNode.put("BinaryValue", Base64.getEncoder().encodeToString(entry.getValue().getBinaryValue()));
                    } else {
                        valNode.put("StringValue", entry.getValue().getStringValue());
                    }
                }
            }

            messagesArray.add(msgNode);
        }
        return Response.ok(response).build();
    }

    private Response handleDeleteMessage(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        String receiptHandle = request.path("ReceiptHandle").asText(null);
        sqsService.deleteMessage(queueUrl, receiptHandle, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleChangeMessageVisibility(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        String receiptHandle = request.path("ReceiptHandle").asText(null);
        int visibilityTimeout = request.path("VisibilityTimeout").asInt(30);
        sqsService.changeMessageVisibility(queueUrl, receiptHandle, visibilityTimeout, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteMessageBatch(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        JsonNode entries = request.path("Entries");

        ArrayNode successful = objectMapper.createArrayNode();
        ArrayNode failed = objectMapper.createArrayNode();

        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                String id = entry.path("Id").asText();
                String receiptHandle = entry.path("ReceiptHandle").asText(null);
                try {
                    sqsService.deleteMessage(queueUrl, receiptHandle, region);
                    ObjectNode success = objectMapper.createObjectNode();
                    success.put("Id", id);
                    successful.add(success);
                } catch (AwsException e) {
                    ObjectNode fail = objectMapper.createObjectNode();
                    fail.put("Id", id);
                    fail.put("Code", e.getErrorCode());
                    fail.put("Message", e.getMessage());
                    fail.put("SenderFault", true);
                    failed.add(fail);
                }
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Successful", successful);
        if (!failed.isEmpty()) {
            response.set("Failed", failed);
        }
        return Response.ok(response).build();
    }

    private Response handleSendMessageBatch(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        JsonNode entries = request.path("Entries");

        ArrayNode successful = objectMapper.createArrayNode();
        ArrayNode failed = objectMapper.createArrayNode();

        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                String id = entry.path("Id").asText();
                String messageBody = entry.path("MessageBody").asText(null);
                int delaySeconds = entry.path("DelaySeconds").asInt(0);
                String messageGroupId = entry.path("MessageGroupId").asText(null);
                String messageDeduplicationId = entry.path("MessageDeduplicationId").asText(null);

                Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
                JsonNode attrsNode = entry.path("MessageAttributes");
                if (attrsNode.isObject()) {
                    attrsNode.fields().forEachRemaining(attrEntry -> {
                        String name = attrEntry.getKey();
                        String dataType = attrEntry.getValue().path("DataType").asText(null);
                        String stringValue = attrEntry.getValue().path("StringValue").asText(null);
                        String binaryValueBase64 = attrEntry.getValue().path("BinaryValue").asText(null);
                        if (dataType != null) {
                            if (binaryValueBase64 != null) {
                                byte[] binaryValue = Base64.getDecoder().decode(binaryValueBase64);
                                messageAttributes.put(name, new MessageAttributeValue(binaryValue, dataType));
                            } else if (stringValue != null) {
                                messageAttributes.put(name, new MessageAttributeValue(stringValue, dataType));
                            }
                        }
                    });
                }

                try {
                    Message msg = sqsService.sendMessage(queueUrl, messageBody, delaySeconds,
                            messageGroupId, messageDeduplicationId, messageAttributes, region);
                    ObjectNode success = objectMapper.createObjectNode();
                    success.put("Id", id);
                    success.put("MessageId", msg.getMessageId());
                    success.put("MD5OfMessageBody", msg.getMd5OfBody());
                    if (msg.getMd5OfMessageAttributes() != null) {
                        success.put("MD5OfMessageAttributes", msg.getMd5OfMessageAttributes());
                    }
                    if (msg.getSequenceNumber() > 0) {
                        success.put("SequenceNumber", String.valueOf(msg.getSequenceNumber()));
                    }
                    successful.add(success);
                } catch (AwsException e) {
                    ObjectNode fail = objectMapper.createObjectNode();
                    fail.put("Id", id);
                    fail.put("Code", e.getErrorCode());
                    fail.put("Message", e.getMessage());
                    fail.put("SenderFault", true);
                    failed.add(fail);
                }
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Successful", successful);
        if (!failed.isEmpty()) {
            response.set("Failed", failed);
        }
        return Response.ok(response).build();
    }

    private Response handleListDeadLetterSourceQueues(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        List<String> queues = sqsService.listDeadLetterSourceQueues(queueUrl, region);
        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode urls = response.putArray("queueUrls");
        for (String q : queues) {
            urls.add(q);
        }
        return Response.ok(response).build();
    }

    private Response handleStartMessageMoveTask(JsonNode request, String region) {
        String sourceArn = request.path("SourceArn").asText(null);
        String destinationArn = request.path("DestinationArn").asText(null);
        String taskHandle = sqsService.startMessageMoveTask(sourceArn, destinationArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.put("TaskHandle", taskHandle);
        return Response.ok(response).build();
    }

    private Response handleListMessageMoveTasks(JsonNode request, String region) {
        String sourceArn = request.path("SourceArn").asText(null);
        sqsService.listMessageMoveTasks(sourceArn, region);
        ObjectNode response = objectMapper.createObjectNode();
        response.putArray("Results");
        return Response.ok(response).build();
    }

    private Response handleSetQueueAttributes(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        Map<String, String> attributes = jsonNodeToMap(request.path("Attributes"));
        sqsService.setQueueAttributes(queueUrl, attributes, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleChangeMessageVisibilityBatch(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        JsonNode entries = request.path("Entries");

        List<SqsService.ChangeVisibilityBatchEntry> batchEntries = new ArrayList<>();
        if (entries.isArray()) {
            for (JsonNode entry : entries) {
                batchEntries.add(new SqsService.ChangeVisibilityBatchEntry(
                        entry.path("Id").asText(),
                        entry.path("ReceiptHandle").asText(null),
                        entry.path("VisibilityTimeout").asInt(30)));
            }
        }

        List<SqsService.BatchResultEntry> results =
                sqsService.changeMessageVisibilityBatch(queueUrl, batchEntries, region);

        ArrayNode successful = objectMapper.createArrayNode();
        ArrayNode failed = objectMapper.createArrayNode();
        for (var result : results) {
            if (result.success()) {
                ObjectNode success = objectMapper.createObjectNode();
                success.put("Id", result.id());
                successful.add(success);
            } else {
                ObjectNode fail = objectMapper.createObjectNode();
                fail.put("Id", result.id());
                fail.put("Code", result.errorCode());
                fail.put("Message", result.errorMessage());
                fail.put("SenderFault", true);
                failed.add(fail);
            }
        }

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Successful", successful);
        if (!failed.isEmpty()) {
            response.set("Failed", failed);
        }
        return Response.ok(response).build();
    }

    private Response handleTagQueue(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        Map<String, String> tags = jsonNodeToMap(request.path("Tags"));
        sqsService.tagQueue(queueUrl, tags, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleUntagQueue(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        List<String> tagKeys = new ArrayList<>();
        JsonNode keysNode = request.path("TagKeys");
        if (keysNode.isArray()) {
            for (JsonNode key : keysNode) {
                tagKeys.add(key.asText());
            }
        }
        sqsService.untagQueue(queueUrl, tagKeys, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleListQueueTags(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        Map<String, String> tags = sqsService.listQueueTags(queueUrl, region);

        ObjectNode response = objectMapper.createObjectNode();
        ObjectNode tagsNode = response.putObject("Tags");
        for (var entry : tags.entrySet()) {
            tagsNode.put(entry.getKey(), entry.getValue());
        }
        return Response.ok(response).build();
    }

    private Response handlePurgeQueue(JsonNode request, String region) {
        String queueUrl = request.path("QueueUrl").asText(null);
        sqsService.purgeQueue(queueUrl, region);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Map<String, String> jsonNodeToMap(JsonNode node) {
        Map<String, String> map = new HashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), entry.getValue().asText()));
        }
        return map;
    }
}
