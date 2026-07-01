package io.github.hectorvent.floci.services.ec2.portforward;

import io.github.hectorvent.floci.services.ec2.model.IpPermission;
import io.github.hectorvent.floci.services.ec2.model.IpRange;
import io.github.hectorvent.floci.services.ec2.model.Ipv6Range;
import io.github.hectorvent.floci.services.ec2.model.SecurityGroup;
import io.github.hectorvent.floci.services.ec2.model.UserIdGroupPair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ec2PortForwardManagerTest {

    private static final int MAX = 20;

    @Test
    void extractsTcpPortsFromCidrSourcedRules() {
        SecurityGroup sg = sg(
                tcpCidr(80, 80, "0.0.0.0/0"),
                tcpCidr(8080, 8080, "10.0.0.0/8"));

        assertEquals(Set.of(80, 8080), extract(sg));
    }

    @Test
    void treatsNumericProtocolSixAsTcp() {
        IpPermission perm = new IpPermission();
        perm.setIpProtocol("6");
        perm.setFromPort(443);
        perm.setToPort(443);
        perm.getIpRanges().add(new IpRange("0.0.0.0/0"));

        assertEquals(Set.of(443), extract(sg(perm)));
    }

    @Test
    void publishesEveryPortInARange() {
        assertEquals(Set.of(8000, 8001, 8002), extract(sg(tcpCidr(8000, 8002, "0.0.0.0/0"))));
    }

    @Test
    void publishesIpv6SourcedRules() {
        IpPermission perm = new IpPermission();
        perm.setIpProtocol("tcp");
        perm.setFromPort(80);
        perm.setToPort(80);
        perm.getIpv6Ranges().add(new Ipv6Range("::/0"));

        assertEquals(Set.of(80), extract(sg(perm)));
    }

    @Test
    void aggregatesAndDedupesAcrossSecurityGroups() {
        SecurityGroup a = sg(tcpCidr(80, 80, "0.0.0.0/0"));
        SecurityGroup b = sg(tcpCidr(80, 80, "0.0.0.0/0"), tcpCidr(443, 443, "0.0.0.0/0"));

        assertEquals(Set.of(80, 443),
                Ec2PortForwardManager.extractPublishablePorts(List.of(a, b), MAX));
    }

    @Test
    void skipsNonTcpProtocols() {
        assertTrue(extract(sg(protoCidr("udp", 53, 53, "0.0.0.0/0"))).isEmpty());
        assertTrue(extract(sg(protoCidr("17", 53, 53, "0.0.0.0/0"))).isEmpty());
        assertTrue(extract(sg(protoCidr("icmp", -1, -1, "0.0.0.0/0"))).isEmpty());
    }

    @Test
    void skipsAllProtocolsRule() {
        // ipProtocol "-1" means all protocols/all ports; fromPort/toPort come as -1.
        assertTrue(extract(sg(protoCidr("-1", -1, -1, "0.0.0.0/0"))).isEmpty());
    }

    @Test
    void skipsRulesSourcedOnlyBySecurityGroupReference() {
        IpPermission perm = new IpPermission();
        perm.setIpProtocol("tcp");
        perm.setFromPort(80);
        perm.setToPort(80);
        UserIdGroupPair pair = new UserIdGroupPair();
        pair.setGroupId("sg-web");
        perm.getUserIdGroupPairs().add(pair);

        assertTrue(extract(sg(perm)).isEmpty());
    }

    @Test
    void neverForwardsSsh() {
        assertTrue(extract(sg(tcpCidr(22, 22, "0.0.0.0/0"))).isEmpty());
        // A range spanning 22 keeps its other ports but drops 22.
        assertEquals(Set.of(21, 23), extract(sg(tcpCidr(21, 23, "0.0.0.0/0"))));
    }

    @Test
    void skipsRuleWhoseSpanExceedsMax() {
        assertTrue(extract(sg(tcpCidr(0, 65535, "0.0.0.0/0"))).isEmpty());
    }

    @Test
    void capsTotalPublishedPortsPerInstance() {
        Set<Integer> ports = Ec2PortForwardManager.extractPublishablePorts(
                List.of(sg(tcpCidr(3000, 3000, "0.0.0.0/0"),
                        tcpCidr(3001, 3001, "0.0.0.0/0"),
                        tcpCidr(3002, 3002, "0.0.0.0/0"))),
                2);

        assertEquals(2, ports.size());
    }

    @Test
    void protocolHelpersRecognizeTcp() {
        assertTrue(Ec2PortForwardManager.isTcp("tcp"));
        assertTrue(Ec2PortForwardManager.isTcp("TCP"));
        assertTrue(Ec2PortForwardManager.isTcp("6"));
        assertFalse(Ec2PortForwardManager.isTcp("udp"));
        assertFalse(Ec2PortForwardManager.isTcp("17"));
        assertFalse(Ec2PortForwardManager.isTcp(null));
    }

    @Test
    void forwardContainerNameIsDeterministic() {
        assertEquals("floci-ec2-fwd-i-123-80",
                Ec2PortForwardManager.forwardContainerName("i-123", 80));
    }

    private static Set<Integer> extract(SecurityGroup sg) {
        return Ec2PortForwardManager.extractPublishablePorts(List.of(sg), MAX);
    }

    private static SecurityGroup sg(IpPermission... perms) {
        SecurityGroup sg = new SecurityGroup();
        sg.setGroupId("sg-test");
        sg.setIpPermissions(new java.util.ArrayList<>(List.of(perms)));
        return sg;
    }

    private static IpPermission tcpCidr(int from, int to, String cidr) {
        return protoCidr("tcp", from, to, cidr);
    }

    private static IpPermission protoCidr(String proto, int from, int to, String cidr) {
        IpPermission perm = new IpPermission();
        perm.setIpProtocol(proto);
        perm.setFromPort(from);
        perm.setToPort(to);
        perm.getIpRanges().add(new IpRange(cidr));
        return perm;
    }
}
