package io.github.hectorvent.floci.services.apigatewayv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RouteResponse {
    private String routeResponseId;
    private String routeResponseKey;
    private String routeId;
    private String modelSelectionExpression;
    private Map<String, String> responseModels;
    private Map<String, String> responseParameters;

    public RouteResponse() {}

    public String getRouteResponseId() { return routeResponseId; }
    public void setRouteResponseId(String routeResponseId) { this.routeResponseId = routeResponseId; }

    public String getRouteResponseKey() { return routeResponseKey; }
    public void setRouteResponseKey(String routeResponseKey) { this.routeResponseKey = routeResponseKey; }

    public String getRouteId() { return routeId; }
    public void setRouteId(String routeId) { this.routeId = routeId; }

    public String getModelSelectionExpression() { return modelSelectionExpression; }
    public void setModelSelectionExpression(String modelSelectionExpression) { this.modelSelectionExpression = modelSelectionExpression; }

    public Map<String, String> getResponseModels() { return responseModels; }
    public void setResponseModels(Map<String, String> responseModels) { this.responseModels = responseModels; }

    public Map<String, String> getResponseParameters() { return responseParameters; }
    public void setResponseParameters(Map<String, String> responseParameters) { this.responseParameters = responseParameters; }
}
