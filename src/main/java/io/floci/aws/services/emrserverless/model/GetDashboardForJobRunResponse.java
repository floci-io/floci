package io.floci.aws.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GetDashboardForJobRunResponse {
    private String url;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
}
