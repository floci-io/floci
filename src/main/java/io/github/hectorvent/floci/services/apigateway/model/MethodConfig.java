package io.github.hectorvent.floci.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MethodConfig {

    private String httpMethod;
    private String authorizationType;
    private String authorizerId;
    private String requestValidatorId;
    private Map<String, Boolean> requestParameters = new HashMap<>();
    private Map<String, String> requestModels = new HashMap<>();
    private Map<String, MethodResponse> methodResponses = new HashMap<>();
    private Integration methodIntegration;

    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }

    public String getAuthorizationType() { return authorizationType; }
    public void setAuthorizationType(String authorizationType) { this.authorizationType = authorizationType; }

    public String getAuthorizerId() { return authorizerId; }
    public void setAuthorizerId(String authorizerId) { this.authorizerId = authorizerId; }

    public String getRequestValidatorId() { return requestValidatorId; }
    public void setRequestValidatorId(String requestValidatorId) { this.requestValidatorId = requestValidatorId; }

    public Map<String, String> getRequestModels() { return requestModels; }
    public void setRequestModels(Map<String, String> requestModels) {
        this.requestModels = requestModels != null ? requestModels : new HashMap<>();
    }

    public Map<String, Boolean> getRequestParameters() { return requestParameters; }
    public void setRequestParameters(Map<String, Boolean> requestParameters) {
        this.requestParameters = requestParameters != null ? requestParameters : new HashMap<>();
    }

    public Map<String, MethodResponse> getMethodResponses() { return methodResponses; }
    public void setMethodResponses(Map<String, MethodResponse> methodResponses) {
        this.methodResponses = methodResponses != null ? methodResponses : new HashMap<>();
    }

    public Integration getMethodIntegration() { return methodIntegration; }
    public void setMethodIntegration(Integration methodIntegration) { this.methodIntegration = methodIntegration; }
}
