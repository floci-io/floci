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
