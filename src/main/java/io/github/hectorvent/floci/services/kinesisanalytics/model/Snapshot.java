package io.github.hectorvent.floci.services.kinesisanalytics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * An application snapshot (a Flink savepoint under the covers), mirroring AWS's
 * {@code SnapshotDetails} shape. Stored embedded on {@link FlinkApplication}, not in its own
 * top-level storage — mirrors how tags/environment properties are embedded rather than tracked
 * separately, since a snapshot has no independent lifecycle outside its owning application.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Snapshot {

    @JsonProperty("SnapshotName")
    private String snapshotName;

    @JsonProperty("SnapshotStatus")
    private SnapshotStatus snapshotStatus;

    @JsonProperty("ApplicationVersionId")
    private long applicationVersionId;

    @JsonProperty("SnapshotCreationTimestamp")
    private Instant snapshotCreationTimestamp;

    @JsonProperty("RuntimeEnvironment")
    private String runtimeEnvironment;

    // Internal bookkeeping — NOT part of the AWS response shape, mirroring how FlinkApplication keeps
    // containerId/restEndpoint/flinkJobId out of its own wire-facing fields.

    // The Flink REST /jobs/:id/savepoints trigger's request-id, used to poll completion status.
    private String flinkRequestId;

    // The savepoint's location once Flink reports COMPLETED (e.g.
    // /opt/flink/savepoints/savepoint-<short-job-id>-<random>), used to remove the files on delete.
    private String flinkLocation;

    public Snapshot() {}

    public Snapshot(String snapshotName, long applicationVersionId, String runtimeEnvironment) {
        this.snapshotName = snapshotName;
        this.snapshotStatus = SnapshotStatus.CREATING;
        this.applicationVersionId = applicationVersionId;
        this.snapshotCreationTimestamp = Instant.now();
        this.runtimeEnvironment = runtimeEnvironment;
    }

    public String getSnapshotName() { return snapshotName; }
    public void setSnapshotName(String snapshotName) { this.snapshotName = snapshotName; }

    public SnapshotStatus getSnapshotStatus() { return snapshotStatus; }
    public void setSnapshotStatus(SnapshotStatus snapshotStatus) { this.snapshotStatus = snapshotStatus; }

    public long getApplicationVersionId() { return applicationVersionId; }
    public void setApplicationVersionId(long applicationVersionId) { this.applicationVersionId = applicationVersionId; }

    public Instant getSnapshotCreationTimestamp() { return snapshotCreationTimestamp; }
    public void setSnapshotCreationTimestamp(Instant snapshotCreationTimestamp) { this.snapshotCreationTimestamp = snapshotCreationTimestamp; }

    public String getRuntimeEnvironment() { return runtimeEnvironment; }
    public void setRuntimeEnvironment(String runtimeEnvironment) { this.runtimeEnvironment = runtimeEnvironment; }

    public String getFlinkRequestId() { return flinkRequestId; }
    public void setFlinkRequestId(String flinkRequestId) { this.flinkRequestId = flinkRequestId; }

    public String getFlinkLocation() { return flinkLocation; }
    public void setFlinkLocation(String flinkLocation) { this.flinkLocation = flinkLocation; }
}
