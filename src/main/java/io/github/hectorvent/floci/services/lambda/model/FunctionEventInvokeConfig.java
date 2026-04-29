package io.github.hectorvent.floci.services.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FunctionEventInvokeConfig {

    @JsonProperty("FunctionArn")
    private String functionArn;

    @JsonIgnore
    private long lastModifiedMillis;

    @JsonProperty("MaximumRetryAttempts")
    private Integer maximumRetryAttempts;

    @JsonProperty("MaximumEventAgeInSeconds")
    private Integer maximumEventAgeInSeconds;

    @JsonProperty("DestinationConfig")
    private DestinationConfig destinationConfig;

    public FunctionEventInvokeConfig() {}

    @JsonProperty("LastModified")
    public double getLastModifiedSeconds() {
        return lastModifiedMillis / 1000.0;
    }

    public String getFunctionArn() { return functionArn; }
    public void setFunctionArn(String functionArn) { this.functionArn = functionArn; }

    @JsonIgnore
    public long getLastModified() { return lastModifiedMillis; }
    public void setLastModified(long lastModifiedMillis) { this.lastModifiedMillis = lastModifiedMillis; }

    public Integer getMaximumRetryAttempts() { return maximumRetryAttempts; }
    public void setMaximumRetryAttempts(Integer maximumRetryAttempts) { this.maximumRetryAttempts = maximumRetryAttempts; }

    public Integer getMaximumEventAgeInSeconds() { return maximumEventAgeInSeconds; }
    public void setMaximumEventAgeInSeconds(Integer maximumEventAgeInSeconds) { this.maximumEventAgeInSeconds = maximumEventAgeInSeconds; }

    public DestinationConfig getDestinationConfig() { return destinationConfig; }
    public void setDestinationConfig(DestinationConfig destinationConfig) { this.destinationConfig = destinationConfig; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DestinationConfig {
        @JsonProperty("OnSuccess")
        private Destination onSuccess;

        @JsonProperty("OnFailure")
        private Destination onFailure;

        public DestinationConfig() {}

        public Destination getOnSuccess() { return onSuccess; }
        public void setOnSuccess(Destination onSuccess) { this.onSuccess = onSuccess; }

        public Destination getOnFailure() { return onFailure; }
        public void setOnFailure(Destination onFailure) { this.onFailure = onFailure; }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Destination {
        @JsonProperty("Destination")
        private String destination;

        public Destination() {}
        public Destination(String destination) { this.destination = destination; }

        public String getDestination() { return destination; }
        public void setDestination(String destination) { this.destination = destination; }
    }
}
