package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.Ipv6Range;
import io.github.hectorvent.floci.services.ec2.model.PrefixListId;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupPolicyTest {

    @Test
    void evaluatesLogicalPeerIdentityAndPorts() {
        SecurityGroup group = group("sg-target");
        IpPermission rule = tcp(443);
        IpRange cidr = new IpRange();
        cidr.setCidrIp("10.42.0.0/16");
        rule.getIpRanges().add(cidr);
        group.getIpPermissions().add(rule);

        assertTrue(SecurityGroupPolicy.allows(List.of(group), false, "6", 443, -1,
                peer("10.42.7.9"), Map.of()));
        assertFalse(SecurityGroupPolicy.allows(List.of(group), false, "tcp", 443, -1,
                peer("10.43.7.9"), Map.of()));
        assertFalse(SecurityGroupPolicy.allows(List.of(group), false, "tcp", 80, -1,
                peer("10.42.7.9"), Map.of()));
        assertFalse(SecurityGroupPolicy.allows(List.of(group), true, "tcp", 443, -1,
                peer("10.42.7.9"), Map.of()));
    }

    @Test
    void evaluatesReferencesPrefixListsAndIpv6() {
        SecurityGroup group = group("sg-target");
        IpPermission byGroup = tcp(80);
        UserIdGroupPair reference = new UserIdGroupPair();
        reference.setGroupId("sg-peer");
        reference.setUserId("000000000000");
        byGroup.getUserIdGroupPairs().add(reference);
        group.getIpPermissions().add(byGroup);

        assertTrue(SecurityGroupPolicy.allows(List.of(group), false, "tcp", 80, -1,
                peer("172.17.0.4"), Map.of()));
        assertFalse(SecurityGroupPolicy.allows(List.of(group), false, "tcp", 80, -1,
                new SecurityGroupPolicy.Peer("172.17.0.4", "000000000000", "vpc-other", Set.of("sg-peer")), Map.of()));

        IpPermission prefix = tcp(8080);
        PrefixListId id = new PrefixListId();
        id.setPrefixListId("pl-1");
        prefix.getPrefixListIds().add(id);
        group.getIpPermissions().add(prefix);
        assertTrue(SecurityGroupPolicy.allows(List.of(group), false, "tcp", 8080, -1,
                peer("10.42.7.9"), Map.of("pl-1", List.of("10.42.0.0/16"))));

        IpPermission ipv6 = tcp(8443);
        Ipv6Range range = new Ipv6Range();
        range.setCidrIpv6("2001:db8::/32");
        ipv6.getIpv6Ranges().add(range);
        group.getIpPermissions().add(ipv6);
        assertTrue(SecurityGroupPolicy.allows(List.of(group), false, "tcp", 8443, -1,
                peer("2001:db8::1"), Map.of()));
        assertFalse(SecurityGroupPolicy.inCidr("10.42.7.9", "invalid/16"));
    }

    private static SecurityGroup group(String id) {
        SecurityGroup group = new SecurityGroup();
        group.setGroupId(id);
        group.setOwnerId("000000000000");
        group.setVpcId("vpc-1");
        return group;
    }

    private static IpPermission tcp(int port) {
        IpPermission rule = new IpPermission();
        rule.setIpProtocol("tcp");
        rule.setFromPort(port);
        rule.setToPort(port);
        return rule;
    }

    private static SecurityGroupPolicy.Peer peer(String ip) {
        return new SecurityGroupPolicy.Peer(ip, "000000000000", "vpc-1", Set.of("sg-peer"));
    }
}
