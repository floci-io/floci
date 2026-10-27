package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkAclEntry {

    private int ruleNumber;
    private String protocol;
    private String ruleAction;
    private boolean egress;
    private String cidrBlock;
    private String ipv6CidrBlock;
    private Integer icmpType;
    private Integer icmpCode;
    private Integer portRangeFrom;
    private Integer portRangeTo;

    public NetworkAclEntry() {}

    public int getRuleNumber() { return ruleNumber; }
    public void setRuleNumber(int ruleNumber) { this.ruleNumber = ruleNumber; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getRuleAction() { return ruleAction; }
    public void setRuleAction(String ruleAction) { this.ruleAction = ruleAction; }

    public boolean isEgress() { return egress; }
    public void setEgress(boolean egress) { this.egress = egress; }

    public String getCidrBlock() { return cidrBlock; }
    public void setCidrBlock(String cidrBlock) { this.cidrBlock = cidrBlock; }

    public String getIpv6CidrBlock() { return ipv6CidrBlock; }
    public void setIpv6CidrBlock(String ipv6CidrBlock) { this.ipv6CidrBlock = ipv6CidrBlock; }

    public Integer getIcmpType() { return icmpType; }
    public void setIcmpType(Integer icmpType) { this.icmpType = icmpType; }

    public Integer getIcmpCode() { return icmpCode; }
    public void setIcmpCode(Integer icmpCode) { this.icmpCode = icmpCode; }

    public Integer getPortRangeFrom() { return portRangeFrom; }
    public void setPortRangeFrom(Integer portRangeFrom) { this.portRangeFrom = portRangeFrom; }

    public Integer getPortRangeTo() { return portRangeTo; }
    public void setPortRangeTo(Integer portRangeTo) { this.portRangeTo = portRangeTo; }
}
