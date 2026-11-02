package io.github.hectorvent.floci.services.dynamodb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.ContributorInsightsAction;
import software.amazon.awssdk.services.dynamodb.model.ContributorInsightsMode;
import software.amazon.awssdk.services.dynamodb.model.ContributorInsightsStatus;
import software.amazon.awssdk.services.dynamodb.model.ContributorInsightsSummary;
import software.amazon.awssdk.services.dynamodb.model.DescribeContributorInsightsResponse;
import software.amazon.awssdk.services.dynamodb.model.UpdateContributorInsightsResponse;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static io.github.hectorvent.floci.testing.DynamoDbRequests.ddb;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class DynamoDbContributorInsightsIntegrationTest {

    private static final String TABLE_ARN_PREFIX = "arn:aws:dynamodb:us-east-1:000000000000:table/";

    @TestHTTPResource
    URI endpoint;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void enablingATableCreatesItsRulesAndDisablingRemovesThem() {
        String table = "CiIT-table";
        createTable(table, true, "");
        describe(table, null)
                .statusCode(200)
                .body("keySet()", containsInAnyOrder("TableName", "ContributorInsightsStatus"))
                .body("TableName", equalTo(table))
                .body("ContributorInsightsStatus", equalTo("DISABLED"));

        long before = System.currentTimeMillis();
        update(table, null, "ENABLE", null)
                .statusCode(200)
                .body("keySet()", containsInAnyOrder("TableName", "ContributorInsightsStatus", "ContributorInsightsMode"))
                .body("TableName", equalTo(table))
                .body("ContributorInsightsStatus", equalTo("ENABLING"))
                .body("ContributorInsightsMode", equalTo("ACCESSED_AND_THROTTLED_KEYS"));
        long after = System.currentTimeMillis();

        ValidatableResponse enabled = describe(table, null)
                .statusCode(200)
                .body("ContributorInsightsStatus", equalTo("ENABLED"))
                .body("ContributorInsightsMode", equalTo("ACCESSED_AND_THROTTLED_KEYS"))
                .body("LastUpdateDateTime", notNullValue())
                .body("IndexName", nullValue());
        List<String> rules = enabled.extract().jsonPath().getList("ContributorInsightsRuleList");
        assertEquals(4, rules.size(), rules.toString());
        long created = ruleTimestamp(rules.getFirst());
        assertTrue(before <= created && created <= after, created + " not in [" + before + ", " + after + "]");
        assertEquals(List.of(
                "DynamoDBContributorInsights-PKC-" + table + "-" + created,
                "DynamoDBContributorInsights-SKC-" + table + "-" + created,
                "DynamoDBContributorInsights-PKT-" + table + "-" + created,
                "DynamoDBContributorInsights-SKT-" + table + "-" + created), rules);

        update(table, null, "DISABLE", null)
                .statusCode(200)
                .body("ContributorInsightsStatus", equalTo("DISABLING"))
                .body("ContributorInsightsMode", nullValue());
        describe(table, null)
                .statusCode(200)
                .body("keySet()", containsInAnyOrder("TableName", "ContributorInsightsStatus", "LastUpdateDateTime"))
                .body("ContributorInsightsStatus", equalTo("DISABLED"));
    }

    @Test
    void switchingTheModeKeepsTheRulesAndReEnablingCreatesThemAgain() {
        String table = "CiIT-mode";
        createTable(table, true, "");
        update(table, null, "ENABLE", null).statusCode(200);
        long created = ruleTimestamp(rulesOf(table, null).getFirst());

        update(table, null, "ENABLE", "THROTTLED_KEYS")
                .statusCode(200)
                .body("ContributorInsightsMode", equalTo("THROTTLED_KEYS"));
        assertEquals(List.of(
                "DynamoDBContributorInsights-PKT-" + table + "-" + created,
                "DynamoDBContributorInsights-SKT-" + table + "-" + created), rulesOf(table, null));

        update(table, null, "ENABLE", null)
                .statusCode(200)
                .body("ContributorInsightsMode", equalTo("THROTTLED_KEYS"));

        update(table, null, "DISABLE", null).statusCode(200);
        update(table, null, "ENABLE", null)
                .statusCode(200)
                .body("ContributorInsightsMode", equalTo("ACCESSED_AND_THROTTLED_KEYS"));
        List<String> recreated = rulesOf(table, null);
        assertEquals(4, recreated.size(), recreated.toString());
        assertTrue(ruleTimestamp(recreated.getFirst()) > created, recreated.toString());
    }

    @Test
    void aGlobalSecondaryIndexHasItsOwnSetting() {
        String table = "CiIT-index";
        createTable(table, false, """
                , "GlobalSecondaryIndexes": [{"IndexName": "byOwner",
                  "KeySchema": [{"AttributeName": "owner", "KeyType": "HASH"}],
                  "Projection": {"ProjectionType": "ALL"}}]
                """);
        update(table, "byOwner", "ENABLE", "ACCESSED_AND_THROTTLED_KEYS")
                .statusCode(200)
                .body("TableName", equalTo(table))
                .body("IndexName", equalTo("byOwner"))
                .body("ContributorInsightsStatus", equalTo("ENABLING"));

        List<String> rules = rulesOf(table, "byOwner");
        long created = ruleTimestamp(rules.getFirst());
        assertEquals(List.of(
                "DynamoDBContributorInsights-PKC-" + table + "-byOwner-" + created,
                "DynamoDBContributorInsights-PKT-" + table + "-byOwner-" + created), rules);
        describe(table, "byOwner").statusCode(200).body("IndexName", equalTo("byOwner"));
        describe(table, null).statusCode(200).body("ContributorInsightsStatus", equalTo("DISABLED"));

        ddb("ListContributorInsights", "{\"TableName\": \"" + table + "\"}")
                .statusCode(200)
                .body("ContributorInsightsSummaries", hasSize(1))
                .body("ContributorInsightsSummaries[0].TableName", equalTo(table))
                .body("ContributorInsightsSummaries[0].IndexName", equalTo("byOwner"))
                .body("ContributorInsightsSummaries[0].ContributorInsightsStatus", equalTo("ENABLED"))
                .body("ContributorInsightsSummaries[0].ContributorInsightsMode", equalTo("ACCESSED_AND_THROTTLED_KEYS"))
                .body("NextToken", nullValue());

        ddb("UpdateTable", """
                {"TableName": "%s", "GlobalSecondaryIndexUpdates": [{"Delete": {"IndexName": "byOwner"}}]}
                """.formatted(table)).statusCode(200);
        expectError(describe(table, "byOwner"), "ResourceNotFoundException",
                "Requested resource not found: Index: byOwner not found for table: " + table);
    }

    @Test
    void deletingTheTableDropsTheSetting() {
        String table = "CiIT-recreate";
        createTable(table, false, "");
        update(table, null, "ENABLE", null).statusCode(200);
        ddb("DeleteTable", "{\"TableName\": \"" + table + "\"}").statusCode(200);
        createTable(table, false, "");
        describe(table, null)
                .statusCode(200)
                .body("keySet()", containsInAnyOrder("TableName", "ContributorInsightsStatus"))
                .body("ContributorInsightsStatus", equalTo("DISABLED"));
    }

    @Test
    void tableArnNamesTheTable() {
        String table = "CiIT-arn";
        createTable(table, false, "");
        update(TABLE_ARN_PREFIX + table, null, "ENABLE", null)
                .statusCode(200)
                .body("TableName", equalTo(table));
        describe(TABLE_ARN_PREFIX + table, null)
                .statusCode(200)
                .body("TableName", equalTo(table))
                .body("ContributorInsightsStatus", equalTo("ENABLED"));
    }

    @Test
    void listPagesTheEnabledTablesAndIndexesInArnOrder() {
        List<String> enabled = List.of("CiIT-list-a", "CiIT-list-b", "CiIT-list-c");
        for (String table : enabled) {
            createTable(table, false, "");
            update(table, null, "ENABLE", null).statusCode(200);
        }
        createTable("CiIT-list-off", false, "");
        update("CiIT-list-off", null, "ENABLE", null).statusCode(200);
        update("CiIT-list-off", null, "DISABLE", null).statusCode(200);

        List<String> listed = new ArrayList<>();
        String nextToken = null;
        do {
            String body = nextToken == null
                    ? "{\"MaxResults\": 2}"
                    : "{\"MaxResults\": 2, \"NextToken\": \"" + nextToken + "\"}";
            ValidatableResponse page = ddb("ListContributorInsights", body)
                    .statusCode(200)
                    .body("ContributorInsightsSummaries.size()", lessThanOrEqualTo(2))
                    .body("ContributorInsightsSummaries.ContributorInsightsStatus", everyItem(equalTo("ENABLED")));
            listed.addAll(page.extract().jsonPath().getList("ContributorInsightsSummaries.TableName"));
            nextToken = page.extract().jsonPath().getString("NextToken");
        } while (nextToken != null);

        List<String> ours = listed.stream().filter(name -> name.startsWith("CiIT-list-")).toList();
        assertEquals(enabled, ours);
        assertEquals(listed.size(), listed.stream().distinct().count(), listed.toString());
    }

    @Test
    void requestsAreValidatedAsTheApiReferenceDescribes() {
        String table = "CiIT-errors";
        createTable(table, true, """
                , "LocalSecondaryIndexes": [{"IndexName": "byOwnerLocal",
                  "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"},
                                {"AttributeName": "owner", "KeyType": "RANGE"}],
                  "Projection": {"ProjectionType": "ALL"}}]
                """);
        expectError(ddb("UpdateContributorInsights", "{\"ContributorInsightsAction\": \"ENABLE\"}"),
                "ValidationException", "The parameter 'TableName' is required but was not present in the request");
        expectError(ddb("UpdateContributorInsights", "{\"TableName\": \"" + table + "\"}"), "ValidationException",
                "1 validation error detected: Value null at 'contributorInsightsAction' failed to satisfy "
                + "constraint: Member must not be null");
        expectError(update(table, null, "PAUSE", "ALL_KEYS"), "ValidationException",
                "2 validation errors detected: Value 'PAUSE' at 'contributorInsightsAction' failed to satisfy "
                + "constraint: Member must satisfy enum value set: [ENABLE, DISABLE]; Value 'ALL_KEYS' at "
                + "'contributorInsightsMode' failed to satisfy constraint: Member must satisfy enum value set: "
                + "[THROTTLED_KEYS, ACCESSED_AND_THROTTLED_KEYS]");
        expectError(update(table, "ab", "ENABLE", null), "ValidationException",
                "1 validation error detected: Value 'ab' at 'indexName' failed to satisfy constraint: "
                + "Member must have length greater than or equal to 3");
        expectError(update("CiIT-missing", null, "ENABLE", null), "ResourceNotFoundException",
                "Requested resource not found: Table: CiIT-missing not found");
        expectError(update(table, "nope", "ENABLE", null), "ResourceNotFoundException",
                "Requested resource not found: Index: nope not found for table: " + table);
        expectError(describe(table, "byOwnerLocal"), "ResourceNotFoundException",
                "Requested resource not found: Index: byOwnerLocal not found for table: " + table);
        expectError(ddb("DescribeContributorInsights", "{}"), "ValidationException",
                "The parameter 'TableName' is required but was not present in the request");
        expectError(describe("CiIT-missing", null), "ResourceNotFoundException",
                "Requested resource not found: Table: CiIT-missing not found");
        expectError(ddb("ListContributorInsights", "{\"TableName\": \"CiIT-missing\"}"),
                "ResourceNotFoundException", "Requested resource not found: Table: CiIT-missing not found");
        expectError(ddb("ListContributorInsights", "{\"MaxResults\": 101}"), "ValidationException",
                "1 validation error detected: Value '101' at 'maxResults' failed to satisfy constraint: "
                + "Member must have value less than or equal to 100");
        expectError(ddb("ListContributorInsights", "{\"NextToken\": \"garbage\"}"), "ValidationException",
                "The provided next token is invalid");
    }

    @Test
    void awsSdkReadsEveryResponse() {
        String table = "CiIT-sdk";
        createTable(table, true, "");
        try (DynamoDbClient client = DynamoDbClient.builder().endpointOverride(endpoint).region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build()) {
            UpdateContributorInsightsResponse updated = client.updateContributorInsights(b -> b.tableName(table)
                    .contributorInsightsAction(ContributorInsightsAction.ENABLE)
                    .contributorInsightsMode(ContributorInsightsMode.THROTTLED_KEYS));
            assertEquals(ContributorInsightsStatus.ENABLING, updated.contributorInsightsStatus());
            assertEquals(ContributorInsightsMode.THROTTLED_KEYS, updated.contributorInsightsMode());

            DescribeContributorInsightsResponse described = client.describeContributorInsights(b -> b.tableName(table));
            assertEquals(ContributorInsightsStatus.ENABLED, described.contributorInsightsStatus());
            assertEquals(ContributorInsightsMode.THROTTLED_KEYS, described.contributorInsightsMode());
            assertEquals(2, described.contributorInsightsRuleList().size());
            assertNotNull(described.lastUpdateDateTime());

            List<ContributorInsightsSummary> summaries =
                    client.listContributorInsights(b -> b.tableName(table)).contributorInsightsSummaries();
            assertEquals(1, summaries.size());
            assertEquals(table, summaries.getFirst().tableName());
            assertEquals(ContributorInsightsStatus.ENABLED, summaries.getFirst().contributorInsightsStatus());
        }
    }

    private static ValidatableResponse update(String table, String index, String action, String mode) {
        StringBuilder body = new StringBuilder("{\"TableName\": \"").append(table).append('"');
        if (index != null) {
            body.append(", \"IndexName\": \"").append(index).append('"');
        }
        body.append(", \"ContributorInsightsAction\": \"").append(action).append('"');
        if (mode != null) {
            body.append(", \"ContributorInsightsMode\": \"").append(mode).append('"');
        }
        return ddb("UpdateContributorInsights", body.append('}').toString());
    }

    private static ValidatableResponse describe(String table, String index) {
        String indexMember = index != null ? ", \"IndexName\": \"" + index + "\"" : "";
        return ddb("DescribeContributorInsights", "{\"TableName\": \"" + table + "\"" + indexMember + "}");
    }

    private static List<String> rulesOf(String table, String index) {
        return describe(table, index)
                .statusCode(200)
                .body("ContributorInsightsStatus", equalTo("ENABLED"))
                .extract().jsonPath().getList("ContributorInsightsRuleList");
    }

    private static long ruleTimestamp(String rule) {
        return Long.parseLong(rule.substring(rule.lastIndexOf('-') + 1));
    }

    /** {@code indexes} is appended to the CreateTable body; an index there keys on {@code owner}. */
    private static void createTable(String table, boolean withSortKey, String indexes) {
        String sortKey = withSortKey ? ", {\"AttributeName\": \"sk\", \"KeyType\": \"RANGE\"}" : "";
        String attributes = (withSortKey ? ", {\"AttributeName\": \"sk\", \"AttributeType\": \"S\"}" : "")
                + (indexes.isEmpty() ? "" : ", {\"AttributeName\": \"owner\", \"AttributeType\": \"S\"}");
        ddb("CreateTable", """
                {"TableName": "%s",
                 "KeySchema": [{"AttributeName": "pk", "KeyType": "HASH"}%s],
                 "AttributeDefinitions": [{"AttributeName": "pk", "AttributeType": "S"}%s],
                 "BillingMode": "PAY_PER_REQUEST"%s}
                """.formatted(table, sortKey, attributes, indexes)).statusCode(200);
    }

    private static void expectError(ValidatableResponse response, String type, String message) {
        response.statusCode(400)
                .body("__type", containsString(type))
                .body("message", equalTo(message));
    }
}
