package io.github.hectorvent.floci.services.efs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class UpdateFileSystemRequest {

    private Double provisionedThroughputInMibps;
    private ThroughputMode throughputMode;

    public Double getProvisionedThroughputInMibps() {
        return provisionedThroughputInMibps;
    }

    public void setProvisionedThroughputInMibps(Double provisionedThroughputInMibps) {
        this.provisionedThroughputInMibps = provisionedThroughputInMibps;
    }

    public ThroughputMode getThroughputMode() {
        return throughputMode;
    }

    public void setThroughputMode(ThroughputMode throughputMode) {
        this.throughputMode = throughputMode;
    }
}