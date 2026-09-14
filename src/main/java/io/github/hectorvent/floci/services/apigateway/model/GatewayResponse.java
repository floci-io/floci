package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A REST API's customisation of one {@link GatewayResponseType}: the status code, the
 * {@code gatewayresponse.header.*} parameter mappings and the body templates the gateway applies
 * whenever it answers a request itself instead of the integration.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class GatewayResponse {

    public static final String DEFAULT_TEMPLATE = "{\"message\":$context.error.messageString}";

    private String responseType;
    private String statusCode;
    private Map<String, String> responseParameters = new LinkedHashMap<>();
    private Map<String, String> responseTemplates = new LinkedHashMap<>();
    private boolean defaultResponse;

    public GatewayResponse() {
    }

    /** The response AWS reports for a type that has never been customised. */
    public static GatewayResponse defaultFor(GatewayResponseType type) {
        GatewayResponse response = new GatewayResponse();
        response.setResponseType(type.name());
        response.setStatusCode(type.defaultStatusCode() != null ? String.valueOf(type.defaultStatusCode()) : null);
        response.getResponseTemplates().put("application/json", DEFAULT_TEMPLATE);
        response.setDefaultResponse(true);
        return response;
    }

    public GatewayResponse copy() {
        GatewayResponse copy = new GatewayResponse();
        copy.setResponseType(responseType);
        copy.setStatusCode(statusCode);
        copy.setResponseParameters(new LinkedHashMap<>(responseParameters));
        copy.setResponseTemplates(new LinkedHashMap<>(responseTemplates));
        copy.setDefaultResponse(defaultResponse);
        return copy;
    }

    public String getResponseType() {
        return responseType;
    }

    public void setResponseType(String responseType) {
        this.responseType = responseType;
    }

    public String getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(String statusCode) {
        this.statusCode = statusCode;
    }

    public Map<String, String> getResponseParameters() {
        return responseParameters;
    }

    public void setResponseParameters(Map<String, String> responseParameters) {
        this.responseParameters = responseParameters != null ? responseParameters : new LinkedHashMap<>();
    }

    public Map<String, String> getResponseTemplates() {
        return responseTemplates;
    }

    public void setResponseTemplates(Map<String, String> responseTemplates) {
        this.responseTemplates = responseTemplates != null ? responseTemplates : new LinkedHashMap<>();
    }

    public boolean isDefaultResponse() {
        return defaultResponse;
    }

    public void setDefaultResponse(boolean defaultResponse) {
        this.defaultResponse = defaultResponse;
    }
}
