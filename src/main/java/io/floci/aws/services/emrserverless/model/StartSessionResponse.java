package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class StartSessionResponse {
    private String applicationId;
    private String sessionId;
    private String arn;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
}
