package io.github.hectorvent.floci.services.bedrockruntime.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsEventStreamEncoder;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;

/**
 * Frames Bedrock ConverseStream events (messageStart, contentBlockDelta, ...) as
 * {@code application/vnd.amazon.eventstream} messages and writes each one to the response
 * {@link OutputStream} as soon as it's known, so SDK clients see incremental delivery instead
 * of the whole response landing at once - the same binary framing S3 Select and Kinesis
 * SubscribeToShard build elsewhere in Floci, just written frame-by-frame instead of batched.
 */
final class BedrockStreamEncoder {

    private BedrockStreamEncoder() {
    }

    /** Writes a normal event frame ({@code :message-type: event}), e.g. messageStart, contentBlockDelta. */
    static void writeEvent(ObjectMapper mapper, OutputStream out, String eventType, ObjectNode payload) {
        writeFrame(mapper, out, "event", ":event-type", eventType, payload);
    }

    /**
     * Writes a modeled-exception frame ({@code :message-type: exception}) - the wire shape real
     * Bedrock uses to report a stream failure after the HTTP 200 response has already been
     * committed. The AWS SDK's generic JSON error unmarshaller reads {@code :exception-type} to
     * pick the matching modeled exception class (e.g. ModelStreamErrorException), the same way
     * it reads {@code x-amzn-ErrorType} for a regular non-streaming error response.
     */
    static void writeException(ObjectMapper mapper, OutputStream out, String exceptionType, ObjectNode payload) {
        writeFrame(mapper, out, "exception", ":exception-type", exceptionType, payload);
    }

    private static void writeFrame(ObjectMapper mapper, OutputStream out, String messageType, String typeHeaderName,
            String typeValue, ObjectNode payload) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put(":message-type", messageType);
        headers.put(typeHeaderName, typeValue);
        headers.put(":content-type", "application/json");
        try {
            out.write(AwsEventStreamEncoder.encodeMessage(headers, mapper.writeValueAsBytes(payload)));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
