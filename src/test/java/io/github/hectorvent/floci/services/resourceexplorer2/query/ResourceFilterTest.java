package io.github.hectorvent.floci.services.resourceexplorer2.query;

import io.github.hectorvent.floci.core.resource.ExplorerResource;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResourceFilterTest {

    private static final Instant NOW = Instant.now();

    /** Every type these fixtures use is taggable, as it is for every provider floci ships. */
    private static final Set<String> TAGGABLE_TYPES =
            Set.of("s3:bucket", "rds:db", "dynamodb:table");

    private static ExplorerResource resource(String arn, String resourceType, String service,
                                              String region, String accountId, Map<String, String> tags) {
        return new ExplorerResource(arn, resourceType, service, region, accountId, NOW, tags);
    }

    private static final ExplorerResource S3_BUCKET = resource(
            "arn:aws:s3:::my-bucket", "s3:bucket", "s3", "us-east-1", "123456789012",
            Map.of("env", "prod", "team", "platform"));

    private static final ExplorerResource RDS_INSTANCE = resource(
            "arn:aws:rds:us-west-2:123456789012:db:orders-db", "rds:db", "rds", "us-west-2", "123456789012",
            Map.of());

    private static final ExplorerResource DYNAMO_TABLE = resource(
            "arn:aws:dynamodb:us-east-1:999999999999:table/users", "dynamodb:table", "dynamodb", "us-east-1", "999999999999",
            Map.of("env", "staging"));

    @Nested
    class EmptyQuery {

        @Test
        void emptyQueryMatchesEverything() {
            ParsedQuery query = QueryParser.parse("");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class RegionFilter {

        @Test
        void exactRegionMatch() {
            ParsedQuery query = QueryParser.parse("region:us-east-1");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }

        @Test
        void regionWildcard() {
            ParsedQuery query = QueryParser.parse("region:us*");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }

        @Test
        void regionWildcardNoMatch() {
            ParsedQuery query = QueryParser.parse("region:eu*");
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class ServiceFilter {

        @Test
        void exactServiceMatch() {
            ParsedQuery query = QueryParser.parse("service:s3");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }

        @Test
        void caseInsensitive() {
            ParsedQuery query = QueryParser.parse("service:S3");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class ResourceTypeFilter {

        @Test
        void exactResourceTypeMatch() {
            ParsedQuery query = QueryParser.parse("resourcetype:rds:db");
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }

        @Test
        void commaOrResourceType() {
            ParsedQuery query = QueryParser.parse("resourcetype:rds:db,s3:bucket");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void wildcardResourceType() {
            ParsedQuery query = QueryParser.parse("resourcetype:rds:*");
            assertTrue(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class TagFilters {

        @Test
        void tagKeyValue() {
            ParsedQuery query = QueryParser.parse("tag:env=prod");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void tagKeyOnly() {
            ParsedQuery query = QueryParser.parse("tag.key:env");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void tagValueOnly() {
            ParsedQuery query = QueryParser.parse("tag.value:prod");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void tagAll() {
            ParsedQuery query = QueryParser.parse("tag:all");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }

        @Test
        void tagNone() {
            ParsedQuery query = QueryParser.parse("tag:none");
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class NegationFilter {

        @Test
        void negatedServiceExcludes() {
            ParsedQuery query = QueryParser.parse("-service:s3");
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class KeywordFilter {

        @Test
        void positiveKeywordMatchingArnNarrowsResults() {
            // "orders-db" appears in RDS_INSTANCE arn; S3_BUCKET and DYNAMO_TABLE should be excluded
            ParsedQuery query = QueryParser.parse("orders-db");
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void positiveKeywordMatchingServiceNarrowsResults() {
            // "dynamodb" appears in DYNAMO_TABLE service; other resources should be excluded
            ParsedQuery query = QueryParser.parse("dynamodb");
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void positiveKeywordMatchingRegionNarrowsResults() {
            // "us-east-1" appears in region of S3_BUCKET and DYNAMO_TABLE
            ParsedQuery query = QueryParser.parse("us-east-1");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void positiveKeywordMatchingNothingReturnsEmpty() {
            ParsedQuery query = QueryParser.parse("xyzzy-no-such-resource");
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void positiveKeywordIsCaseInsensitive() {
            ParsedQuery query = QueryParser.parse("DYNAMODB");
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void negatedKeywordStillExcludes() {
            // regression guard: negated keyword excludes matching resources
            ParsedQuery query = QueryParser.parse("-dynamodb");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void positiveKeywordAndAttributeFilterAreAnded() {
            // "us-east-1" matches S3_BUCKET and DYNAMO_TABLE by region keyword,
            // but service:s3 further restricts to only S3_BUCKET
            ParsedQuery query = QueryParser.parse("us-east-1 service:s3");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }

        @Test
        void multiplePositiveKeywordsAreOred() {
            // AWS reads "s3 us-east-1" as "s3 OR us-east-1", so a resource matching either
            // qualifies: the bucket on both counts, the Dynamo table on its Region alone.
            ParsedQuery query = QueryParser.parse("s3 us-east-1");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }

        @Test
        void keywordMatchesATagKeyOrValue() {
            assertTrue(ResourceFilter.matches(S3_BUCKET, QueryParser.parse("platform"), TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, QueryParser.parse("platform"), TAGGABLE_TYPES));
        }

        @Test
        void negatedKeywordExcludesEvenWhenAPositiveOneMatches() {
            ParsedQuery query = QueryParser.parse("us-east-1 -s3");
            assertFalse(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertTrue(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class AndSemantics {

        @Test
        void multipleFiltersMustAllMatch() {
            ParsedQuery query = QueryParser.parse("service:s3 region:us-east-1");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }

        @Test
        void filterAndNegation() {
            ParsedQuery query = QueryParser.parse("region:us-east-1 -service:dynamodb");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class AccountIdFilter {

        @Test
        void matchesAccountId() {
            ParsedQuery query = QueryParser.parse("accountid:123456789012");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(DYNAMO_TABLE, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class IdFilter {

        @Test
        void matchesExactArn() {
            ParsedQuery query = QueryParser.parse("id:arn:aws:s3:::my-bucket");
            assertTrue(ResourceFilter.matches(S3_BUCKET, query, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, query, TAGGABLE_TYPES));
        }
    }

    @Nested
    class ViewFilterMerging {

        @Test
        void viewFilterAndRequestFilterAnded() {
            ParsedQuery viewFilter = QueryParser.parse("service:s3");
            ParsedQuery requestFilter = QueryParser.parse("region:us-east-1");
            ParsedQuery combined = ResourceFilter.combine(viewFilter, requestFilter);
            assertTrue(ResourceFilter.matches(S3_BUCKET, combined, TAGGABLE_TYPES));
            assertFalse(ResourceFilter.matches(RDS_INSTANCE, combined, TAGGABLE_TYPES));
        }
    }
}
