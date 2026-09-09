package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.Map;

/**
 * The persisted shape of an MSK cluster.
 *
 * <p>This model is what the storage backends serialize: {@code WalStorage},
 * {@code PersistentStorage} and {@code HybridStorage} all write it with a plain
 * {@code ObjectMapper}, so any field hidden here is hidden from the store too and does
 * not survive a restart. Internal bookkeeping ({@code bootstrapBrokers},
 * {@code containerId}, {@code accountId}, {@code volumeId}) therefore stays serializable.
 *
 * <p>Keeping those fields out of the API is the responsibility of the response views in
 * {@code MskController} ({@code toClusterViewV1}/{@code toClusterViewV2}), which build the
 * client-facing JSON explicitly rather than serializing this model directly - the same
 * split {@code MskConfiguration} and {@code toConfigurationView} already use.
 */
@RegisterForReflection
public class MskCluster {

    @JsonProperty("clusterArn")
    private String clusterArn;

    @JsonProperty("clusterName")
    private String clusterName;

    // PROVISIONED or SERVERLESS. Selects which envelope the v2 view emits, and whether the v1
    // operations - which predate serverless and have no way to describe one - accept it at all.
    @JsonProperty("clusterType")
    private String clusterType;

    @JsonProperty("serverless")
    private Serverless serverless;

    @JsonProperty("state")
    private ClusterState state;

    @JsonProperty("creationTime")
    private Instant creationTime;

    @JsonProperty("currentVersion")
    private String currentVersion;

    @JsonProperty("numberOfBrokerNodes")
    private int numberOfBrokerNodes;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("zookeeperConnectString")
    private String zookeeperConnectString;

    @JsonProperty("currentBrokerSoftwareInfo")
    private BrokerSoftwareInfo currentBrokerSoftwareInfo;

    @JsonProperty("brokerNodeGroupInfo")
    private BrokerNodeGroupInfo brokerNodeGroupInfo;

    @JsonProperty("encryptionInfo")
    private EncryptionInfo encryptionInfo;

    @JsonProperty("clientAuthentication")
    private ClientAuthentication clientAuthentication;

    @JsonProperty("enhancedMonitoring")
    private String enhancedMonitoring;

    @JsonProperty("loggingInfo")
    private LoggingInfo loggingInfo;

    @JsonProperty("openMonitoring")
    private OpenMonitoring openMonitoring;

    @JsonProperty("storageMode")
    private String storageMode;

    @JsonProperty("rebalancing")
    private Rebalancing rebalancing;

    // Internal bookkeeping: excluded from API responses by MskController's views, but
    // persisted, since GetBootstrapBrokers, the readiness poller and container/volume
    // teardown all need these back after a restart.

    // Needed for GetBootstrapBrokers (and by Pipes, to resolve a Kafka source's brokers)
    @JsonProperty("bootstrapBrokers")
    private String bootstrapBrokers;

    // Docker container ID for mock=false
    @JsonProperty("containerId")
    private String containerId;

    // Owning account, used by putCluster to write back to the right account partition
    @JsonProperty("accountId")
    private String accountId;

    // 6-char hex generated once at creation for stable, collision-free volume/container naming
    @JsonProperty("volumeId")
    private String volumeId;

    public MskCluster() {}

    public MskCluster(String clusterArn, String clusterName, String kafkaVersion) {
        this.clusterArn = clusterArn;
        this.clusterName = clusterName;
        this.state = ClusterState.CREATING;
        this.creationTime = Instant.now();
        this.currentVersion = "K3V6I1"; // Example version
        this.numberOfBrokerNodes = 1;
        this.zookeeperConnectString = "localhost:2181"; // Mock ZK
        this.currentBrokerSoftwareInfo = new BrokerSoftwareInfo(kafkaVersion);
    }

    public String getClusterArn() { return clusterArn; }
    public void setClusterArn(String clusterArn) { this.clusterArn = clusterArn; }

    public String getClusterName() { return clusterName; }
    public void setClusterName(String clusterName) { this.clusterName = clusterName; }

    public String getClusterType() { return clusterType; }
    public void setClusterType(String clusterType) { this.clusterType = clusterType; }

    public Serverless getServerless() { return serverless; }
    public void setServerless(Serverless serverless) { this.serverless = serverless; }

    public ClusterState getState() { return state; }
    public void setState(ClusterState state) { this.state = state; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    public String getCurrentVersion() { return currentVersion; }
    public void setCurrentVersion(String currentVersion) { this.currentVersion = currentVersion; }

    public int getNumberOfBrokerNodes() { return numberOfBrokerNodes; }
    public void setNumberOfBrokerNodes(int numberOfBrokerNodes) { this.numberOfBrokerNodes = numberOfBrokerNodes; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    public String getZookeeperConnectString() { return zookeeperConnectString; }
    public void setZookeeperConnectString(String zookeeperConnectString) { this.zookeeperConnectString = zookeeperConnectString; }

    public BrokerSoftwareInfo getCurrentBrokerSoftwareInfo() { return currentBrokerSoftwareInfo; }
    public void setCurrentBrokerSoftwareInfo(BrokerSoftwareInfo currentBrokerSoftwareInfo) { this.currentBrokerSoftwareInfo = currentBrokerSoftwareInfo; }

    public BrokerNodeGroupInfo getBrokerNodeGroupInfo() { return brokerNodeGroupInfo; }
    public void setBrokerNodeGroupInfo(BrokerNodeGroupInfo brokerNodeGroupInfo) { this.brokerNodeGroupInfo = brokerNodeGroupInfo; }

    public EncryptionInfo getEncryptionInfo() { return encryptionInfo; }
    public void setEncryptionInfo(EncryptionInfo encryptionInfo) { this.encryptionInfo = encryptionInfo; }

    public ClientAuthentication getClientAuthentication() { return clientAuthentication; }
    public void setClientAuthentication(ClientAuthentication clientAuthentication) { this.clientAuthentication = clientAuthentication; }

    public String getEnhancedMonitoring() { return enhancedMonitoring; }
    public void setEnhancedMonitoring(String enhancedMonitoring) { this.enhancedMonitoring = enhancedMonitoring; }

    public LoggingInfo getLoggingInfo() { return loggingInfo; }
    public void setLoggingInfo(LoggingInfo loggingInfo) { this.loggingInfo = loggingInfo; }

    public OpenMonitoring getOpenMonitoring() { return openMonitoring; }
    public void setOpenMonitoring(OpenMonitoring openMonitoring) { this.openMonitoring = openMonitoring; }

    public String getStorageMode() { return storageMode; }
    public void setStorageMode(String storageMode) { this.storageMode = storageMode; }

    public Rebalancing getRebalancing() { return rebalancing; }
    public void setRebalancing(Rebalancing rebalancing) { this.rebalancing = rebalancing; }

    public String getBootstrapBrokers() { return bootstrapBrokers; }
    public void setBootstrapBrokers(String bootstrapBrokers) { this.bootstrapBrokers = bootstrapBrokers; }

    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }
}
