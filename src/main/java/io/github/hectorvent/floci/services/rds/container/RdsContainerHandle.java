package io.github.hectorvent.floci.services.rds.container;

import java.io.Closeable;

/**
 * Wraps a running backend Docker container for an RDS DB instance or cluster.
 */
public class RdsContainerHandle {

    private final String containerId;
    private final String runtimeId;
    private final String instanceId;
    private final String host;
    private final int port;
    private final String storageKey;
    private final String containerKey;
    private Closeable logStream;

    public RdsContainerHandle(String containerId, String instanceId, String host, int port) {
        this(containerId, instanceId, instanceId, host, port, null);
    }

    public RdsContainerHandle(
            String containerId, String runtimeId, String instanceId, String host, int port) {
        this(containerId, runtimeId, instanceId, host, port, null);
    }

    public RdsContainerHandle(
            String containerId, String runtimeId, String instanceId,
            String host, int port, String storageKey) {
        this(containerId, runtimeId, instanceId, host, port, storageKey, null);
    }

    public RdsContainerHandle(
            String containerId, String runtimeId, String instanceId,
            String host, int port, String storageKey, String containerKey) {
        this.containerId = containerId;
        this.runtimeId = runtimeId == null || runtimeId.isBlank() ? instanceId : runtimeId;
        this.instanceId = instanceId;
        this.host = host;
        this.port = port;
        this.storageKey = storageKey;
        this.containerKey = containerKey;
    }

    public String getContainerId() { return containerId; }
    public String getRuntimeId() { return runtimeId; }
    public String getInstanceId() { return instanceId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getStorageKey() { return storageKey; }
    public String getContainerKey() { return containerKey; }
    public Closeable getLogStream() { return logStream; }
    public void setLogStream(Closeable logStream) { this.logStream = logStream; }
}
