package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

/** State-machine aliases resolve to their published version while preserving execution metadata. */
@QuarkusTest
class StepFunctionsAliasesIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String DEF =
            "{\\\"StartAt\\\":\\\"D\\\",\\\"States\\\":{\\\"D\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"Result\\\":1,\\\"End\\\":true}}}";
    private static final String DEF2 =
            "{\\\"StartAt\\\":\\\"D\\\",\\\"States\\\":{\\\"D\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"Result\\\":2,\\\"End\\\":true}}}";

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String target, String body) {
        return given().header("X-Amz-Target", "AWSStepFunctions." + target)
                .contentType(CT).body(body).when().post("/");
    }

    @Test
    void aliasLifecycleResolvesDescribeAndExecutionToPublishedVersions() {
        String name = "alias-test-" + System.currentTimeMillis();
        Response created = call("CreateStateMachine", """
                {"name":"%s","definition":"%s",
                 "roleArn":"arn:aws:iam::000000000000:role/r","publish":true}
                """.formatted(name, DEF));
        String stateMachineArn = created.then().statusCode(200)
                .extract().jsonPath().getString("stateMachineArn");
        String versionOneArn = created.jsonPath().getString("stateMachineVersionArn");

        String versionTwoArn = call("UpdateStateMachine", """
                {"stateMachineArn":"%s","definition":"%s","publish":true}
                """.formatted(stateMachineArn, DEF2))
                .then().statusCode(200)
                .extract().jsonPath().getString("stateMachineVersionArn");

        String createAliasRequest = """
                {"name":"PROD","description":"production",
                 "routingConfiguration":[{"stateMachineVersionArn":"%s","weight":100}]}
                """.formatted(versionOneArn);
        String aliasArn = call("CreateStateMachineAlias", createAliasRequest)
                .then().statusCode(200)
                .body("stateMachineAliasArn", is(stateMachineArn + ":PROD"))
                .extract().jsonPath().getString("stateMachineAliasArn");

        // CreateStateMachineAlias is idempotent for the same request.
        call("CreateStateMachineAlias", createAliasRequest)
                .then().statusCode(200)
                .body("stateMachineAliasArn", is(aliasArn));

        call("DescribeStateMachineAlias",
                "{\"stateMachineAliasArn\":\"" + aliasArn + "\"}")
                .then().statusCode(200)
                .body("name", is("PROD"))
                .body("description", is("production"))
                .body("routingConfiguration[0].stateMachineVersionArn", is(versionOneArn))
                .body("routingConfiguration[0].weight", is(100));

        call("ListStateMachineAliases",
                "{\"stateMachineArn\":\"" + stateMachineArn + "\"}")
                .then().statusCode(200)
                .body("stateMachineAliases[0].stateMachineAliasArn", is(aliasArn));
        call("ListStateMachineAliases",
                "{\"stateMachineArn\":\"" + versionOneArn + "\"}")
                .then().statusCode(200)
                .body("stateMachineAliases[0].stateMachineAliasArn", is(aliasArn));

        // DescribeStateMachine accepts an alias ARN and returns the selected version snapshot.
        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + aliasArn + "\"}")
                .then().statusCode(200)
                .body("stateMachineArn", is(versionOneArn))
                .body("definition", containsString("\"Result\":1"));

        String executionArn = call("StartExecution", """
                {"stateMachineArn":"%s","name":"through-alias","input":"{}"}
                """.formatted(aliasArn))
                .then().statusCode(200)
                .extract().jsonPath().getString("executionArn");
        call("DescribeExecution", "{\"executionArn\":\"" + executionArn + "\"}")
                .then().statusCode(200)
                .body("stateMachineArn", is(stateMachineArn))
                .body("stateMachineVersionArn", is(versionOneArn))
                .body("stateMachineAliasArn", is(aliasArn));

        call("UpdateStateMachineAlias", """
                {"stateMachineAliasArn":"%s","description":"promoted",
                 "routingConfiguration":[{"stateMachineVersionArn":"%s","weight":100}]}
                """.formatted(aliasArn, versionTwoArn))
                .then().statusCode(200);
        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + aliasArn + "\"}")
                .then().statusCode(200)
                .body("stateMachineArn", is(versionTwoArn))
                .body("definition", containsString("\"Result\":2"));

        // A Distributed Map-qualified ARN is recognized but rejected, as documented by AWS.
        call("DescribeStateMachine", "{\"stateMachineArn\":\""
                + stateMachineArn + "/map-label\"}")
                .then().statusCode(400)
                .body(containsString("ValidationException"));

        call("DeleteStateMachineVersion",
                "{\"stateMachineVersionArn\":\"" + versionTwoArn + "\"}")
                .then().statusCode(409)
                .body(containsString("ConflictException"));
        call("DeleteStateMachineAlias",
                "{\"stateMachineAliasArn\":\"" + aliasArn + "\"}")
                .then().statusCode(200);
        call("DescribeStateMachine", "{\"stateMachineArn\":\"" + aliasArn + "\"}")
                .then().statusCode(400)
                .body(containsString("StateMachineDoesNotExist"));
    }

    @Test
    void aliasValidationRejectsNamesAndInvalidRoutingConfiguration() {
        String name = "alias-validation-" + System.currentTimeMillis();
        Response created = call("CreateStateMachine", """
                {"name":"%s","definition":"%s",
                 "roleArn":"arn:aws:iam::000000000000:role/r","publish":true}
                """.formatted(name, DEF));
        String versionArn = created.then().statusCode(200)
                .extract().jsonPath().getString("stateMachineVersionArn");

        call("CreateStateMachineAlias", """
                {"name":"123","routingConfiguration":[
                 {"stateMachineVersionArn":"%s","weight":100}]}
                """.formatted(versionArn))
                .then().statusCode(400).body(containsString("InvalidName"));
        call("CreateStateMachineAlias", """
                {"name":"TEST","routingConfiguration":[
                 {"stateMachineVersionArn":"%s","weight":90}]}
                """.formatted(versionArn))
                .then().statusCode(400).body(containsString("ValidationException"));
    }

    @Test
    void listAliasesPaginatesWithAnOpaqueRoundTripToken() {
        String name = "alias-pagination-" + System.currentTimeMillis();
        Response created = call("CreateStateMachine", """
                {"name":"%s","definition":"%s",
                 "roleArn":"arn:aws:iam::000000000000:role/r","publish":true}
                """.formatted(name, DEF));
        String stateMachineArn = created.then().statusCode(200)
                .extract().jsonPath().getString("stateMachineArn");
        String versionArn = created.jsonPath().getString("stateMachineVersionArn");

        for (String aliasName : new String[] {"ALPHA", "BETA"}) {
            call("CreateStateMachineAlias", """
                    {"name":"%s","routingConfiguration":[
                     {"stateMachineVersionArn":"%s","weight":100}]}
                    """.formatted(aliasName, versionArn))
                    .then().statusCode(200);
        }

        Response firstPage = call("ListStateMachineAliases", """
                {"stateMachineArn":"%s","maxResults":1}
                """.formatted(stateMachineArn));
        firstPage.then().statusCode(200)
                .body("stateMachineAliases.size()", is(1));
        String firstArn = firstPage.jsonPath().getString("stateMachineAliases[0].stateMachineAliasArn");
        String nextToken = firstPage.jsonPath().getString("nextToken");
        assertNotNull(nextToken);

        Response secondPage = call("ListStateMachineAliases", """
                {"stateMachineArn":"%s","maxResults":1,"nextToken":"%s"}
                """.formatted(stateMachineArn, nextToken));
        secondPage.then().statusCode(200)
                .body("stateMachineAliases.size()", is(1));
        assertNotEquals(firstArn,
                secondPage.jsonPath().getString("stateMachineAliases[0].stateMachineAliasArn"));
        assertNull(secondPage.jsonPath().getString("nextToken"));

        call("ListStateMachineAliases", """
                {"stateMachineArn":"%s","maxResults":1001}
                """.formatted(stateMachineArn))
                .then().statusCode(400).body(containsString("ValidationException"));
    }

    @Test
    void createAliasEnforcesTheQuotaOfOneHundredAliasesPerStateMachine() {
        String name = "alias-quota-" + System.currentTimeMillis();
        Response created = call("CreateStateMachine", """
                {"name":"%s","definition":"%s",
                 "roleArn":"arn:aws:iam::000000000000:role/r","publish":true}
                """.formatted(name, DEF));
        String stateMachineArn = created.then().statusCode(200)
                .extract().jsonPath().getString("stateMachineArn");
        String versionArn = created.jsonPath().getString("stateMachineVersionArn");
        String createAliasRequest = """
                {"name":"%s","routingConfiguration":[
                 {"stateMachineVersionArn":"%s","weight":100}]}
                """;

        for (int i = 1; i <= 100; i++) {
            call("CreateStateMachineAlias", createAliasRequest.formatted("Q-" + i, versionArn))
                    .then().statusCode(200);
        }

        call("CreateStateMachineAlias", createAliasRequest.formatted("Q-101", versionArn))
                .then().statusCode(402)
                .body(containsString("ServiceQuotaExceededException"));
        call("CreateStateMachineAlias", createAliasRequest.formatted("Q-1", versionArn))
                .then().statusCode(200)
                .body("stateMachineAliasArn", is(stateMachineArn + ":Q-1"));

        call("DeleteStateMachineAlias",
                "{\"stateMachineAliasArn\":\"" + stateMachineArn + ":Q-1\"}")
                .then().statusCode(200);
        call("CreateStateMachineAlias", createAliasRequest.formatted("Q-101", versionArn))
                .then().statusCode(200);
    }
}
