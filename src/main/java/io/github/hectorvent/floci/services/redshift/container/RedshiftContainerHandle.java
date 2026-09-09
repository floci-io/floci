package io.github.hectorvent.floci.services.redshift.container;

import java.io.Closeable;

public class RedshiftContainerHandle {
    private final String containerId;
    private final String clusterIdentifier;
    private final String host;
    private final int port;
    private Closeable logStream;

    public RedshiftContainerHandle(String containerId, String clusterIdentifier, String host, int port) {
        this.containerId = containerId;
        this.clusterIdentifier = clusterIdentifier;
        this.host = host;
        this.port = port;
    }

    public String getContainerId() { return containerId; }
    public String getClusterIdentifier() { return clusterIdentifier; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public void setLogStream(Closeable logStream) { this.logStream = logStream; }
    public Closeable getLogStream() { return logStream; }
}
