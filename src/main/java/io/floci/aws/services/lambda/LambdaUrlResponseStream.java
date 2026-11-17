package io.floci.aws.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.services.lambda.model.ResponseStreamListener;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * The response of a {@code RESPONSE_STREAM} function URL, as the function streams it.
 *
 * <p>A stream with the {@code application/vnd.awslambda.http-integration-response} content type,
 * which {@code awslambda.HttpResponseStream.from} sets, starts with a JSON prelude (statusCode,
 * headers, cookies) ended by eight NUL bytes. The head of the response is known once that prelude
 * has arrived, or, for any other stream, once the first bytes have.
 */
final class LambdaUrlResponseStream implements ResponseStreamListener {

    static final String HTTP_INTEGRATION_RESPONSE = "application/vnd.awslambda.http-integration-response";
    private static final int PRELUDE_DELIMITER_LENGTH = 8;
    private static final byte[] END = new byte[0];

    /** The prelude, or null for a stream without one, and the content type the runtime sent. */
    record Head(JsonNode prelude, String contentType) {
    }

    private final ObjectMapper objectMapper;
    private final CompletableFuture<Head> head = new CompletableFuture<>();
    private final BlockingQueue<byte[]> body = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream prelude = new ByteArrayOutputStream();
    private String contentType;
    private boolean expectsPrelude;
    private int delimiterBytes;

    LambdaUrlResponseStream(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void onStart(String contentType) {
        this.contentType = contentType;
        this.expectsPrelude = contentType != null && contentType.startsWith(HTTP_INTEGRATION_RESPONSE);
    }

    @Override
    public void onChunk(byte[] chunk) {
        if (chunk.length == 0) {
            return;
        }
        if (head.isDone()) {
            body.add(chunk);
        } else if (!expectsPrelude) {
            body.add(chunk);
            head.complete(new Head(null, contentType));
        } else {
            readPrelude(chunk);
        }
    }

    private void readPrelude(byte[] chunk) {
        for (int i = 0; i < chunk.length; i++) {
            prelude.write(chunk[i]);
            delimiterBytes = chunk[i] == 0 ? delimiterBytes + 1 : 0;
            if (delimiterBytes == PRELUDE_DELIMITER_LENGTH) {
                byte[] json = Arrays.copyOf(prelude.toByteArray(), prelude.size() - PRELUDE_DELIMITER_LENGTH);
                if (i + 1 < chunk.length) {
                    body.add(Arrays.copyOfRange(chunk, i + 1, chunk.length));
                }
                try {
                    head.complete(new Head(objectMapper.readTree(json), contentType));
                } catch (IOException e) {
                    head.completeExceptionally(e);
                }
                return;
            }
        }
    }

    /** Ends the body; the function has returned, whether or not it streamed anything. */
    void end() {
        body.add(END);
    }

    CompletableFuture<Head> head() {
        return head;
    }

    /**
     * Writes the body as it arrives, until the function returns. The {@code GenericEntity} keeps
     * {@code StreamingOutput} as the entity type, as the Bedrock controllers do.
     */
    GenericEntity<StreamingOutput> body() {
        return new GenericEntity<>(this::write, StreamingOutput.class);
    }

    private void write(OutputStream output) throws IOException {
        try {
            for (byte[] chunk = body.take(); chunk != END; chunk = body.take()) {
                output.write(chunk);
                output.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
