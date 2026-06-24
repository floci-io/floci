package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class BrokerSoftwareInfo {

    @JsonProperty("kafkaVersion")
    private String kafkaVersion;

    public BrokerSoftwareInfo() {}

    public BrokerSoftwareInfo(String kafkaVersion) {
        this.kafkaVersion = kafkaVersion;
    }

    public String getKafkaVersion() { return kafkaVersion; }
    public void setKafkaVersion(String kafkaVersion) { this.kafkaVersion = kafkaVersion; }
}
