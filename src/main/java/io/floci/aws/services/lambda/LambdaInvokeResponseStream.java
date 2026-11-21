package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsEventStreamEncoder;
import io.floci.aws.services.lambda.model.InvokeResult;
import io.floci.aws.services.lambda.model.ResponseStreamListener;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * The response of {@code InvokeWithResponseStream}: {@code PayloadChunk} events as the function
 * streams, then one {@code InvokeComplete}. A function that does not stream has its whole response
 * sent as one chunk.
 */
final class LambdaInvokeResponseStream implements ResponseStreamListener {

    static final String EVENT_STREAM = "application/vnd.amazon.eventstream";
    private static final String JSON = MediaType.APPLICATION_JSON;
    private static final String OCTET_STREAM = MediaType.APPLICATION_OCTET_STREAM;

    private final ObjectMapper objectMapper;
    private final CompletableFuture<String> started = new CompletableFuture<>();
    private final ResponseChunkQueue frames = new ResponseChunkQueue();
    private volatile Failure failure;

    private record Failure(String errorType, String errorBody) {
    }

    LambdaInvokeResponseStream(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void onStart(String contentType) {
        started.complete(contentType != null ? contentType : OCTET_STREAM);
    }

    @Override
    public void onChunk(byte[] chunk) {
        if (chunk.length > 0) {
            frames.add(frame("PayloadChunk", OCTET_STREAM, chunk));
        }
    }

    @Override
    public void onFailure(String errorType, String errorBody) {
        failure = new Failure(errorType, errorBody);
    }

    /** Completes with the content type of the stream once the runtime starts it. */
    CompletableFuture<String> started() {
        return started;
    }

    /** The function has returned, whether or not it streamed anything. */
    void end() {
        frames.end();
    }

    /** The content type AWS reports for the stream in {@code x-amzn-Remapped-Content-Type}. */
    String remappedContentType(CompletableFuture<InvokeResult> invocation) {
        if (started.isDone()) {
            return started.join();
        }
        return invocation.join().isTimedOut() ? OCTET_STREAM : JSON;
    }

    GenericEntity<StreamingOutput> body(CompletableFuture<InvokeResult> invocation, boolean logTail) {
        return new GenericEntity<>(output -> write(output, invocation, logTail), StreamingOutput.class);
    }

    /** Writes the frames as they arrive until {@link #end()}, then the final {@code InvokeComplete}. */
    void write(OutputStream output, CompletableFuture<InvokeResult> invocation, boolean logTail) throws IOException {
        frames.drainTo(output);
        InvokeResult result = invocation.join();
        boolean streamed = started.isDone();
        boolean failed = result.getFunctionError() != null;
        if (!streamed && !result.isTimedOut() && result.getPayload() != null && result.getPayload().length > 0) {
            output.write(frame("PayloadChunk", OCTET_STREAM, result.getPayload()));
        }
        output.write(frame("InvokeComplete", JSON, complete(result, streamed, failed, logTail)));
        output.flush();
    }

    private byte[] complete(InvokeResult result, boolean streamed, boolean failed, boolean logTail) {
        ObjectNode payload = objectMapper.createObjectNode();
        if (logTail && result.getLogResult() != null) {
            // AWS leaves ErrorCode out when it sends LogResult, also for a failed invocation.
            payload.put("LogResult", result.getLogResult());
        } else if (streamed && result.isTimedOut()) {
            payload.put("ErrorCode", "Sandbox.Timeout");
        } else if (failure != null) {
            payload.put("ErrorCode", failure.errorType());
            if (failure.errorBody() != null) {
                payload.put("ErrorDetails", failure.errorBody());
            }
        } else if (!streamed && failed) {
            payload.put("ErrorCode", "Unhandled");
            payload.put("ErrorDetails", "");
        } else if (streamed && failed) {
            payload.put("ErrorCode", "Runtime.TruncatedResponse");
        }
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] frame(String eventType, String contentType, byte[] payload) {
        LinkedHashMap<String, String> headers = new LinkedHashMap<>();
        headers.put(":event-type", eventType);
        headers.put(":content-type", contentType);
        headers.put(":message-type", "event");
        try {
            return AwsEventStreamEncoder.encodeMessage(headers, payload);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
