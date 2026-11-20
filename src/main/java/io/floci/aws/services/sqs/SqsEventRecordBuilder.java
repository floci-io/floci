package io.floci.aws.services.sqs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.sqs.model.Message;

import java.util.Base64;
import java.util.function.LongSupplier;

/** The SQS record shared by Lambda event source mappings and EventBridge Pipes. */
public final class SqsEventRecordBuilder {

    private SqsEventRecordBuilder() {
    }

    public static ObjectNode build(ObjectMapper mapper, Message msg, String queueArn,
                                   String region, LongSupplier clockMs) {
        ObjectNode record = mapper.createObjectNode();
        record.put("messageId", msg.getMessageId());
        record.put("receiptHandle", msg.getReceiptHandle());
        record.put("body", msg.getBody());
        ObjectNode attrs = record.putObject("attributes");
        attrs.put("ApproximateReceiveCount", String.valueOf(msg.getReceiveCount()));
        attrs.put("SentTimestamp", String.valueOf(msg.getSentTimestamp().toEpochMilli()));
        attrs.put("SenderId", AwsArnUtils.accountOrDefault(queueArn, "000000000000"));
        attrs.put("ApproximateFirstReceiveTimestamp",
                String.valueOf(msg.getFirstReceiveTimestamp() != null
                        ? msg.getFirstReceiveTimestamp().toEpochMilli()
                        : clockMs.getAsLong()));
        if (msg.getSequenceNumber() > 0) {
            attrs.put("SequenceNumber", String.valueOf(msg.getSequenceNumber()));
        }
        if (msg.getMessageGroupId() != null) {
            attrs.put("MessageGroupId", msg.getMessageGroupId());
        }
        if (msg.getMessageDeduplicationId() != null) {
            attrs.put("MessageDeduplicationId", msg.getMessageDeduplicationId());
        }
        // Populate messageAttributes from the message model
        ObjectNode msgAttrs = record.putObject("messageAttributes");
        if (msg.getMessageAttributes() != null) {
            msg.getMessageAttributes().forEach((name, val) -> {
                ObjectNode attrNode = msgAttrs.putObject(name);
                attrNode.put("dataType", val.getDataType() != null ? val.getDataType() : "String");
                if (val.getBinaryValue() != null) {
                    attrNode.put("binaryValue",
                            Base64.getEncoder().encodeToString(val.getBinaryValue()));
                } else if (val.getStringValue() != null) {
                    attrNode.put("stringValue", val.getStringValue());
                }
                attrNode.putArray("stringListValues");
                attrNode.putArray("binaryListValues");
            });
        }
        record.put("md5OfBody", msg.getMd5OfBody() != null ? msg.getMd5OfBody() : "");
        if (msg.getMd5OfMessageAttributes() != null) {
            record.put("md5OfMessageAttributes", msg.getMd5OfMessageAttributes());
        }
        record.put("eventSource", "aws:sqs");
        record.put("eventSourceARN", queueArn);
        record.put("awsRegion", region);
        return record;
    }
}
