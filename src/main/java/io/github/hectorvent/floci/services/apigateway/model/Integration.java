package io.github.hectorvent.floci.services.apigateway.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class Integration {

    private String type;          // MOCK, HTTP, AWS, HTTP_PROXY, AWS_PROXY
    private String uri;
    private String httpMethod;
    private String passthroughBehavior = "WHEN_NO_MATCH"; // WHEN_NO_MATCH, WHEN_NO_TEMPLATES, NEVER
    private String contentHandling;   // CONVERT_TO_BINARY, CONVERT_TO_TEXT, or null to pass through
    private Integer timeoutInMillis;  // 50–29000; AWS caps REST integrations at 29s
    private String connectionType = "INTERNET"; // INTERNET or VPC_LINK
    private String connectionId;      // VpcLink id when connectionType is VPC_LINK
    private String credentials;       // IAM role ARN assumed for AWS integrations
    private String cacheNamespace;
    private List<String> cacheKeyParameters = new ArrayList<>();
    private TlsConfig tlsConfig;
    private Map<String, String> requestParameters = new HashMap<>(); // integration.request.* → method.request.*
    private Map<String, String> requestTemplates = new HashMap<>();
    private Map<String, IntegrationResponse> integrationResponses = new HashMap<>();

    /** Integration TLS settings. {@code insecureSkipVerification} skips backend cert validation. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public static class TlsConfig {
        private boolean insecureSkipVerification;

        public TlsConfig() {}

        public TlsConfig(boolean insecureSkipVerification) {
            this.insecureSkipVerification = insecureSkipVerification;
        }

        public boolean isInsecureSkipVerification() {
            return insecureSkipVerification;
        }

        public void setInsecureSkipVerification(boolean insecureSkipVerification) {
            this.insecureSkipVerification = insecureSkipVerification;
        }
    }

    public String getContentHandling() {
        return contentHandling;
    }

    public void setContentHandling(String contentHandling) {
        this.contentHandling = contentHandling;
    }

    public Integer getTimeoutInMillis() {
        return timeoutInMillis;
    }

    public void setTimeoutInMillis(Integer timeoutInMillis) {
        this.timeoutInMillis = timeoutInMillis;
    }

    public String getConnectionType() {
        return connectionType;
    }

    public void setConnectionType(String connectionType) {
        this.connectionType = connectionType != null ? connectionType : "INTERNET";
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public String getCacheNamespace() {
        return cacheNamespace;
    }

    public void setCacheNamespace(String cacheNamespace) {
        this.cacheNamespace = cacheNamespace;
    }

    public List<String> getCacheKeyParameters() {
        return cacheKeyParameters;
    }

    public void setCacheKeyParameters(List<String> cacheKeyParameters) {
        this.cacheKeyParameters = cacheKeyParameters != null ? cacheKeyParameters : new ArrayList<>();
    }

    public TlsConfig getTlsConfig() {
        return tlsConfig;
    }

    public void setTlsConfig(TlsConfig tlsConfig) {
        this.tlsConfig = tlsConfig;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getUri() {
        return uri;
    }

    public void setUri(String uri) {
        this.uri = uri;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getPassthroughBehavior() {
        return passthroughBehavior;
    }

    public void setPassthroughBehavior(String passthroughBehavior) {
        this.passthroughBehavior = passthroughBehavior != null ? passthroughBehavior : "WHEN_NO_MATCH";
    }

    public Map<String, String> getRequestParameters() {
        return requestParameters;
    }

    public void setRequestParameters(Map<String, String> requestParameters) {
        this.requestParameters = requestParameters != null ? requestParameters : new HashMap<>();
    }

    public Map<String, String> getRequestTemplates() {
        return requestTemplates;
    }

    public void setRequestTemplates(Map<String, String> requestTemplates) {
        this.requestTemplates = requestTemplates != null ? requestTemplates : new HashMap<>();
    }

    public Map<String, IntegrationResponse> getIntegrationResponses() {
        return integrationResponses;
    }

    public void setIntegrationResponses(Map<String, IntegrationResponse> integrationResponses) {
        this.integrationResponses = integrationResponses != null ? integrationResponses : new HashMap<>();
    }
}
