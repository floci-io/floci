package io.github.hectorvent.floci.services.apigateway;

import jakarta.enterprise.context.RequestScoped;

/**
 * Carries routing information established by pre-matching execute-api filters to the
 * execute controller without changing the AWS-compatible request.
 */
@RequestScoped
public class ApiGatewayExecuteRouteContext {

    private String httpApiRegion;

    void routeToHttpApi(String region) {
        this.httpApiRegion = region;
    }

    String httpApiRegion() {
        return httpApiRegion;
    }
}
