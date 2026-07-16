package io.github.hectorvent.floci.services.cloudfront;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudFrontOriginSecurityTest {

    @Test
    void blocksPrivateAndSpecialUseAddresses() throws Exception {
        for (String address : new String[] {
                "0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1",
                "169.254.169.254", "100.64.0.1", "224.0.0.1", "240.0.0.1",
                "::1", "fe80::1", "fc00::1", "2001:db8::1"
        }) {
            assertTrue(CloudFrontServingController.isBlockedOriginAddress(InetAddress.getByName(address)),
                    address + " must not be reachable as a default custom origin");
        }
    }

    @Test
    void permitsPublicAddresses() throws Exception {
        assertFalse(CloudFrontServingController.isBlockedOriginAddress(InetAddress.getByName("8.8.8.8")));
        assertFalse(CloudFrontServingController.isBlockedOriginAddress(
                InetAddress.getByName("2606:4700:4700::1111")));
    }

    @Test
    void configuredPortOverridesEmbeddedDomainPortAndRawQueryIsPreserved() {
        URI target = CloudFrontServingController.buildCustomOriginUri(
                "http", "127.0.0.1:1", 8080, "/a%2Fb", "x=1&x=2&encoded=a%2Fb");

        assertEquals("127.0.0.1", target.getHost());
        assertEquals(8080, target.getPort());
        assertEquals("/a%2Fb", target.getRawPath());
        assertEquals("x=1&x=2&encoded=a%2Fb", target.getRawQuery());
    }

    @Test
    void supportsBracketedIpv6Authorities() {
        URI target = CloudFrontServingController.buildCustomOriginUri(
                "https", "[2606:4700:4700::1111]:80", 443, "/", null);

        assertEquals("[2606:4700:4700::1111]", target.getHost());
        assertEquals(443, target.getPort());
    }

    @Test
    void rejectsAuthorityInjectionAndMalformedPorts() {
        assertThrows(IllegalArgumentException.class, () ->
                CloudFrontServingController.buildCustomOriginUri(
                        "http", "user@example.com", 80, "/", null));
        assertThrows(IllegalArgumentException.class, () ->
                CloudFrontServingController.buildCustomOriginUri(
                        "http", "example.com/path", 80, "/", null));
        assertThrows(IllegalArgumentException.class, () ->
                CloudFrontServingController.buildCustomOriginUri(
                        "http", "example.com?token=secret", 80, "/", null));
        assertThrows(IllegalArgumentException.class, () ->
                CloudFrontServingController.buildCustomOriginUri(
                        "http", "example.com", 0, "/", null));
    }
}
