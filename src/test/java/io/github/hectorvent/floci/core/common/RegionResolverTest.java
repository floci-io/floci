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
