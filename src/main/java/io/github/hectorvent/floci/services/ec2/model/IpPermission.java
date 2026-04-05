package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IpPermission {

    private String ipProtocol;
    private Integer fromPort;
    private Integer toPort;
    private List<IpRange> ipRanges = new ArrayList<>();
    private List<Ipv6Range> ipv6Ranges = new ArrayList<>();
    private List<UserIdGroupPair> userIdGroupPairs = new ArrayList<>();

    public IpPermission() {}

    public String getIpProtocol() { return ipProtocol; }
    public void setIpProtocol(String ipProtocol) { this.ipProtocol = ipProtocol; }

    public Integer getFromPort() { return fromPort; }
    public void setFromPort(Integer fromPort) { this.fromPort = fromPort; }

    public Integer getToPort() { return toPort; }
    public void setToPort(Integer toPort) { this.toPort = toPort; }

    public List<IpRange> getIpRanges() { return ipRanges; }
    public void setIpRanges(List<IpRange> ipRanges) { this.ipRanges = ipRanges; }

    public List<Ipv6Range> getIpv6Ranges() { return ipv6Ranges; }
    public void setIpv6Ranges(List<Ipv6Range> ipv6Ranges) { this.ipv6Ranges = ipv6Ranges; }

    public List<UserIdGroupPair> getUserIdGroupPairs() { return userIdGroupPairs; }
    public void setUserIdGroupPairs(List<UserIdGroupPair> userIdGroupPairs) { this.userIdGroupPairs = userIdGroupPairs; }
}
