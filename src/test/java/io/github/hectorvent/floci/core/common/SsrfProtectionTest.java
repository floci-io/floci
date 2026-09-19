package io.github.hectorvent.floci.core.common;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SsrfProtectionTest {

    @Test
    void metadataAddressCoversIpv4LinkLocal() throws UnknownHostException {
        assertTrue(SsrfProtection.isMetadataAddress(InetAddress.getByName("169.254.169.254")));
        assertTrue(SsrfProtection.isMetadataAddress(InetAddress.getByName("169.254.0.1")));
    }

    @Test
    void metadataAddressCoversIpv6LinkLocalAndAwsMetadata() throws UnknownHostException {
        assertTrue(SsrfProtection.isMetadataAddress(InetAddress.getByName("fe80::1")));
        assertTrue(SsrfProtection.isMetadataAddress(InetAddress.getByName("fd00:ec2::254")));
    }

    @Test
    void metadataAddressCoversIpv4MappedLinkLocal() throws UnknownHostException {
        assertTrue(SsrfProtection.isMetadataAddress(InetAddress.getByName("::ffff:169.254.169.254")));
    }

    @Test
    void metadataAddressLeavesLoopbackAndPrivateRangesReachable() throws UnknownHostException {
        assertFalse(SsrfProtection.isMetadataAddress(InetAddress.getByName("127.0.0.1")));
        assertFalse(SsrfProtection.isMetadataAddress(InetAddress.getByName("::1")));
        assertFalse(SsrfProtection.isMetadataAddress(InetAddress.getByName("10.0.0.5")));
        assertFalse(SsrfProtection.isMetadataAddress(InetAddress.getByName("172.17.0.2")));
        assertFalse(SsrfProtection.isMetadataAddress(InetAddress.getByName("192.168.1.10")));
        assertFalse(SsrfProtection.isMetadataAddress(InetAddress.getByName("fd00:ec2::255")));
    }
}
