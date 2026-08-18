package io.github.hectorvent.floci.services.apprunner.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AppRunnerConnection {

    @JsonProperty("ConnectionName")
    private String connectionName;

    @JsonProperty("ConnectionArn")
    private String connectionArn;

    @JsonProperty("ProviderType")
    private String providerType;

    @JsonProperty("Status")
    private String status;

    @JsonProperty("CreatedAt")
    private Long createdAt;

    public AppRunnerConnection() {}

    public String getConnectionName() { return connectionName; }
    public void setConnectionName(String connectionName) { this.connectionName = connectionName; }

    public String getConnectionArn() { return connectionArn; }
    public void setConnectionArn(String connectionArn) { this.connectionArn = connectionArn; }

    public String getProviderType() { return providerType; }
    public void setProviderType(String providerType) { this.providerType = providerType; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }
}
