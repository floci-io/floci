package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class TerminateSessionResponse {
    private String applicationId;
    private String sessionId;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
