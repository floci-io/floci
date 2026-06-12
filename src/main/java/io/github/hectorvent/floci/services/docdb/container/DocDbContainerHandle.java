package io.github.hectorvent.floci.services.docdb.container;

import java.io.Closeable;

public class DocDbContainerHandle {

    private final String containerId;
    private final String clusterId;
    private final String host;
    private final int port;
    private Closeable logStream;

    public DocDbContainerHandle(String containerId, String clusterId, String host, int port) {
        this.containerId = containerId;
        this.clusterId = clusterId;
        this.host = host;
        this.port = port;
    }

    public String getContainerId() { return containerId; }
    public String getClusterId() { return clusterId; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public Closeable getLogStream() { return logStream; }
    public void setLogStream(Closeable logStream) { this.logStream = logStream; }
}
