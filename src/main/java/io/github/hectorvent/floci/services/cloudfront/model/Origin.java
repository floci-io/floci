package io.github.hectorvent.floci.services.cloudfront.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public class Origin {

    private String id;
    private String domainName;
    private String originPath;
    private String originAccessControlId;
    private Map<String, String> s3OriginConfig;
    private Map<String, Object> customOriginConfig;
    private int connectionAttempts = 3;
    private int connectionTimeout = 10;
    private List<Map<String, String>> customHeaders;

    public Origin() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getDomainName() { return domainName; }
    public void setDomainName(String domainName) { this.domainName = domainName; }

    public String getOriginPath() { return originPath; }
    public void setOriginPath(String originPath) { this.originPath = originPath; }

    public String getOriginAccessControlId() { return originAccessControlId; }
    public void setOriginAccessControlId(String originAccessControlId) { this.originAccessControlId = originAccessControlId; }

    public Map<String, String> getS3OriginConfig() { return s3OriginConfig; }
    public void setS3OriginConfig(Map<String, String> s3OriginConfig) { this.s3OriginConfig = s3OriginConfig; }

    public Map<String, Object> getCustomOriginConfig() { return customOriginConfig; }
    public void setCustomOriginConfig(Map<String, Object> customOriginConfig) { this.customOriginConfig = customOriginConfig; }

    public int getConnectionAttempts() { return connectionAttempts; }
    public void setConnectionAttempts(int connectionAttempts) { this.connectionAttempts = connectionAttempts; }

    public int getConnectionTimeout() { return connectionTimeout; }
    public void setConnectionTimeout(int connectionTimeout) { this.connectionTimeout = connectionTimeout; }

    public List<Map<String, String>> getCustomHeaders() { return customHeaders; }
    public void setCustomHeaders(List<Map<String, String>> customHeaders) { this.customHeaders = customHeaders; }
}
