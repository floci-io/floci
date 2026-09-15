package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Ipv4CidrsTest {

    @Test
    void containsIsTrueOnlyForBlocksInsideTheOuterCidr() {
        assertTrue(Ipv4Cidrs.contains("10.0.0.0/8", "10.4.0.0/14"));
        assertTrue(Ipv4Cidrs.contains("10.0.0.0/8", "10.0.0.0/8"));
        assertFalse(Ipv4Cidrs.contains("10.0.0.0/8", "192.168.0.0/16"));
        assertFalse(Ipv4Cidrs.contains("10.4.0.0/14", "10.0.0.0/8"));
    }

    @Test
    void overlapsDetectsAnySharedAddressSpace() {
        assertTrue(Ipv4Cidrs.overlaps("10.0.0.0/24", "10.0.0.128/25"));
        assertTrue(Ipv4Cidrs.overlaps("10.0.0.0/8", "10.255.0.0/16"));
        assertFalse(Ipv4Cidrs.overlaps("10.0.0.0/24", "10.0.1.0/24"));
    }

    @Test
    void isIpv4AcceptsParseableBlocksAndRejectsTheRest() {
        assertTrue(Ipv4Cidrs.isIpv4("10.0.1.0/24"));
        assertFalse(Ipv4Cidrs.isIpv4(null));
        assertFalse(Ipv4Cidrs.isIpv4(""));
        assertFalse(Ipv4Cidrs.isIpv4("10.0.1.0"));
        assertFalse(Ipv4Cidrs.isIpv4("2600:1f18::/56"));
    }

    @Test
    void isSubnetReservedCoversTheFirstFourAddressesAndTheLast() {
        for (String reserved : List.of("10.0.1.0", "10.0.1.1", "10.0.1.2", "10.0.1.3", "10.0.1.255")) {
            assertTrue(Ipv4Cidrs.isSubnetReserved("10.0.1.0/24", reserved + "/32"), reserved);
        }
        assertFalse(Ipv4Cidrs.isSubnetReserved("10.0.1.0/24", "10.0.1.4/32"));
        assertFalse(Ipv4Cidrs.isSubnetReserved("10.0.1.0/24", "10.0.1.254/32"));
        assertFalse(Ipv4Cidrs.isSubnetReserved("10.0.1.0/24", "10.0.2.1/32"),
                "an address outside the block is not reserved by it");
    }

    @Test
    void isSubnetReservedLeavesTheHostsOfASmallSubnetAlone() {
        // A /28 is the smallest subnet AWS accepts: five reserved, eleven usable.
        assertTrue(Ipv4Cidrs.isSubnetReserved("10.0.1.16/28", "10.0.1.19/32"));
        assertTrue(Ipv4Cidrs.isSubnetReserved("10.0.1.16/28", "10.0.1.31/32"));
        assertFalse(Ipv4Cidrs.isSubnetReserved("10.0.1.16/28", "10.0.1.20/32"));
        assertFalse(Ipv4Cidrs.isSubnetReserved("10.0.1.16/28", "10.0.1.30/32"));
        // A /29 keeps three hosts between the two reserved ends.
        assertFalse(Ipv4Cidrs.isSubnetReserved("10.0.1.8/29", "10.0.1.12/32"));
        assertTrue(Ipv4Cidrs.isSubnetReserved("10.0.1.8/29", "10.0.1.15/32"));
    }

    @Test
    void isSubnetReservedClaimsEveryAddressOfABlockTooSmallToHoldAHost() {
        for (String address : List.of("10.0.1.0", "10.0.1.1", "10.0.1.2", "10.0.1.3")) {
            assertTrue(Ipv4Cidrs.isSubnetReserved("10.0.1.0/30", address + "/32"), address);
        }
        assertTrue(Ipv4Cidrs.isSubnetReserved("10.0.1.0/32", "10.0.1.0/32"));
    }

    @Test
    void firstFreeBlockSkipsOccupiedSpaceInOrder() {
        assertEquals("10.0.1.0/24",
                Ipv4Cidrs.firstFreeBlock(List.of("10.0.0.0/16"), List.of("10.0.0.0/24"), 24));
        assertEquals("10.0.0.0/24",
                Ipv4Cidrs.firstFreeBlock(List.of("10.0.0.0/16"), List.of(), 24));
        // occupied space in the middle: allocation order fills around it
        assertEquals("10.0.2.0/24",
                Ipv4Cidrs.firstFreeBlock(List.of("10.0.0.0/16"),
                        List.of("10.0.0.0/24", "10.0.1.0/24", "10.0.3.0/24"), 24));
    }

    @Test
    void firstFreeBlockReturnsNullWhenExhausted() {
        assertEquals(null,
                Ipv4Cidrs.firstFreeBlock(List.of("10.0.0.0/24"), List.of("10.0.0.0/24"), 24));
        // requested block larger than the pool itself
        assertEquals(null,
                Ipv4Cidrs.firstFreeBlock(List.of("10.0.0.0/24"), List.of(), 16));
    }

    @Test
    void invalidCidrsThrow() {
        assertThrows(AwsException.class, () -> Ipv4Cidrs.contains("10.0.0.0", "10.0.0.0/8"));
        assertThrows(AwsException.class, () -> Ipv4Cidrs.contains("10.0.0.0/33", "10.0.0.0/8"));
        assertThrows(AwsException.class, () -> Ipv4Cidrs.contains("banana/8", "10.0.0.0/8"));
    }
}
