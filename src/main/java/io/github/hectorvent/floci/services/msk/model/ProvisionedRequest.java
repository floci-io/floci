package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProvisionedRequest {

    @JsonProperty("kafkaVersion")
    private String kafkaVersion;

    // See BrokerCountDeserializer: binding straight to Integer would let Jackson (or a later
    // double comparison) silently narrow a fractional or precision-collapsed value. BrokerCount
    // carries "malformed" as a value rather than throwing during binding - see that class.
    @JsonProperty("numberOfBrokerNodes")
    @JsonDeserialize(using = BrokerCountDeserializer.class)
    private BrokerCount numberOfBrokerNodes;

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

    @JsonProperty("openMonitoring")
    private OpenMonitoring openMonitoring;

    @JsonProperty("storageMode")
    private String storageMode;

    @JsonProperty("rebalancing")
    private Rebalancing rebalancing;

    public ProvisionedRequest() {}

    public String getKafkaVersion() { return kafkaVersion; }
    public void setKafkaVersion(String kafkaVersion) { this.kafkaVersion = kafkaVersion; }

    // See CreateClusterRequest's identical accessors for why these are @JsonIgnore'd.
    @JsonIgnore
    public Integer getNumberOfBrokerNodes() { return numberOfBrokerNodes != null ? numberOfBrokerNodes.value() : null; }
    @JsonIgnore
    public void setNumberOfBrokerNodes(Integer numberOfBrokerNodes) {
        this.numberOfBrokerNodes = numberOfBrokerNodes != null ? BrokerCount.of(numberOfBrokerNodes) : null;
    }

    /** True when the request supplied a numberOfBrokerNodes that isn't an exact whole number. */
    public boolean isNumberOfBrokerNodesMalformed() {
        return numberOfBrokerNodes != null && numberOfBrokerNodes.isMalformed();
    }

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

    public OpenMonitoring getOpenMonitoring() { return openMonitoring; }
    public void setOpenMonitoring(OpenMonitoring openMonitoring) { this.openMonitoring = openMonitoring; }

    public String getStorageMode() { return storageMode; }
    public void setStorageMode(String storageMode) { this.storageMode = storageMode; }

    public Rebalancing getRebalancing() { return rebalancing; }
    public void setRebalancing(Rebalancing rebalancing) { this.rebalancing = rebalancing; }
}
