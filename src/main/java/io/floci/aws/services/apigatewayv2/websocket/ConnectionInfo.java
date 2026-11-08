package io.floci.aws.services.apigatewayv2.websocket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ConnectionInfo {
    private String connectionId;
    private String apiId;
    private String stageName;
    private String region;
    private long connectedAt;
    private long lastActiveAt;
    private String sourceIp;
    private String userAgent;

    public ConnectionInfo() {}

    public ConnectionInfo(String connectionId, String apiId, String stageName, String region,
                          long connectedAt, long lastActiveAt, String sourceIp, String userAgent) {
        this.connectionId = connectionId;
        this.apiId = apiId;
        this.stageName = stageName;
        this.region = region;
        this.connectedAt = connectedAt;
        this.lastActiveAt = lastActiveAt;
        this.sourceIp = sourceIp;
        this.userAgent = userAgent;
    }

    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }

    public String getApiId() { return apiId; }
    public void setApiId(String apiId) { this.apiId = apiId; }

    public String getStageName() { return stageName; }
    public void setStageName(String stageName) { this.stageName = stageName; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public long getConnectedAt() { return connectedAt; }
    public void setConnectedAt(long connectedAt) { this.connectedAt = connectedAt; }

    public long getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(long lastActiveAt) { this.lastActiveAt = lastActiveAt; }

    public String getSourceIp() { return sourceIp; }
    public void setSourceIp(String sourceIp) { this.sourceIp = sourceIp; }

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
}
