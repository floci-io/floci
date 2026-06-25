package io.github.hectorvent.floci.services.memorydb.container;

import java.io.Closeable;

/**
 * Wraps a running backend Docker container for a MemoryDB cluster.
 */
public class MemoryDbContainerHandle {

    private final String containerId;
    private final String clusterName;
    private final String host;
    private final int port;
    private Closeable logStream;

    public MemoryDbContainerHandle(String containerId, String clusterName, String host, int port) {
        this.containerId = containerId;
        this.clusterName = clusterName;
        this.host = host;
        this.port = port;
    }

    public String getContainerId() { return containerId; }
    public String getClusterName() { return clusterName; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public Closeable getLogStream() { return logStream; }
    public void setLogStream(Closeable logStream) { this.logStream = logStream; }
}
