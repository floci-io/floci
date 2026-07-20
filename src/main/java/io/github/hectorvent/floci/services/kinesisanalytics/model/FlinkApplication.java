package io.github.hectorvent.floci.services.kinesisanalytics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * In-memory representation of a Managed Service for Apache Flink application
 * (Kinesis Analytics V2). Mirrors the {@code amazonmq/model/Broker} shape: a mutable
 * POJO whose {@link ApplicationStatus} transitions in place (READY → STARTING → RUNNING)
 * as the backing Flink container comes up.
 *
 * <p>Wire keys are PascalCase to match the Kinesis Analytics V2 (application/x-amz-json-1.1)
 * protocol (e.g. {@code ApplicationName}, {@code ApplicationARN}).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class FlinkApplication {

    @JsonProperty("ApplicationName")
    private String applicationName;

    @JsonProperty("ApplicationARN")
    private String applicationArn;

    @JsonProperty("ApplicationDescription")
    private String applicationDescription;

    @JsonProperty("RuntimeEnvironment")
    private String runtimeEnvironment;

    @JsonProperty("ServiceExecutionRole")
    private String serviceExecutionRole;

    @JsonProperty("ApplicationStatus")
    private ApplicationStatus applicationStatus;

    @JsonProperty("ApplicationVersionId")
    private long applicationVersionId;

    @JsonProperty("ApplicationMode")
    private String applicationMode;

    @JsonProperty("CreateTimestamp")
    private Instant createTimestamp;

    @JsonProperty("LastUpdateTimestamp")
    private Instant lastUpdateTimestamp;

    // Internal bookkeeping — NOT part of the AWS response shape, but persisted so the
    // application stays manageable after an emulator restart in persistent mode (container
    // teardown, account-aware storage routing). The handler builds the ApplicationDetail
    // response explicitly, so these are never exposed to clients despite being stored.
    private String containerId;

    private String accountId;

    // JobManager REST endpoint (host:port) resolved when the container starts; used by the
    // readiness probe. Re-resolved on every StartApplication, so it is transient bookkeeping.
    private String restEndpoint;

    public FlinkApplication() {}

    public FlinkApplication(String applicationName, String applicationArn,
                            String runtimeEnvironment, String serviceExecutionRole,
                            String applicationMode) {
        this.applicationName = applicationName;
        this.applicationArn = applicationArn;
        this.runtimeEnvironment = runtimeEnvironment;
        this.serviceExecutionRole = serviceExecutionRole;
        this.applicationMode = applicationMode;
        this.applicationStatus = ApplicationStatus.READY;
        this.applicationVersionId = 1L;
        this.createTimestamp = Instant.now();
        this.lastUpdateTimestamp = this.createTimestamp;
    }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public String getApplicationArn() { return applicationArn; }
    public void setApplicationArn(String applicationArn) { this.applicationArn = applicationArn; }

    public String getApplicationDescription() { return applicationDescription; }
    public void setApplicationDescription(String applicationDescription) { this.applicationDescription = applicationDescription; }

    public String getRuntimeEnvironment() { return runtimeEnvironment; }
    public void setRuntimeEnvironment(String runtimeEnvironment) { this.runtimeEnvironment = runtimeEnvironment; }

    public String getServiceExecutionRole() { return serviceExecutionRole; }
    public void setServiceExecutionRole(String serviceExecutionRole) { this.serviceExecutionRole = serviceExecutionRole; }

    public ApplicationStatus getApplicationStatus() { return applicationStatus; }
    public void setApplicationStatus(ApplicationStatus applicationStatus) { this.applicationStatus = applicationStatus; }

    public long getApplicationVersionId() { return applicationVersionId; }
    public void setApplicationVersionId(long applicationVersionId) { this.applicationVersionId = applicationVersionId; }

    public String getApplicationMode() { return applicationMode; }
    public void setApplicationMode(String applicationMode) { this.applicationMode = applicationMode; }

    public Instant getCreateTimestamp() { return createTimestamp; }
    public void setCreateTimestamp(Instant createTimestamp) { this.createTimestamp = createTimestamp; }

    public Instant getLastUpdateTimestamp() { return lastUpdateTimestamp; }
    public void setLastUpdateTimestamp(Instant lastUpdateTimestamp) { this.lastUpdateTimestamp = lastUpdateTimestamp; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getRestEndpoint() { return restEndpoint; }
    public void setRestEndpoint(String restEndpoint) { this.restEndpoint = restEndpoint; }
}
