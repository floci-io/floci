package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

public final class SnsMessageAttributes {

    private SnsMessageAttributes() {}

    public static Map<String, MessageAttributeValue> parse(JsonNode attrsNode) {
        Map<String, MessageAttributeValue> attributes = new HashMap<>();
        if (!attrsNode.isObject()) {
            return attributes;
        }
        attrsNode.fields().forEachRemaining(entry -> {
            JsonNode valueNode = entry.getValue();
            String binaryValueBase64 = valueNode.path("BinaryValue").asText(null);
            String defaultDataType = binaryValueBase64 != null ? "Binary" : "String";
            String dataType = valueNode.path("DataType").asText(defaultDataType);
            if (binaryValueBase64 != null) {
                byte[] binaryValue;
                try {
                    binaryValue = Base64.getDecoder().decode(binaryValueBase64);
                } catch (IllegalArgumentException e) {
                    throw new AwsException("InvalidParameterValue",
                            "Invalid binary value for message attribute '" + entry.getKey() + "': not valid base64.", 400);
                }
                attributes.put(entry.getKey(), new MessageAttributeValue(binaryValue, dataType));
            } else {
                String stringValue = valueNode.path("StringValue").asText();
                attributes.put(entry.getKey(), new MessageAttributeValue(stringValue, dataType));
            }
        });
        return attributes;
    }
}
