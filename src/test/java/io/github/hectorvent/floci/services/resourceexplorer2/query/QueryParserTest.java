package io.github.hectorvent.floci.services.resourceexplorer2.query;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class QueryParserTest {

    @Nested
    class EmptyInput {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        void emptyOrBlankReturnsEmptyQuery(String input) {
            ParsedQuery query = QueryParser.parse(input);
            assertTrue(query.keywords().isEmpty());
            assertTrue(query.filters().isEmpty());
        }
    }

    @Nested
    class FreeFormKeywords {

        @Test
        void singleKeyword() {
            ParsedQuery query = QueryParser.parse("ec2");
            assertEquals(1, query.keywords().size());
            assertEquals("ec2", query.keywords().getFirst().value());
            assertFalse(query.keywords().getFirst().negated());
            assertTrue(query.filters().isEmpty());
        }

        @Test
        void multipleKeywordsAreSplitByWhitespace() {
            ParsedQuery query = QueryParser.parse("ec2 billing test");
            assertEquals(3, query.keywords().size());
            assertEquals("ec2", query.keywords().get(0).value());
            assertEquals("billing", query.keywords().get(1).value());
            assertEquals("test", query.keywords().get(2).value());
        }

        @Test
        void negatedKeyword() {
            ParsedQuery query = QueryParser.parse("-forbidden");
            assertEquals(1, query.keywords().size());
            assertTrue(query.keywords().getFirst().negated());
            assertEquals("forbidden", query.keywords().getFirst().value());
        }

        @Test
        void quotedMultiWordKeyword() {
            ParsedQuery query = QueryParser.parse("\"multi word phrase\"");
            assertEquals(1, query.keywords().size());
            assertEquals("multi word phrase", query.keywords().getFirst().value());
        }

        @Test
        void negatedQuotedKeyword() {
            ParsedQuery query = QueryParser.parse("-\"multi word\"");
            assertEquals(1, query.keywords().size());
            assertTrue(query.keywords().getFirst().negated());
            assertEquals("multi word", query.keywords().getFirst().value());
        }

        @Test
        void unknownPrefixTreatedAsKeyword() {
            ParsedQuery query = QueryParser.parse("cat:blue");
            assertEquals(1, query.keywords().size());
            assertEquals("cat:blue", query.keywords().getFirst().value());
            assertTrue(query.filters().isEmpty());
        }
    }

    @Nested
    class Filters {

        @Test
        void serviceFilter() {
            ParsedQuery query = QueryParser.parse("service:ec2");
            assertTrue(query.keywords().isEmpty());
            assertEquals(1, query.filters().size());
            var filter = query.filters().getFirst();
            assertEquals(FilterAttribute.SERVICE, filter.attribute());
            assertEquals(1, filter.values().size());
            assertEquals("ec2", filter.values().getFirst().value());
            assertFalse(filter.values().getFirst().prefixMatch());
            assertFalse(filter.negated());
        }

        @Test
        void regionFilter() {
            ParsedQuery query = QueryParser.parse("region:us-east-1");
            assertEquals(FilterAttribute.REGION, query.filters().getFirst().attribute());
            assertEquals("us-east-1", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void resourceTypeFilter() {
            ParsedQuery query = QueryParser.parse("resourcetype:s3:bucket");
            var filter = query.filters().getFirst();
            assertEquals(FilterAttribute.RESOURCE_TYPE, filter.attribute());
            assertEquals("s3:bucket", filter.values().getFirst().value());
        }

        @Test
        void resourceTypeSupportsFilter() {
            ParsedQuery query = QueryParser.parse("resourcetype.supports:tags");
            assertEquals(FilterAttribute.RESOURCE_TYPE_SUPPORTS, query.filters().getFirst().attribute());
            assertEquals("tags", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void accountIdFilter() {
            ParsedQuery query = QueryParser.parse("accountid:123456789012");
            assertEquals(FilterAttribute.ACCOUNT_ID, query.filters().getFirst().attribute());
            assertEquals("123456789012", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void idFilterPreservesColonsInArn() {
            ParsedQuery query = QueryParser.parse("id:arn:aws:s3:::my-bucket");
            assertEquals(FilterAttribute.ID, query.filters().getFirst().attribute());
            assertEquals("arn:aws:s3:::my-bucket", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void tagKeyValueFilter() {
            ParsedQuery query = QueryParser.parse("tag:environment=production");
            assertEquals(FilterAttribute.TAG, query.filters().getFirst().attribute());
            assertEquals("environment=production", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void tagAllSpecialValue() {
            ParsedQuery query = QueryParser.parse("tag:all");
            assertEquals(FilterAttribute.TAG, query.filters().getFirst().attribute());
            assertEquals("all", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void tagNoneSpecialValue() {
            ParsedQuery query = QueryParser.parse("tag:none");
            assertEquals(FilterAttribute.TAG, query.filters().getFirst().attribute());
            assertEquals("none", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void tagKeyFilter() {
            ParsedQuery query = QueryParser.parse("tag.key:environment");
            assertEquals(FilterAttribute.TAG_KEY, query.filters().getFirst().attribute());
            assertEquals("environment", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void tagValueFilter() {
            ParsedQuery query = QueryParser.parse("tag.value:production");
            assertEquals(FilterAttribute.TAG_VALUE, query.filters().getFirst().attribute());
            assertEquals("production", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void applicationFilter() {
            ParsedQuery query = QueryParser.parse("application:MyApp");
            assertEquals(FilterAttribute.APPLICATION, query.filters().getFirst().attribute());
            assertEquals("MyApp", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void caseInsensitiveFilterPrefix() {
            ParsedQuery query = QueryParser.parse("Service:EC2");
            assertEquals(1, query.filters().size());
            assertEquals(FilterAttribute.SERVICE, query.filters().getFirst().attribute());
            assertEquals("EC2", query.filters().getFirst().values().getFirst().value());
        }
    }

    @Nested
    class CommaOrValues {

        @Test
        void twoValuesCommaOr() {
            ParsedQuery query = QueryParser.parse("region:us-east-1,us-west-2");
            var filter = query.filters().getFirst();
            assertEquals(2, filter.values().size());
            assertEquals("us-east-1", filter.values().get(0).value());
            assertEquals("us-west-2", filter.values().get(1).value());
        }

        @Test
        void threeValuesCommaOr() {
            ParsedQuery query = QueryParser.parse("resourcetype:rds:db,s3:bucket,dynamodb:table");
            var filter = query.filters().getFirst();
            assertEquals(3, filter.values().size());
            assertEquals("rds:db", filter.values().get(0).value());
            assertEquals("s3:bucket", filter.values().get(1).value());
            assertEquals("dynamodb:table", filter.values().get(2).value());
        }

        @Test
        void doubleCommaProducesEmptySegment() {
            ParsedQuery query = QueryParser.parse("region:us-east-1,,us-west-2");
            var filter = query.filters().getFirst();
            assertEquals(3, filter.values().size());
            assertEquals("us-east-1", filter.values().get(0).value());
            assertEquals("", filter.values().get(1).value());
            assertEquals("us-west-2", filter.values().get(2).value());
        }
    }

    @Nested
    class Negation {

        @Test
        void negatedFilter() {
            ParsedQuery query = QueryParser.parse("-service:ec2");
            var filter = query.filters().getFirst();
            assertTrue(filter.negated());
            assertEquals(FilterAttribute.SERVICE, filter.attribute());
            assertEquals("ec2", filter.values().getFirst().value());
        }

        @Test
        void negatedFilterWithCommaOr() {
            ParsedQuery query = QueryParser.parse("-region:us-east-1,us-west-2");
            var filter = query.filters().getFirst();
            assertTrue(filter.negated());
            assertEquals(2, filter.values().size());
        }
    }

    @Nested
    class Wildcards {

        @Test
        void wildcardSuffix() {
            ParsedQuery query = QueryParser.parse("region:us*");
            var value = query.filters().getFirst().values().getFirst();
            assertEquals("us", value.value());
            assertTrue(value.prefixMatch());
        }

        @Test
        void wildcardWithCommaOr() {
            ParsedQuery query = QueryParser.parse("resourcetype:ec2:*,s3:bucket");
            var values = query.filters().getFirst().values();
            assertEquals("ec2:", values.get(0).value());
            assertTrue(values.get(0).prefixMatch());
            assertEquals("s3:bucket", values.get(1).value());
            assertFalse(values.get(1).prefixMatch());
        }
    }

    @Nested
    class Escaping {

        @Test
        void escapedCommaInFilterValue() {
            ParsedQuery query = QueryParser.parse("tag.key:comma\\,literal");
            var filter = query.filters().getFirst();
            assertEquals(1, filter.values().size());
            assertEquals("comma,literal", filter.values().getFirst().value());
        }

        @Test
        void escapedBackslash() {
            ParsedQuery query = QueryParser.parse("tag.value:back\\\\slash");
            assertEquals("back\\slash", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void danglingEscapeAtEndOfInput() {
            ParsedQuery query = QueryParser.parse("service:ec2\\");
            assertEquals("ec2\\", query.filters().getFirst().values().getFirst().value());
        }
    }

    @Nested
    class QuotedStrings {

        @Test
        void quotedFilterValue() {
            ParsedQuery query = QueryParser.parse("tag:\"key=value with spaces\"");
            assertEquals("key=value with spaces", query.filters().getFirst().values().getFirst().value());
        }

        @Test
        void escapedQuoteInsideQuotes() {
            ParsedQuery query = QueryParser.parse("\"escaped \\\"inner\\\" quote\"");
            assertEquals("escaped \"inner\" quote", query.keywords().getFirst().value());
        }

        @Test
        void emptyQuotedStringAsToken() {
            ParsedQuery query = QueryParser.parse("service:ec2 \"\" region:us-east-1");
            assertEquals(2, query.filters().size());
            assertEquals(1, query.keywords().size());
            assertEquals("", query.keywords().getFirst().value());
        }
    }

    @Nested
    class Combined {

        @Test
        void keywordsAndFilters() {
            ParsedQuery query = QueryParser.parse("test instance service:EC2 region:us-west-2");
            assertEquals(2, query.keywords().size());
            assertEquals("test", query.keywords().get(0).value());
            assertEquals("instance", query.keywords().get(1).value());
            assertEquals(2, query.filters().size());
        }

        @Test
        void awsDocExample() {
            ParsedQuery query = QueryParser.parse("region:us* service:ec2 -tag:stage=prod");
            assertEquals(0, query.keywords().size());
            assertEquals(3, query.filters().size());

            var regionFilter = query.filters().stream()
                    .filter(f -> f.attribute() == FilterAttribute.REGION).findFirst().orElseThrow();
            assertTrue(regionFilter.values().getFirst().prefixMatch());
            assertEquals("us", regionFilter.values().getFirst().value());
            assertFalse(regionFilter.negated());

            var serviceFilter = query.filters().stream()
                    .filter(f -> f.attribute() == FilterAttribute.SERVICE).findFirst().orElseThrow();
            assertEquals("ec2", serviceFilter.values().getFirst().value());

            var tagFilter = query.filters().stream()
                    .filter(f -> f.attribute() == FilterAttribute.TAG).findFirst().orElseThrow();
            assertTrue(tagFilter.negated());
            assertEquals("stage=prod", tagFilter.values().getFirst().value());
        }

        @Test
        void multipleSpacesBetweenTokens() {
            ParsedQuery query = QueryParser.parse("service:ec2   region:us-east-1");
            assertEquals(2, query.filters().size());
        }

        @Test
        void trailingWhitespace() {
            ParsedQuery query = QueryParser.parse("service:ec2 ");
            assertEquals(1, query.filters().size());
        }
    }

    @Nested
    class FilterWithEmptyValue {

        @Test
        void filterPrefixWithNoValue() {
            ParsedQuery query = QueryParser.parse("service:");
            assertEquals(1, query.filters().size());
            assertEquals(1, query.filters().getFirst().values().size());
            assertEquals("", query.filters().getFirst().values().getFirst().value());
        }
    }

    @Nested
    class ListResourcesMode {

        @Test
        void rejectsFreeFormTextInFilterOnlyMode() {
            assertThrows(IllegalArgumentException.class,
                    () -> QueryParser.parseFilterOnly("hello world"));
        }

        @Test
        void acceptsFiltersInFilterOnlyMode() {
            ParsedQuery query = QueryParser.parseFilterOnly("service:ec2 region:us-east-1");
            assertEquals(2, query.filters().size());
            assertTrue(query.keywords().isEmpty());
        }

        @Test
        void emptyStringAllowedInFilterOnlyMode() {
            ParsedQuery query = QueryParser.parseFilterOnly("");
            assertTrue(query.filters().isEmpty());
        }
    }
}
