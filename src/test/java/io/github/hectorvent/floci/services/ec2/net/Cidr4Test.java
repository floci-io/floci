package io.github.hectorvent.floci.services.ec2.net;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cidr4Test {

    @Test
    void parsesAndNormalisesToTheNetworkAddress() {
        assertEquals("10.0.1.0/24", Cidr4.parse("10.0.1.37/24").orElseThrow().toString());
        assertEquals(256L, Cidr4.parse("10.0.1.0/24").orElseThrow().size());
        assertEquals("10.0.1.255", Cidr4.format(Cidr4.parse("10.0.1.0/24").orElseThrow().broadcast()));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[]{null, "", "10.0.0.0", "10.0.0.0/33", "10.0.0.256/24", "not-a-cidr"}) {
            assertEquals(Optional.empty(), Cidr4.parse(bad), "should reject " + bad);
        }
    }

    @Test
    void recognisesTheThreeRfc1918Ranges() {
        assertTrue(Cidr4.parse("10.0.0.0/16").orElseThrow().isRfc1918());
        assertTrue(Cidr4.parse("172.16.0.0/12").orElseThrow().isRfc1918());
        assertTrue(Cidr4.parse("172.31.0.0/16").orElseThrow().isRfc1918());
        assertTrue(Cidr4.parse("192.168.215.0/24").orElseThrow().isRfc1918());

        assertFalse(Cidr4.parse("172.15.0.0/16").orElseThrow().isRfc1918());
        assertFalse(Cidr4.parse("172.32.0.0/16").orElseThrow().isRfc1918());
        assertFalse(Cidr4.parse("11.0.0.0/8").orElseThrow().isRfc1918());
        assertFalse(Cidr4.parse("100.64.0.0/10").orElseThrow().isRfc1918(), "carrier-grade NAT is not RFC 1918");
        assertFalse(Cidr4.parse("0.0.0.0/0").orElseThrow().isRfc1918());
    }

    @Test
    void containmentAndOverlapAreExactAtTheBoundaries() {
        Cidr4 vpc = Cidr4.parse("10.0.0.0/16").orElseThrow();
        assertTrue(vpc.contains(Cidr4.parse("10.0.255.0/24").orElseThrow()));
        assertFalse(vpc.contains(Cidr4.parse("10.1.0.0/24").orElseThrow()));
        assertFalse(vpc.contains(Cidr4.parse("10.0.0.0/8").orElseThrow()), "a supernet is not contained");

        assertTrue(vpc.overlaps(Cidr4.parse("10.0.0.0/8").orElseThrow()));
        assertFalse(vpc.overlaps(Cidr4.parse("10.1.0.0/16").orElseThrow()));
    }

    @Test
    void comparesCorrectlyAboveTheSignedIntBoundary() {
        // 192.168.x as an int is negative; arithmetic that forgot to widen breaks exactly here.
        Cidr4 orbstack = Cidr4.parse("192.168.215.0/24").orElseThrow();
        assertTrue(orbstack.overlaps(Cidr4.parse("192.168.0.0/16").orElseThrow()));
        assertFalse(orbstack.overlaps(Cidr4.parse("192.168.216.0/24").orElseThrow()));
        assertEquals("192.168.215.10", orbstack.addressAt(10).orElseThrow());
    }

    @Test
    void addressAtStaysInsideTheBlock() {
        Cidr4 subnet = Cidr4.parse("10.0.1.0/24").orElseThrow();
        assertEquals("10.0.1.10", subnet.addressAt(10).orElseThrow());
        assertEquals("10.0.1.255", subnet.addressAt(255).orElseThrow());
        assertEquals(Optional.empty(), subnet.addressAt(256));
        assertEquals(Optional.empty(), subnet.addressAt(-1));
    }
}
