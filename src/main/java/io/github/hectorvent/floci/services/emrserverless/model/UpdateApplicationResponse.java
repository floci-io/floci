package io.github.hectorvent.floci.services.emrserverless.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class UpdateApplicationResponse {
    private String applicationId;
    private String arn;
    private String name;

    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
