package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamingResponsesTest {

    @Test
    void responseThatFailsToBuildClosesTheBodyStream() {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream body = new ByteArrayInputStream(new byte[16]) {
            @Override
            public void close() {
                closed.set(true);
            }
        };

        assertThrows(NullPointerException.class, () -> StreamingResponses.streamingResponse(body, stream -> {
            throw new NullPointerException("Last-Modified is missing");
        }));

        assertTrue(closed.get(), "the body stream was left open");
    }
}
