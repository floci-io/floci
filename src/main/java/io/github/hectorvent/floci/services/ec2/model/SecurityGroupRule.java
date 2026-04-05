package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityGroupRule {

    private String securityGroupRuleId;
    private String groupId;
    private String groupOwnerId;
    private boolean isEgress;
    private String ipProtocol;
    private Integer fromPort;
    private Integer toPort;
    private String cidrIpv4;
    private String cidrIpv6;
    private String description;
    private List<Tag> tags = new ArrayList<>();

    public SecurityGroupRule() {}

    public String getSecurityGroupRuleId() { return securityGroupRuleId; }
    public void setSecurityGroupRuleId(String securityGroupRuleId) { this.securityGroupRuleId = securityGroupRuleId; }

    public String getGroupId() { return groupId; }
    public void setGroupId(String groupId) { this.groupId = groupId; }

    public String getGroupOwnerId() { return groupOwnerId; }
    public void setGroupOwnerId(String groupOwnerId) { this.groupOwnerId = groupOwnerId; }

    public boolean isEgress() { return isEgress; }
    public void setEgress(boolean egress) { isEgress = egress; }

    public String getIpProtocol() { return ipProtocol; }
    public void setIpProtocol(String ipProtocol) { this.ipProtocol = ipProtocol; }

    public Integer getFromPort() { return fromPort; }
    public void setFromPort(Integer fromPort) { this.fromPort = fromPort; }

    public Integer getToPort() { return toPort; }
    public void setToPort(Integer toPort) { this.toPort = toPort; }

    public String getCidrIpv4() { return cidrIpv4; }
    public void setCidrIpv4(String cidrIpv4) { this.cidrIpv4 = cidrIpv4; }

    public String getCidrIpv6() { return cidrIpv6; }
    public void setCidrIpv6(String cidrIpv6) { this.cidrIpv6 = cidrIpv6; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }
}
