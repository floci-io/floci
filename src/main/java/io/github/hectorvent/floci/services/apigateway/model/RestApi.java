package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class RestApi {

    public static final String API_KEY_SOURCE_HEADER = "HEADER";
    public static final String API_KEY_SOURCE_AUTHORIZER = "AUTHORIZER";

    private String id;
    private String name;
    private String description;
    private long createdDate;
    private String rootResourceId;
    private Map<String, String> tags = new HashMap<>();
    private EndpointConfiguration endpointConfiguration;
    /** Content types treated as binary when applying integration contentHandling. */
    private List<String> binaryMediaTypes = new ArrayList<>();
    /** Resource policy document, stored exactly as the caller sent it. */
    private String policy;
    /**
     * Where a request's API key is read from: {@code HEADER} (the {@code x-api-key} header, AWS's
     * default) or {@code AUTHORIZER} (the {@code usageIdentifierKey} a Lambda authorizer returns).
     */
    private String apiKeySource = API_KEY_SOURCE_HEADER;
    private List<String> warnings = new ArrayList<>();

    public String getApiKeySource() {
        return apiKeySource;
    }

    /** {@code null} keeps the default, {@code HEADER}. */
    public void setApiKeySource(String apiKeySource) {
        this.apiKeySource = apiKeySource != null ? apiKeySource : API_KEY_SOURCE_HEADER;
    }

    public boolean readsApiKeyFromAuthorizer() {
        return API_KEY_SOURCE_AUTHORIZER.equals(apiKeySource);
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings != null ? new ArrayList<>(warnings) : new ArrayList<>();
    }

    public String getPolicy() {
        return policy;
    }

    public void setPolicy(String policy) {
        this.policy = policy;
    }

    public List<String> getBinaryMediaTypes() {
        return binaryMediaTypes;
    }

    public void setBinaryMediaTypes(List<String> binaryMediaTypes) {
        this.binaryMediaTypes = binaryMediaTypes != null ? binaryMediaTypes : new ArrayList<>();
    }


    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(long createdDate) {
        this.createdDate = createdDate;
    }

    public String getRootResourceId() {
        return rootResourceId;
    }

    public void setRootResourceId(String rootResourceId) {
        this.rootResourceId = rootResourceId;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new HashMap<>();
    }

    public EndpointConfiguration getEndpointConfiguration() {
        return endpointConfiguration;
    }

    public void setEndpointConfiguration(EndpointConfiguration endpointConfiguration) {
        this.endpointConfiguration = endpointConfiguration;
    }
}
