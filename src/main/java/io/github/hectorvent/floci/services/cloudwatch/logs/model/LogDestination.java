package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogDestination {
    private String destinationName;
    private String targetArn;
    private String roleArn;
    private String accessPolicy;
    private String arn;
    private long creationTime;

    public String getDestinationName() { return destinationName; }
    public void setDestinationName(String destinationName) { this.destinationName = destinationName; }
    public String getTargetArn() { return targetArn; }
    public void setTargetArn(String targetArn) { this.targetArn = targetArn; }
    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }
    public String getAccessPolicy() { return accessPolicy; }
    public void setAccessPolicy(String accessPolicy) { this.accessPolicy = accessPolicy; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public long getCreationTime() { return creationTime; }
    public void setCreationTime(long creationTime) { this.creationTime = creationTime; }
}
