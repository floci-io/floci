package io.github.hectorvent.floci.services.amazonmq.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * In-memory representation of an Amazon MQ broker. Mirrors the
 * {@code msk/model/MskCluster} shape: a mutable POJO whose state transitions
 * in place (CREATION_IN_PROGRESS -> RUNNING) as the backing container comes up.
 *
 * <p>Wire keys are camelCase to match the Amazon MQ REST-JSON protocol
 * (e.g. {@code brokerName}, {@code brokerInstances}).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Broker {

    @JsonProperty("brokerId")
    private String brokerId;

    @JsonProperty("brokerArn")
    private String brokerArn;

    @JsonProperty("brokerName")
    private String brokerName;

    @JsonProperty("brokerState")
    private BrokerState brokerState;

    @JsonProperty("engineType")
    private String engineType;

    @JsonProperty("engineVersion")
    private String engineVersion;

    @JsonProperty("deploymentMode")
    private String deploymentMode;

    @JsonProperty("hostInstanceType")
    private String hostInstanceType;

    @JsonProperty("publiclyAccessible")
    private boolean publiclyAccessible;

    @JsonProperty("autoMinorVersionUpgrade")
    private boolean autoMinorVersionUpgrade;

    @JsonProperty("created")
    private Instant created;

    @JsonProperty("brokerInstances")
    private List<BrokerInstance> brokerInstances = new ArrayList<>();

    @JsonProperty("users")
    private List<MqUser> users = new ArrayList<>();

    @JsonProperty("tags")
    private Map<String, String> tags;

    // Internal bookkeeping. These are NOT part of the AWS response shape, but they ARE
    // persisted so the broker stays manageable after an emulator restart in persistent
    // mode (container teardown, volume cleanup, account-aware storage routing). The
    // controller builds the DescribeBroker response explicitly, so these are never
    // exposed to clients despite being written to storage.
    private String containerId;

    private String accountId;

    // 6-char hex generated once at creation for stable, collision-free volume/container
    // naming (same convention as MskCluster.volumeId, which is likewise persisted so
    // removeBrokerStorage can locate the named volume after a restart).
    private String volumeId;

    public Broker() {}

    public Broker(String brokerId, String brokerArn, String brokerName,
                  String engineType, String engineVersion, String deploymentMode,
                  String hostInstanceType) {
        this.brokerId = brokerId;
        this.brokerArn = brokerArn;
        this.brokerName = brokerName;
        this.engineType = engineType;
        this.engineVersion = engineVersion;
        this.deploymentMode = deploymentMode;
        this.hostInstanceType = hostInstanceType;
        this.brokerState = BrokerState.CREATION_IN_PROGRESS;
        this.created = Instant.now();
    }

    public String getBrokerId() { return brokerId; }
    public void setBrokerId(String brokerId) { this.brokerId = brokerId; }

    public String getBrokerArn() { return brokerArn; }
    public void setBrokerArn(String brokerArn) { this.brokerArn = brokerArn; }

    public String getBrokerName() { return brokerName; }
    public void setBrokerName(String brokerName) { this.brokerName = brokerName; }

    public BrokerState getBrokerState() { return brokerState; }
    public void setBrokerState(BrokerState brokerState) { this.brokerState = brokerState; }

    public String getEngineType() { return engineType; }
    public void setEngineType(String engineType) { this.engineType = engineType; }

    public String getEngineVersion() { return engineVersion; }
    public void setEngineVersion(String engineVersion) { this.engineVersion = engineVersion; }

    public String getDeploymentMode() { return deploymentMode; }
    public void setDeploymentMode(String deploymentMode) { this.deploymentMode = deploymentMode; }

    public String getHostInstanceType() { return hostInstanceType; }
    public void setHostInstanceType(String hostInstanceType) { this.hostInstanceType = hostInstanceType; }

    public boolean isPubliclyAccessible() { return publiclyAccessible; }
    public void setPubliclyAccessible(boolean publiclyAccessible) { this.publiclyAccessible = publiclyAccessible; }

    public boolean isAutoMinorVersionUpgrade() { return autoMinorVersionUpgrade; }
    public void setAutoMinorVersionUpgrade(boolean autoMinorVersionUpgrade) { this.autoMinorVersionUpgrade = autoMinorVersionUpgrade; }

    public Instant getCreated() { return created; }
    public void setCreated(Instant created) { this.created = created; }

    public List<BrokerInstance> getBrokerInstances() { return brokerInstances; }
    public void setBrokerInstances(List<BrokerInstance> brokerInstances) { this.brokerInstances = brokerInstances; }

    public List<MqUser> getUsers() { return users; }
    public void setUsers(List<MqUser> users) { this.users = users; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }
}
