package io.github.hectorvent.floci.services.bedrockagentcorecontrol.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Immutable snapshot of an AgentCore harness at one version. {@code CreateHarness} writes version 1
 * and each {@code UpdateHarness} appends the next; earlier ones stay readable through
 * {@code GetHarness?harnessVersion=} and {@code ListHarnessVersions}.
 *
 * <p>{@code configuration} holds the harness members after {@code executionRoleArn} exactly as
 * {@code GetHarness} returns them ({@code model} through {@code timeoutSeconds}), defaults filled in.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HarnessVersion {

    private String version;
    private Instant createdAt;
    private String executionRoleArn;
    private ObjectNode configuration;

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public String getExecutionRoleArn() {
        return executionRoleArn;
    }

    public void setExecutionRoleArn(String executionRoleArn) {
        this.executionRoleArn = executionRoleArn;
    }

    public ObjectNode getConfiguration() {
        return configuration;
    }

    public void setConfiguration(ObjectNode configuration) {
        this.configuration = configuration;
    }
}
