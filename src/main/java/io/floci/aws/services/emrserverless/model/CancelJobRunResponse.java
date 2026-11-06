package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class CancelJobRunResponse {
    private String applicationId;
    private String jobRunId;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getJobRunId() { return jobRunId; }
    public void setJobRunId(String jobRunId) { this.jobRunId = jobRunId; }
}
