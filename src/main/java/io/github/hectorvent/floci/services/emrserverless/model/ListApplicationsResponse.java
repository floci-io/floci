package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
public class ListApplicationsResponse {
    private List<ApplicationSummary> applications;
    private String nextToken;

    public ListApplicationsResponse() {}

    public List<ApplicationSummary> getApplications() {
        return applications;
    }

    public void setApplications(List<ApplicationSummary> applications) {
        this.applications = applications;
    }

    public String getNextToken() {
        return nextToken;
    }

    public void setNextToken(String nextToken) {
        this.nextToken = nextToken;
    }
}
