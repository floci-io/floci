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
    private boolean disableExecuteApiEndpoint;
    private Cors corsConfiguration;
    private String version;
    /** Warnings reported when failOnWarnings is off during an OpenAPI import. */
    private List<String> warnings;
    /** Definition properties that the import ignored. */
    private List<String> importInfo;

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

    public boolean isDisableExecuteApiEndpoint() { return disableExecuteApiEndpoint; }
    public void setDisableExecuteApiEndpoint(boolean disableExecuteApiEndpoint) {
        this.disableExecuteApiEndpoint = disableExecuteApiEndpoint;
    }

    public Cors getCorsConfiguration() { return corsConfiguration; }
    public void setCorsConfiguration(Cors corsConfiguration) { this.corsConfiguration = corsConfiguration; }

    /** Caller-supplied API version string, e.g. "1.0.0". Opaque to AWS. */
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public List<String> getWarnings() { return warnings; }
    public void setWarnings(List<String> warnings) { this.warnings = warnings; }

    public List<String> getImportInfo() { return importInfo; }
    public void setImportInfo(List<String> importInfo) { this.importInfo = importInfo; }

    @RegisterForReflection
    public record Cors(
            List<String> allowOrigins,
            List<String> allowMethods,
            List<String> allowHeaders,
            List<String> exposeHeaders,
            Integer maxAge,
            Boolean allowCredentials) {}
}
