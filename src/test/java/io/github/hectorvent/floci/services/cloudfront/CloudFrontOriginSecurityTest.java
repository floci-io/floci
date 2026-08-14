package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

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
        assertFalse(CloudFrontServingController.isBlockedOriginAddress(ipv4MappedAddress("8.8.8.8")));
    }

    @Test
    void blocksPrivateAndSpecialUseIpv4MappedAddresses() throws Exception {
        for (String address : new String[] {
                "0.0.0.0", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1",
                "169.254.169.254", "100.64.0.1", "192.0.0.1", "192.0.2.1", "198.18.0.1",
                "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1"
        }) {
            assertTrue(CloudFrontServingController.isBlockedOriginAddress(ipv4MappedAddress(address)),
                    "IPv4-mapped " + address + " must not be reachable as a default custom origin");
        }
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
    void matchViewerUsesTheViewerProtocolAndMatchingPort() {
        Origin origin = customOrigin("match-viewer", 8080, 8443);

        URI http = CloudFrontServingController.buildCustomOriginUri(
                origin, "http", "/asset", null);
        URI https = CloudFrontServingController.buildCustomOriginUri(
                origin, "https", "/asset", null);

        assertEquals("http", http.getScheme());
        assertEquals(8080, http.getPort());
        assertEquals("https", https.getScheme());
        assertEquals(8443, https.getPort());
    }

    @Test
    void extractsRawViewerPathWithoutDecodingOrNormalizingIt() {
        String raw = CloudFrontServingController.rawViewerPath(
                "//api//data%2Fraw.json?token=redacted");

        assertEquals("//api//data%2Fraw.json", raw);
        assertEquals("//api//data/raw.json", CloudFrontServingController.decodedViewerPath(raw));
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

    private static InetAddress ipv4MappedAddress(String address) throws Exception {
        byte[] mapped = new byte[16];
        mapped[10] = (byte) 0xff;
        mapped[11] = (byte) 0xff;
        System.arraycopy(InetAddress.getByName(address).getAddress(), 0, mapped, 12, 4);
        return Inet6Address.getByAddress(null, mapped, -1);
    }

    private static Origin customOrigin(String policy, int httpPort, int httpsPort) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("HTTPPort", Integer.toString(httpPort));
        config.put("HTTPSPort", Integer.toString(httpsPort));
        config.put("OriginProtocolPolicy", policy);
        Origin origin = new Origin();
        origin.setDomainName("origin.example.test");
        origin.setCustomOriginConfig(config);
        return origin;
    }
}
