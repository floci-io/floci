package io.github.hectorvent.floci.services.dlm;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class DlmIntegrationTest {

    private static final String ACCOUNT_ID = "723679240095";

    @Test
    void lifecycleRoundTripsThroughRestJsonIncludingTagsAndFilters() {
        Response created = dlm(ACCOUNT_ID, "us-east-1")
                .body("""
                        {"ExecutionRoleArn":"arn:aws:iam::723679240095:role/dlm",
                         "Description":"daily","State":"ENABLED","Tags":{"env":"test"},
                         "PolicyDetails":{"PolicyType":"EBS_SNAPSHOT_MANAGEMENT",
                           "ResourceTypes":["VOLUME"],
                           "TargetTags":[{"Key":"backup","Value":"true"}],
                           "Schedules":[{"Name":"daily","CreateRule":{"Interval":24,"IntervalUnit":"HOURS"},
                             "RetainRule":{"Count":7},"TagsToAdd":[{"Key":"managed","Value":"dlm"}]}]}}
                        """)
        .when()
                .post("/policies")
        .then()
                .statusCode(200)
                .body("PolicyId", matchesPattern("policy-[a-f0-9]+"))
                .extract().response();
        String policyId = created.path("PolicyId");
        String policyArn = "arn:aws:dlm:us-east-1:" + ACCOUNT_ID + ":policy/" + policyId;

        try {
            dlm(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/policies/" + policyId)
            .then()
                    .statusCode(200)
                    .body("Policy.Description", equalTo("daily"))
                    .body("Policy.PolicyArn", equalTo(policyArn))
                    .body("Policy.DateCreated", matchesPattern(".*Z"))
                    .body("Policy.PolicyDetails.Schedules[0].CreateRule.Interval", equalTo(24))
                    .body("Policy.Tags.env", equalTo("test"));

            dlm(ACCOUNT_ID, "us-east-1")
                    .queryParam("state", "ENABLED")
                    .queryParam("resourceTypes", "VOLUME")
                    .queryParam("targetTags", "backup=true")
                    .queryParam("tagsToAdd", "managed=dlm")
            .when()
                    .get("/policies")
            .then()
                    .statusCode(200)
                    .body("Policies", hasSize(1))
                    .body("Policies.PolicyId", hasItem(policyId));

            dlm(ACCOUNT_ID, "us-east-1")
                    .body("{\"Description\":\"weekly\",\"State\":\"DISABLED\"}")
            .when()
                    .patch("/policies/" + policyId)
            .then()
                    .statusCode(200);

            dlm(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/policies/" + policyId)
            .then()
                    .statusCode(200)
                    .body("Policy.Description", equalTo("weekly"))
                    .body("Policy.State", equalTo("DISABLED"))
                    .body("Policy.ExecutionRoleArn", equalTo("arn:aws:iam::723679240095:role/dlm"))
                    .body("Policy.Tags.env", equalTo("test"));

            dlm(ACCOUNT_ID, "us-east-1")
                    .body("{\"Tags\":{\"owner\":\"platform\"}}")
            .when()
                    .post("/tags/" + policyArn)
            .then()
                    .statusCode(200);

            dlm(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/tags/" + policyArn)
            .then()
                    .statusCode(200)
                    .body("Tags.env", equalTo("test"))
                    .body("Tags.owner", equalTo("platform"));

            dlm(ACCOUNT_ID, "us-east-1")
                    .queryParam("tagKeys", "env")
            .when()
                    .delete("/tags/" + policyArn)
            .then()
                    .statusCode(200);

            dlm(ACCOUNT_ID, "us-east-1")
            .when()
                    .get("/tags/" + policyArn)
            .then()
                    .statusCode(200)
                    .body("Tags.env", nullValue())
                    .body("Tags.owner", equalTo("platform"));

            dlm("999999999999", "us-east-1")
            .when()
                    .get("/policies/" + policyId)
            .then()
                    .statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));

            dlm(ACCOUNT_ID, "eu-west-1")
            .when()
                    .get("/policies/" + policyId)
            .then()
                    .statusCode(404)
                    .body("__type", equalTo("ResourceNotFoundException"));
        } finally {
            dlm(ACCOUNT_ID, "us-east-1")
            .when()
                    .delete("/policies/" + policyId)
            .then()
                    .statusCode(200);
        }
    }

    @Test
    void createValidatesRequiredMembersBeforeWriting() {
        dlm(ACCOUNT_ID, "us-east-1")
                .body("{\"Description\":\"daily\",\"State\":\"ENABLED\"}")
        .when()
                .post("/policies")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidRequestException"));
    }

    private static RequestSpecification dlm(String accountId, String region) {
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260101/" + region + "/dlm/aws4_request";
        return given()
                .contentType("application/json")
                .header("Authorization", authorization);
    }
}
