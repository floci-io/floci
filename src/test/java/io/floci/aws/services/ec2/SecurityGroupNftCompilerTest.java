package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.Ipv6Range;
import io.floci.aws.services.ec2.model.SecurityGroup;
import io.floci.aws.services.ec2.model.UserIdGroupPair;
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
    void vpcResolversStayReachableWhenTheGroupsAllowNoEgress() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");

        String nft = SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of("172.18.0.2", "fd00::53"));

        assertTrue(nft.contains("egress ip daddr 172.18.0.2 udp dport 53 accept"));
        assertTrue(nft.contains("egress ip daddr 172.18.0.2 tcp dport 53 accept"));
        assertTrue(nft.contains("egress ip6 daddr fd00::53 udp dport 53 accept"));
        assertFalse(nft.contains("daddr 172.18.0.2 accept"), "only DNS is allowed to the resolver");
    }

    @Test
    void theEcsTaskMetadataEndpointStaysReachableOnItsOneListenerOnly() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");

        String nft = SecurityGroupNftCompiler.compile(endpoint("eni-a", "10.0.0.2", "172.17.0.2", group),
                List.of(), Map.of(), List.of());

        assertTrue(nft.contains("add rule inet floci_sg egress ip daddr 169.254.170.2 tcp dport 80 accept\n"), nft);
        assertFalse(nft.contains("daddr 169.254.170.2 accept"), "only the endpoint's listener is allowed");
        assertFalse(nft.contains("saddr 169.254.170.2"), "replies ride the established rule; nothing opens ingress");
    }

    /** A group allowing tcp/443 from {@code cidrIpv6} only. */
    private static SecurityGroup ipv6Group(String cidrIpv6) {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(443);
        permission.setToPort(443);
        Ipv6Range range = new Ipv6Range();
        range.setCidrIpv6(cidrIpv6);
        permission.getIpv6Ranges().add(range);
        group.getIpPermissions().add(permission);
        return group;
    }

    private static SecurityGroupNftCompiler.Endpoint dualStack(String eni, String logical, List<String> logicalIpv6,
                                                               String transport, String transportIpv6,
                                                               SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1", eni, logical, logicalIpv6,
                transport, transportIpv6, Set.of("sg-1"), List.of(group));
    }

    // Catches: a rule allowing a peer's IPv6 range never matching it, because only its IPv4 address was an identity.
    @Test
    void aRuleOnAnIpv6RangeMatchesThePeerOnItsIpv6Transport() {
        SecurityGroup group = ipv6Group("2600:1f18:0:a600::/64");
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of("2600:1f18:0:a600::10"),
                "172.17.0.2", "fd00:d0c::2", group);
        SecurityGroupNftCompiler.Endpoint peer = dualStack("eni-b", "10.0.0.3",
                List.of("2600:1f18:0:a600::20", "2600:1f18:0:a600::21"), "172.17.0.3", "fd00:d0c::3", group);

        String nft = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());

        assertTrue(nft.contains("ingress ip6 saddr fd00:d0c::3 tcp dport 443 accept"));
        // An IPv6-only permission never admits the peer's IPv4 transport.
        assertFalse(nft.contains("ip saddr 172.17.0.3 tcp dport 443 accept"));
        assertTrue(nft.contains("ingress ip saddr 172.17.0.3 drop"));
        assertTrue(nft.contains("ingress ip6 saddr fd00:d0c::3 drop"));
    }

    // Catches: a peer's IPv6 address paired with its IPv4 transport when its Docker network has no IPv6, which
    // would let IPv4 traffic through on a rule that only names an IPv6 range.
    @Test
    void aPeerWithoutAnIpv6TransportGetsNoIpv6Rule() {
        SecurityGroup group = ipv6Group("2600:1f18:0:a600::/64");
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of(), "172.17.0.2", null,
                group);
        SecurityGroupNftCompiler.Endpoint peer = dualStack("eni-b", "10.0.0.3", List.of("2600:1f18:0:a600::20"),
                "172.17.0.3", null, group);

        String nft = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());

        assertFalse(nft.contains("172.17.0.3 tcp dport 443 accept"));
        assertTrue(nft.contains("ingress ip saddr 172.17.0.3 drop"));
    }

    // Catches: an IPv4-only ENI on an IPv6-enabled Docker network sending on its helper's IPv6 address past the
    // managed-peer drop, straight into a broad IPv6 CIDR rule.
    @Test
    void aPeersIpv6TransportIsDroppedEvenWithoutAnIpv6Address() {
        SecurityGroup group = ipv6Group("::/0");
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of(), "172.17.0.2",
                "fd00:d0c::2", group);
        SecurityGroupNftCompiler.Endpoint ipv4Only = dualStack("eni-b", "10.0.0.3", List.of(), "172.17.0.3",
                "fd00:d0c::3", group);

        String nft = SecurityGroupNftCompiler.compile(target, List.of(ipv4Only), Map.of());

        assertTrue(nft.contains("ingress ip6 saddr fd00:d0c::3 drop"));
        assertTrue(nft.indexOf("ingress ip6 saddr fd00:d0c::3 drop")
                < nft.indexOf("ingress ip6 saddr ::/0 tcp dport 443 accept"));
    }

    // Catches: a group-reference ICMP rule written against a peer's IPv6 transport, which nftables refuses
    // ("ip6 saddr ... icmp type"), failing the whole policy.
    @Test
    void anIcmpGroupRuleIsWrittenOnlyForThePeersIpv4Identity() {
        SecurityGroup group = new SecurityGroup();
        group.setVpcId("vpc-1");
        group.setOwnerId("000000000000");
        IpPermission echo = new IpPermission();
        echo.setIpProtocol("icmp");
        echo.setFromPort(8);
        echo.setToPort(0);
        UserIdGroupPair self = new UserIdGroupPair();
        self.setGroupId("sg-1");
        echo.getUserIdGroupPairs().add(self);
        group.getIpPermissions().add(echo);
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of("2600:1f18:0:a600::10"),
                "172.17.0.2", "fd00:d0c::2", group);
        SecurityGroupNftCompiler.Endpoint peer = dualStack("eni-b", "10.0.0.3", List.of("2600:1f18:0:a600::20"),
                "172.17.0.3", "fd00:d0c::3", group);

        String nft = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());

        assertTrue(nft.contains("ingress ip saddr 172.17.0.3 icmp type 8 icmp code 0 accept"));
        assertFalse(nft.contains("ip6 saddr fd00:d0c::3 icmp"));
    }

    // Catches: an address change leaving the endpoint's identity as it was registered.
    @Test
    void anEndpointTakesNewIpv6AddressesKeepingItsPolicy() {
        SecurityGroup group = ipv6Group("2600:1f18:0:a600::/64");
        SecurityGroupNftCompiler.Endpoint peer = dualStack("eni-b", "10.0.0.3", List.of(), "172.17.0.3",
                "fd00:d0c::3", group);
        SecurityGroupNftCompiler.Endpoint target = dualStack("eni-a", "10.0.0.2", List.of(), "172.17.0.2",
                "fd00:d0c::2", group);

        String before = SecurityGroupNftCompiler.compile(target, List.of(peer), Map.of());
        String after = SecurityGroupNftCompiler.compile(target,
                List.of(peer.withIpv6Addresses(List.of("2600:1f18:0:a600::20"))), Map.of());

        assertFalse(before.contains("ip6 saddr fd00:d0c::3 tcp dport 443 accept"));
        assertTrue(after.contains("ip6 saddr fd00:d0c::3 tcp dport 443 accept"));
    }

    private static SecurityGroupNftCompiler.Endpoint endpoint(String eni, String logical,
                                                               String transport, SecurityGroup group) {
        return new SecurityGroupNftCompiler.Endpoint("000000000000", "us-east-1", "vpc-1",
                eni, logical, transport, Set.of("sg-1"), List.of(group));
    }
}
