package io.github.hectorvent.floci.core.common;

import io.quarkus.runtime.BlockingOperationControl;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;

/**
 * Buffers the request body for {@link HarLoggingFilter} and hands it over under
 * {@link HarLoggingFilter#BODY_PROPERTY}.
 *
 * <p>Separate from {@link HarLoggingFilter} because of where each one can run.
 * {@link HarLoggingFilter} is pre-matching, which on RESTEasy Reactive means the Vert.x IO
 * thread, and reading the JAX-RS entity stream there fails with
 * {@code BlockingNotAllowedException} instead of returning the body: every AWS Query POST was
 * dropped from the HAR that way, leaving only bodyless requests. This filter is post-matching,
 * so it runs after the dispatch to a worker thread that a blocking resource method triggers, and
 * the same read is legal. {@link io.github.hectorvent.floci.services.iam.ResourceArnBuilder} and
 * {@link io.github.hectorvent.floci.services.s3.S3HeaderSignatureFilter} read the entity stream
 * from that same position.
 *
 * <p>Priority 1 puts it ahead of every other post-matching filter, so a request that
 * {@link IamEnforcementFilter} (default priority) or {@link ChaosInterceptorFilter} (6500)
 * aborts still has its body in the HAR. It does nothing unless {@link HarLoggingFilter} already
 * opened a capture for the request, which only happens while HAR logging is enabled.
 *
 * <p>Like the rest of the HAR path this never disturbs the request it observes: the stream is put
 * back as an equivalent one for the resource method to read, and any failure is logged at warn
 * and swallowed.
 */
@Provider
@Priority(1)
public class HarRequestBodyFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(HarRequestBodyFilter.class);

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!(ctx.getProperty(HarLoggingFilter.CAPTURE_PROPERTY) instanceof HarLoggingFilter.RequestCapture)) {
            return;
        }
        try {
            String body = captureRequestBody(ctx);
            if (body != null) {
                ctx.setProperty(HarLoggingFilter.BODY_PROPERTY, body);
            }
        } catch (Exception e) {
            LOG.warnv(e, "HAR logging: could not capture request body for {0} {1}",
                    ctx.getMethod(), ctx.getUriInfo().getPath());
        }
    }

    /**
     * Buffers up to {@link HarLoggingFilter#MAX_BODY_BYTES} of the request body for logging and
     * puts an equivalent stream back so the resource method still reads the whole body. Returns
     * {@code null} when there is no body, and a marker when the read would block on the IO thread
     * or when the body is skipped for its size.
     *
     * <p>A body whose declared {@code Content-Length} already exceeds the cap is skipped without
     * reading anything (the fast path): reading up to the cap of a large declared body would make
     * the filter wait on a slow client and hold a worker thread before the resource runs, and on a
     * blocking route (for example Bedrock {@code InvokeModel}) enough concurrent large requests
     * could exhaust the worker pool. Only a body of unknown length (chunked, {@code getLength() ==
     * -1}) is read, and then only bounded: {@code readNBytes} stops at the cap and the untouched
     * remainder is chained back via {@link SequenceInputStream}, so the resource still receives the
     * full body while the log never holds more than the cap.
     */
    static String captureRequestBody(ContainerRequestContext ctx) throws IOException {
        if (!ctx.hasEntity()) {
            return null;
        }
        // Fast path: a declared length over the cap is never read, so a large declared body cannot
        // make this filter wait on the client and occupy a worker thread.
        if (ctx.getLength() > HarLoggingFilter.MAX_BODY_BYTES) {
            return HarLoggingFilter.TRUNCATED;
        }
        if (!BlockingOperationControl.isBlockingAllowed()) {
            return HarLoggingFilter.NOT_BUFFERED_IO_THREAD;
        }
        InputStream entityStream = ctx.getEntityStream();
        if (entityStream == null) {
            return null;
        }
        // Bounded read for an unknown or within-cap length; readNBytes stops at the cap.
        byte[] prefix = entityStream.readNBytes(HarLoggingFilter.MAX_BODY_BYTES);
        // Peek one more byte to learn whether an unknown-length body exceeded the cap.
        int overflow = entityStream.read();
        boolean truncated = overflow != -1;
        if (truncated) {
            // Put the buffered prefix, the peeked byte, and the untouched remainder back so the
            // resource reads the complete body; we keep only the prefix in memory for the log.
            InputStream remainder = new SequenceInputStream(
                    new ByteArrayInputStream(new byte[] {(byte) overflow}), entityStream);
            ctx.setEntityStream(new SequenceInputStream(new ByteArrayInputStream(prefix), remainder));
            return HarLoggingFilter.TRUNCATED;
        }
        ctx.setEntityStream(new ByteArrayInputStream(prefix));
        if (prefix.length == 0) {
            return null;
        }
        return new String(prefix, StandardCharsets.UTF_8);
    }
}
