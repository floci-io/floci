package io.github.hectorvent.floci.services.wafv2.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** WAF v2 IP set. {@code ipAddressVersion} is IPV4 or IPV6; {@code addresses} are CIDRs. */
@RegisterForReflection
public class IpSet {

    private String id;
    private String name;
    private String arn;
    private String scope;
    private String description;
    private String ipAddressVersion;
    private List<String> addresses = new ArrayList<>();
    private String lockToken;
    private String region;
    private Map<String, String> tags = new HashMap<>();

    public IpSet() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getScope() { return scope; }
    public void setScope(String scope) { this.scope = scope; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getIpAddressVersion() { return ipAddressVersion; }
    public void setIpAddressVersion(String ipAddressVersion) { this.ipAddressVersion = ipAddressVersion; }

    public List<String> getAddresses() { return addresses; }
    public void setAddresses(List<String> addresses) { this.addresses = addresses; }

    public String getLockToken() { return lockToken; }
    public void setLockToken(String lockToken) { this.lockToken = lockToken; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
