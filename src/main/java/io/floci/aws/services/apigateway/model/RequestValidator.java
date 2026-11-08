package io.floci.aws.services.apigateway.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class RequestValidator {
    private String id;
    private String name;
    private boolean validateRequestBody;
    private boolean validateRequestParameters;

    public RequestValidator() {}

    public RequestValidator(String id, String name, boolean validateRequestBody, boolean validateRequestParameters) {
        this.id = id;
        this.name = name;
        this.validateRequestBody = validateRequestBody;
        this.validateRequestParameters = validateRequestParameters;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public boolean isValidateRequestBody() { return validateRequestBody; }
    public void setValidateRequestBody(boolean validateRequestBody) { this.validateRequestBody = validateRequestBody; }

    public boolean isValidateRequestParameters() { return validateRequestParameters; }
    public void setValidateRequestParameters(boolean validateRequestParameters) { this.validateRequestParameters = validateRequestParameters; }
}
