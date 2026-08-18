package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.Map;

@RegisterForReflection
public class MskCluster {

    @JsonProperty("clusterArn")
    private String clusterArn;

    @JsonProperty("clusterName")
    private String clusterName;

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

    @JsonProperty("configurationInfo")
    private ConfigurationInfo configurationInfo;

    // Internal field, not directly in AWS response but needed for GetBootstrapBrokers
    @JsonIgnore
    private String bootstrapBrokers;

    // Docker container ID for mock=false
    @JsonIgnore
    private String containerId;

    @JsonIgnore
    private String accountId;

    // 6-char hex generated once at creation for stable, collision-free volume/container naming
    @JsonIgnore
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

    public ConfigurationInfo getConfigurationInfo() { return configurationInfo; }
    public void setConfigurationInfo(ConfigurationInfo configurationInfo) { this.configurationInfo = configurationInfo; }

    @JsonIgnore
    public String getBootstrapBrokers() { return bootstrapBrokers; }
    public void setBootstrapBrokers(String bootstrapBrokers) { this.bootstrapBrokers = bootstrapBrokers; }

    @JsonIgnore
    public String getContainerId() { return containerId; }
    public void setContainerId(String containerId) { this.containerId = containerId; }

    @JsonIgnore
    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    @JsonIgnore
    public String getVolumeId() { return volumeId; }
    public void setVolumeId(String volumeId) { this.volumeId = volumeId; }
}
