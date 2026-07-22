package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.services.cloudfront.model.CacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DefaultCacheBehavior;
import io.github.hectorvent.floci.services.cloudfront.model.DistributionConfig;
import io.github.hectorvent.floci.services.cloudfront.model.Origin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the CloudFront data-plane routing rules against the AWS spec. */
class CloudFrontRequestRouterTest {

    // ── Path normalization (RFC 3986; collapse //, resolve ./..) ──────────────────

    @Test
    void normalizePathCollapsesSlashesAndResolvesDotSegments() {
        assertEquals("/", CloudFrontRequestRouter.normalizePath(""));
        assertEquals("/", CloudFrontRequestRouter.normalizePath("/"));
        assertEquals("/a/b", CloudFrontRequestRouter.normalizePath("//a//b"));
        assertEquals("/b", CloudFrontRequestRouter.normalizePath("/a/../b"));
        assertEquals("/a", CloudFrontRequestRouter.normalizePath("/a/b/.."));
        assertEquals("/a/b", CloudFrontRequestRouter.normalizePath("/a/./b"));
        assertEquals("/a/b/", CloudFrontRequestRouter.normalizePath("/a/b/"));   // trailing slash preserved
        assertEquals("/a/b", CloudFrontRequestRouter.normalizePath("/a/b?x=1&y=2"));  // query stripped
    }

    // ── Path pattern matching: * / ? , case-sensitive, leading slash optional ─────

    @Test
    void pathPatternWildcardsMatchPerSpec() {
        assertTrue(CloudFrontRequestRouter.pathPatternMatches("*.jpg", "/images/logo.jpg"));
        assertTrue(CloudFrontRequestRouter.pathPatternMatches("images/*.jpg", "/images/logo.jpg"));
        assertTrue(CloudFrontRequestRouter.pathPatternMatches("images/*.jpg", "/images/product1/logo.jpg"));
        // ? matches exactly one character
        assertTrue(CloudFrontRequestRouter.pathPatternMatches("a??.jpg", "/abe.jpg"));
        assertFalse(CloudFrontRequestRouter.pathPatternMatches("a??.jpg", "/ant2.jpg"));
        // case-sensitive: *.jpg does not match LOGO.JPG
        assertFalse(CloudFrontRequestRouter.pathPatternMatches("*.jpg", "/LOGO.JPG"));
        // leading slash is optional / ignored on the pattern
        assertTrue(CloudFrontRequestRouter.pathPatternMatches("/images/*", "/images/a.png"));
        // '*' (default behavior) matches everything
        assertTrue(CloudFrontRequestRouter.pathPatternMatches("*", "/anything/at/all"));
        // dot in the pattern is literal, not a regex wildcard
        assertFalse(CloudFrontRequestRouter.pathPatternMatches("a.jpg", "/axjpg"));
    }

    // ── Behavior selection: first match wins, default behavior evaluated LAST ─────

    @Test
    void matchTargetOriginIdEvaluatesBehaviorsInOrderThenDefaultLast() {
        DistributionConfig cfg = new DistributionConfig();
        cfg.setCacheBehaviors(List.of(
                behavior("images/*.jpg", "jpg-origin"),
                behavior("images/*", "images-origin")));
        cfg.setDefaultCacheBehavior(defaultBehavior("default-origin"));

        // /images/sample.gif fails the first pattern, matches the second (not the default).
        assertEquals("images-origin", CloudFrontRequestRouter.matchTargetOriginId(cfg, "/images/sample.gif"));
        // /images/photo.jpg matches the FIRST behavior (order matters).
        assertEquals("jpg-origin", CloudFrontRequestRouter.matchTargetOriginId(cfg, "/images/photo.jpg"));
        // No behavior matches → default behavior (processed last).
        assertEquals("default-origin", CloudFrontRequestRouter.matchTargetOriginId(cfg, "/index.html"));
    }

    // ── Default root object: ROOT ONLY (never appended to subdirectories) ─────────

    @Test
    void defaultRootObjectAppliesOnlyToTheRoot() {
        // root → default root object, with origin path prepended
        assertEquals("app/index.html",
                CloudFrontRequestRouter.resolveOriginKey(null, "/", "app/index.html"));
        assertEquals("prod/index.html",
                CloudFrontRequestRouter.resolveOriginKey("/prod", "/", "index.html"));
        // subdirectory does NOT get the default root object appended (differs from S3 website)
        assertEquals("install/",
                CloudFrontRequestRouter.resolveOriginKey(null, "/install/", "index.html"));
        // a normal object request is served as-is under the origin path
        assertEquals("prod/shell/bootstrap.js",
                CloudFrontRequestRouter.resolveOriginKey("/prod", "/shell/bootstrap.js", "index.html"));
    }

    @Test
    void resolveForwardUriKeepsLeadingSlashAndAppliesRootObjectAtRootOnly() {
        // Forward URI keeps its leading slash (custom-origin form) and applies the default root
        // object only at the root — for custom origins as well as S3.
        assertEquals("/index.html",
                CloudFrontRequestRouter.resolveForwardUri(null, "/", "index.html"));
        assertEquals("/prod/index.html",
                CloudFrontRequestRouter.resolveForwardUri("/prod", "/", "index.html"));
        assertEquals("/shell/bootstrap.js",
                CloudFrontRequestRouter.resolveForwardUri(null, "/shell/bootstrap.js", "index.html"));
        // no default root object at the root → the root path is forwarded unchanged
        assertEquals("/", CloudFrontRequestRouter.resolveForwardUri(null, "/", null));
    }

    // ── Origin resolution + S3 bucket extraction ─────────────────────────────────

    @Test
    void findsOriginByIdAndClassifiesS3() {
        Origin s3 = new Origin();
        s3.setId("s3-origin");
        s3.setDomainName("my-bucket.s3.us-east-1.amazonaws.com");
        s3.setS3OriginConfig(java.util.Map.of("originAccessIdentity", ""));
        DistributionConfig cfg = new DistributionConfig();
        cfg.setOrigins(List.of(s3));

        assertEquals(s3, CloudFrontRequestRouter.findOrigin(cfg, "s3-origin"));
        assertNull(CloudFrontRequestRouter.findOrigin(cfg, "missing"));
        assertTrue(CloudFrontRequestRouter.isS3Origin(s3));
    }

    @Test
    void extractsBucketFromS3OriginDomainForms() {
        assertEquals("my-bucket",
                CloudFrontRequestRouter.bucketFromS3Domain("my-bucket.s3.us-east-1.amazonaws.com"));
        assertEquals("my-bucket",
                CloudFrontRequestRouter.bucketFromS3Domain("my-bucket.s3.amazonaws.com"));
        assertEquals("my-bucket",
                CloudFrontRequestRouter.bucketFromS3Domain("my-bucket.s3-website-us-east-1.amazonaws.com"));
    }

    private static CacheBehavior behavior(String pattern, String originId) {
        CacheBehavior b = new CacheBehavior();
        b.setPathPattern(pattern);
        b.setTargetOriginId(originId);
        return b;
    }

    private static DefaultCacheBehavior defaultBehavior(String originId) {
        DefaultCacheBehavior b = new DefaultCacheBehavior();
        b.setTargetOriginId(originId);
        return b;
    }
}
