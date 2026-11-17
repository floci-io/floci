package io.floci.aws.services.emr.model;

import io.floci.aws.services.emrserverless.SparkConnectSession;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Spark Connect session on a cluster, as GetSession reports it, plus what only Floci needs: its place among its
 * cluster's sessions, and the client request token and the parameters it was started with, which decide whether a
 * repeated StartSession is a retry. Its engine and monitoring configurations are kept as the request's raw JSON.
 */
@RegisterForReflection
public class EmrSession implements SparkConnectSession {

    private String id;
    private String clusterId;
    // Its place among its cluster's sessions, 1 for the first: the newer of two made in the same millisecond is later.
    private long sequence;
    private String name;
    private String arn;
    private String state;
    private String stateChangeReason;
    private String releaseLabel;
    private String executionRoleArn;
    private String accountId;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant startedAt;
    private Instant endedAt;
    private Instant idleSince;
    private String engineConfigurations;
    private String monitoringConfiguration;
    private long sessionIdleTimeoutInMinutes;
    private Map<String, String> tags = new LinkedHashMap<>();
    private String clientRequestToken;
    private String request;
    // Whether Floci runs a Spark Connect server for it, as it does in the docker runner mode.
    private boolean containerSession;

    public EmrSession() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getClusterId() { return clusterId; }
    public void setClusterId(String clusterId) { this.clusterId = clusterId; }
    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getStateChangeReason() { return stateChangeReason; }
    public void setStateChangeReason(String stateChangeReason) { this.stateChangeReason = stateChangeReason; }
    public String getReleaseLabel() { return releaseLabel; }
    public void setReleaseLabel(String releaseLabel) { this.releaseLabel = releaseLabel; }
    public String getExecutionRoleArn() { return executionRoleArn; }
    public void setExecutionRoleArn(String executionRoleArn) { this.executionRoleArn = executionRoleArn; }
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public Instant getIdleSince() { return idleSince; }
    public void setIdleSince(Instant idleSince) { this.idleSince = idleSince; }
    public String getEngineConfigurations() { return engineConfigurations; }
    public void setEngineConfigurations(String engineConfigurations) {
        this.engineConfigurations = engineConfigurations;
    }
    public String getMonitoringConfiguration() { return monitoringConfiguration; }
    public void setMonitoringConfiguration(String monitoringConfiguration) {
        this.monitoringConfiguration = monitoringConfiguration;
    }
    public long getSessionIdleTimeoutInMinutes() { return sessionIdleTimeoutInMinutes; }
    public void setSessionIdleTimeoutInMinutes(long sessionIdleTimeoutInMinutes) {
        this.sessionIdleTimeoutInMinutes = sessionIdleTimeoutInMinutes;
    }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
    public String getClientRequestToken() { return clientRequestToken; }
    public void setClientRequestToken(String clientRequestToken) { this.clientRequestToken = clientRequestToken; }
    public String getRequest() { return request; }
    public void setRequest(String request) { this.request = request; }
    public boolean isContainerSession() { return containerSession; }
    public void setContainerSession(boolean containerSession) { this.containerSession = containerSession; }
}
