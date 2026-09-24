package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RegionResolverTest {

    private final RegionResolver resolver = new RegionResolver("us-east-1", "000000000000");

    @Test
    void resolveRegionFromAuthorizationHeader() {
        HttpHeaders headers = stubHeaders(
                "AWS4-HMAC-SHA256 Credential=AKID/20260215/us-west-2/s3/aws4_request, " +
                "SignedHeaders=host, Signature=abc123");

        assertEquals("us-west-2", resolver.resolveRegion(headers));
    }

    @Test
    void resolveRegionFromDifferentRegion() {
        HttpHeaders headers = stubHeaders(
                "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20260215/eu-west-1/sqs/aws4_request, " +
                "SignedHeaders=host, Signature=xyz");

        assertEquals("eu-west-1", resolver.resolveRegion(headers));
    }

    @Test
    void fallsBackToDefaultWhenNoAuthHeader() {
        HttpHeaders headers = stubHeaders(null);
        assertEquals("us-east-1", resolver.resolveRegion(headers));
    }

    @Test
    void resolveRegionFromAuthOrNullReturnsCredentialRegion() {
        assertEquals("eu-west-1", resolver.resolveRegionFromAuthOrNull(
                "AWS4-HMAC-SHA256 Credential=AKID/20260215/eu-west-1/iotdata/aws4_request, " +
                "SignedHeaders=host, Signature=abc123"));
    }

    @Test
    void resolveRegionFromAuthOrNullIsNullWhereResolveReturnsDefault() {
        assertNull(resolver.resolveRegionFromAuthOrNull("Bearer some-token"));
        assertEquals("us-east-1", resolver.resolveRegionFromAuth("Bearer some-token"));
    }

    @Test
    void resolveRegionFromAuthOrNullIsNullForAbsentOrBlank() {
        assertNull(resolver.resolveRegionFromAuthOrNull(null));
        assertNull(resolver.resolveRegionFromAuthOrNull(""));
        assertNull(resolver.resolveRegionFromAuthOrNull(" "));
    }

    @Test
    void fallsBackToDefaultWhenEmptyAuthHeader() {
        HttpHeaders headers = stubHeaders("");
        assertEquals("us-east-1", resolver.resolveRegion(headers));
    }

    @Test
    void fallsBackToDefaultWhenNullHeaders() {
        assertEquals("us-east-1", resolver.resolveRegion(null));
    }

    @Test
    void fallsBackToDefaultWhenMalformedAuthHeader() {
        HttpHeaders headers = stubHeaders("Bearer some-token");
        assertEquals("us-east-1", resolver.resolveRegion(headers));
    }

    @Test
    void getAccountId() {
        assertEquals("000000000000", resolver.getAccountId());
    }

    @Test
    void buildArn() {
        assertEquals("arn:aws:ssm:us-west-2:000000000000:parameter/myParam",
                resolver.buildArn("ssm", "us-west-2", "parameter/myParam"));
    }

    @Test
    void customDefaultRegionAndAccountId() {
        RegionResolver custom = new RegionResolver("ap-southeast-1", "123456789012");
        assertEquals("ap-southeast-1", custom.getDefaultRegion());
        assertEquals("123456789012", custom.getAccountId());
        assertEquals("ap-southeast-1", custom.resolveRegion(null));
    }

    // --- resolveRegionFromPresignedCredential(String credentialValue) tests ---

    @Test
    void resolveRegionFromPresignedCredential_validCredential_returnsRegion() {
        assertEquals("us-west-2",
                resolver.resolveRegionFromPresignedCredential("000000000001/20260617/us-west-2/s3/aws4_request"));
    }

    @Test
    void resolveRegionFromPresignedCredential_differentRegion() {
        assertEquals("eu-central-1",
                resolver.resolveRegionFromPresignedCredential("AKID/20260617/eu-central-1/s3/aws4_request"));
    }

    @Test
    void resolveRegionFromPresignedCredential_null_returnsDefault() {
        assertEquals("us-east-1", resolver.resolveRegionFromPresignedCredential(null));
    }

    @Test
    void resolveRegionFromPresignedCredential_empty_returnsDefault() {
        assertEquals("us-east-1", resolver.resolveRegionFromPresignedCredential(""));
    }

    @Test
    void resolveRegionFromPresignedCredential_malformedTooFewParts_returnsDefault() {
        assertEquals("us-east-1", resolver.resolveRegionFromPresignedCredential("only-two/parts"));
    }

    // --- resolveRegionFromHost(String host) tests (issue #1871) ---

    @Test
    void resolveRegionFromHost_regionBearingHost_returnsRegion() {
        assertEquals("ap-northeast-2",
                resolver.resolveRegionFromHost("abc123.execute-api.ap-northeast-2.localhost:4566"));
        assertEquals("us-west-2",
                resolver.resolveRegionFromHost("abc123.execute-api.us-west-2.localhost"));
        assertEquals("eu-west-1",
                resolver.resolveRegionFromHost("abc123.execute-api.eu-west-1.amazonaws.com"));
        assertEquals("us-gov-east-1",
                resolver.resolveRegionFromHost("abc123.execute-api.us-gov-east-1.amazonaws.com"));
    }

    @Test
    void resolveRegionFromHost_normalizesUppercaseRegionToLowercase() {
        // Hostnames are case-insensitive; the region must be lowercased so the region-scoped
        // API lookup does not 403 on a casing mismatch (PR review P1).
        assertEquals("us-east-1",
                resolver.resolveRegionFromHost("abc123.execute-api.US-EAST-1.localhost:4566"));
    }

    @Test
    void resolveRegionFromHost_builtinDnsSuffixes_returnNull() {
        // Floci's built-in execute-api suffixes carry NO region label. The old pattern parsed
        // "localhost" as the region and broke the lookup — these must return null so the caller
        // falls back (default region / cross-region apiId scan) instead. (PR #2188 review.)
        assertNull(resolver.resolveRegionFromHost("abc123.execute-api.localhost:4566"));
        assertNull(resolver.resolveRegionFromHost("abc123.execute-api.localhost.floci.io:4566"));
        assertNull(resolver.resolveRegionFromHost("abc123.execute-api.localhost.localstack.cloud:4566"));
    }

    @Test
    void resolveRegionFromHost_nonExecuteApiHost_returnsNull() {
        assertNull(resolver.resolveRegionFromHost("localhost:4566"));
        assertNull(resolver.resolveRegionFromHost("my-bucket.localhost:4566"));
        assertNull(resolver.resolveRegionFromHost("abc123.lambda-url.us-east-1.localhost"));
    }

    @Test
    void resolveRegionFromHost_nullOrEmpty_returnsNull() {
        assertNull(resolver.resolveRegionFromHost(null));
        assertNull(resolver.resolveRegionFromHost(""));
    }

    @Test
    void theDeploymentPartitionFollowsTheDefaultRegion() {
        assertEquals("aws", resolver.getDefaultPartition());
        assertEquals("aws-cn", new RegionResolver("cn-north-1", "000000000000").getDefaultPartition());
        assertEquals("aws-us-gov", new RegionResolver("us-gov-west-1", "000000000000").getDefaultPartition());
        assertEquals("aws-iso-b", new RegionResolver("us-isob-east-1", "000000000000", "aws-iso-b").getDefaultPartition());
    }

    @Test
    void anExplicitPartitionIdOverridesTheRegionDerivedOne() {
        assertEquals("aws-cn", RegionResolver.effectivePartition("cn-north-1", Optional.empty()));
        assertEquals("aws-us-gov", RegionResolver.effectivePartition("us-east-1", Optional.of(" AWS-US-GOV ")));
        assertEquals("aws", RegionResolver.effectivePartition("us-east-1", Optional.of("  ")));
    }

    /**
     * The request's partition comes from its signing region. A blank region (no credential) is
     * the deployment's partition, never a literal {@code aws}; a pseudo-region resolves to its
     * partition; and a region the catalog cannot place stays local rather than commercial.
     */
    @Test
    void partitionForRegionScopesARequest() {
        RegionResolver china = new RegionResolver("cn-north-1", "000000000000");
        assertEquals("aws-cn", china.partitionForRegion(null));
        assertEquals("aws-cn", china.partitionForRegion(""));
        assertEquals("aws-cn", china.partitionForRegion("xx-nowhere-9"));
        assertEquals("aws", china.partitionForRegion("us-east-1"));
        assertEquals("aws-us-gov", china.partitionForRegion("us-gov-west-1"));
        assertEquals("aws-eusc", china.partitionForRegion("eusc-de-east-1"));
        assertEquals("aws-cn", resolver.partitionForRegion("aws-cn-global"));
        assertEquals("aws-iso", resolver.partitionForRegion("aws-iso-global"));
        assertEquals("aws", resolver.partitionForRegion("aws-global"));
    }

    @Test
    void getPartitionOutsideARequestIsTheDeploymentPartition() {
        assertEquals("aws", resolver.getPartition());
        assertEquals("aws-cn", new RegionResolver("cn-north-1", "000000000000").getPartition());
    }

    @Test
    void buildArnRoutesABlankRegionToTheDeploymentPartition() {
        RegionResolver govCloud = new RegionResolver("us-gov-west-1", "000000000000");
        assertEquals("arn:aws-us-gov:iam::000000000000:role/r", govCloud.buildArn("iam", "", "role/r"));
        assertEquals("arn:aws-us-gov:iam::000000000000:role/r", govCloud.buildArn("iam", null, "role/r"));
        assertEquals("arn:aws-us-gov:s3:::bucket", govCloud.buildGlobalArn("s3", "", "bucket"));
        assertEquals("arn:aws-us-gov:sqs:us-gov-west-1:000000000000:q", govCloud.buildArn("sqs", "us-gov-west-1", "q"));
        assertEquals("arn:aws:iam::000000000000:role/r", resolver.buildArn("iam", "", "role/r"));
    }

    @Test
    void resolveRegionFromHostAcceptsEveryPartitionsShapeButOnlyPublishedIds() {
        assertEquals("eusc-de-east-1", resolver.resolveRegionFromHost("abc123.execute-api.eusc-de-east-1.localhost:4566"));
        assertEquals("us-isob-east-1", resolver.resolveRegionFromHost("abc123.execute-api.us-isob-east-1.localhost"));
        assertEquals("cn-north-1", resolver.resolveRegionFromHost("abc123.execute-api.cn-north-1.amazonaws.com.cn"));
        assertNull(resolver.resolveRegionFromHost("abc123.execute-api.xx-nowhere-9.localhost:4566"));
        assertNull(resolver.resolveRegionFromHost("abc123.execute-api.my-cd-1.localhost:4566"));
    }

    private static HttpHeaders stubHeaders(String authorizationValue) {
        return new HttpHeaders() {
            @Override public List<String> getRequestHeader(String name) {
                if ("Authorization".equalsIgnoreCase(name) && authorizationValue != null) {
                    return List.of(authorizationValue);
                }
                return List.of();
            }
            @Override public String getHeaderString(String name) {
                if ("Authorization".equalsIgnoreCase(name)) return authorizationValue;
                return null;
            }
            @Override public MultivaluedMap<String, String> getRequestHeaders() { return new MultivaluedHashMap<>(); }
            @Override public List<MediaType> getAcceptableMediaTypes() { return List.of(); }
            @Override public List<Locale> getAcceptableLanguages() { return List.of(); }
            @Override public MediaType getMediaType() { return null; }
            @Override public Locale getLanguage() { return null; }
            @Override public Map<String, Cookie> getCookies() { return Map.of(); }
            @Override public Date getDate() { return null; }
            @Override public int getLength() { return 0; }
        };
    }
}
