package io.github.hectorvent.floci.services.s3vectors;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3VectorsMetadataFilterTest {

    @Test
    void matchesEqualityAndInequalityAcrossScalarTypes() {
        Map<String, Object> metadata = Map.of(
                "name", "documentary",
                "published", true,
                "score", 2,
                "genres", List.of("documentary", "comedy"));

        assertTrue(matches(metadata, Map.of("name", "documentary")));
        assertTrue(matches(metadata, Map.of("published", Map.of("$eq", true))));
        assertTrue(matches(metadata, Map.of("score", Map.of("$eq", 2.0))));
        assertTrue(matches(metadata, Map.of("score", Map.of("$eq", new BigDecimal("2.00")))));
        assertTrue(matches(metadata, Map.of("genres", Map.of("$eq", "comedy"))));
        assertTrue(matches(metadata, Map.of("name", Map.of("$ne", "drama"))));
        assertFalse(matches(metadata, Map.of("score", Map.of("$ne", 2.0))));
        assertTrue(matches(metadata, Map.of("score", Map.of("$ne", 3.0))));

        assertFalse(matches(metadata, Map.of("name", Map.of("$eq", true))));
        assertFalse(matches(metadata, Map.of("score", Map.of("$eq", "2"))));
        assertFalse(matches(metadata, Map.of("published", Map.of("$ne", true))));
    }

    @Test
    void matchesOrderedComparisonsAndBoundaries() {
        Map<String, Object> metadata = Map.of("score", 10);

        assertTrue(matches(metadata, Map.of("score", Map.of("$gt", 9))));
        assertFalse(matches(metadata, Map.of("score", Map.of("$gt", 10))));
        assertTrue(matches(metadata, Map.of("score", Map.of("$gte", 10.0))));
        assertTrue(matches(metadata, Map.of("score", Map.of("$lt", 11))));
        assertFalse(matches(metadata, Map.of("score", Map.of("$lt", 10))));
        assertTrue(matches(metadata, Map.of("score", Map.of("$lte", new BigDecimal("10.00")))));
        assertTrue(matches(metadata, Map.of("score", Map.of("$gte", 10, "$lte", 10))));
        assertFalse(matches(Map.of("score", "10"), Map.of("score", Map.of("$gt", 9))));
    }

    @Test
    void matchesMembershipPredicates() {
        Map<String, Object> metadata = Map.of(
                "genre", "documentary",
                "tags", List.of("history", "nature"));

        assertTrue(matches(metadata, Map.of("genre", Map.of("$in", List.of("drama", "documentary")))));
        assertFalse(matches(metadata, Map.of("genre", Map.of("$in", List.of("drama", "comedy")))));
        assertTrue(matches(metadata, Map.of("tags", Map.of("$in", List.of("nature", "science")))));
        assertTrue(matches(metadata, Map.of("genre", Map.of("$nin", List.of("drama", "comedy")))));
        assertFalse(matches(metadata, Map.of("tags", Map.of("$nin", List.of("nature", "science")))));
    }

    @Test
    void handlesMissingFieldsAndPresentNullValuesExplicitly() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nullable", null);

        assertTrue(matches(metadata, Map.of("nullable", Map.of("$exists", true))));
        assertFalse(matches(metadata, Map.of("missing", Map.of("$exists", true))));
        assertTrue(matches(metadata, Map.of("missing", Map.of("$exists", false))));
        assertFalse(matches(metadata, Map.of("nullable", Map.of("$exists", false))));

        assertFalse(matches(metadata, Map.of("missing", Map.of("$eq", "value"))));
        assertFalse(matches(metadata, Map.of("missing", Map.of("$gt", 1))));
        assertFalse(matches(metadata, Map.of("missing", Map.of("$in", List.of("value")))));
        assertTrue(matches(metadata, Map.of("missing", Map.of("$ne", "value"))));
        assertTrue(matches(metadata, Map.of("missing", Map.of("$nin", List.of("value")))));
        assertFalse(matches(metadata, Map.of("nullable", Map.of("$eq", "value"))));
        assertTrue(matches(metadata, Map.of("nullable", Map.of("$ne", "value"))));
        assertFalse(matches(metadata, Map.of("nullable", Map.of("$in", List.of("value")))));
        assertTrue(matches(metadata, Map.of("nullable", Map.of("$nin", List.of("value")))));
    }

    @Test
    void recursivelyCombinesLogicalAndFieldClauses() {
        Map<String, Object> metadata = Map.of(
                "tenant", "tenant-a",
                "year", 2024,
                "published", true);

        Map<String, Object> filter = Map.of(
                "tenant", "tenant-a",
                "$and", List.of(
                        Map.of("year", Map.of("$gte", 2020)),
                        Map.of("$or", List.of(
                                Map.of("published", false),
                                Map.of("published", true)))));

        assertTrue(matches(metadata, filter));
        assertFalse(matches(metadata, Map.of(
                "tenant", "tenant-a",
                "year", Map.of("$lt", 2020))));
        assertFalse(matches(metadata, Map.of("$and", List.of(
                Map.of("tenant", "tenant-a"),
                Map.of("published", false)))));
        assertFalse(matches(metadata, Map.of("$or", List.of(
                Map.of("tenant", "tenant-b"),
                Map.of("year", Map.of("$lt", 2020))))));
    }

    @Test
    void rejectsMalformedOrUnsupportedFilters() {
        assertValidation(Map.of());
        assertValidation(Map.of("$and", Map.of("tenant", "tenant-a")));
        assertValidation(Map.of("$and", List.of("tenant-a")));
        assertValidation(Map.of("$or", List.of()));
        assertValidation(Map.of("tenant", Map.of("$in", "tenant-a")));
        assertValidation(Map.of("tenant", Map.of("$nin", List.of())));
        assertValidation(Map.of("tenant", Map.of("$in", List.of(Map.of("nested", true)))));
        assertValidation(Map.of("tenant", Map.of("$unknown", "tenant-a")));
        assertValidation(Map.of("tenant", Map.of("$exists", "true")));
        assertValidation(Map.of("year", Map.of("$gt", "2020")));
        assertValidation(Map.of("year", Map.of("$eq", List.of(2020))));
        assertValidation(Map.of("tenant", Map.of()));
        assertValidation("tenant-a");
    }

    private boolean matches(Map<String, Object> metadata, Object filter) {
        return S3VectorsMetadataFilter.compile(filter).matches(metadata);
    }

    private void assertValidation(Object filter) {
        AwsException exception = assertThrows(AwsException.class, () -> S3VectorsMetadataFilter.compile(filter));
        assertEquals("ValidationException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }
}
