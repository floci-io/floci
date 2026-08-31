package io.github.hectorvent.floci.services.apigatewayv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Route {
    private String routeId;
    private String routeKey;
    private String authorizationType; // NONE, AWS_IAM, CUSTOM, JWT
    private String authorizerId;
    private java.util.List<String> authorizationScopes;
    private String target; // integrations/{integrationId}
    private String routeResponseSelectionExpression;

    public Route() {}

    public String getRouteId() { return routeId; }
    public void setRouteId(String routeId) { this.routeId = routeId; }

    public String getRouteKey() { return routeKey; }
    public void setRouteKey(String routeKey) { this.routeKey = routeKey; }

    public String getAuthorizationType() { return authorizationType; }
    public void setAuthorizationType(String authorizationType) { this.authorizationType = authorizationType; }

    public String getAuthorizerId() { return authorizerId; }
    public void setAuthorizerId(String authorizerId) { this.authorizerId = authorizerId; }

    public java.util.List<String> getAuthorizationScopes() { return authorizationScopes; }
    public void setAuthorizationScopes(java.util.List<String> authorizationScopes) { this.authorizationScopes = authorizationScopes; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getRouteResponseSelectionExpression() { return routeResponseSelectionExpression; }
    public void setRouteResponseSelectionExpression(String routeResponseSelectionExpression) { this.routeResponseSelectionExpression = routeResponseSelectionExpression; }
}
