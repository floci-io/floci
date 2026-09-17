package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.Ipv6Range;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityGroupNftCompilerTest {

    @Test
    void managedPeersAreCheckedBeforeDockerAddressCidrs() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        IpRange cidr = new IpRange();
        cidr.setCidrIp("10.0.0.0/16");
        permission.getIpRanges().add(cidr);
        IpRange broadTransport = new IpRange();
        broadTransport.setCidrIp("172.17.0.0/16");
        permission.getIpRanges().add(broadTransport);
        group.getIpPermissions().add(permission);

        SecurityGroupNftCompiler.Endpoint target = endpoint("eni-a", "10.0.0.2", "172.17.0.2", group);
        SecurityGroupNftCompiler.Endpoint allowed = endpoint("eni-b", "10.0.0.3", "172.17.0.3", group);
        SecurityGroupNftCompiler.Endpoint denied = endpoint("eni-c", "10.1.0.3", "172.17.0.4", group);
        String nft = SecurityGroupNftCompiler.compile(target, List.of(allowed, denied), Map.of());

        assertTrue(nft.contains("ip saddr 172.17.0.3 tcp dport 443 accept"));
        assertTrue(nft.indexOf("ip saddr 172.17.0.4 drop")
                < nft.indexOf("ip saddr 172.17.0.0/16 tcp dport 443 accept"));
    }

    @Test
    void icmpTypeAndCodeRepeatTheProtocolKeyword() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        // AWS maps IpPermission.FromPort to the ICMP type and ToPort to the ICMP code.
        IpPermission echo = new IpPermission();
        echo.setIpProtocol("icmp");
        echo.setFromPort(8);
        echo.setToPort(0);
        IpRange cidr = new IpRange();
        cidr.setCidrIp("10.0.0.0/16");
        echo.getIpRanges().add(cidr);
        group.getIpPermissions().add(echo);
        IpPermission anyIcmpv6 = new IpPermission();
        anyIcmpv6.setIpProtocol("icmpv6");
        anyIcmpv6.setFromPort(-1);
        anyIcmpv6.setToPort(-1);
        IpRange allV4 = new IpRange();
        allV4.setCidrIp("10.1.0.0/16");
        anyIcmpv6.getIpRanges().add(allV4);
        group.getIpPermissions().add(anyIcmpv6);

        String nft = SecurityGroupNftCompiler.compile(
                endpoint("eni-a", "10.0.0.2", "172.17.0.2", group), List.of(), Map.of());

        assertTrue(nft.contains("ip saddr 10.0.0.0/16 icmp type 8 icmp code 0 accept"));
        assertFalse(nft.contains("icmp type 8 code 0"));
        assertTrue(nft.contains("ip saddr 10.1.0.0/16 meta l4proto icmpv6 accept"));
    }

    @Test
    void managedIpv6PeersUseLogicalAddressForPolicyAndTransportAddressForNft() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(8443);
        permission.setToPort(8443);
        Ipv6Range range = new Ipv6Range();
        range.setCidrIpv6("2001:db8:1::/64");
        permission.getIpv6Ranges().add(range);
        group.getIpPermissions().add(permission);

        SecurityGroupNftCompiler.Endpoint target = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-1", "eni-a",
                "10.0.0.2", "2001:db8:2::2", "172.17.0.2", "fd00::2",
                Set.of("sg-1"), List.of(group));
        SecurityGroupNftCompiler.Endpoint peer = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-1", "eni-b",
                "10.0.0.3", "2001:db8:1::3", "172.17.0.3", "fd00::3",
                Set.of("sg-1"), List.of(group));

        String nft = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());

        assertTrue(nft.contains("ip6 saddr fd00::3 tcp dport 8443 accept"));
        assertFalse(nft.contains("ip6 saddr 2001:db8:1::3"));
    }

    @Test
    void ipv6OnlyPermissionNeverAllowsThePeersIpv4Transport() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("udp");
        permission.setFromPort(53);
        permission.setToPort(53);
        permission.getIpv6Ranges().add(new Ipv6Range("2001:db8:1::/64"));
        group.getIpPermissions().add(permission);

        SecurityGroupNftCompiler.Endpoint target = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-1", "eni-a",
                "10.0.0.2", "2001:db8:2::2", "172.17.0.2", null,
                Set.of("sg-1"), List.of(group));
        SecurityGroupNftCompiler.Endpoint peer = new SecurityGroupNftCompiler.Endpoint(
                "000000000000", "us-east-1", "vpc-1", "eni-b",
                "10.0.0.3", "2001:db8:1::3", "172.17.0.3", null,
                Set.of("sg-1"), List.of(group));

        String nft = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());

        // Only the peer's IPv6 range is authorized, and neither endpoint has an IPv6 transport, so
        // the peer is unreachable rather than reachable over IPv4 the permission never allowed. The
        // peer's logical IPv4 address is outside the range, so the identity carries no permission.
        assertFalse(nft.contains("ip saddr 172.17.0.3 udp dport 53 accept"));
        assertTrue(nft.contains("ip saddr 172.17.0.3 drop"));
    }

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String logical,
                                                               String transport, SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1",
                eni, logical, transport, Set.of("sg-1"), List.of(group));
    }
}
