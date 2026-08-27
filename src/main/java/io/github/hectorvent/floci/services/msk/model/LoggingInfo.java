package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoggingInfo {

    @JsonProperty("brokerLogs")
    private BrokerLogs brokerLogs;

    public LoggingInfo() {}

    public BrokerLogs getBrokerLogs() { return brokerLogs; }
    public void setBrokerLogs(BrokerLogs brokerLogs) { this.brokerLogs = brokerLogs; }
}