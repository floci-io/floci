package io.github.hectorvent.floci.services.firehose;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3ObjectKeyResolverTest {

    private static final Instant INSTANT = Instant.parse("2026-07-13T10:42:07Z");
    private static final ZonedDateTime TIME = ZonedDateTime.ofInstant(INSTANT, ZoneOffset.UTC);
    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    @Test
    void nullPrefixEvaluatesToDefaultTimePrefix() {
        assertEquals("2026/07/13/10/", S3ObjectKeyResolver.evaluatePrefix(null, TIME));
    }

    @Test
    void emptyPrefixEvaluatesToDefaultTimePrefix() {
        assertEquals("2026/07/13/10/", S3ObjectKeyResolver.evaluatePrefix("", TIME));
    }

    @Test
    void staticPrefixGetsDefaultTimePrefixAppended() {
        assertEquals("events/data/2026/07/13/10/", S3ObjectKeyResolver.evaluatePrefix("events/data/", TIME));
    }

    @Test
    void staticPrefixWithoutTrailingSlashIsConcatenatedLiterally() {
        assertEquals("legacy2026/07/13/10/", S3ObjectKeyResolver.evaluatePrefix("legacy", TIME));
    }

    @Test
    void timestampExpressionIsEvaluatedWithoutAppendingDefault() {
        assertEquals("data/2026/07/13/", S3ObjectKeyResolver.evaluatePrefix("data/!{timestamp:yyyy/MM/dd}/", TIME));
    }

    @Test
    void multipleTimestampExpressionsShareTheSameInstant() {
        assertEquals("year=2026/hour=10/",
                S3ObjectKeyResolver.evaluatePrefix("year=!{timestamp:yyyy}/hour=!{timestamp:HH}/", TIME));
    }

    @Test
    void singleQuotedTextInTimestampPatternIsLiteral() {
        assertEquals("year=2026/", S3ObjectKeyResolver.evaluatePrefix("!{timestamp:'year='yyyy}/", TIME));
    }

    @Test
    void randomStringExpressionYieldsFreshElevenCharAlphanumerics() {
        String evaluated = S3ObjectKeyResolver.evaluatePrefix(
                "rand/!{firehose:random-string}/!{firehose:random-string}/", TIME);
        assertTrue(evaluated.matches("rand/[A-Za-z0-9]{11}/[A-Za-z0-9]{11}/"), evaluated);
        String[] parts = evaluated.split("/");
        assertNotEquals(parts[1], parts[2]);
    }

    // Observed on real AWS but not documented: the docs' semantic rules say the
    // default time prefix is appended whenever there is no *timestamp* expression,
    // yet a prefix whose only expression is !{firehose:random-string} gets nothing
    // appended (https://github.com/floci-io/floci/issues/1857).
    @Test
    void randomStringExpressionSuppressesDefaultTimePrefix() {
        String evaluated = S3ObjectKeyResolver.evaluatePrefix("rand/!{firehose:random-string}/", TIME);
        assertTrue(evaluated.matches("rand/[A-Za-z0-9]{11}/"), evaluated);
    }

    @Test
    void dynamicPartitioningExpressionIsKeptLiterally() {
        assertEquals("data/!{partitionKeyFromQuery:customerId}/",
                S3ObjectKeyResolver.evaluatePrefix("data/!{partitionKeyFromQuery:customerId}/", TIME));
    }

    @Test
    void unknownNamespaceIsKeptLiterally() {
        assertEquals("data/!{bogus}/", S3ObjectKeyResolver.evaluatePrefix("data/!{bogus}/", TIME));
    }

    @Test
    void invalidTimestampPatternIsKeptLiterally() {
        assertEquals("data/!{timestamp:bbbb}/", S3ObjectKeyResolver.evaluatePrefix("data/!{timestamp:bbbb}/", TIME));
    }

    @Test
    void unclosedExpressionIsLiteralAndStillGetsDefaultAppended() {
        assertEquals("data/!{timestamp:yyyy2026/07/13/10/",
                S3ObjectKeyResolver.evaluatePrefix("data/!{timestamp:yyyy", TIME));
    }

    @Test
    void objectNameSuffixFollowsAwsFormat() {
        String suffix = S3ObjectKeyResolver.objectNameSuffix("my-stream", "1", TIME);
        assertTrue(suffix.matches("my-stream-1-2026-07-13-10-42-07-" + UUID_REGEX), suffix);
    }

    @Test
    void resolveKeyConcatenatesEvaluatedPrefixAndSuffix() {
        String key = S3ObjectKeyResolver.resolveKey("legacy", "my-stream", "3", INSTANT, ZoneOffset.UTC);
        assertTrue(key.matches("legacy2026/07/13/10/my-stream-3-2026-07-13-10-42-07-" + UUID_REGEX), key);
    }

    @Test
    void customTimeZoneShiftsPrefixAndSuffixAcrossTheDayBoundary() {
        String key = S3ObjectKeyResolver.resolveKey(null, "s", "1",
                Instant.parse("2026-01-01T23:30:00Z"), ZoneId.of("Europe/Madrid"));
        assertTrue(key.matches("2026/01/02/00/s-1-2026-01-02-00-30-00-" + UUID_REGEX), key);
    }

    @Test
    void resolveZoneFallsBackToUtc() {
        assertEquals(ZoneOffset.UTC, S3ObjectKeyResolver.resolveZone(null));
        assertEquals(ZoneOffset.UTC, S3ObjectKeyResolver.resolveZone("  "));
        assertEquals(ZoneOffset.UTC, S3ObjectKeyResolver.resolveZone("Not/AZone"));
        assertEquals(ZoneId.of("Europe/Madrid"), S3ObjectKeyResolver.resolveZone("Europe/Madrid"));
    }
}
