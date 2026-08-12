package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Verifies AddPermission / GetPolicy / RemovePermission, TagResource /
 * UntagResource / ListTags, and the ListLayers / ListLayerVersions stub endpoints.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaPermissionTagLayerIntegrationTest {

    private static final String FN       = "perm-test-fn";
    private static final String FN_ARN   = "arn:aws:lambda:us-east-1:000000000000:function:" + FN;
    private static final String STMT_ID  = "allow-apigw";
    private static final String STMT_ID2 = "allow-s3";

    // ── setup ─────────────────────────────────────────────────────────────────

    private static byte[] minimalZip() throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry("index.js"));
            zos.write("exports.handler = async () => ({});".getBytes());
            zos.closeEntry();
        }
        return baos.toByteArray();
    }

    @Test
    @Order(1)
    void createFunction() throws Exception {
        byte[] zip = minimalZip();
        String zipBase64 = java.util.Base64.getEncoder().encodeToString(zip);

        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Code": { "ZipFile": "%s" }
                }
                """.formatted(FN, zipBase64))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201)
            .body("FunctionName", equalTo(FN));
    }

    // ── AddPermission ─────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void addPermission_returnsStatement() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "StatementId": "%s",
                    "Action": "lambda:InvokeFunction",
                    "Principal": "apigateway.amazonaws.com",
                    "SourceArn": "arn:aws:execute-api:us-east-1:000000000000:api/*"
                }
                """.formatted(STMT_ID))
        .when()
            .post("/2015-03-31/functions/" + FN + "/policy")
        .then()
            .statusCode(201)
            .body("Statement", notNullValue());
    }

    @Test
    @Order(3)
    void addPermission_duplicateStatementId_returns409() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "StatementId": "%s",
                    "Action": "lambda:InvokeFunction",
                    "Principal": "apigateway.amazonaws.com"
                }
                """.formatted(STMT_ID))
        .when()
            .post("/2015-03-31/functions/" + FN + "/policy")
        .then()
            .statusCode(409);
    }

    @Test
    @Order(4)
    void addSecondPermission() {
        given()
            .contentType("application/json")
            .body("""
                {
                    "StatementId": "%s",
                    "Action": "lambda:InvokeFunction",
                    "Principal": "s3.amazonaws.com",
                    "SourceAccount": "000000000000"
                }
                """.formatted(STMT_ID2))
        .when()
            .post("/2015-03-31/functions/" + FN + "/policy")
        .then()
            .statusCode(201);
    }

    // ── GetPolicy ─────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void getPolicy_returnsBothStatements() throws Exception {
        String response = given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/policy")
        .then()
            .statusCode(200)
            .body("Policy", notNullValue())
            .body("RevisionId", notNullValue())
            .extract().body().asString();

        // Policy is a JSON string — parse it and verify statements
        ObjectMapper om = new ObjectMapper();
        String policyJson = om.readTree(response).get("Policy").asText();
        var policy = om.readTree(policyJson);
        assert policy.get("Statement").size() == 2;
    }

    @Test
    @Order(6)
    void getPolicy_nonExistentFunction_returns404() {
        given()
        .when()
            .get("/2015-03-31/functions/does-not-exist/policy")
        .then()
            .statusCode(404);
    }

    // ── RemovePermission ──────────────────────────────────────────────────────

    @Test
    @Order(7)
    void removePermission_removesStatement() {
        given()
        .when()
            .delete("/2015-03-31/functions/" + FN + "/policy/" + STMT_ID)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(8)
    void getPolicy_afterRemove_showsOneStatement() throws Exception {
        String response = given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/policy")
        .then()
            .statusCode(200)
            .extract().body().asString();

        ObjectMapper om = new ObjectMapper();
        String policyJson = om.readTree(response).get("Policy").asText();
        var policy = om.readTree(policyJson);
        assert policy.get("Statement").size() == 1;
    }

    @Test
    @Order(9)
    void removePermission_nonExistentStatement_returns404() {
        given()
        .when()
            .delete("/2015-03-31/functions/" + FN + "/policy/nonexistent-stmt")
        .then()
            .statusCode(404);
    }

    @Test
    @Order(10)
    void removeLastPermission() {
        given()
        .when()
            .delete("/2015-03-31/functions/" + FN + "/policy/" + STMT_ID2)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(11)
    void getPolicy_noStatements_returns404() {
        given()
        .when()
            .get("/2015-03-31/functions/" + FN + "/policy")
        .then()
            .statusCode(404);
    }

    // ── TagResource / UntagResource / ListTags ────────────────────────────────

    @Test
    @Order(12)
    void listTags_initiallyEmpty() {
        given()
        .when()
            .get("/2017-03-31/tags/" + FN_ARN)
        .then()
            .statusCode(200)
            .body("Tags", anEmptyMap());
    }

    @Test
    @Order(13)
    void tagResource_addsTags() {
        given()
            .contentType("application/json")
            .body("""
                {"Tags": {"env": "test", "team": "platform"}}
                """)
        .when()
            .post("/2017-03-31/tags/" + FN_ARN)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(14)
    void listTags_showsAddedTags() {
        given()
        .when()
            .get("/2017-03-31/tags/" + FN_ARN)
        .then()
            .statusCode(200)
            .body("Tags.env", equalTo("test"))
            .body("Tags.team", equalTo("platform"));
    }

    @Test
    @Order(15)
    void untagResource_removesOneTag() {
        given()
            .queryParam("tagKeys", "env")
        .when()
            .delete("/2017-03-31/tags/" + FN_ARN)
        .then()
            .statusCode(204);
    }

    @Test
    @Order(16)
    void listTags_afterUntag_showsRemainingTag() {
        given()
        .when()
            .get("/2017-03-31/tags/" + FN_ARN)
        .then()
            .statusCode(200)
            .body("Tags.env", nullValue())
            .body("Tags.team", equalTo("platform"));
    }

    // ── GetFunction Tags ──────────────────────────────────────────────────────

    @Test
    @Order(17)
    void getFunction_returnsTopLevelTags() {
        // https://github.com/lex00/floci/issues/26 — Tags is a member of the GetFunction
        // response, so a client reads a function's tags back without calling ListTags.
        // Only "team" survives the untag in the preceding test.
        given()
        .when()
            .get("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(200)
            .body("Configuration.FunctionName", equalTo(FN))
            .body("Tags.team", equalTo("platform"))
            .body("Tags.env", nullValue());
    }

    @Test
    @Order(17)
    void getFunction_omitsTagsForAnUntaggedFunction() {
        String untagged = "untagged-get-fn";
        String zipBase64;
        try {
            zipBase64 = java.util.Base64.getEncoder().encodeToString(minimalZip());
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Code": { "ZipFile": "%s" }
                }
                """.formatted(untagged, zipBase64))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        try {
            given()
            .when()
                .get("/2015-03-31/functions/" + untagged)
            .then()
                .statusCode(200)
                .body("$", not(hasKey("Tags")));
        } finally {
            given().when().delete("/2015-03-31/functions/" + untagged).then().statusCode(204);
        }
    }

    @Test
    @Order(17)
    void createFunctionWithTags_thenGetFunctionShowsThem() {
        String taggedFn = "tagged-get-fn";
        String zipBase64;
        try {
            zipBase64 = java.util.Base64.getEncoder().encodeToString(minimalZip());
        } catch (Exception e) {
            throw new AssertionError(e);
        }

        given()
            .contentType("application/json")
            .body("""
                {
                    "FunctionName": "%s",
                    "Runtime": "nodejs20.x",
                    "Role": "arn:aws:iam::000000000000:role/lambda-role",
                    "Handler": "index.handler",
                    "Code": { "ZipFile": "%s" },
                    "Tags": { "tofu-estate": "probe1" }
                }
                """.formatted(taggedFn, zipBase64))
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        try {
            given()
            .when()
                .get("/2015-03-31/functions/" + taggedFn)
            .then()
                .statusCode(200)
                .body("Tags.'tofu-estate'", equalTo("probe1"));

            // ListTags stays the separate read path it always was.
            given()
            .when()
                .get("/2017-03-31/tags/arn:aws:lambda:us-east-1:000000000000:function:" + taggedFn)
            .then()
                .statusCode(200)
                .body("Tags.'tofu-estate'", equalTo("probe1"));
        } finally {
            given().when().delete("/2015-03-31/functions/" + taggedFn).then().statusCode(204);
        }
    }

    // ── ListLayers / ListLayerVersions ────────────────────────────────────────

    @Test
    @Order(17)
    void listLayers_returnsEmptyList() {
        given()
        .when()
            .get("/2018-10-31/layers")
        .then()
            .statusCode(200)
            .body("Layers", empty());
    }

    @Test
    @Order(18)
    void listLayerVersions_returnsEmptyList() {
        given()
        .when()
            .get("/2018-10-31/layers/my-layer/versions")
        .then()
            .statusCode(200)
            .body("LayerVersions", empty());
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void deleteFunction() {
        given()
        .when()
            .delete("/2015-03-31/functions/" + FN)
        .then()
            .statusCode(204);
    }
}
