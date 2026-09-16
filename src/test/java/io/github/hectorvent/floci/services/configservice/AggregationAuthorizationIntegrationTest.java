package io.github.hectorvent.floci.services.configservice;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AggregationAuthorizationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "StarlingDoveService.";
    private static final String AUTHORIZED_ACCOUNT = "111122223333";
    private static final String AUTHORIZED_REGION = "eu-west-1";
    private static final String MATCHING = "AggregationAuthorizations.findAll { it.AuthorizedAccountId == '"
            + AUTHORIZED_ACCOUNT + "' && it.AuthorizedAwsRegion == '" + AUTHORIZED_REGION + "' }";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.response.Response call(String action, String body) {
        return given()
            .header("X-Amz-Target", TARGET_PREFIX + action)
            .contentType(CONTENT_TYPE)
            .body(body)
        .when()
            .post("/");
    }

    private static String authorizationBody() {
        return """
            {
                "AuthorizedAccountId": "%s",
                "AuthorizedAwsRegion": "%s"
            }
            """.formatted(AUTHORIZED_ACCOUNT, AUTHORIZED_REGION);
    }

    @Test
    @Order(1)
    void putAggregationAuthorization() {
        call("PutAggregationAuthorization", """
            {
                "AuthorizedAccountId": "%s",
                "AuthorizedAwsRegion": "%s",
                "Tags": [{"Key": "env", "Value": "prod"}]
            }
            """.formatted(AUTHORIZED_ACCOUNT, AUTHORIZED_REGION))
        .then()
            .statusCode(200)
            .body("AggregationAuthorization.AggregationAuthorizationArn",
                    allOf(startsWith("arn:aws:config:"),
                            endsWith(":aggregation-authorization/" + AUTHORIZED_ACCOUNT + "/" + AUTHORIZED_REGION)))
            .body("AggregationAuthorization.AuthorizedAccountId", equalTo(AUTHORIZED_ACCOUNT))
            .body("AggregationAuthorization.AuthorizedAwsRegion", equalTo(AUTHORIZED_REGION))
            .body("AggregationAuthorization.CreationTime", notNullValue());
    }

    @Test
    @Order(2)
    void putAggregationAuthorizationIsIdempotent() {
        String arn = call("PutAggregationAuthorization", authorizationBody())
                .then().statusCode(200)
                .extract().path("AggregationAuthorization.AggregationAuthorizationArn");

        call("DescribeAggregationAuthorizations", "{}")
        .then()
            .statusCode(200)
            .body(MATCHING, hasSize(1))
            .body(MATCHING + ".AggregationAuthorizationArn", contains(arn));
    }

    @Test
    @Order(3)
    void describeAggregationAuthorizationsListsTheAuthorization() {
        call("DescribeAggregationAuthorizations", "{}")
        .then()
            .statusCode(200)
            .body(MATCHING, hasSize(1))
            .body(MATCHING + "[0].CreationTime", notNullValue());
    }

    @Test
    @Order(4)
    void tagsSuppliedOnPutAreVisibleToListTagsForResource() {
        String arn = call("DescribeAggregationAuthorizations", "{}")
                .then().statusCode(200)
                .extract().<String>path(MATCHING + "[0].AggregationAuthorizationArn");

        call("ListTagsForResource", "{\"ResourceArn\": \"" + arn + "\"}")
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"))
            .body("Tags[0].Value", equalTo("prod"));
    }

    @Test
    @Order(5)
    void rePutIgnoresItsTagsAndTheTaggingApiStillWorks() {
        String arn = call("DescribeAggregationAuthorizations", "{}")
                .then().statusCode(200)
                .extract().<String>path(MATCHING + "[0].AggregationAuthorizationArn");
        String listTags = "{\"ResourceArn\": \"" + arn + "\"}";

        call("PutAggregationAuthorization", """
            {
                "AuthorizedAccountId": "%s",
                "AuthorizedAwsRegion": "%s",
                "Tags": [{"Key": "env", "Value": "dev"}, {"Key": "tier", "Value": "gold"}]
            }
            """.formatted(AUTHORIZED_ACCOUNT, AUTHORIZED_REGION))
        .then()
            .statusCode(200);

        call("ListTagsForResource", listTags)
        .then()
            .statusCode(200)
            .body("Tags", hasSize(1))
            .body("Tags[0].Key", equalTo("env"))
            .body("Tags[0].Value", equalTo("prod"));

        call("TagResource", """
            {"ResourceArn": "%s", "Tags": [{"Key": "tier", "Value": "gold"}]}
            """.formatted(arn))
        .then()
            .statusCode(200);

        call("ListTagsForResource", listTags)
        .then()
            .statusCode(200)
            .body("Tags.Key", containsInAnyOrder("env", "tier"));

        call("UntagResource", """
            {"ResourceArn": "%s", "TagKeys": ["tier"]}
            """.formatted(arn))
        .then()
            .statusCode(200);

        call("ListTagsForResource", listTags)
        .then()
            .statusCode(200)
            .body("Tags.Key", contains("env"));
    }

    @Test
    @Order(6)
    void deleteAggregationAuthorization() {
        call("DeleteAggregationAuthorization", authorizationBody())
        .then()
            .statusCode(200);

        call("DescribeAggregationAuthorizations", "{}")
        .then()
            .statusCode(200)
            .body(MATCHING, empty());
    }

    @Test
    @Order(7)
    void deleteNonexistentAggregationAuthorizationSucceeds() {
        call("DeleteAggregationAuthorization", authorizationBody())
        .then()
            .statusCode(200);
    }

    @Test
    @Order(8)
    void putRejectsAnAccountIdThatIsNotTwelveDigits() {
        call("PutAggregationAuthorization", """
            {"AuthorizedAccountId": "12345", "AuthorizedAwsRegion": "eu-west-1"}
            """)
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(9)
    void unrelatedActionsStillReportInvalidAction() {
        call("PutAggregationAuthorizations", authorizationBody())
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidAction"));
    }
}
