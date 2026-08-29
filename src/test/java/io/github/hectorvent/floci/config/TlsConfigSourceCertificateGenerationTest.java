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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        assertTrue(sans.contains("*.execute-api.localhost.floci.io"),
            "Certificate SANs should include API Gateway execution hosts");
        assertTrue(sans.contains("*.execute-api.localhost.localstack.cloud"),
            "Certificate SANs should include LocalStack-compatible API Gateway execution hosts");

        // Should not contain any custom hostnames
        assertEquals(9, sans.size(),
            "Certificate SANs should contain exactly 9 default entries, including API Gateway execution hosts");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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
        Path metadataFile = tempDir.resolve("tls/floci-selfsigned.metadata.json");
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
        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
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

        Path certFile = tempDir.resolve("tls/floci-selfsigned.crt");
        List<String> sans = extractSansFromCertificate(certFile);
        assertTrue(sans.contains("*.s3.amazonaws.com"),
            "Certificate SANs should cover global virtual-hosted S3");
        assertTrue(sans.contains("*.s3.us-east-1.amazonaws.com"),
            "Certificate SANs should cover regional virtual-hosted S3");
    }

    /**
     * A client can hit an explicit HTTPS AWS endpoint outside floci.default-region (e.g. a
     * cross-region call, or a Lambda whose own AWS_REGION differs from the emulator default).
     * DNS spoofing routes it to Floci regardless of region, so the cert must cover every
     * region the emulator advertises ({@link io.github.hectorvent.floci.core.common.AwsRegions#ALL}),
     * not just the configured default — otherwise TLS fails before the request reaches Floci.
     */
    @Test
    void testAwsRegionalWildcardsCoverEveryAdvertisedRegion() throws Exception {
        // Arrange
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");
        System.setProperty("floci.default-region", "eu-west-1");

        // Act
        new TlsConfigSource();

        // Assert
        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-selfsigned.crt"));
        for (String region : io.github.hectorvent.floci.core.common.AwsRegions.ALL) {
            assertTrue(sans.contains("*." + region + ".amazonaws.com"),
                "Certificate SANs should include '*." + region + ".amazonaws.com' regardless of the "
                    + "configured default region");
        }
    }

    /**
     * A client can also hit an explicit HTTPS endpoint in a region Floci doesn't itself
     * advertise via DescribeRegions but that AWS has published (e.g. {@code eu-north-1}).
     * DNS spoofing still routes it to Floci, so SAN coverage must key off
     * {@link io.github.hectorvent.floci.core.common.AwsRegions#KNOWN_IDS}, the superset of
     * every published region id, not {@code ALL} (what this emulator advertises).
     */
    @Test
    void testAwsRegionalWildcardsCoverEveryKnownRegionNotJustAdvertisedOnes() throws Exception {
        // Arrange
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");
        System.setProperty("floci.default-region", "eu-west-1");

        // Act
        new TlsConfigSource();

        // Assert
        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-selfsigned.crt"));
        assertTrue(sans.contains("*.eu-north-1.amazonaws.com"),
            "Certificate SANs should include '*.eu-north-1.amazonaws.com', a published region "
                + "outside AwsRegions.ALL");
        assertTrue(sans.contains("*.s3.eu-north-1.amazonaws.com"),
            "Certificate SANs should include regional virtual-hosted S3 for eu-north-1");
    }

    /**
     * A wildcard SAN matches exactly one label (RFC 6125 6.4.3), so {@code *.<region>.amazonaws.com}
     * covers a single-label regional service ({@code sts.us-east-1.amazonaws.com}) but not the
     * multi-label endpoints several AWS services use, where a resource id contributes an extra
     * label before the service name: {@code <api-id>.execute-api.<region>.amazonaws.com},
     * {@code <account>.dkr.ecr.<region>.amazonaws.com}, and
     * {@code <bucket>.s3.dualstack.<region>.amazonaws.com}. DNS spoofing still routes these to
     * Floci, so without dedicated SANs the handshake fails before the request reaches the emulator.
     */
    @Test
    void testCertificateCoversMultiLabelRegionalEndpointsWhenSpoofEnabled() throws Exception {
        // Arrange
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        // Act
        new TlsConfigSource();

        // Assert
        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-selfsigned.crt"));
        assertTrue(sans.contains("*.execute-api.us-east-1.amazonaws.com"),
            "Certificate SANs should cover API Gateway execute-api endpoints");
        assertTrue(sans.contains("*.dkr.ecr.us-east-1.amazonaws.com"),
            "Certificate SANs should cover ECR dkr.ecr endpoints");
        assertTrue(sans.contains("*.s3.dualstack.us-east-1.amazonaws.com"),
            "Certificate SANs should cover dualstack virtual-hosted S3 endpoints");
        assertTrue(sans.contains("*.lambda-url.us-east-1.amazonaws.com"),
            "Certificate SANs should cover Lambda function URL endpoints");
    }

    /**
     * S3 static website hosting publishes two regional endpoint forms —
     * {@code my-bucket.s3-website-us-east-1.amazonaws.com} (legacy, region joined to the
     * "s3-website" label with a hyphen) and {@code my-bucket.s3-website.us-east-1.amazonaws.com}
     * (region as its own label). DNS spoofing routes both to Floci, so the cert needs a SAN for
     * each shape, not just the dotted {@code s3.dualstack} family already covered.
     */
    @Test
    void testCertificateCoversS3WebsiteEndpointsWhenSpoofEnabled() throws Exception {
        // Arrange
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        // Act
        new TlsConfigSource();

        // Assert
        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-selfsigned.crt"));
        assertTrue(sans.contains("*.s3-website-us-east-1.amazonaws.com"),
            "Certificate SANs should cover the hyphenated S3 website endpoint form");
        assertTrue(sans.contains("*.s3-website.us-east-1.amazonaws.com"),
            "Certificate SANs should cover the dotted S3 website endpoint form");
    }

    /**
     * S3VirtualHostFilter#isS3QualifierTail documents the full set of endpoint forms AWS
     * publishes for S3 itself (not counting the multi-label services and website forms already
     * covered above): the legacy hyphenated regional form ({@code s3-<region>}), the FIPS
     * endpoint and its dualstack variant ({@code s3-fips.<region>},
     * {@code s3-fips.dualstack.<region>}), and the regionless transfer-acceleration endpoint and
     * its dualstack variant ({@code s3-accelerate}, {@code s3-accelerate.dualstack}). DNS
     * spoofing routes every one of these to Floci, so each needs a SAN.
     */
    @Test
    void testCertificateCoversRemainingS3EndpointFormsWhenSpoofEnabled() throws Exception {
        // Arrange
        System.setProperty("floci.dns.spoof-aws-endpoints", "true");

        // Act
        new TlsConfigSource();

        // Assert
        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-selfsigned.crt"));
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
        List<String> sans = extractSansFromCertificate(tempDir.resolve("tls/floci-selfsigned.crt"));
        assertFalse(sans.contains("*.amazonaws.com"),
            "Certificate SANs should not include '*.amazonaws.com' when spoofing is disabled");
        assertFalse(sans.contains("*.us-east-1.amazonaws.com"),
            "Certificate SANs should not include '*.us-east-1.amazonaws.com' when spoofing is disabled");
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
