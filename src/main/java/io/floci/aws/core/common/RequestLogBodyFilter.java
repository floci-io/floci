package io.floci.aws.core.common;

import io.floci.aws.core.common.RequestLogFilter.Body;
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
import java.util.function.BooleanSupplier;

/**
 * Buffers the request body for {@link RequestLogFilter} and hands it over under {@link RequestLogFilter#BODY_PROPERTY}.
 *
 * <p>Separate from {@link RequestLogFilter} because of where each can run. {@link RequestLogFilter} is pre-matching,
 * which on RESTEasy Reactive means the Vert.x IO thread, and reading the JAX-RS entity stream there fails with
 * {@code BlockingNotAllowedException} instead of returning the body, which would drop the body of every AWS Query
 * POST. This filter is post-matching, so it runs after the dispatch to a worker thread that a blocking resource method
 * triggers, and the same read is legal. {@link io.floci.aws.services.iam.ResourceArnBuilder} and
 * {@link io.floci.aws.services.s3.S3HeaderSignatureFilter} read the entity stream from that same position.
 *
 * <p>Priority 1 puts it ahead of every other post-matching filter, so a request that {@link IamEnforcementFilter}
 * (default priority) aborts still has its body logged. It does nothing unless {@link RequestLogFilter} already opened
 * a capture for the request, which only happens while the request log is enabled.
 *
 * <p>Like the rest of the request log, it never disturbs the request it observes: the stream is put back as an
 * equivalent one for the resource method to read, and any failure is logged at warn and swallowed.
 */
@Provider
@Priority(1)
public class RequestLogBodyFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(RequestLogBodyFilter.class);

    private final BooleanSupplier blockingAllowed;

    public RequestLogBodyFilter() {
        this(BlockingOperationControl::isBlockingAllowed);
    }

    /** {@code blockingAllowed} answers whether this thread may block, which only a running Quarkus can tell. */
    RequestLogBodyFilter(BooleanSupplier blockingAllowed) {
        this.blockingAllowed = blockingAllowed;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (!(ctx.getProperty(RequestLogFilter.CAPTURE_PROPERTY) instanceof RequestLogFilter.RequestCapture)) {
            return;
        }
        try {
            Body body = captureRequestBody(ctx, blockingAllowed);
            if (body != null) {
                ctx.setProperty(RequestLogFilter.BODY_PROPERTY, body);
            }
        } catch (Exception e) {
            LOG.warnv(e, "Request log: could not capture request body for {0} {1}",
                    ctx.getMethod(), ctx.getUriInfo().getPath());
        }
    }

    /**
     * Buffers the request body for logging when its declared {@code Content-Length} is within
     * {@link RequestLogFilter#MAX_BODY_BYTES}, and puts an equivalent stream back so the resource method still reads
     * the whole body. Returns {@code null} when there is no body, and a placeholder when the body is not read.
     *
     * <p>Only a declared length is read, and only that many bytes, which the resource would wait for anyway. A body
     * over the cap, and one with no declared length (chunked), is not read at all: reading it would make this filter
     * wait on the client, holding a worker thread before the resource runs, and on a blocking route enough slow
     * uploads could exhaust the worker pool.
     */
    static Body captureRequestBody(ContainerRequestContext ctx, BooleanSupplier blockingAllowed) throws IOException {
        if (!ctx.hasEntity()) {
            return null;
        }
        int length = ctx.getLength();
        if (length > RequestLogFilter.MAX_BODY_BYTES) {
            return Body.placeholder(RequestLogFilter.TRUNCATED, length);
        }
        if (length < 0) {
            return Body.placeholder(RequestLogFilter.UNKNOWN_LENGTH, -1);
        }
        if (!blockingAllowed.getAsBoolean()) {
            return Body.placeholder(RequestLogFilter.NOT_BUFFERED_IO_THREAD, length);
        }
        InputStream entityStream = ctx.getEntityStream();
        if (entityStream == null) {
            return null;
        }
        byte[] declared = entityStream.readNBytes(length);
        // Whatever follows a declared length that undercounts still reaches the resource.
        ctx.setEntityStream(new SequenceInputStream(new ByteArrayInputStream(declared), entityStream));
        return declared.length == 0 ? null : Body.bytes(declared);
    }
}
