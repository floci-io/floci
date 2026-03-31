package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Integration {

    private String type;          // MOCK, HTTP, AWS, HTTP_PROXY, AWS_PROXY
    private String uri;
    private String httpMethod;
    private String passthroughBehavior = "WHEN_NO_MATCH"; // WHEN_NO_MATCH, WHEN_NO_TEMPLATES, NEVER
    private Map<String, String> requestParameters = new HashMap<>(); // integration.request.* → method.request.*
    private Map<String, String> requestTemplates = new HashMap<>();
    private Map<String, IntegrationResponse> integrationResponses = new HashMap<>();

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
