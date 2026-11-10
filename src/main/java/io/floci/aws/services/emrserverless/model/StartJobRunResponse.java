package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class StartJobRunResponse {
    private String applicationId;
    private String jobRunId;
    private String arn;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getJobRunId() { return jobRunId; }
    public void setJobRunId(String jobRunId) { this.jobRunId = jobRunId; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
}
