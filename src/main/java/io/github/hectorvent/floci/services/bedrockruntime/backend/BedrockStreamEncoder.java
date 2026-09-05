package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsEventStreamEncoder;
import io.github.hectorvent.floci.core.common.AwsException;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Frames a sequence of Bedrock ConverseStream events (messageStart, contentBlockDelta, ...)
 * as {@code application/vnd.amazon.eventstream} messages, one per event, concatenated into
 * a single response body - the same one-shot framing S3 Select and Kinesis SubscribeToShard
 * use elsewhere in Floci, rather than true incremental HTTP chunking.
 */
final class BedrockStreamEncoder {

    private BedrockStreamEncoder() {
    }

    record Event(String type, ObjectNode payload) {
    }

    static byte[] encode(ObjectMapper mapper, List<Event> events) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (Event event : events) {
                LinkedHashMap<String, String> headers = new LinkedHashMap<>();
                headers.put(":message-type", "event");
                headers.put(":event-type", event.type());
                headers.put(":content-type", "application/json");
                out.write(AwsEventStreamEncoder.encodeMessage(headers, mapper.writeValueAsBytes(event.payload())));
            }
            return out.toByteArray();
        } catch (Exception e) {
            throw new AwsException("InternalServerException",
                    "Failed to encode ConverseStream response: " + e.getMessage(), 500);
        }
    }
}
