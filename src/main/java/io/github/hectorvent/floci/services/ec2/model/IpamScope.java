package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IpamScope {

    private String ipamScopeId;
    private String ipamScopeArn;
    private String ipamId;
    private String scopeType;
    private boolean isDefault;
    private String state;

    public IpamScope() {}

    public String getIpamScopeId() { return ipamScopeId; }
    public void setIpamScopeId(String ipamScopeId) { this.ipamScopeId = ipamScopeId; }

    public String getIpamScopeArn() { return ipamScopeArn; }
    public void setIpamScopeArn(String ipamScopeArn) { this.ipamScopeArn = ipamScopeArn; }

    public String getIpamId() { return ipamId; }
    public void setIpamId(String ipamId) { this.ipamId = ipamId; }

    public String getScopeType() { return scopeType; }
    public void setScopeType(String scopeType) { this.scopeType = scopeType; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
}
