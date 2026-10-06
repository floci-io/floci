package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class ApiDestination {

    private String name;
    private String arn;
    private String description;
    private String connectionArn;
    private String invocationEndpoint;
    private String httpMethod;
    private Integer invocationRateLimitPerSecond;
    private ApiDestinationState apiDestinationState;
    private Instant creationTime;
    private Instant lastModifiedTime;

    public ApiDestination() {
    }

    public ApiDestination(String name, String arn, String connectionArn, String invocationEndpoint,
                          String httpMethod, Integer invocationRateLimitPerSecond, String description) {
        this.name = name;
        this.arn = arn;
        this.connectionArn = connectionArn;
        this.invocationEndpoint = invocationEndpoint;
        this.httpMethod = httpMethod;
        this.invocationRateLimitPerSecond = invocationRateLimitPerSecond;
        this.description = description;
        this.apiDestinationState = ApiDestinationState.ACTIVE;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getConnectionArn() {
        return connectionArn;
    }

    public void setConnectionArn(String connectionArn) {
        this.connectionArn = connectionArn;
    }

    public String getInvocationEndpoint() {
        return invocationEndpoint;
    }

    public void setInvocationEndpoint(String invocationEndpoint) {
        this.invocationEndpoint = invocationEndpoint;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public Integer getInvocationRateLimitPerSecond() {
        return invocationRateLimitPerSecond;
    }

    public void setInvocationRateLimitPerSecond(Integer invocationRateLimitPerSecond) {
        this.invocationRateLimitPerSecond = invocationRateLimitPerSecond;
    }

    public ApiDestinationState getApiDestinationState() {
        return apiDestinationState;
    }

    public void setApiDestinationState(ApiDestinationState apiDestinationState) {
        this.apiDestinationState = apiDestinationState;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Instant creationTime) {
        this.creationTime = creationTime;
    }

    public Instant getLastModifiedTime() {
        return lastModifiedTime;
    }

    public void setLastModifiedTime(Instant lastModifiedTime) {
        this.lastModifiedTime = lastModifiedTime;
    }
}
