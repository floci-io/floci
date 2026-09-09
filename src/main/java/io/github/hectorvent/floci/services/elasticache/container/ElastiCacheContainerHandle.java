package io.github.hectorvent.floci.services.elasticache.container;

import java.io.Closeable;

/**
 * Wraps a running backend Docker container for an ElastiCache replication group.
 */
public class ElastiCacheContainerHandle {

    private final String containerId;
    private final String groupId;
    private final String host;
    private final int port;
    private String networkIp;
    private Closeable logStream;

    public ElastiCacheContainerHandle(String containerId, String groupId, String host, int port) {
        this.containerId = containerId;
        this.groupId = groupId;
        this.host = host;
        this.port = port;
    }

    public String getContainerId() { return containerId; }
    public String getGroupId() { return groupId; }
    public String getHost() { return host; }
    public int getPort() { return port; }

    /** The container's IP on its Docker network — the address sibling containers dial. */
    public String getNetworkIp() { return networkIp; }
    public void setNetworkIp(String networkIp) { this.networkIp = networkIp; }

    public Closeable getLogStream() { return logStream; }
    public void setLogStream(Closeable logStream) { this.logStream = logStream; }
}
