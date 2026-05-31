package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.HashMap;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GraphqlApi {
    private String apiId;
    private String name;
    private String arn;
    private String authenticationType;
    private Map<String, String> uris = new HashMap<>();
    private String logConfig;
    private Map<String, String> additionalAuthenticationProviders;
    private String xrayEnabled;
    private Map<String, String> tags = new HashMap<>();
    private Map<String, String> environmentVariables = new HashMap<>();

    public String getApiId() { return apiId; }
    public void setApiId(String apiId) { this.apiId = apiId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getAuthenticationType() { return authenticationType; }
    public void setAuthenticationType(String authenticationType) { this.authenticationType = authenticationType; }

    public Map<String, String> getUris() { return uris; }
    public void setUris(Map<String, String> uris) { this.uris = uris; }

    public String getLogConfig() { return logConfig; }
    public void setLogConfig(String logConfig) { this.logConfig = logConfig; }

    public Map<String, String> getAdditionalAuthenticationProviders() { return additionalAuthenticationProviders; }
    public void setAdditionalAuthenticationProviders(Map<String, String> providers) { this.additionalAuthenticationProviders = providers; }

    public String getXrayEnabled() { return xrayEnabled; }
    public void setXrayEnabled(String xrayEnabled) { this.xrayEnabled = xrayEnabled; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public Map<String, String> getEnvironmentVariables() { return environmentVariables; }
    public void setEnvironmentVariables(Map<String, String> environmentVariables) { this.environmentVariables = environmentVariables; }
}
