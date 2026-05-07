package io.github.hectorvent.floci.services.route53.model;

public class HealthCheckConfig {

    private String type;
    private String ipAddress;
    private Integer port;
    private String resourcePath;
    private String fullyQualifiedDomainName;
    private Integer requestInterval;
    private Integer failureThreshold;

    public HealthCheckConfig() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getResourcePath() { return resourcePath; }
    public void setResourcePath(String resourcePath) { this.resourcePath = resourcePath; }

    public String getFullyQualifiedDomainName() { return fullyQualifiedDomainName; }
    public void setFullyQualifiedDomainName(String fullyQualifiedDomainName) {
        this.fullyQualifiedDomainName = fullyQualifiedDomainName;
    }

    public Integer getRequestInterval() { return requestInterval; }
    public void setRequestInterval(Integer requestInterval) { this.requestInterval = requestInterval; }

    public Integer getFailureThreshold() { return failureThreshold; }
    public void setFailureThreshold(Integer failureThreshold) { this.failureThreshold = failureThreshold; }
}
