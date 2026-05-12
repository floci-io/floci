package io.github.hectorvent.floci.services.s3;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Removes the {@code charset} parameter from {@code application/xml} and
 * {@code text/xml} response {@code Content-Type} headers.
 *
 * <p>Quarkus REST (RESTEasy Reactive) automatically appends
 * {@code ;charset=UTF-8} to text-like media types declared via
 * {@code @Produces(MediaType.APPLICATION_XML)} or
 * {@code Response.ok(...).type(MediaType.APPLICATION_XML)}. Production Amazon
 * S3 returns {@code Content-Type: application/xml} without any {@code charset}
 * parameter for every XML response — see the response examples in the AWS S3
 * API Reference (ListBuckets, ListObjects, GetBucketAcl, etc.).
 *
 * <p>Some AWS SDKs and ecosystem tools parse the response media type strictly
 * and treat the extra {@code charset} parameter as a deviation from the
 * documented wire format, so Floci normalises the header to match the real
 * service.
 *
 * <p><strong>Scope.</strong> The handler only rewrites when the base media
 * type is {@code application/xml} or {@code text/xml}, so non-XML responses
 * (JSON 1.1, REST JSON, binary object bodies) are passed through untouched.
 * The same correction is in fact valid for every XML-based AWS protocol
 * (Query: SQS / SNS / IAM / STS, REST XML: S3), so the provider is registered
 * globally and lives in the S3 package because S3 is where the discrepancy
 * was originally reported.
 *
 * <p><strong>Why a request filter + Vert.x headers-end handler.</strong>
 * For Quarkus REST resource methods that return {@code Response.ok(entity).build()}
 * without an explicit {@code .type(...)} — which is the common pattern across
 * {@link S3Controller} — neither {@link
 * jakarta.ws.rs.container.ContainerResponseFilter} nor {@link
 * jakarta.ws.rs.ext.WriterInterceptor} sees a non-null media type, because
 * Quarkus REST's optimised write path resolves the {@code Content-Type}
 * (including the framework-added charset) outside the standard
 * {@code MessageBodyWriter} pipeline. The earliest hook at which the final
 * {@code Content-Type} is observable and still mutable is Vert.x's
 * {@link RoutingContext#addHeadersEndHandler}, which fires immediately before
 * the HTTP response headers are written to the wire. Registering the handler
 * from a {@link ContainerRequestFilter} guarantees it is installed once per
 * request, before any response processing begins.
 */
@Provider
public class S3ContentTypeCharsetFilter implements ContainerRequestFilter {

    @Inject
    CurrentVertxRequest currentVertxRequest;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        RoutingContext rc = currentVertxRequest.getCurrent();
        if (rc == null) return;
        rc.addHeadersEndHandler(v -> {
            String ct = rc.response().headers().get("Content-Type");
            if (ct == null) return;
            int semi = ct.indexOf(';');
            if (semi <= 0) return;
            String base = ct.substring(0, semi).trim();
            if ("application/xml".equalsIgnoreCase(base) || "text/xml".equalsIgnoreCase(base)) {
                rc.response().headers().set("Content-Type", base);
            }
        });
    }
}
