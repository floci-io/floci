package io.github.hectorvent.floci.services.eks;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EksPodNetworkRoutingTest {

    @Test
    void linkLocalEndpointValidatesInput() {
        assertThrows(NullPointerException.class, () -> new LinkLocalEndpoint(null, 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("   ", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("invalid-ip", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.256", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("-1.0.0.0", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254.1", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.01.1", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("; rm -rf / ;", 80));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254", 0));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254", -1));
        assertThrows(IllegalArgumentException.class, () -> new LinkLocalEndpoint("169.254.169.254", 65536));

        LinkLocalEndpoint ep = new LinkLocalEndpoint("169.254.169.254", 80);
        assertEquals("169.254.169.254", ep.ip());
        assertEquals(80, ep.port());
    }

    @Test
    void buildRoutingCommandRejectsNullOrEmptyEndpoints() {
        assertThrows(IllegalArgumentException.class, () ->
                EksPodNetworkRouting.buildRoutingCommand(null, null));
        assertThrows(IllegalArgumentException.class, () ->
                EksPodNetworkRouting.buildRoutingCommand("10.42.0.0/16", List.of()));
    }

    @Test
    void buildRoutingCommandUsesDefaultPodCidrWhenNullOrBlank() {
        String[] cmdNull = EksPodNetworkRouting.buildRoutingCommand(null, EksPodNetworkRouting.DEFAULT_ENDPOINTS);
        assertEquals(3, cmdNull.length);
        assertEquals("sh", cmdNull[0]);
        assertEquals("-c", cmdNull[1]);
        assertTrue(cmdNull[2].contains(EksPodNetworkRouting.DEFAULT_POD_CIDR));

        String[] cmdBlank = EksPodNetworkRouting.buildRoutingCommand("   ", EksPodNetworkRouting.DEFAULT_ENDPOINTS);
        assertTrue(cmdBlank[2].contains(EksPodNetworkRouting.DEFAULT_POD_CIDR));
    }

    @Test
    void buildRoutingCommandGeneratesIdempotentChainAndRules() {
        String[] cmd = EksPodNetworkRouting.buildRoutingCommand("10.42.0.0/16", List.of(
                new LinkLocalEndpoint("169.254.169.254", 80),
                new LinkLocalEndpoint("169.254.170.23", 80)
        ));

        String script = cmd[2];
        assertNotNull(script);

        // Finds iptables binary across standard and k3s aux locations
        assertTrue(script.contains("IPTABLES=$(command -v iptables 2>/dev/null || command -v /bin/aux/iptables 2>/dev/null || true)"));
        assertTrue(script.contains("iptables not found in container"));

        // Creates custom chain idempotently
        assertTrue(script.contains("$IPTABLES -t nat -N FLOCI-LINK-LOCAL 2>/dev/null || true"));

        // Inserts jump in PREROUTING only if not already present
        assertTrue(script.contains("$IPTABLES -t nat -C PREROUTING -j FLOCI-LINK-LOCAL 2>/dev/null || $IPTABLES -t nat -I PREROUTING 1 -j FLOCI-LINK-LOCAL"));

        // Adds rule for IMDS endpoint with check before append
        String imdsCheck = "$IPTABLES -t nat -C FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.169.254 -p tcp --dport 80 -j DNAT --to-destination 169.254.169.254:80 2>/dev/null";
        String imdsAdd = "$IPTABLES -t nat -A FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.169.254 -p tcp --dport 80 -j DNAT --to-destination 169.254.169.254:80";
        assertTrue(script.contains(imdsCheck + " || " + imdsAdd));

        // Adds rule for Pod Identity endpoint with check before append
        String podIdCheck = "$IPTABLES -t nat -C FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.170.23 -p tcp --dport 80 -j DNAT --to-destination 169.254.170.23:80 2>/dev/null";
        String podIdAdd = "$IPTABLES -t nat -A FLOCI-LINK-LOCAL -s 10.42.0.0/16 -d 169.254.170.23 -p tcp --dport 80 -j DNAT --to-destination 169.254.170.23:80";
        assertTrue(script.contains(podIdCheck + " || " + podIdAdd));
    }

    @Test
    void buildRoutingCommandSupportsCustomCidr() {
        String customCidr = "192.168.0.0/16";
        String[] cmd = EksPodNetworkRouting.buildRoutingCommand(customCidr, EksPodNetworkRouting.DEFAULT_ENDPOINTS);
        String script = cmd[2];

        assertTrue(script.contains("-s 192.168.0.0/16 -d 169.254.169.254"));
    }
}
