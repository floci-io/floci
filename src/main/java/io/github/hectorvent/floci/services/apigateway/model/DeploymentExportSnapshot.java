package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public record DeploymentExportSnapshot(
        RestApi api,
        List<ApiGatewayResource> resources,
        List<Model> models,
        List<Authorizer> authorizers,
        List<RequestValidator> validators,
        List<GatewayResponse> gatewayResponses
) {
}
