package io.github.hectorvent.floci.services.efs.model;

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