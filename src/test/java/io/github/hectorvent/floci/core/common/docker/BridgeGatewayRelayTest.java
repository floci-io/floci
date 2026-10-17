package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.model.Network;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BridgeGatewayRelayTest {

    @Test
    void runsOnlyOnALinuxHostListeningOnLoopback() {
        assertThat(BridgeGatewayRelay.shouldRun("Linux", false, "127.0.0.1"), is(true));
        assertThat(BridgeGatewayRelay.shouldRun("Linux", false, "localhost"), is(true));
        assertThat(BridgeGatewayRelay.shouldRun("Linux", true, "127.0.0.1"), is(false));
        assertThat(BridgeGatewayRelay.shouldRun("Linux", false, "0.0.0.0"), is(false));
        assertThat(BridgeGatewayRelay.shouldRun("Mac OS X", false, "127.0.0.1"), is(false));
        assertThat(BridgeGatewayRelay.shouldRun("Windows 11", false, "127.0.0.1"), is(false));
    }

    @Test
    void withoutTlsTheHttpPortIsRelayedEvenWhenItDiffersFromTheFlociPort() {
        assertThat(BridgeGatewayRelay.relayPorts(false, 4566, 4570, 443), equalTo(List.of(4570)));
    }

    @Test
    void withTlsTheProxyPortsAreRelayed() {
        assertThat(BridgeGatewayRelay.relayPorts(true, 4566, 4510, 443), equalTo(List.of(4566, 443)));
        assertThat(BridgeGatewayRelay.relayPorts(true, 4566, 4510, 0), equalTo(List.of(4566)));
        assertThat(BridgeGatewayRelay.relayPorts(true, 443, 4510, 443), equalTo(List.of(443)));
    }

    @Test
    void onlySourcesOnADockerNetworkAreRelayed() {
        List<String> subnets = List.of("172.17.0.0/16", "172.18.0.0/16", "fd00:d0c::/64");

        assertThat(BridgeGatewayRelay.inAnySubnet("172.17.0.5", subnets), is(true));
        assertThat(BridgeGatewayRelay.inAnySubnet("172.18.3.9", subnets), is(true));
        assertThat(BridgeGatewayRelay.inAnySubnet("::ffff:172.17.0.5", subnets), is(true));
        assertThat(BridgeGatewayRelay.inAnySubnet("fd00:d0c::7", subnets), is(true));
        // A LAN neighbour routing 172.17.0.1 through this host.
        assertThat(BridgeGatewayRelay.inAnySubnet("192.168.1.20", subnets), is(false));
        assertThat(BridgeGatewayRelay.inAnySubnet("172.19.0.5", subnets), is(false));
        assertThat(BridgeGatewayRelay.inAnySubnet("192.168.1.20", List.of()), is(false));
    }

    @Test
    void subnetMatchingRejectsWhatIsNotAnIpOrACidr() {
        assertThat(BridgeGatewayRelay.inSubnet("example.com", "172.17.0.0/16"), is(false));
        assertThat(BridgeGatewayRelay.inSubnet("172.17.0.5", "172.17.0.0/x"), is(false));
        assertThat(BridgeGatewayRelay.inSubnet("172.17.0.5", "172.17.0.0/40"), is(false));
        assertThat(BridgeGatewayRelay.inSubnet("10.1.2.3", "0.0.0.0/0"), is(true));
    }

    @Test
    void subnetsAreReadFromEveryNetwork() {
        assertThat(BridgeGatewayRelay.subnetsOf(List.of(network(subnet("172.17.0.0/16"), subnet(null)),
                        network(subnet("172.18.0.0/16"), subnet("fd00:d0c::/64")), mock(Network.class))),
                equalTo(List.of("172.17.0.0/16", "172.18.0.0/16", "fd00:d0c::/64")));
    }

    @Test
    void gatewayIsTheFirstIpv4Gateway() {
        assertThat(BridgeGatewayRelay.gatewayOf(network(ipam("fd00::1"), ipam("172.17.0.1"))), equalTo("172.17.0.1"));
    }

    @Test
    void noGatewayWhenTheBridgeHasNone() {
        assertThat(BridgeGatewayRelay.gatewayOf(network(ipam(null))), is(nullValue()));
        assertThat(BridgeGatewayRelay.gatewayOf(null), is(nullValue()));
        assertThat(BridgeGatewayRelay.gatewayOf(mock(Network.class)), is(nullValue()));
    }

    private static Network.Ipam.Config ipam(String gateway) {
        Network.Ipam.Config config = mock(Network.Ipam.Config.class);
        when(config.getGateway()).thenReturn(gateway);
        return config;
    }

    private static Network.Ipam.Config subnet(String subnet) {
        Network.Ipam.Config config = mock(Network.Ipam.Config.class);
        when(config.getSubnet()).thenReturn(subnet);
        return config;
    }

    private static Network network(Network.Ipam.Config... configs) {
        Network network = mock(Network.class);
        Network.Ipam ipam = mock(Network.Ipam.class);
        when(network.getIpam()).thenReturn(ipam);
        when(ipam.getConfig()).thenReturn(List.of(configs));
        return network;
    }
}
