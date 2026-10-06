package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;

import java.time.Instant;
import java.util.UUID;

/**
 * Carries routing information established by pre-matching execute-api filters to the
 * execute controller without changing the AWS-compatible request.
 */
@RequestScoped
public class ApiGatewayExecuteRouteContext {

    private final CurrentVertxRequest currentVertxRequest;

    public ApiGatewayExecuteRouteContext() {
        this(null);
    }

    @Inject
    public ApiGatewayExecuteRouteContext(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    String sourceIp() {
        RoutingContext context = currentVertxRequest != null ? currentVertxRequest.getCurrent() : null;
        return context != null ? context.request().connection().remoteAddress().hostAddress() : "127.0.0.1";
    }

    private final Instant requestTime = Instant.now();
    private final String extendedRequestId = UUID.randomUUID().toString();
    private String requestId;

    String requestId(HttpHeaders headers) {
        return requestIdFromOverride(headers != null ? headers.getHeaderString("x-amzn-RequestId") : null);
    }

    String requestIdFromOverride(String override) {
        if (requestId != null) {
            return requestId;
        }
        requestId = UUID.randomUUID().toString();
        if (override == null) {
            return requestId;
        }
        try {
            if (UUID.fromString(override).toString().equalsIgnoreCase(override)) {
                requestId = override;
            } else {
                requestId += "_REPLACED_INVALID_REQUEST_ID";
            }
        } catch (IllegalArgumentException ignored) {
            // AWS replaces invalid overrides and marks them in the request ID.
            requestId += "_REPLACED_INVALID_REQUEST_ID";
        }
        return requestId;
    }

    String extendedRequestId() {
        return extendedRequestId;
    }

    Instant requestTime() {
        return requestTime;
    }

    private String httpApiRegion;
    private boolean restApiRoute;
    private String signedRequestPath;

    void routeToHttpApi(String region) {
        this.httpApiRegion = region;
    }

    String httpApiRegion() {
        return httpApiRegion;
    }

    void routeToRestApi() {
        this.restApiRoute = true;
    }

    boolean isRestApiRoute() {
        return restApiRoute;
    }

    /**
     * Records the raw path as the client sent it, before a pre-matching filter rewrote the request
     * URI onto the internal {@code /execute-api/...} form. SigV4 covers the path the caller signed,
     * so AWS_IAM verification has to rebuild its canonical request from this value rather than from
     * the rewritten {@link UriInfo}.
     */
    void recordSignedRequestPath(String rawPath) {
        this.signedRequestPath = rawPath;
    }

    String signedRequestPath() {
        return signedRequestPath;
    }
}
