package io.github.hectorvent.floci.services.glue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Glue data quality rulesets: create, read, update, list and delete, the list filters, tags and
 * paging, the errors the model names, and isolation by account and Region. Each test works in an
 * account of its own, so a listing sees only the rulesets that test created.
 */
@QuarkusTest
class GlueDataQualityRulesetIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String REGION = "us-east-1";
    private static final String RULESET = "Rules = [ RowCount > 0, IsComplete \"id\", "
            + "(ColumnValues \"age\" > 0) and (ColumnValues \"age\" < 150) ]";

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRulesetRoundTripsThroughCreateGetUpdateListAndDelete() {
        String account = "000000000071";
        String name = unique("orders");
        call("CreateDataQualityRuleset", account, REGION, """
                {"Name":"%s","Description":"orders checks","Ruleset":%s,
                 "TargetTable":{"TableName":"orders","DatabaseName":"sales"},
                 "Tags":{"team":"data"}}
                """.formatted(name, json(RULESET)))
                .statusCode(200)
                .body("Name", equalTo(name));

        call("GetDataQualityRuleset", account, REGION, "{\"Name\":\"" + name + "\"}")
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("Description", equalTo("orders checks"))
                .body("Ruleset", equalTo(RULESET))
                .body("TargetTable.TableName", equalTo("orders"))
                .body("TargetTable.DatabaseName", equalTo("sales"))
                .body("CreatedOn", instanceOf(Number.class))
                .body("LastModifiedOn", instanceOf(Number.class));

        call("UpdateDataQualityRuleset", account, REGION,
                "{\"Name\":\"" + name + "\",\"Description\":\"orders, nightly\"}")
                .statusCode(200)
                .body("Name", equalTo(name))
                .body("Description", equalTo("orders, nightly"))
                .body("Ruleset", equalTo(RULESET));
        call("UpdateDataQualityRuleset", account, REGION,
                "{\"Name\":\"" + name + "\",\"Ruleset\":\"Rules = [ RowCount > 10 ]\"}")
                .statusCode(200)
                .body("Description", equalTo("orders, nightly"))
                .body("Ruleset", equalTo("Rules = [ RowCount > 10 ]"));

        call("ListDataQualityRulesets", account, REGION, "{}")
                .statusCode(200)
                .body("Rulesets", hasSize(1))
                .body("Rulesets[0].Name", equalTo(name))
                .body("Rulesets[0].Description", equalTo("orders, nightly"))
                .body("Rulesets[0].RuleCount", equalTo(1))
                .body("Rulesets[0].TargetTable.TableName", equalTo("orders"))
                .body("Rulesets[0].CreatedOn", instanceOf(Number.class))
                .body("Rulesets[0].Ruleset", nullValue())
                .body("NextToken", nullValue());

        String arn = "arn:aws:glue:" + REGION + ":" + account + ":dataQualityRuleset/" + name;
        call("GetTags", account, REGION, "{\"ResourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("Tags.team", equalTo("data"));

        call("DeleteDataQualityRuleset", account, REGION, "{\"Name\":\"" + name + "\"}")
                .statusCode(200);
        call("GetDataQualityRuleset", account, REGION, "{\"Name\":\"" + name + "\"}")
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
        call("GetTags", account, REGION, "{\"ResourceArn\":\"" + arn + "\"}")
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));

        create(account, REGION, name, RULESET, null, null);
        call("GetTags", account, REGION, "{\"ResourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("Tags.team", nullValue());
        call("DeleteDataQualityRuleset", account, REGION, "{\"Name\":\"" + name + "\"}")
                .statusCode(200);
    }

    @Test
    void theListCountsTopLevelRulesWithACompositeRuleAsOne() {
        String account = "000000000072";
        String name = unique("composite");
        create(account, REGION, name, "Rules = [ (ColumnValues \"code\" in [1, 2]) or (IsComplete \"id\"), "
                + "RowCount > 0, IsComplete \"name\" ]", null, null);

        call("ListDataQualityRulesets", account, REGION, "{}")
                .statusCode(200)
                .body("Rulesets[0].RuleCount", equalTo(3));
    }

    @Test
    void theListFiltersByTableTagsAndTime() throws Exception {
        String account = "000000000073";
        String first = "a-" + unique("first");
        String second = "b-" + unique("second");
        create(account, REGION, first, RULESET, "{\"TableName\":\"orders\",\"DatabaseName\":\"sales\"}",
                "{\"team\":\"data\"}");
        String firstBody = call("GetDataQualityRuleset", account, REGION, "{\"Name\":\"" + first + "\"}")
                .statusCode(200)
                .extract().asString();
        create(account, REGION, second, RULESET, "{\"TableName\":\"items\",\"DatabaseName\":\"sales\"}",
                "{\"team\":\"ops\"}");

        assertEquals(List.of(first), listNames(account,
                "{\"Filter\":{\"TargetTable\":{\"TableName\":\"orders\",\"DatabaseName\":\"sales\"}}}"));
        assertEquals(List.of(second), listNames(account, "{\"Tags\":{\"team\":\"ops\"}}"));
        assertEquals(List.of(second), listNames(account, "{\"Filter\":{\"Name\":\"" + second + "\"}}"));
        // RestAssured reads JSON numbers as Float, too coarse for epoch seconds.
        double created = MAPPER.readTree(firstBody).get("CreatedOn").asDouble();
        assertEquals(List.of(first, second), listNames(account,
                "{\"Filter\":{\"CreatedAfter\":" + (created - 1) + ",\"CreatedBefore\":" + (created + 3600) + "}}"));
        assertEquals(List.of(), listNames(account, "{\"Filter\":{\"CreatedAfter\":" + (created + 3600) + "}}"));
        assertEquals(List.of(), listNames(account, "{\"Filter\":{\"CreatedBefore\":" + (created - 1) + "}}"));
        assertEquals(List.of(), listNames(account, "{\"Filter\":{\"LastModifiedAfter\":" + (created + 3600) + "}}"));
        assertEquals(List.of(first, second), listNames(account,
                "{\"Filter\":{\"LastModifiedBefore\":" + (created + 3600) + "}}"));
        call("ListDataQualityRulesets", account, REGION,
                "{\"Filter\":{\"CreatedAfter\":\"" + created + "\"}}")
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));

        String token = call("ListDataQualityRulesets", account, REGION, "{\"MaxResults\":1}")
                .statusCode(200)
                .body("Rulesets.Name", contains(first))
                .body("NextToken", notNullValue())
                .extract().path("NextToken");
        call("ListDataQualityRulesets", account, REGION, "{\"MaxResults\":1,\"NextToken\":\"" + token + "\"}")
                .statusCode(200)
                .body("Rulesets.Name", contains(second))
                .body("NextToken", nullValue());
        call("ListDataQualityRulesets", account, REGION, "{\"MaxResults\":0}")
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void theTargetTableFilterMatchesTheCatalogWhenOneIsGiven() {
        String account = "000000000077";
        String own = "a-" + unique("own");
        String shared = "b-" + unique("shared");
        create(account, REGION, own, RULESET, "{\"TableName\":\"orders\",\"DatabaseName\":\"sales\"}", null);
        create(account, REGION, shared, RULESET,
                "{\"TableName\":\"orders\",\"DatabaseName\":\"sales\",\"CatalogId\":\"111111111111\"}", null);

        assertEquals(List.of(own, shared), listNames(account,
                "{\"Filter\":{\"TargetTable\":{\"TableName\":\"orders\",\"DatabaseName\":\"sales\"}}}"));
        assertEquals(List.of(own), listNames(account, "{\"Filter\":{\"TargetTable\":"
                + "{\"TableName\":\"orders\",\"DatabaseName\":\"sales\",\"CatalogId\":\"" + account + "\"}}}"));
        assertEquals(List.of(shared), listNames(account, "{\"Filter\":{\"TargetTable\":"
                + "{\"TableName\":\"orders\",\"DatabaseName\":\"sales\",\"CatalogId\":\"111111111111\"}}}"));
    }

    @Test
    void requestsTheModelRefusesUseItsErrors() {
        String account = "000000000074";
        String name = unique("errors");
        create(account, REGION, name, RULESET, null, null);

        call("CreateDataQualityRuleset", account, REGION,
                "{\"Name\":\"" + name + "\",\"Ruleset\":" + json(RULESET) + "}")
                .statusCode(400)
                .body("__type", equalTo("AlreadyExistsException"));
        call("CreateDataQualityRuleset", account, REGION, "{\"Name\":\"" + unique("no-rules") + "\"}")
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
        call("CreateDataQualityRuleset", account, REGION, "{\"Name\":\"" + unique("no-db") + "\",\"Ruleset\":"
                + json(RULESET) + ",\"TargetTable\":{\"TableName\":\"orders\"}}")
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
        call("CreateDataQualityRuleset", account, REGION,
                "{\"Name\":\"" + "n".repeat(256) + "\",\"Ruleset\":" + json(RULESET) + "}")
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
        call("UpdateDataQualityRuleset", account, REGION, "{\"Name\":\"" + unique("missing") + "\"}")
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
        call("DeleteDataQualityRuleset", account, REGION, "{\"Name\":\"" + unique("missing") + "\"}")
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
        call("UpdateDataQualityRuleset", account, REGION,
                "{\"Name\":\"" + name + "\",\"Ruleset\":\"\"}")
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
        call("GetDataQualityRuleset", account, REGION, "{\"Name\":\"" + name + "\"}")
                .statusCode(200)
                .body("Ruleset", equalTo(RULESET));
    }

    @Test
    void rulesetsAreIsolatedByAccountAndRegion() {
        String name = unique("isolated");
        create("000000000075", REGION, name, RULESET, null, null);

        call("GetDataQualityRuleset", "000000000076", REGION, "{\"Name\":\"" + name + "\"}")
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
        call("GetDataQualityRuleset", "000000000075", "us-west-2", "{\"Name\":\"" + name + "\"}")
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
        assertTrue(listNames("000000000076", "{}").isEmpty());

        create("000000000076", REGION, name, "Rules = [ RowCount > 1 ]", null, null);
        call("GetDataQualityRuleset", "000000000075", REGION, "{\"Name\":\"" + name + "\"}")
                .statusCode(200)
                .body("Ruleset", equalTo(RULESET));
    }

    private static void create(String account, String region, String name, String ruleset, String targetTable,
                               String tags) {
        StringBuilder body = new StringBuilder("{\"Name\":\"" + name + "\",\"Ruleset\":" + json(ruleset));
        if (targetTable != null) {
            body.append(",\"TargetTable\":").append(targetTable);
        }
        if (tags != null) {
            body.append(",\"Tags\":").append(tags);
        }
        call("CreateDataQualityRuleset", account, region, body.append('}').toString()).statusCode(200);
    }

    private static List<String> listNames(String account, String body) {
        return call("ListDataQualityRulesets", account, REGION, body)
                .statusCode(200)
                .extract().jsonPath().getList("Rulesets.Name", String.class);
    }

    private static ValidatableResponse call(String action, String account, String region, String body) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue." + action)
                .header("Authorization", authorization(account, region))
                .body(body)
        .when().post("/")
        .then();
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String authorization(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account
                + "/20260918/" + region + "/glue/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
