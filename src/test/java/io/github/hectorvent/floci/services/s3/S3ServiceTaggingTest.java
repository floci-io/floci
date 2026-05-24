package io.github.hectorvent.floci.services.s3;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Tests for S3 tagging: service-layer behavior and {@link S3RequestParser} utility methods.
 *
 * <p>Phase 1 (#932): tests 10+ cover {@code hasQueryParam} false-positive fix.
 * Phase 2 (#986): tests 1-9 cover inline tagging header parsing and persistence.
 */
class S3ServiceTaggingTest {

    // ========== Phase 2: Inline tagging header parsing ==========

    // (Tests 1-9 will be added in Phase 2 — fix/s3-putobject-inline-tagging)

    // ========== Phase 1: hasQueryParam false-positive fix ==========

    @Test
    void hasQueryParamReturnsTrueForExactParamName() {
        assertTrue(S3RequestParser.hasQueryParamInString("tagging", "tagging"));
    }

    @Test
    void hasQueryParamReturnsTrueForParamWithValue() {
        assertTrue(S3RequestParser.hasQueryParamInString("tagging=", "tagging"));
    }

    @Test
    void hasQueryParamReturnsTrueForParamAmongOthers() {
        assertTrue(S3RequestParser.hasQueryParamInString(
                "versionId=abc&tagging&acl", "tagging"));
    }

    @Test
    void hasQueryParamReturnsFalseForNullQuery() {
        assertFalse(S3RequestParser.hasQueryParamInString(null, "tagging"));
    }

    @Test
    void hasQueryParamReturnsFalseForEmptyQuery() {
        assertFalse(S3RequestParser.hasQueryParamInString("", "tagging"));
    }

    @Test
    void hasQueryParamNoFalsePositiveOnValueContainingParamName() {
        // This is the actual #932 bug: X-Amz-SignedHeaders value contains "x-amz-tagging"
        // but there is no "tagging" query parameter. The old code would falsely match.
        String query = "X-Amz-SignedHeaders=host%3Bx-amz-tagging&X-Amz-Algorithm=AWS4-HMAC-SHA256";
        assertFalse(S3RequestParser.hasQueryParamInString(query, "tagging"));
    }

    @Test
    void hasQueryParamNoFalsePositiveOnPartialPrefix() {
        // "tagging" should not match "tag"
        assertFalse(S3RequestParser.hasQueryParamInString("tagging", "tag"));
    }

    @Test
    void hasQueryParamDetectsParamValueContainsEquals() {
        // Param with = in value — only the first = is the delimiter
        String query = "X-Amz-Credential=test/2024/us-east-1/s3/aws4_request";
        assertTrue(S3RequestParser.hasQueryParamInString(query, "X-Amz-Credential"));
    }

    @Test
    void hasQueryParamNoFalsePositiveOnUnrelatedQuery() {
        String query = "versionId=abc&uploadId=xyz";
        assertFalse(S3RequestParser.hasQueryParamInString(query, "tagging"));
        assertFalse(S3RequestParser.hasQueryParamInString(query, "acl"));
    }
}
