package io.github.hectorvent.floci.services.apigateway;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.core.UriInfo;

/**
 * Carries routing information established by pre-matching execute-api filters to the
 * execute controller without changing the AWS-compatible request.
 */
@RequestScoped
public class ApiGatewayExecuteRouteContext {

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
