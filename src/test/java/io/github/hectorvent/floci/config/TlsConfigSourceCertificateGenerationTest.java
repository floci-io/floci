package io.github.hectorvent.floci.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for TLS certificate generation with custom hostnames.
 * 
 * Tests Task 3.5: Update certificate generation to include custom hostnames
 * - Verifies that extractCustomHostnames() is called
 * - Verifies that custom hostnames are combined with default SANs
 * - Verifies that the combined list is deduplicated
 * - Verifies that the combined SANs are passed to CertificateGenerator
 * - Verifies that logging shows custom hostnames when present
 */
class TlsConfigSourceCertificateGenerationTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setup() {
        // Enable TLS and self-signed mode
        System.setProperty("floci.tls.enabled", "true");
        System.setProperty("floci.tls.self-signed", "true");
        System.setProperty("floci.storage.persistent-path", tempDir.toString());
    }

    @AfterEach
    void cleanup() {
        System.clearProperty("floci.tls.enabled");
        System.clearProperty("floci.tls.self-signed");
        System.clearProperty("floci.storage.persistent-path");
        System.clearProperty("floci.hostname");
        System.clearProperty("floci.base-url");
        System.clearProperty("floci.dns.spoof-aws-endpoints");
        System.clearProperty("floci.default-region");
        System.clearProperty("floci.services.iot.endpoint-address");
    }

    /**
     * Test that certificate includes custom hostname from FLOCI_HOSTNAME
     */
    @Test
    void testCertificateIncludesFlociHostname() throws Exception {
        // Arrange
        System.setProperty("floci.hostname", "floci");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("floci"), 
            "Certificate SANs should include 'floci' from FLOCI_HOSTNAME");
        assertTrue(sans.contains("localhost"), 
            "Certificate SANs should include default 'localhost'");
    }

    /**
     * Test that certificate includes custom hostname from FLOCI_BASE_URL
     */
    @Test
    void testCertificateIncludesBaseUrlHostname() throws Exception {
        // Arrange
        System.setProperty("floci.base-url", "https://myhost:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("myhost"), 
            "Certificate SANs should include 'myhost' from FLOCI_BASE_URL");
        assertTrue(sans.contains("localhost"), 
            "Certificate SANs should include default 'localhost'");
    }

    /**
     * Test that certificate includes IP address from FLOCI_BASE_URL
     */
    @Test
    void testCertificateIncludesIpAddress() throws Exception {
        // Arrange
        System.setProperty("floci.base-url", "https://192.168.1.100:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("192.168.1.100"), 
            "Certificate SANs should include '192.168.1.100' from FLOCI_BASE_URL");
    }

    /**
     * Test that certificate includes both FLOCI_HOSTNAME and FLOCI_BASE_URL hostnames
     */
    @Test
    void testCertificateIncludesBothHostnames() throws Exception {
        // Arrange
        System.setProperty("floci.hostname", "newhost");
        System.setProperty("floci.base-url", "http://oldhost:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("newhost"), 
            "Certificate SANs should include 'newhost' from FLOCI_HOSTNAME");
        assertTrue(sans.contains("oldhost"), 
            "Certificate SANs should include 'oldhost' from FLOCI_BASE_URL");
        assertTrue(sans.contains("localhost"), 
            "Certificate SANs should include default 'localhost'");
    }

    /**
     * Test that certificate with default configuration includes only default SANs
     */
    @Test
    void testCertificateWithDefaultConfiguration() throws Exception {
        // Arrange - no custom hostnames
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("localhost"), 
            "Certificate SANs should include default 'localhost'");
        assertTrue(sans.contains("127.0.0.1"), 
            "Certificate SANs should include default '127.0.0.1'");
        assertTrue(sans.contains("0.0.0.0"), 
            "Certificate SANs should include default '0.0.0.0'");
        
        assertTrue(sans.contains("host.docker.internal"),
            "Certificate SANs should include default 'host.docker.internal'");
        // Should be the defaults and nothing else - no custom hostnames
        assertEquals(Set.copyOf(TlsConfigSource.DEFAULT_SAN_HOSTNAMES), Set.copyOf(sans),
            "Certificate SANs should be exactly the default entries");
    }

    /**
     * Test that duplicate hostnames are deduplicated
     */
    @Test
    void testDeduplicationInCertificate() throws Exception {
        // Arrange - same hostname in both sources
        System.setProperty("floci.hostname", "myhost");
        System.setProperty("floci.base-url", "http://myhost:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");
        
        List<String> sans = extractSansFromCertificate(certFile);
        long myhostCount = sans.stream().filter(s -> s.equals("myhost")).count();
        assertEquals(1, myhostCount, 
            "Certificate SANs should contain 'myhost' exactly once (deduplicated)");
    }

    /**
     * Test that metadata file is created with correct hostnames
     */
    @Test
    void testMetadataIncludesCustomHostnames() throws Exception {
        // Arrange
        System.setProperty("floci.hostname", "floci");
        System.setProperty("floci.base-url", "https://myhost:4566");
        
        // Act
        new TlsConfigSource();
        
        // Assert
        Path metadataFile = tempDir.resolve("tls/floci-server.metadata.json");
        assertTrue(Files.exists(metadataFile), "Metadata file should exist");
        
        String json = Files.readString(metadataFile);
        assertTrue(json.contains("floci"), 
            "Metadata should include 'floci' hostname");
        assertTrue(json.contains("myhost"), 
            "Metadata should include 'myhost' hostname");
        assertTrue(json.contains("localhost"), 
            "Metadata should include default 'localhost' hostname");
    }

    /**
     * Test that the AWS endpoint wildcards are included when spoof-aws-endpoints is enabled
     */
    @Test
    void testCertificateIncludesAwsWildcardsWhenSpoofEnabled() throws Exception {
        // Arrange
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        // Act
        new TlsConfigSource();

        // Assert
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertTrue(Files.exists(certFile), "Certificate file should exist");

        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("*.amazonaws.com"),
            "Certificate SANs should include '*.amazonaws.com' when spoofing is enabled");
        assertTrue(sans.contains("*.us-east-1.amazonaws.com"),
            "Certificate SANs should include the default region wildcard '*.us-east-1.amazonaws.com'");
        assertTrue(sans.contains("localhost"),
            "Certificate SANs should still include default 'localhost'");
    }

    /**
     * A TLS wildcard matches exactly one label (RFC 6125 6.4.3), so the broad
     * *.amazonaws.com wildcards do not cover virtual-hosted S3 addressing, where the
     * bucket contributes an extra label. The DNS spoof does route those hostnames to
     * Floci, so without dedicated SANs the request dies at the handshake.
     */
    @Test
    void testCertificateCoversVirtualHostedS3WhenSpoofEnabled() throws Exception {
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        new TlsConfigSource();

        Path certFile = tempDir.resolve("tls/floci-server.crt");
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("*.s3.amazonaws.com"),
            "Certificate SANs should cover global virtual-hosted S3");
        assertTrue(sans.contains("*.s3.us-east-1.amazonaws.com"),
            "Certificate SANs should cover regional virtual-hosted S3");
    }

    /**
     * A client can hit an explicit HTTPS AWS endpoint outside floci.default-region (a
     * cross-region call, or a Lambda whose own AWS_REGION differs from the emulator default).
     * DNS spoofing routes it to Floci regardless of region, so the cert must cover every
     * published region, including ones the emulator does not advertise (eu-north-1), not
     * just the configured default.
     */
    @Test
    void testAwsRegionalWildcardsCoverEveryKnownRegion() throws Exception {
        // Catches: SANs limited to the configured default region, failing the handshake elsewhere
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");
        System.setProperty("floci.default-region", "eu-west-1");

        new TlsConfigSource();

        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-server.crt"));
        for (String region : List.of("us-east-1", "eu-west-1", "eu-north-1", "ap-southeast-2")) {
            assertTrue(sans.contains("*." + region + ".amazonaws.com"),
                "Certificate SANs should include '*." + region + ".amazonaws.com' regardless of the "
                    + "configured default region");
            assertTrue(sans.contains("*.s3." + region + ".amazonaws.com"),
                "Certificate SANs should include regional virtual-hosted S3 for " + region);
        }
    }

    /**
     * A wildcard SAN matches exactly one label (RFC 6125 6.4.3), so {@code *.<region>.amazonaws.com}
     * does not cover endpoints where a resource id adds a label before the service name. DNS
     * spoofing still routes them to Floci, so each needs a dedicated SAN.
     */
    @Test
    void testCertificateCoversMultiLabelRegionalEndpointsWhenSpoofEnabled() throws Exception {
        // Catches: a missing SAN for execute-api, dkr.ecr, s3-control or s3.dualstack hosts
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        new TlsConfigSource();

        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-server.crt"));
        assertTrue(sans.contains("*.execute-api.us-east-1.amazonaws.com"),
            "Certificate SANs should cover API Gateway execute-api endpoints");
        assertTrue(sans.contains("*.dkr.ecr.us-east-1.amazonaws.com"),
            "Certificate SANs should cover ECR dkr.ecr endpoints");
        assertTrue(sans.contains("*.s3.dualstack.us-east-1.amazonaws.com"),
            "Certificate SANs should cover dualstack virtual-hosted S3 endpoints");
        assertTrue(sans.contains("*.s3-control.us-east-1.amazonaws.com"),
            "Certificate SANs should cover S3 Control <account>.s3-control endpoints");
    }

    @Test
    void testCertificateDoesNotClaimLambdaUrlUnderAmazonaws() throws Exception {
        // Catches: a lambda-url SAN under the amazonaws suffix, where Lambda function URLs do not live
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        new TlsConfigSource();

        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-server.crt"));
        assertFalse(sans.contains("*.lambda-url.us-east-1.amazonaws.com"),
            "Function URLs are <url-id>.lambda-url.<region>.on.aws, never under the partition suffix");
    }

    @Test
    void testCertificateUsesEachRegionsOwnPartitionSuffix() throws Exception {
        // Catches: cn-* regions named under amazonaws.com and non-commercial partition suffixes uncovered
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        new TlsConfigSource();

        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-server.crt"));
        assertTrue(sans.contains("*.amazonaws.com.cn"), "China global endpoints");
        assertTrue(sans.contains("*.cn-north-1.amazonaws.com.cn"), "China regional endpoints");
        assertTrue(sans.contains("*.execute-api.cn-north-1.amazonaws.com.cn"), "China multi-label endpoints");
        assertTrue(sans.contains("*.s3-website-cn-north-1.amazonaws.com.cn"), "China hyphenated S3 endpoints");
        assertTrue(sans.contains("*.us-iso-east-1.c2s.ic.gov"), "ISO regional endpoints");
        assertTrue(sans.contains("*.amazonaws.eu"), "EUSC global endpoints");
        assertFalse(sans.contains("*.cn-north-1.amazonaws.com"),
            "cn-north-1 has no endpoints under the commercial suffix");
        assertFalse(sans.contains("*.us-east-1.amazonaws.com.cn"),
            "us-east-1 has no endpoints under the China suffix");
    }

    /**
     * S3 publishes more endpoint forms than the dotted regional one: website (dotted and
     * hyphenated), legacy hyphenated regional, FIPS and its dualstack variant, and the regionless
     * transfer-acceleration endpoints. The set follows S3VirtualHostFilter#isS3QualifierTail.
     */
    @Test
    void testCertificateCoversEveryS3EndpointFormWhenSpoofEnabled() throws Exception {
        // Catches: an S3 endpoint form that is routed to Floci but has no matching SAN
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        new TlsConfigSource();

        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-server.crt"));
        assertTrue(sans.contains("*.s3-website-us-east-1.amazonaws.com"),
            "Certificate SANs should cover the hyphenated S3 website endpoint form");
        assertTrue(sans.contains("*.s3-website.us-east-1.amazonaws.com"),
            "Certificate SANs should cover the dotted S3 website endpoint form");
        assertTrue(sans.contains("*.s3-us-east-1.amazonaws.com"),
            "Certificate SANs should cover the legacy hyphenated S3 regional endpoint form");
        assertTrue(sans.contains("*.s3-fips.us-east-1.amazonaws.com"),
            "Certificate SANs should cover the S3 FIPS endpoint");
        assertTrue(sans.contains("*.s3-fips.dualstack.us-east-1.amazonaws.com"),
            "Certificate SANs should cover the dualstack S3 FIPS endpoint");
        assertTrue(sans.contains("*.s3-accelerate.amazonaws.com"),
            "Certificate SANs should cover the regionless S3 transfer-acceleration endpoint");
        assertTrue(sans.contains("*.s3-accelerate.dualstack.amazonaws.com"),
            "Certificate SANs should cover the dualstack S3 transfer-acceleration endpoint");
    }

    /**
     * Test that no AWS wildcards are included when spoof-aws-endpoints is disabled
     */
    @Test
    void testCertificateExcludesAwsWildcardsWhenSpoofDisabled() throws Exception {
        // Act
        new TlsConfigSource();

        // Assert
        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-server.crt"));
        assertFalse(sans.contains("*.amazonaws.com"),
            "Certificate SANs should not include '*.amazonaws.com' when spoofing is disabled");
        assertFalse(sans.contains("*.us-east-1.amazonaws.com"),
            "Certificate SANs should not include '*.us-east-1.amazonaws.com' when spoofing is disabled");
    }

    /**
     * The IoT endpoint address is what devices verify on 8883 and 443, so the boot certificate
     * covers it even when it has more labels than the wildcard SAN matches.
     */
    @Test
    void testCertificateIncludesIotEndpointAddress() throws Exception {
        System.setProperty("floci.services.iot.endpoint-address", "iot.example.localhost.floci.io:8883");

        new TlsConfigSource();

        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-server.crt"));
        assertTrue(sans.contains("iot.example.localhost.floci.io"), sans.toString());
        assertFalse(sans.contains("iot.example.localhost.floci.io:8883"), "the port is not part of the name");
        assertTrue(Files.readString(tempDir.resolve("tls/floci-server.metadata.json")).contains("iot.example.localhost.floci.io"),
            "the metadata records it as a configured name, so a later change is detected");
    }

    @Test
    void testIotEndpointAddressAddedAfterBootRegeneratesTheCertificate() throws Exception {
        new TlsConfigSource();
        Path certFile = tempDir.resolve("tls/floci-server.crt");
        assertFalse(extractSansFromCertificate(certFile).contains("iot.example.localhost.floci.io"));

        System.setProperty("floci.services.iot.endpoint-address", "iot.example.localhost.floci.io");
        new TlsConfigSource();

        assertTrue(extractSansFromCertificate(certFile).contains("iot.example.localhost.floci.io"),
            "a changed endpoint address is a hostname configuration change");
    }

    // ==================== Helper Methods ====================

    /**
     * Extracts Subject Alternative Names (SANs) from a certificate file.
     * 
     * @param certFile Path to the certificate file
     * @return List of SANs (DNS names and IP addresses)
     */
    private List<String> extractSansFromCertificate(Path certFile) throws Exception {
        String certPem = Files.readString(certFile);
        
        // Parse certificate
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) cf.generateCertificate(
            new ByteArrayInputStream(certPem.getBytes())
        );
        
        // Extract SANs
        Collection<List<?>> sans = cert.getSubjectAlternativeNames();
        if (sans == null) {
            return List.of();
        }
        
        return sans.stream()
            .filter(san -> san.size() >= 2)
            .map(san -> san.get(1).toString())
            .toList();
    }
}
