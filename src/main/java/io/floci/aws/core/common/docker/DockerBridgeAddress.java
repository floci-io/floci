package io.floci.aws.core.common.docker;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The host's IPv4 address on Docker's default bridge, {@code docker0}: where {@code host-gateway} points unless the
 * daemon sets {@code host-gateway-ip}, so it is the address a container reaches a natively running Floci at. Empty
 * where the host has no such interface, as with Docker Desktop or rootless Docker, whose bridge lives inside a VM or
 * a namespace.
 */
@ApplicationScoped
public class DockerBridgeAddress {

    private static final Logger LOG = Logger.getLogger(DockerBridgeAddress.class);
    static final String INTERFACE_NAME = "docker0";

    public Optional<String> resolve() {
        try {
            NetworkInterface bridge = NetworkInterface.getByName(INTERFACE_NAME);
            return bridge == null ? Optional.empty() : firstIpv4(bridge.inetAddresses());
        } catch (SocketException e) {
            LOG.debugv("Could not read the {0} interface: {1}", INTERFACE_NAME, e.getMessage());
            return Optional.empty();
        }
    }

    static Optional<String> firstIpv4(Stream<InetAddress> addresses) {
        return addresses
                .filter(Inet4Address.class::isInstance)
                .map(InetAddress::getHostAddress)
                .findFirst();
    }
}
