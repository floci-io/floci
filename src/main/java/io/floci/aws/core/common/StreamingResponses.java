package io.floci.aws.core.common;

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Function;

/**
 * Builds a JAX-RS {@link Response} whose entity streams an already-open {@link InputStream}
 * straight through to the client, never buffering it into a {@code byte[]} first.
 */
public final class StreamingResponses {

    private StreamingResponses() {
    }

    /**
     * Builds a response whose entity streams {@code body} and then closes it. If building the
     * response fails, the entity is never written, so {@code body} is closed here instead.
     */
    public static Response streamingResponse(InputStream body, Function<StreamingOutput, Response> build) {
        StreamingOutput stream = output -> {
            try (InputStream in = body) {
                in.transferTo(output);
            }
        };
        try {
            return build.apply(stream);
        } catch (RuntimeException e) {
            closeQuietly(body);
            throw e;
        }
    }

    /**
     * Closes {@code in}, discarding any {@link IOException}: every caller uses this on a stream
     * it is abandoning (a failed response build, a redirect hop's unread body, a short-circuited
     * fetch), where nothing downstream depends on the close completing cleanly and the only
     * alternative to swallowing it is a log line nobody acts on. {@code in} being {@code null} is
     * itself treated as nothing to close, not an error: a caller that only conditionally opened a
     * stream before reaching its own cleanup (one branch of a {@code try}/{@code finally} that
     * never got that far) shouldn't have to null-check before calling this, and an uncaught
     * {@code NullPointerException} here, inside a {@code finally}, would silently skip whatever
     * cleanup was meant to run after it.
     */
    public static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Wraps {@code in} so that closing it also runs {@code afterClose} exactly once, right after
     * the underlying stream's own close completes. For a caller that hands this stream off to be
     * drained later by someone else (a deferred {@link StreamingOutput}, built from this class's
     * own {@link #streamingResponse}), the wrapped stream's eventual close is the only reliable
     * signal that the real read is actually finished: a resource tied to {@code in} staying open
     * for as long as the read takes (e.g. a sidecar-container use lease) must not be released any
     * earlier than that, even though the method that obtained {@code in} returns long before the
     * drain happens.
     */
    public static InputStream closingWith(InputStream in, Runnable afterClose) {
        return new FilterInputStream(in) {
            private boolean closed;

            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                try {
                    super.close();
                } finally {
                    afterClose.run();
                }
            }
        };
    }
}
