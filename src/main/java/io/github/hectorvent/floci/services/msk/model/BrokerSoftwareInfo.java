package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Where a cluster reports the Apache Kafka version it runs and the MSK configuration it
 * was created with.
 *
 * <p>{@code configurationInfo} is a CreateCluster *request* member only - neither
 * DescribeCluster's {@code ClusterInfo} nor DescribeClusterV2's {@code Provisioned} has a
 * top-level {@code configurationInfo}. AWS echoes the configuration back here instead, as
 * {@code configurationArn}/{@code configurationRevision}, and that is where
 * terraform-provider-aws reads it from to populate {@code configuration_info}.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BrokerSoftwareInfo {

    @JsonProperty("configurationArn")
    private String configurationArn;

    @JsonProperty("configurationRevision")
    private Long configurationRevision;

    @JsonProperty("kafkaVersion")
    private String kafkaVersion;

    public BrokerSoftwareInfo() {}

    public BrokerSoftwareInfo(String kafkaVersion) {
        this.kafkaVersion = kafkaVersion;
    }

    public String getConfigurationArn() { return configurationArn; }
    public void setConfigurationArn(String configurationArn) { this.configurationArn = configurationArn; }

    public Long getConfigurationRevision() { return configurationRevision; }
    public void setConfigurationRevision(Long configurationRevision) { this.configurationRevision = configurationRevision; }

    public String getKafkaVersion() { return kafkaVersion; }
    public void setKafkaVersion(String kafkaVersion) { this.kafkaVersion = kafkaVersion; }
}
