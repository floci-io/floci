package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GetJobRunResponse {
    private JobRun jobRun;

    public JobRun getJobRun() { return jobRun; }
    public void setJobRun(JobRun jobRun) { this.jobRun = jobRun; }
}
