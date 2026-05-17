package io.github.hectorvent.floci.services.apigatewayv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Integration {
    private String integrationId;
    private String integrationType; // AWS_PROXY, HTTP_PROXY
    private String integrationUri;
    private String payloadFormatVersion; // 1.0, 2.0
    private String integrationMethod;
    private int timeoutInMillis;
    private Map<String, String> requestTemplates;
    private Map<String, String> responseTemplates;
    private String templateSelectionExpression;
    private Map<String, String> requestParameters;

    public Integration() {}

    public String getIntegrationId() { return integrationId; }
    public void setIntegrationId(String integrationId) { this.integrationId = integrationId; }

    public String getIntegrationType() { return integrationType; }
    public void setIntegrationType(String integrationType) { this.integrationType = integrationType; }

    public String getIntegrationUri() { return integrationUri; }
    public void setIntegrationUri(String integrationUri) { this.integrationUri = integrationUri; }

    public String getPayloadFormatVersion() { return payloadFormatVersion; }
    public void setPayloadFormatVersion(String payloadFormatVersion) { this.payloadFormatVersion = payloadFormatVersion; }

    public String getIntegrationMethod() { return integrationMethod; }
    public void setIntegrationMethod(String integrationMethod) { this.integrationMethod = integrationMethod; }

    public int getTimeoutInMillis() { return timeoutInMillis; }
    public void setTimeoutInMillis(int timeoutInMillis) { this.timeoutInMillis = timeoutInMillis; }

    public Map<String, String> getRequestTemplates() { return requestTemplates; }
    public void setRequestTemplates(Map<String, String> requestTemplates) { this.requestTemplates = requestTemplates; }

    public Map<String, String> getResponseTemplates() { return responseTemplates; }
    public void setResponseTemplates(Map<String, String> responseTemplates) { this.responseTemplates = responseTemplates; }

    public String getTemplateSelectionExpression() { return templateSelectionExpression; }
    public void setTemplateSelectionExpression(String templateSelectionExpression) { this.templateSelectionExpression = templateSelectionExpression; }

    public Map<String, String> getRequestParameters() { return requestParameters; }
    public void setRequestParameters(Map<String, String> requestParameters) { this.requestParameters = requestParameters; }
}
