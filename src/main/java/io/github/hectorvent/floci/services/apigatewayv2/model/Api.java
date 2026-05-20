package io.github.hectorvent.floci.services.apigatewayv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Api {
    private String apiId;
    private String name;
    private String protocolType; // HTTP, WEBSOCKET
    private String apiEndpoint;
    private long createdDate;
    private Map<String, String> tags = new HashMap<>();
    private String routeSelectionExpression;
    private String description;
    private String apiKeySelectionExpression;
    private Cors corsConfiguration;

    public Api() {}

    public String getApiId() { return apiId; }
    public void setApiId(String apiId) { this.apiId = apiId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProtocolType() { return protocolType; }
    public void setProtocolType(String protocolType) { this.protocolType = protocolType; }

    public String getApiEndpoint() { return apiEndpoint; }
    public void setApiEndpoint(String apiEndpoint) { this.apiEndpoint = apiEndpoint; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getRouteSelectionExpression() { return routeSelectionExpression; }
    public void setRouteSelectionExpression(String routeSelectionExpression) { this.routeSelectionExpression = routeSelectionExpression; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getApiKeySelectionExpression() { return apiKeySelectionExpression; }
    public void setApiKeySelectionExpression(String apiKeySelectionExpression) { this.apiKeySelectionExpression = apiKeySelectionExpression; }

    public Cors getCorsConfiguration() { return corsConfiguration; }
    public void setCorsConfiguration(Cors corsConfiguration) { this.corsConfiguration = corsConfiguration; }

    @RegisterForReflection
    public record Cors(
            List<String> allowOrigins,
            List<String> allowMethods,
            List<String> allowHeaders,
            List<String> exposeHeaders,
            Integer maxAge,
            Boolean allowCredentials) {}
}
