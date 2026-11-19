package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.testing.EventStreamMessages;
import io.floci.aws.testing.EventStreamMessages.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LambdaInvokeResponseStreamTest {

    private LambdaInvokeResponseStream stream;

    @BeforeEach
    void setUp() {
        stream = new LambdaInvokeResponseStream(new ObjectMapper());
    }

    @Test
    void streamedResponseSendsItsChunksThenAnEmptyInvokeComplete() throws IOException {
        stream.onStart(null);
        stream.onChunk("hello ".getBytes(StandardCharsets.UTF_8));
        stream.onChunk(new byte[0]);
        stream.onChunk("world".getBytes(StandardCharsets.UTF_8));

        List<Message> messages = write(new InvokeResult(200, null, new byte[0], null, "r"), false);

        assertEquals(List.of("PayloadChunk", "PayloadChunk", "InvokeComplete"), types(messages));
        assertEquals("hello ", messages.get(0).text());
        assertEquals("world", messages.get(1).text());
        assertEquals("{}", messages.get(2).text());
        assertEquals("application/octet-stream", stream.remappedContentType(done(new InvokeResult())));
    }

    @Test
    void framesCarryTheirHeadersInOrder() throws IOException {
        stream.onStart("text/plain");
        stream.onChunk(new byte[] {1});

        List<Message> messages = write(new InvokeResult(200, null, new byte[0], null, "r"), false);

        assertEquals(List.of(":event-type", ":content-type", ":message-type"),
                List.copyOf(messages.get(0).headers().keySet()));
        assertEquals("application/octet-stream", messages.get(0).headers().get(":content-type"));
        assertEquals("event", messages.get(0).headers().get(":message-type"));
        assertEquals(List.of(":event-type", ":content-type", ":message-type"),
                List.copyOf(messages.get(1).headers().keySet()));
        assertEquals("application/json", messages.get(1).headers().get(":content-type"));
        assertEquals("text/plain", stream.remappedContentType(done(new InvokeResult())));
    }

    @Test
    void streamWithoutBytesSendsOnlyInvokeComplete() throws IOException {
        stream.onStart(null);

        List<Message> messages = write(new InvokeResult(200, null, new byte[0], null, "r"), false);

        assertEquals(List.of("InvokeComplete"), types(messages));
        assertEquals("{}", messages.get(0).text());
    }

    @Test
    void functionThatDoesNotStreamSendsItsPayloadAsOneChunk() throws IOException {
        InvokeResult result = new InvokeResult(200, null, "{\"ok\":true}".getBytes(StandardCharsets.UTF_8), null, "r");

        List<Message> messages = write(result, false);

        assertEquals(List.of("PayloadChunk", "InvokeComplete"), types(messages));
        assertEquals("{\"ok\":true}", messages.get(0).text());
        assertEquals("{}", messages.get(1).text());
        assertEquals("application/json", stream.remappedContentType(done(result)));
    }

    @Test
    void functionThatDoesNotStreamAndReturnsNothingSendsOnlyInvokeComplete() throws IOException {
        List<Message> messages = write(new InvokeResult(200, null, new byte[0], null, "r"), false);

        assertEquals(List.of("InvokeComplete"), types(messages));
    }

    @Test
    void functionErrorSendsTheErrorPayloadThenUnhandled() throws IOException {
        InvokeResult result = new InvokeResult(200, "Unhandled", "{\"errorMessage\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                null, "r");

        List<Message> messages = write(result, false);

        assertEquals(List.of("PayloadChunk", "InvokeComplete"), types(messages));
        assertEquals("{\"errorMessage\":\"x\"}", messages.get(0).text());
        assertEquals("{\"ErrorCode\":\"Unhandled\",\"ErrorDetails\":\"\"}", messages.get(1).text());
        assertEquals("application/json", stream.remappedContentType(done(result)));
    }

    @Test
    void timeoutWithoutStreamingSendsNoChunk() throws IOException {
        InvokeResult result = new InvokeResult(200, "Unhandled", "{\"errorMessage\":\"timeout\"}".getBytes(StandardCharsets.UTF_8),
                null, "r");
        result.setTimedOut(true);

        List<Message> messages = write(result, false);

        assertEquals(List.of("InvokeComplete"), types(messages));
        assertEquals("{\"ErrorCode\":\"Unhandled\",\"ErrorDetails\":\"\"}", messages.get(0).text());
        assertEquals("application/octet-stream", stream.remappedContentType(done(result)));
    }

    @Test
    void timeoutAfterStreamingReportsSandboxTimeout() throws IOException {
        stream.onStart(null);
        stream.onChunk("partial".getBytes(StandardCharsets.UTF_8));
        InvokeResult result = new InvokeResult(200, "Unhandled", "{}".getBytes(StandardCharsets.UTF_8), null, "r");
        result.setTimedOut(true);

        List<Message> messages = write(result, false);

        assertEquals(List.of("PayloadChunk", "InvokeComplete"), types(messages));
        assertEquals("{\"ErrorCode\":\"Sandbox.Timeout\"}", messages.get(1).text());
    }

    @Test
    void failureAfterStreamingReportsTruncatedResponse() throws IOException {
        stream.onStart(null);
        stream.onChunk("partial".getBytes(StandardCharsets.UTF_8));
        InvokeResult result = new InvokeResult(200, "Unhandled", "{}".getBytes(StandardCharsets.UTF_8), null, "r");

        List<Message> messages = write(result, false);

        assertEquals(List.of("PayloadChunk", "InvokeComplete"), types(messages));
        assertEquals("{\"ErrorCode\":\"Runtime.TruncatedResponse\"}", messages.get(1).text());
    }

    @Test
    void logResultReplacesTheErrorFieldsAndKeepsTheChunkOfItsCase() throws IOException {
        InvokeResult result = new InvokeResult(200, "Unhandled", "{\"errorMessage\":\"x\"}".getBytes(StandardCharsets.UTF_8),
                "bG9ncw==", "r");

        List<Message> messages = write(result, true);

        assertEquals(List.of("PayloadChunk", "InvokeComplete"), types(messages));
        assertEquals("{\"LogResult\":\"bG9ncw==\"}", messages.get(1).text());
    }

    @Test
    void logResultOnATimeoutSendsNoChunk() throws IOException {
        InvokeResult result = new InvokeResult(200, "Unhandled", "{}".getBytes(StandardCharsets.UTF_8), "bG9ncw==", "r");
        result.setTimedOut(true);

        List<Message> messages = write(result, true);

        assertEquals(List.of("InvokeComplete"), types(messages));
        assertEquals("{\"LogResult\":\"bG9ncw==\"}", messages.get(0).text());
    }

    @Test
    void logResultIsLeftOutWhenNoTailWasAsked() throws IOException {
        InvokeResult result = new InvokeResult(200, null, new byte[0], "bG9ncw==", "r");

        List<Message> messages = write(result, false);

        assertEquals("{}", messages.get(0).text());
    }

    @Test
    void startedCompletesOnlyForAStreamingFunction() {
        assertFalse(stream.started().isDone());
        stream.onStart(null);
        assertTrue(stream.started().isDone());
    }

    private List<Message> write(InvokeResult result, boolean logTail) throws IOException {
        stream.end();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        stream.write(out, result, logTail);
        return EventStreamMessages.decode(out.toByteArray());
    }

    private static List<String> types(List<Message> messages) {
        return messages.stream().map(Message::type).toList();
    }

    private static CompletableFuture<InvokeResult> done(InvokeResult result) {
        return CompletableFuture.completedFuture(result);
    }
}
