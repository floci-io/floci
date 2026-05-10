package io.github.hectorvent.floci.services.sqs;

import io.github.hectorvent.floci.core.common.*;
import io.github.hectorvent.floci.services.sqs.model.Message;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Query-protocol handler for SQS actions.
 * Receives pre-dispatched calls from {@link AwsQueryController}.
 */
@ApplicationScoped
public class SqsQueryHandler {

    private static final Logger LOG = Logger.getLogger(SqsQueryHandler.class);

    private final SqsService sqsService;

    @Inject
    public SqsQueryHandler(SqsService sqsService) {
        this.sqsService = sqsService;
    }

    public Response handle(String action, MultivaluedMap<String, String> params, String region) {
        LOG.debugv("SQS action: {0}", action);

        return switch (action) {
            case "CreateQueue" -> handleCreateQueue(params, region);
            case "DeleteQueue" -> handleDeleteQueue(params, region);
            case "ListQueues" -> handleListQueues(params, region);
            case "GetQueueUrl" -> handleGetQueueUrl(params, region);
            case "GetQueueAttributes" -> handleGetQueueAttributes(params, region);
            case "SendMessage" -> handleSendMessage(params, region);
            case "ReceiveMessage" -> handleReceiveMessage(params, region);
            case "DeleteMessage" -> handleDeleteMessage(params, region);
            case "DeleteMessageBatch" -> handleDeleteMessageBatch(params, region);
            case "SendMessageBatch" -> handleSendMessageBatch(params, region);
            case "ChangeMessageVisibility" -> handleChangeMessageVisibility(params, region);
            case "ChangeMessageVisibilityBatch" -> handleChangeMessageVisibilityBatch(params, region);
            case "SetQueueAttributes" -> handleSetQueueAttributes(params, region);
            case "TagQueue" -> handleTagQueue(params, region);
            case "UntagQueue" -> handleUntagQueue(params, region);
            case "ListQueueTags" -> handleListQueueTags(params, region);
            case "PurgeQueue" -> handlePurgeQueue(params, region);
            case "ListDeadLetterSourceQueues" -> handleListDeadLetterSourceQueues(params, region);
            case "StartMessageMoveTask" -> handleStartMessageMoveTask(params, region);
            case "ListMessageMoveTasks" -> handleListMessageMoveTasks(params, region);
            case "AddPermission" -> handleAddPermission(params, region);
            case "RemovePermission" -> handleRemovePermission(params, region);
            default -> AwsQueryResponse.error("UnsupportedOperation",
                    "Operation " + action + " is not supported by SQS.", AwsNamespaces.SQS, 400);
        };
    }

    private Response handleAddPermission(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        String label = getParam(params, "Label");
        List<String> accountIds = collectIndexed(params, "AWSAccountId.");
        List<String> actions = collectIndexed(params, "ActionName.");
        sqsService.addPermission(queueUrl, label, accountIds, actions, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("AddPermission", null)).build();
    }

    private Response handleRemovePermission(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        String label = getParam(params, "Label");
        sqsService.removePermission(queueUrl, label, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("RemovePermission", null)).build();
    }

    private List<String> collectIndexed(MultivaluedMap<String, String> params, String prefix) {
        List<String> values = new ArrayList<>();
        for (int i = 1; ; i++) {
            String v = getParam(params, prefix + i);
            if (v == null) break;
            values.add(v);
        }
        return values;
    }

    private Response handleCreateQueue(MultivaluedMap<String, String> params, String region) {
        String queueName = getParam(params, "QueueName");
        Map<String, String> attributes = extractAttributes(params);
        Map<String, String> tags = extractTags(params);
        Queue queue = sqsService.createQueue(queueName, attributes, tags, region);

        String result = new XmlBuilder().elem("QueueUrl", queue.getQueueUrl()).build();
        return Response.ok(AwsQueryResponse.envelope("CreateQueue", null, result)).build();
    }

    private Response handleDeleteQueue(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        sqsService.deleteQueue(queueUrl, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteQueue", null)).build();
    }

    private Response handleListQueues(MultivaluedMap<String, String> params, String region) {
        String prefix = getParam(params, "QueueNamePrefix");
        List<Queue> queues = sqsService.listQueues(prefix, region);

        var xml = new XmlBuilder();
        for (Queue q : queues) {
            xml.elem("QueueUrl", q.getQueueUrl());
        }
        return Response.ok(AwsQueryResponse.envelope("ListQueues", null, xml.build())).build();
    }

    private Response handleGetQueueUrl(MultivaluedMap<String, String> params, String region) {
        String queueName = getParam(params, "QueueName");
        String queueUrl = sqsService.getQueueUrl(queueName, region);

        String result = new XmlBuilder().elem("QueueUrl", queueUrl).build();
        return Response.ok(AwsQueryResponse.envelope("GetQueueUrl", null, result)).build();
    }

    private Response handleGetQueueAttributes(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        List<String> attributeNames = new ArrayList<>();
        for (int i = 1; ; i++) {
            String name = getParam(params, "AttributeName." + i);
            if (name == null) break;
            attributeNames.add(name);
        }
        if (attributeNames.isEmpty()) {
            attributeNames.add("All");
        }

        Map<String, String> attributes = sqsService.getQueueAttributes(queueUrl, attributeNames, region);

        var xml = new XmlBuilder();
        for (var entry : attributes.entrySet()) {
            xml.start("Attribute")
               .elem("Name", entry.getKey())
               .elem("Value", entry.getValue())
               .end("Attribute");
        }
        return Response.ok(AwsQueryResponse.envelope("GetQueueAttributes", null, xml.build())).build();
    }

    private Response handleSendMessage(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        String body = getParam(params, "MessageBody");
        int delaySeconds = getIntParam(params, "DelaySeconds", 0);
        String messageGroupId = getParam(params, "MessageGroupId");
        String messageDeduplicationId = getParam(params, "MessageDeduplicationId");

        Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
        for (int i = 1; ; i++) {
            String name = getParam(params, "MessageAttribute." + i + ".Name");
            if (name == null) break;
            String dataType = getParam(params, "MessageAttribute." + i + ".Value.DataType");
            String stringValue = getParam(params, "MessageAttribute." + i + ".Value.StringValue");
            String binaryValueBase64 = getParam(params, "MessageAttribute." + i + ".Value.BinaryValue");
            if (dataType != null) {
                if (binaryValueBase64 != null) {
                    byte[] binaryValue = Base64.getDecoder().decode(binaryValueBase64);
                    messageAttributes.put(name, new MessageAttributeValue(binaryValue, dataType));
                } else if (stringValue != null) {
                    messageAttributes.put(name, new MessageAttributeValue(stringValue, dataType));
                }
            }
        }

        Message msg = sqsService.sendMessage(queueUrl, body, delaySeconds, messageGroupId, messageDeduplicationId, messageAttributes, region);

        var xml = new XmlBuilder()
                .elem("MessageId", msg.getMessageId())
                .elem("MD5OfMessageBody", msg.getMd5OfBody());
        if (msg.getMd5OfMessageAttributes() != null) {
            xml.elem("MD5OfMessageAttributes", msg.getMd5OfMessageAttributes());
        }
        if (msg.getSequenceNumber() > 0) {
            xml.elem("SequenceNumber", msg.getSequenceNumber());
        }
        return Response.ok(AwsQueryResponse.envelope("SendMessage", null, xml.build())).build();
    }

    private Response handleReceiveMessage(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        int maxMessages = getIntParam(params, "MaxNumberOfMessages", 1);
        int visibilityTimeout = getIntParam(params, "VisibilityTimeout", -1);
        int waitTimeSeconds = getIntParam(params, "WaitTimeSeconds", 0);

        List<Message> messages = sqsService.receiveMessage(queueUrl, maxMessages, visibilityTimeout, waitTimeSeconds, region);

        var xml = new XmlBuilder();
        for (Message msg : messages) {
            xml.start("Message")
               .elem("MessageId", msg.getMessageId())
               .elem("ReceiptHandle", msg.getReceiptHandle())
               .elem("MD5OfBody", msg.getMd5OfBody());
            if (msg.getMd5OfMessageAttributes() != null) {
                xml.elem("MD5OfMessageAttributes", msg.getMd5OfMessageAttributes());
            }
            xml.elem("Body", msg.getBody())
               .start("Attribute").elem("Name", "ApproximateReceiveCount")
                 .elem("Value", String.valueOf(msg.getReceiveCount())).end("Attribute")
               .start("Attribute").elem("Name", "SentTimestamp")
                 .elem("Value", String.valueOf(msg.getSentTimestamp().toEpochMilli())).end("Attribute");
            if (msg.getMessageGroupId() != null) {
                xml.start("Attribute").elem("Name", "MessageGroupId")
                   .elem("Value", msg.getMessageGroupId()).end("Attribute");
            }
            if (msg.getSequenceNumber() > 0) {
                xml.start("Attribute").elem("Name", "SequenceNumber")
                   .elem("Value", String.valueOf(msg.getSequenceNumber())).end("Attribute");
            }
            if (msg.getMessageDeduplicationId() != null) {
                xml.start("Attribute").elem("Name", "MessageDeduplicationId")
                   .elem("Value", msg.getMessageDeduplicationId()).end("Attribute");
            }
            if (msg.getMessageAttributes() != null && !msg.getMessageAttributes().isEmpty()) {
                for (var entry : msg.getMessageAttributes().entrySet()) {
                    xml.start("MessageAttribute")
                       .elem("Name", entry.getKey())
                       .start("Value")
                       .elem("DataType", entry.getValue().getDataType());
                    if (entry.getValue().getBinaryValue() != null) {
                        xml.elem("BinaryValue", Base64.getEncoder().encodeToString(entry.getValue().getBinaryValue()));
                    } else {
                        xml.elem("StringValue", entry.getValue().getStringValue());
                    }
                    xml.end("Value")
                       .end("MessageAttribute");
                }
            }
            xml.end("Message");
        }
        return Response.ok(AwsQueryResponse.envelope("ReceiveMessage", null, xml.build())).build();
    }

    private Response handleDeleteMessage(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        String receiptHandle = getParam(params, "ReceiptHandle");
        sqsService.deleteMessage(queueUrl, receiptHandle, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("DeleteMessage", null)).build();
    }

    private Response handleChangeMessageVisibility(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        String receiptHandle = getParam(params, "ReceiptHandle");
        int visibilityTimeout = getIntParam(params, "VisibilityTimeout", 30);
        sqsService.changeMessageVisibility(queueUrl, receiptHandle, visibilityTimeout, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("ChangeMessageVisibility", null)).build();
    }

    private Response handleDeleteMessageBatch(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        var xml = new XmlBuilder();

        for (int i = 1; ; i++) {
            String id = getParam(params, "DeleteMessageBatchRequestEntry." + i + ".Id");
            if (id == null) break;
            String receiptHandle = getParam(params, "DeleteMessageBatchRequestEntry." + i + ".ReceiptHandle");
            try {
                sqsService.deleteMessage(queueUrl, receiptHandle, region);
                xml.start("DeleteMessageBatchResultEntry").elem("Id", id).end("DeleteMessageBatchResultEntry");
            } catch (AwsException e) {
                xml.start("BatchResultErrorEntry")
                   .elem("Id", id)
                   .elem("Code", e.getErrorCode())
                   .elem("Message", e.getMessage())
                   .elem("SenderFault", "true")
                   .end("BatchResultErrorEntry");
            }
        }

        return Response.ok(AwsQueryResponse.envelope("DeleteMessageBatch", null, xml.build())).build();
    }

    private Response handleSendMessageBatch(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        var xml = new XmlBuilder();

        for (int i = 1; ; i++) {
            String id = getParam(params, "SendMessageBatchRequestEntry." + i + ".Id");
            if (id == null) break;
            String body = getParam(params, "SendMessageBatchRequestEntry." + i + ".MessageBody");
            int delaySeconds = getIntParam(params, "SendMessageBatchRequestEntry." + i + ".DelaySeconds", 0);
            String messageGroupId = getParam(params, "SendMessageBatchRequestEntry." + i + ".MessageGroupId");
            String messageDeduplicationId = getParam(params, "SendMessageBatchRequestEntry." + i + ".MessageDeduplicationId");

            Map<String, MessageAttributeValue> messageAttributes = new HashMap<>();
            for (int j = 1; ; j++) {
                String name = getParam(params, "SendMessageBatchRequestEntry." + i + ".MessageAttribute." + j + ".Name");
                if (name == null) break;
                String dataType = getParam(params, "SendMessageBatchRequestEntry." + i + ".MessageAttribute." + j + ".Value.DataType");
                String stringValue = getParam(params, "SendMessageBatchRequestEntry." + i + ".MessageAttribute." + j + ".Value.StringValue");
                String binaryValueBase64 = getParam(params, "SendMessageBatchRequestEntry." + i + ".MessageAttribute." + j + ".Value.BinaryValue");
                if (dataType != null) {
                    if (binaryValueBase64 != null) {
                        byte[] binaryValue = Base64.getDecoder().decode(binaryValueBase64);
                        messageAttributes.put(name, new MessageAttributeValue(binaryValue, dataType));
                    } else if (stringValue != null) {
                        messageAttributes.put(name, new MessageAttributeValue(stringValue, dataType));
                    }
                }
            }

            try {
                var msg = sqsService.sendMessage(queueUrl, body, delaySeconds, messageGroupId, messageDeduplicationId, messageAttributes, region);
                xml.start("SendMessageBatchResultEntry")
                   .elem("Id", id)
                   .elem("MessageId", msg.getMessageId())
                   .elem("MD5OfMessageBody", msg.getMd5OfBody());
                if (msg.getMd5OfMessageAttributes() != null) {
                    xml.elem("MD5OfMessageAttributes", msg.getMd5OfMessageAttributes());
                }
                if (msg.getSequenceNumber() > 0) {
                    xml.elem("SequenceNumber", msg.getSequenceNumber());
                }
                xml.end("SendMessageBatchResultEntry");
            } catch (AwsException e) {
                xml.start("BatchResultErrorEntry")
                   .elem("Id", id)
                   .elem("Code", e.getErrorCode())
                   .elem("Message", e.getMessage())
                   .elem("SenderFault", "true")
                   .end("BatchResultErrorEntry");
            }
        }

        return Response.ok(AwsQueryResponse.envelope("SendMessageBatch", null, xml.build())).build();
    }

    private Response handleListDeadLetterSourceQueues(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        List<String> queues = sqsService.listDeadLetterSourceQueues(queueUrl, region);
        var xml = new XmlBuilder();
        for (String q : queues) {
            xml.elem("QueueUrl", q);
        }
        return Response.ok(AwsQueryResponse.envelope("ListDeadLetterSourceQueues", null, xml.build())).build();
    }

    private Response handleStartMessageMoveTask(MultivaluedMap<String, String> params, String region) {
        String sourceArn = getParam(params, "SourceArn");
        String destinationArn = getParam(params, "DestinationArn");
        String taskHandle = sqsService.startMessageMoveTask(sourceArn, destinationArn, region);
        var xml = new XmlBuilder().elem("TaskHandle", taskHandle);
        return Response.ok(AwsQueryResponse.envelope("StartMessageMoveTask", null, xml.build())).build();
    }

    private Response handleListMessageMoveTasks(MultivaluedMap<String, String> params, String region) {
        String sourceArn = getParam(params, "SourceArn");
        sqsService.listMessageMoveTasks(sourceArn, region);
        var xml = new XmlBuilder(); // Empty list for mock
        return Response.ok(AwsQueryResponse.envelope("ListMessageMoveTasks", null, xml.build())).build();
    }

    private Response handlePurgeQueue(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        sqsService.purgeQueue(queueUrl, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("PurgeQueue", null)).build();
    }

    private Response handleSetQueueAttributes(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        Map<String, String> attributes = extractAttributes(params);
        sqsService.setQueueAttributes(queueUrl, attributes, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("SetQueueAttributes", null)).build();
    }

    private Response handleChangeMessageVisibilityBatch(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        var entries = new ArrayList<SqsService.ChangeVisibilityBatchEntry>();
        for (int i = 1; ; i++) {
            String id = getParam(params, "ChangeMessageVisibilityBatchRequestEntry." + i + ".Id");
            if (id == null) break;
            String receiptHandle = getParam(params, "ChangeMessageVisibilityBatchRequestEntry." + i + ".ReceiptHandle");
            int visibilityTimeout = getIntParam(params, "ChangeMessageVisibilityBatchRequestEntry." + i + ".VisibilityTimeout", 30);
            entries.add(new SqsService.ChangeVisibilityBatchEntry(id, receiptHandle, visibilityTimeout));
        }

        var results = sqsService.changeMessageVisibilityBatch(queueUrl, entries, region);
        var xml = new XmlBuilder();
        for (var result : results) {
            if (result.success()) {
                xml.start("ChangeMessageVisibilityBatchResultEntry")
                   .elem("Id", result.id())
                   .end("ChangeMessageVisibilityBatchResultEntry");
            } else {
                xml.start("BatchResultErrorEntry")
                   .elem("Id", result.id())
                   .elem("Code", result.errorCode())
                   .elem("Message", result.errorMessage())
                   .elem("SenderFault", "true")
                   .end("BatchResultErrorEntry");
            }
        }
        return Response.ok(AwsQueryResponse.envelope("ChangeMessageVisibilityBatch", null, xml.build())).build();
    }

    private Response handleTagQueue(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        Map<String, String> tags = new HashMap<>();
        for (int i = 1; ; i++) {
            String key = getParam(params, "Tag." + i + ".Key");
            String value = getParam(params, "Tag." + i + ".Value");
            if (key == null) break;
            tags.put(key, value);
        }
        sqsService.tagQueue(queueUrl, tags, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("TagQueue", null)).build();
    }

    private Response handleUntagQueue(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        List<String> tagKeys = new ArrayList<>();
        for (int i = 1; ; i++) {
            String key = getParam(params, "TagKey." + i);
            if (key == null) break;
            tagKeys.add(key);
        }
        sqsService.untagQueue(queueUrl, tagKeys, region);
        return Response.ok(AwsQueryResponse.envelopeNoResult("UntagQueue", null)).build();
    }

    private Response handleListQueueTags(MultivaluedMap<String, String> params, String region) {
        String queueUrl = getParam(params, "QueueUrl");
        Map<String, String> tags = sqsService.listQueueTags(queueUrl, region);

        var xml = new XmlBuilder();
        for (var entry : tags.entrySet()) {
            xml.start("Tag")
               .elem("Key", entry.getKey())
               .elem("Value", entry.getValue())
               .end("Tag");
        }
        return Response.ok(AwsQueryResponse.envelope("ListQueueTags", null, xml.build())).build();
    }

    // --- Helpers ---

    private String getParam(MultivaluedMap<String, String> params, String name) {
        return params.getFirst(name);
    }

    private int getIntParam(MultivaluedMap<String, String> params, String name, int defaultValue) {
        String value = params.getFirst(name);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Map<String, String> extractAttributes(MultivaluedMap<String, String> params) {
        Map<String, String> attributes = new HashMap<>();
        for (int i = 1; ; i++) {
            String name = getParam(params, "Attribute." + i + ".Name");
            String value = getParam(params, "Attribute." + i + ".Value");
            if (name == null) break;
            attributes.put(name, value);
        }
        return attributes;
    }

    private Map<String, String> extractTags(MultivaluedMap<String, String> params) {
        Map<String, String> tags = new HashMap<>();
        for (int i = 1; ; i++) {
            String key = getParam(params, "Tag." + i + ".Key");
            String value = getParam(params, "Tag." + i + ".Value");
            if (key == null) break;
            tags.put(key, value);
        }
        return tags;
    }

    Response xmlErrorResponse(String code, String message, int status) {
        return AwsQueryResponse.error(code, message, AwsNamespaces.SQS, status);
    }
}
