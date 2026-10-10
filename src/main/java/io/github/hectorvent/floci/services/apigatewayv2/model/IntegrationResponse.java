package io.github.hectorvent.floci.services.apigatewayv2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IntegrationResponse {
    private String integrationResponseId;
    private String integrationResponseKey;
    private String integrationId;
    private String contentHandlingStrategy;
    private String templateSelectionExpression;
    private Map<String, String> responseTemplates;
    private Map<String, String> responseParameters;

    public IntegrationResponse() {}

    public String getIntegrationResponseId() { return integrationResponseId; }
    public void setIntegrationResponseId(String integrationResponseId) { this.integrationResponseId = integrationResponseId; }

    public String getIntegrationResponseKey() { return integrationResponseKey; }
    public void setIntegrationResponseKey(String integrationResponseKey) { this.integrationResponseKey = integrationResponseKey; }

    public String getIntegrationId() { return integrationId; }
    public void setIntegrationId(String integrationId) { this.integrationId = integrationId; }

    public String getContentHandlingStrategy() { return contentHandlingStrategy; }
    public void setContentHandlingStrategy(String contentHandlingStrategy) { this.contentHandlingStrategy = contentHandlingStrategy; }

    public String getTemplateSelectionExpression() { return templateSelectionExpression; }
    public void setTemplateSelectionExpression(String templateSelectionExpression) { this.templateSelectionExpression = templateSelectionExpression; }

    public Map<String, String> getResponseTemplates() { return responseTemplates; }
    public void setResponseTemplates(Map<String, String> responseTemplates) { this.responseTemplates = responseTemplates; }

    public Map<String, String> getResponseParameters() { return responseParameters; }
    public void setResponseParameters(Map<String, String> responseParameters) { this.responseParameters = responseParameters; }
}
