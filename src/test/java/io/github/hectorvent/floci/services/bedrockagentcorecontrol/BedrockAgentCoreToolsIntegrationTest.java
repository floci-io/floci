package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * HTTP-level coverage for {@link BedrockAgentCoreToolsController}: browsers, browser profiles and
 * code interpreters (custom and system) across create/get/list/delete, plus their validation and
 * not-found error paths.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreToolsIntegrationTest {

    // Tool names must match [a-zA-Z][a-zA-Z0-9_]{0,47}, so the random suffix is alphanumeric only.
    private static final String SUFFIX = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    private static final String BROWSER_NAME = "toolsBrowser" + SUFFIX;
    private static final String PROFILE_NAME = "toolsProfile" + SUFFIX;
    private static final String INTERPRETER_NAME = "toolsInterp" + SUFFIX;

    private static final String VALID_CLIENT_TOKEN = "b".repeat(40);
    private static final String UNKNOWN_ID = "nosuch-ABCDEFGHIJ";

    private static String browserId;
    private static String profileId;
    private static String codeInterpreterId;

    // ---------------------------------------------------------------------------------------------
    // Browsers
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(1)
    void createBrowser() {
        browserId = given()
                .contentType("application/json")
                .body("""
                        {"name":"%s","description":"custom browser",
                         "networkConfiguration":{"networkMode":"PUBLIC"},
                         "executionRoleArn":"arn:aws:iam::000000000000:role/browser",
                         "tags":{"env":"test"}}""".formatted(BROWSER_NAME))
                .when()
                .put("/browsers")
                .then()
                .statusCode(202)
                .body("browserId", startsWith(BROWSER_NAME + "-"))
                .body("browserArn", containsString(":browser-custom/"))
                .body("status", equalTo("READY"))
                .body("createdAt", notNullValue())
                // The create response is a projection: no echo of the request fields.
                .body("name", nullValue())
                .body("tags", nullValue())
                .extract()
                .path("browserId");
    }

    @Test
    @Order(2)
    void getBrowser() {
        given()
                .when()
                .get("/browsers/" + browserId)
                .then()
                .statusCode(200)
                .body("browserId", equalTo(browserId))
                .body("name", equalTo(BROWSER_NAME))
                .body("description", equalTo("custom browser"))
                .body("browserArn", containsString(":browser-custom/" + browserId))
                .body("networkConfiguration.networkMode", equalTo("PUBLIC"))
                .body("executionRoleArn", equalTo("arn:aws:iam::000000000000:role/browser"))
                .body("status", equalTo("READY"))
                .body("createdAt", notNullValue())
                .body("lastUpdatedAt", notNullValue())
                // The controller strips clientToken and tags from the get response.
                .body("clientToken", nullValue())
                .body("tags", nullValue());
    }

    @Test
    @Order(3)
    void getSystemBrowser() {
        given()
                .when()
                .get("/browsers/aws.browser.v1")
                .then()
                .statusCode(200)
                .body("browserId", equalTo("aws.browser.v1"))
                .body("name", equalTo("aws.browser.v1"))
                .body("browserArn", containsString(":browser/aws.browser.v1"))
                .body("networkConfiguration.networkMode", equalTo("PUBLIC"))
                .body("status", equalTo("READY"));
    }

    @Test
    @Order(4)
    void listBrowsersByType() {
        // Default (no type) returns the system browser and the custom one.
        given()
                .contentType("application/json")
                .when()
                .post("/browsers")
                .then()
                .statusCode(200)
                .body("browserSummaries.browserId", hasItem(browserId))
                .body("browserSummaries.browserId", hasItem("aws.browser.v1"));

        given()
                .contentType("application/json")
                .queryParam("type", "CUSTOM")
                .when()
                .post("/browsers")
                .then()
                .statusCode(200)
                .body("browserSummaries.browserId", hasItem(browserId))
                .body("browserSummaries.browserId", not(hasItem("aws.browser.v1")))
                .body("browserSummaries.find { it.browserId == '" + browserId + "' }.name", equalTo(BROWSER_NAME))
                .body("browserSummaries.find { it.browserId == '" + browserId + "' }.status", equalTo("READY"))
                // Summaries are a projection too: no networkConfiguration or tags.
                .body("browserSummaries.find { it.browserId == '" + browserId + "' }.networkConfiguration",
                        nullValue())
                .body("browserSummaries.find { it.browserId == '" + browserId + "' }.tags", nullValue());

        given()
                .contentType("application/json")
                .queryParam("type", "SYSTEM")
                .when()
                .post("/browsers")
                .then()
                .statusCode(200)
                .body("browserSummaries.browserId", hasItem("aws.browser.v1"))
                .body("browserSummaries.browserId", not(hasItem(browserId)));
    }

    @Test
    @Order(5)
    void listBrowsersRejectsInvalidTypeAndMaxResults() {
        given()
                .contentType("application/json")
                .queryParam("type", "OTHER")
                .when()
                .post("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("type must be SYSTEM or CUSTOM"));

        given()
                .contentType("application/json")
                .queryParam("maxResults", 0)
                .when()
                .post("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("maxResults must be between 1 and 100"));

        given()
                .contentType("application/json")
                .queryParam("maxResults", "abc")
                .when()
                .post("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("maxResults must be an integer."));
    }

    @Test
    @Order(6)
    void createBrowserValidation() {
        given()
                .contentType("application/json")
                .body("""
                        {"networkConfiguration":{"networkMode":"PUBLIC"}}""")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("name is required"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"has-hyphen","networkConfiguration":{"networkMode":"PUBLIC"}}""")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("name must match [a-zA-Z][a-zA-Z0-9_]{0,47}"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"noNetwork"}""")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("networkConfiguration is required"));

        // SANDBOX is a code-interpreter-only network mode.
        given()
                .contentType("application/json")
                .body("""
                        {"name":"sandboxBrowser","networkConfiguration":{"networkMode":"SANDBOX"}}""")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("networkMode must be PUBLIC or VPC"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"vpcBrowser","networkConfiguration":{"networkMode":"VPC"}}""")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("vpcConfig is required when networkMode is VPC"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"badRole","networkConfiguration":{"networkMode":"PUBLIC"},
                         "executionRoleArn":"not-an-arn"}""")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("executionRoleArn is invalid"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"badToken","networkConfiguration":{"networkMode":"PUBLIC"},
                         "clientToken":"too-short"}""")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("clientToken does not satisfy length or pattern constraints"));

        // A JSON array is not a valid request body.
        given()
                .contentType("application/json")
                .body("[]")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("request body must be a JSON object"));
    }

    @Test
    @Order(7)
    void createBrowserWithDuplicateNameConflicts() {
        given()
                .contentType("application/json")
                .body("""
                        {"name":"%s","networkConfiguration":{"networkMode":"PUBLIC"}}""".formatted(BROWSER_NAME))
                .when()
                .put("/browsers")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"))
                .body("message", equalTo("Browser already exists: " + BROWSER_NAME));
    }

    @Test
    @Order(8)
    void createBrowserWithVpcConfigAndClientTokenIsIdempotent() {
        String name = "vpcBrowser" + SUFFIX;
        String body = """
                {"name":"%s","clientToken":"%s",
                 "networkConfiguration":{"networkMode":"VPC",
                   "vpcConfig":{"securityGroups":["sg-0123456789"],"subnets":["subnet-0123456789"]}}}"""
                .formatted(name, VALID_CLIENT_TOKEN);

        String firstId = given()
                .contentType("application/json")
                .body(body)
                .when()
                .put("/browsers")
                .then()
                .statusCode(202)
                .body("browserId", startsWith(name + "-"))
                .extract()
                .path("browserId");

        // Same clientToken replays the original browser rather than creating a second one.
        given()
                .contentType("application/json")
                .body(body)
                .when()
                .put("/browsers")
                .then()
                .statusCode(202)
                .body("browserId", equalTo(firstId));

        given()
                .when()
                .get("/browsers/" + firstId)
                .then()
                .statusCode(200)
                .body("networkConfiguration.networkMode", equalTo("VPC"))
                .body("networkConfiguration.vpcConfig.subnets", hasItem("subnet-0123456789"))
                .body("clientToken", nullValue());

        given()
                .when()
                .delete("/browsers/" + firstId)
                .then()
                .statusCode(202);
    }

    @Test
    @Order(9)
    void createBrowserWithInvalidVpcIdsIsRejected() {
        given()
                .contentType("application/json")
                .body("""
                        {"name":"badVpc","networkConfiguration":{"networkMode":"VPC",
                           "vpcConfig":{"securityGroups":["sg-0123456789"],"subnets":["nope"]}}}""")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("subnets contains an invalid identifier"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"badVpc","networkConfiguration":{"networkMode":"VPC",
                           "vpcConfig":{"subnets":["subnet-0123456789"]}}}""")
                .when()
                .put("/browsers")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("securityGroups must contain between 1 and 16 items"));
    }

    @Test
    @Order(10)
    void getBrowserErrorPaths() {
        given()
                .when()
                .get("/browsers/not-a-valid-id")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("browserId does not satisfy the required pattern"));

        given()
                .when()
                .get("/browsers/" + UNKNOWN_ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Browser not found: " + UNKNOWN_ID));
    }

    @Test
    @Order(11)
    void deleteBrowserErrorPaths() {
        given()
                .when()
                .delete("/browsers/aws.browser.v1")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("System browser cannot be deleted"));

        given()
                .queryParam("clientToken", "too-short")
                .when()
                .delete("/browsers/" + browserId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("clientToken does not satisfy length or pattern constraints"));

        given()
                .when()
                .delete("/browsers/" + UNKNOWN_ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Browser not found: " + UNKNOWN_ID));
    }

    @Test
    @Order(12)
    void deleteBrowserWithClientTokenIsReplayable() {
        String clientToken = "c".repeat(40);

        given()
                .queryParam("clientToken", clientToken)
                .when()
                .delete("/browsers/" + browserId)
                .then()
                .statusCode(202)
                .body("browserId", equalTo(browserId))
                .body("status", equalTo("DELETING"))
                .body("lastUpdatedAt", notNullValue());

        // Replaying the same clientToken after the browser is gone still returns the delete response.
        given()
                .queryParam("clientToken", clientToken)
                .when()
                .delete("/browsers/" + browserId)
                .then()
                .statusCode(202)
                .body("browserId", equalTo(browserId))
                .body("status", equalTo("DELETING"));

        given()
                .when()
                .get("/browsers/" + browserId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .queryParam("type", "CUSTOM")
                .when()
                .post("/browsers")
                .then()
                .statusCode(200)
                .body("browserSummaries.browserId", not(hasItem(browserId)));
    }

    // ---------------------------------------------------------------------------------------------
    // Browser profiles
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(20)
    void createBrowserProfile() {
        profileId = given()
                .contentType("application/json")
                .body("""
                        {"name":"%s","description":"profile","tags":{"env":"test"}}""".formatted(PROFILE_NAME))
                .when()
                .put("/browser-profiles")
                .then()
                // Unlike browsers and code interpreters, CreateBrowserProfile is synchronous (200).
                .statusCode(200)
                .body("profileId", startsWith(PROFILE_NAME + "-"))
                .body("profileArn", containsString(":browser-profile/"))
                .body("status", equalTo("READY"))
                .body("createdAt", notNullValue())
                .body("name", nullValue())
                .extract()
                .path("profileId");
    }

    @Test
    @Order(21)
    void getBrowserProfile() {
        given()
                .when()
                .get("/browser-profiles/" + profileId)
                .then()
                .statusCode(200)
                .body("profileId", equalTo(profileId))
                .body("profileArn", containsString(":browser-profile/" + profileId))
                .body("name", equalTo(PROFILE_NAME))
                .body("description", equalTo("profile"))
                .body("status", equalTo("READY"))
                .body("createdAt", notNullValue())
                .body("lastUpdatedAt", notNullValue())
                .body("clientToken", nullValue())
                .body("tags", nullValue());
    }

    @Test
    @Order(22)
    void listBrowserProfilesFiltersByName() {
        given()
                .contentType("application/json")
                .when()
                .post("/browser-profiles")
                .then()
                .statusCode(200)
                .body("profileSummaries.profileId", hasItem(profileId))
                .body("profileSummaries.find { it.profileId == '" + profileId + "' }.name", equalTo(PROFILE_NAME))
                .body("profileSummaries.find { it.profileId == '" + profileId + "' }.description",
                        equalTo("profile"))
                .body("profileSummaries.find { it.profileId == '" + profileId + "' }.tags", nullValue());

        // The name filter travels in the request body, not the query string.
        given()
                .contentType("application/json")
                .body("""
                        {"name":"%s"}""".formatted(PROFILE_NAME))
                .when()
                .post("/browser-profiles")
                .then()
                .statusCode(200)
                .body("profileSummaries.profileId", hasItem(profileId))
                .body("profileSummaries.size()", equalTo(1));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"noSuchProfile"}""")
                .when()
                .post("/browser-profiles")
                .then()
                .statusCode(200)
                .body("profileSummaries", empty());
    }

    @Test
    @Order(23)
    void listBrowserProfilesValidation() {
        given()
                .contentType("application/json")
                .body("""
                        {"name":"bad-name"}""")
                .when()
                .post("/browser-profiles")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("name must match [a-zA-Z][a-zA-Z0-9_]{0,47}"));

        given()
                .contentType("application/json")
                .queryParam("maxResults", 101)
                .when()
                .post("/browser-profiles")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("maxResults must be between 1 and 100"));
    }

    @Test
    @Order(24)
    void createBrowserProfileValidation() {
        given()
                .contentType("application/json")
                .body("{}")
                .when()
                .put("/browser-profiles")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("name is required"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"1startsWithDigit"}""")
                .when()
                .put("/browser-profiles")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("name must match [a-zA-Z][a-zA-Z0-9_]{0,47}"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"emptyDescription","description":""}""")
                .when()
                .put("/browser-profiles")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("description must be between 1 and 4096 characters"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"badTags","tags":{"env":123}}""")
                .when()
                .put("/browser-profiles")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("tag value must be a string"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"%s"}""".formatted(PROFILE_NAME))
                .when()
                .put("/browser-profiles")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"))
                .body("message", equalTo("Browser profile already exists: " + PROFILE_NAME));
    }

    @Test
    @Order(25)
    void createBrowserProfileWithClientTokenIsIdempotent() {
        String name = "tokenProfile" + SUFFIX;
        String body = """
                {"name":"%s","clientToken":"%s"}""".formatted(name, VALID_CLIENT_TOKEN);

        String firstId = given()
                .contentType("application/json")
                .body(body)
                .when()
                .put("/browser-profiles")
                .then()
                .statusCode(200)
                .extract()
                .path("profileId");

        given()
                .contentType("application/json")
                .body(body)
                .when()
                .put("/browser-profiles")
                .then()
                .statusCode(200)
                .body("profileId", equalTo(firstId));

        given()
                .when()
                .delete("/browser-profiles/" + firstId)
                .then()
                .statusCode(200);
    }

    @Test
    @Order(26)
    void browserProfileErrorPaths() {
        given()
                .when()
                .get("/browser-profiles/not-a-valid-id")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("profileId does not satisfy the required pattern"));

        given()
                .when()
                .get("/browser-profiles/" + UNKNOWN_ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Browser profile not found: " + UNKNOWN_ID));

        given()
                .when()
                .delete("/browser-profiles/" + UNKNOWN_ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Browser profile not found: " + UNKNOWN_ID));

        given()
                .queryParam("clientToken", "too-short")
                .when()
                .delete("/browser-profiles/" + profileId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("clientToken does not satisfy length or pattern constraints"));
    }

    @Test
    @Order(27)
    void deleteBrowserProfileWithClientTokenIsReplayable() {
        String clientToken = "d".repeat(40);

        given()
                .queryParam("clientToken", clientToken)
                .when()
                .delete("/browser-profiles/" + profileId)
                .then()
                .statusCode(200)
                .body("profileId", equalTo(profileId))
                .body("profileArn", containsString(":browser-profile/" + profileId))
                .body("status", equalTo("DELETING"))
                .body("lastUpdatedAt", notNullValue());

        given()
                .queryParam("clientToken", clientToken)
                .when()
                .delete("/browser-profiles/" + profileId)
                .then()
                .statusCode(200)
                .body("profileId", equalTo(profileId))
                .body("status", equalTo("DELETING"));

        given()
                .when()
                .get("/browser-profiles/" + profileId)
                .then()
                .statusCode(404);

        given()
                .contentType("application/json")
                .when()
                .post("/browser-profiles")
                .then()
                .statusCode(200)
                .body("profileSummaries.profileId", not(hasItem(profileId)));
    }

    // ---------------------------------------------------------------------------------------------
    // Code interpreters
    // ---------------------------------------------------------------------------------------------

    @Test
    @Order(30)
    void createCodeInterpreter() {
        codeInterpreterId = given()
                .contentType("application/json")
                .body("""
                        {"name":"%s","description":"custom interpreter",
                         "networkConfiguration":{"networkMode":"SANDBOX"},
                         "tags":{"env":"test"}}""".formatted(INTERPRETER_NAME))
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(202)
                .body("codeInterpreterId", startsWith(INTERPRETER_NAME + "-"))
                .body("codeInterpreterArn", containsString(":code-interpreter-custom/"))
                .body("status", equalTo("READY"))
                .body("createdAt", notNullValue())
                .body("name", nullValue())
                .extract()
                .path("codeInterpreterId");
    }

    @Test
    @Order(31)
    void getCodeInterpreter() {
        given()
                .when()
                .get("/code-interpreters/" + codeInterpreterId)
                .then()
                .statusCode(200)
                .body("codeInterpreterId", equalTo(codeInterpreterId))
                .body("codeInterpreterArn", containsString(":code-interpreter-custom/" + codeInterpreterId))
                .body("name", equalTo(INTERPRETER_NAME))
                .body("description", equalTo("custom interpreter"))
                .body("networkConfiguration.networkMode", equalTo("SANDBOX"))
                .body("status", equalTo("READY"))
                .body("createdAt", notNullValue())
                .body("lastUpdatedAt", notNullValue())
                .body("clientToken", nullValue())
                .body("tags", nullValue());
    }

    @Test
    @Order(32)
    void getSystemCodeInterpreter() {
        given()
                .when()
                .get("/code-interpreters/aws.codeinterpreter.v1")
                .then()
                .statusCode(200)
                .body("codeInterpreterId", equalTo("aws.codeinterpreter.v1"))
                .body("name", equalTo("aws.codeinterpreter.v1"))
                .body("codeInterpreterArn", containsString(":aws:code-interpreter/aws.codeinterpreter.v1"))
                .body("networkConfiguration.networkMode", equalTo("SANDBOX"))
                .body("status", equalTo("READY"));
    }

    @Test
    @Order(33)
    void listCodeInterpretersByType() {
        given()
                .contentType("application/json")
                .when()
                .post("/code-interpreters")
                .then()
                .statusCode(200)
                .body("codeInterpreterSummaries.codeInterpreterId", hasItem(codeInterpreterId))
                .body("codeInterpreterSummaries.codeInterpreterId", hasItem("aws.codeinterpreter.v1"));

        given()
                .contentType("application/json")
                .queryParam("type", "CUSTOM")
                .when()
                .post("/code-interpreters")
                .then()
                .statusCode(200)
                .body("codeInterpreterSummaries.codeInterpreterId", hasItem(codeInterpreterId))
                .body("codeInterpreterSummaries.codeInterpreterId", not(hasItem("aws.codeinterpreter.v1")))
                .body("codeInterpreterSummaries.find { it.codeInterpreterId == '" + codeInterpreterId + "' }.name",
                        equalTo(INTERPRETER_NAME))
                .body("codeInterpreterSummaries.find { it.codeInterpreterId == '" + codeInterpreterId
                        + "' }.networkConfiguration", nullValue());

        given()
                .contentType("application/json")
                .queryParam("type", "SYSTEM")
                .when()
                .post("/code-interpreters")
                .then()
                .statusCode(200)
                .body("codeInterpreterSummaries.codeInterpreterId", hasItem("aws.codeinterpreter.v1"))
                .body("codeInterpreterSummaries.codeInterpreterId", not(hasItem(codeInterpreterId)));

        given()
                .contentType("application/json")
                .queryParam("type", "OTHER")
                .when()
                .post("/code-interpreters")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("type must be SYSTEM or CUSTOM"));

        given()
                .contentType("application/json")
                .queryParam("maxResults", 0)
                .when()
                .post("/code-interpreters")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("maxResults must be between 1 and 100"));
    }

    @Test
    @Order(34)
    void createCodeInterpreterValidation() {
        given()
                .contentType("application/json")
                .body("""
                        {"networkConfiguration":{"networkMode":"SANDBOX"}}""")
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("name is required"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"noNetwork"}""")
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("networkConfiguration is required"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"noMode","networkConfiguration":{}}""")
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("networkConfiguration.networkMode is required"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"badMode","networkConfiguration":{"networkMode":"PRIVATE"}}""")
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("networkMode must be PUBLIC, SANDBOX, or VPC"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"badTagValue","networkConfiguration":{"networkMode":"PUBLIC"},
                         "tags":{"env":"has*star"}}""")
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("tag value does not satisfy AgentCore constraints"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"tooManyCerts","networkConfiguration":{"networkMode":"PUBLIC"},
                         "certificates":[]}""")
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("certificates must contain between 1 and 200 items"));

        given()
                .contentType("application/json")
                .body("""
                        {"name":"%s","networkConfiguration":{"networkMode":"SANDBOX"}}""".formatted(INTERPRETER_NAME))
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(409)
                .body("__type", equalTo("ConflictException"))
                .body("message", equalTo("Code interpreter already exists: " + INTERPRETER_NAME));
    }

    @Test
    @Order(35)
    void createCodeInterpreterWithClientTokenIsIdempotent() {
        String name = "tokenInterp" + SUFFIX;
        String body = """
                {"name":"%s","clientToken":"%s","networkConfiguration":{"networkMode":"PUBLIC"}}"""
                .formatted(name, VALID_CLIENT_TOKEN);

        String firstId = given()
                .contentType("application/json")
                .body(body)
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(202)
                .extract()
                .path("codeInterpreterId");

        given()
                .contentType("application/json")
                .body(body)
                .when()
                .put("/code-interpreters")
                .then()
                .statusCode(202)
                .body("codeInterpreterId", equalTo(firstId));

        given()
                .when()
                .delete("/code-interpreters/" + firstId)
                .then()
                .statusCode(202);
    }

    @Test
    @Order(36)
    void codeInterpreterErrorPaths() {
        given()
                .when()
                .get("/code-interpreters/not-a-valid-id")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("codeInterpreterId does not satisfy the required pattern"));

        given()
                .when()
                .get("/code-interpreters/" + UNKNOWN_ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Code interpreter not found: " + UNKNOWN_ID));

        given()
                .when()
                .delete("/code-interpreters/" + UNKNOWN_ID)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("message", equalTo("Code interpreter not found: " + UNKNOWN_ID));

        given()
                .when()
                .delete("/code-interpreters/aws.codeinterpreter.v1")
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("System code interpreter cannot be deleted"));

        given()
                .queryParam("clientToken", "too-short")
                .when()
                .delete("/code-interpreters/" + codeInterpreterId)
                .then()
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("clientToken does not satisfy length or pattern constraints"));
    }

    @Test
    @Order(37)
    void deleteCodeInterpreterWithClientTokenIsReplayable() {
        String clientToken = "e".repeat(40);

        given()
                .queryParam("clientToken", clientToken)
                .when()
                .delete("/code-interpreters/" + codeInterpreterId)
                .then()
                .statusCode(202)
                .body("codeInterpreterId", equalTo(codeInterpreterId))
                .body("status", equalTo("DELETING"))
                .body("lastUpdatedAt", notNullValue());

        given()
                .queryParam("clientToken", clientToken)
                .when()
                .delete("/code-interpreters/" + codeInterpreterId)
                .then()
                .statusCode(202)
                .body("codeInterpreterId", equalTo(codeInterpreterId))
                .body("status", equalTo("DELETING"));

        given()
                .when()
                .get("/code-interpreters/" + codeInterpreterId)
                .then()
                .statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"));

        given()
                .contentType("application/json")
                .queryParam("type", "CUSTOM")
                .when()
                .post("/code-interpreters")
                .then()
                .statusCode(200)
                .body("codeInterpreterSummaries.codeInterpreterId", not(hasItem(codeInterpreterId)));
    }
}
