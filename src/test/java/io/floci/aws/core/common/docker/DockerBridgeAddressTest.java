package io.floci.aws.core.common.docker;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The address a container reaches a natively running Floci at, read from the {@code docker0} interface. */
class DockerBridgeAddressTest {

    // Catches: the bridge's IPv6 link-local address handed to containers as their resolver, which
    // Docker's --dns cannot use.
    @Test
    void theBridgesIpv4AddressIsTheOne() throws UnknownHostException {
        Stream<InetAddress> addresses = Stream.of(InetAddress.getByName("fe80::1"), InetAddress.getByName("172.17.0.1"));

        assertEquals(Optional.of("172.17.0.1"), DockerBridgeAddress.firstIpv4(addresses));
    }

    @Test
    void aBridgeWithoutAnIpv4AddressHasNoAddress() throws UnknownHostException {
        assertEquals(Optional.empty(), DockerBridgeAddress.firstIpv4(Stream.of(InetAddress.getByName("fe80::1"))));
    }

    @Test
    void aBridgeWithNoAddressHasNoAddress() {
        assertEquals(Optional.empty(), DockerBridgeAddress.firstIpv4(Stream.empty()));
    }
}
