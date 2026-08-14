package io.github.hectorvent.floci.services.mwaa;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * MWAA REST flow against {@code floci.services.mwaa.mock=true} (see test {@code application.yml}),
 * so no Docker daemon is touched. Mirrors {@code EksNodegroupIntegrationTest}'s style: real HTTP
 * calls via RestAssured, ordered because environment state carries across tests.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MwaaIntegrationTest {

    private static final String JSON = "application/json";
    private static final String ENV = "mwaa-it-env";

    private static String createBody(String extraFields) {
        return "{\"ExecutionRoleArn\":\"arn:aws:iam::000000000000:role/mwaa-execution-role\","
                + "\"SourceBucketArn\":\"arn:aws:s3:::mwaa-it-bucket\","
                + "\"DagS3Path\":\"dags\","
                + "\"NetworkConfiguration\":{\"SubnetIds\":[\"subnet-1\",\"subnet-2\"],\"SecurityGroupIds\":[\"sg-1\"]}"
                + (extraFields != null ? "," + extraFields : "")
                + "}";
    }

    @Test
    @Order(1)
    void createEnvironment() {
        given().contentType(JSON)
                .body(createBody(null))
                .when().put("/environments/" + ENV)
                .then().statusCode(200)
                .body("Arn", containsString("environment/" + ENV))
                .body("Arn", containsString(":airflow:"));
    }

    @Test
    @Order(2)
    void getEnvironmentReturnsDefaultsAndAvailableStatus() {
        given().contentType(JSON)
                .when().get("/environments/" + ENV)
                .then().statusCode(200)
                .body("Environment.Name", equalTo(ENV))
                .body("Environment.Status", equalTo("AVAILABLE"))
                .body("Environment.AirflowVersion", equalTo("2.10.5"))
                .body("Environment.WebserverUrl", notNullValue())
                .body("Environment.NetworkConfiguration.SubnetIds", hasItem("subnet-1"));
    }

    @Test
    @Order(3)
    void createEnvironmentDuplicateFails() {
        given().contentType(JSON)
                .body(createBody(null))
                .when().put("/environments/" + ENV)
                .then().statusCode(400);
    }

    @Test
    @Order(4)
    void listEnvironmentsIncludesCreatedEnvironment() {
        given().contentType(JSON)
                .when().get("/environments")
                .then().statusCode(200)
                .body("Environments", hasItem(ENV));
    }

    @Test
    @Order(5)
    void updateEnvironmentAppliesMetadataOnlyChange() {
        given().contentType(JSON)
                .body("{\"EnvironmentClass\":\"mw1.large\"}")
                .when().patch("/environments/" + ENV)
                .then().statusCode(200)
                .body("Arn", containsString("environment/" + ENV));

        given().contentType(JSON)
                .when().get("/environments/" + ENV)
                .then().statusCode(200)
                .body("Environment.EnvironmentClass", equalTo("mw1.large"));
    }

    @Test
    @Order(6)
    void updateEnvironmentRejectsAirflowVersionChange() {
        given().contentType(JSON)
                .body("{\"AirflowVersion\":\"2.9.3\"}")
                .when().patch("/environments/" + ENV)
                .then().statusCode(400);
    }

    @Test
    @Order(7)
    void tagResourceUntagResourceAndListTagsForResourceRoundTrip() {
        String arn = given().contentType(JSON)
                .when().get("/environments/" + ENV)
                .then().statusCode(200)
                .extract().path("Environment.Arn");

        given().contentType(JSON)
                .body("{\"Tags\":{\"team\":\"data\",\"env\":\"test\"}}")
                .when().post("/tags/" + arn)
                .then().statusCode(204);

        given().contentType(JSON)
                .when().get("/tags/" + arn)
                .then().statusCode(200)
                .body("Tags.team", equalTo("data"))
                .body("Tags.env", equalTo("test"));

        given().contentType(JSON)
                .when().delete("/tags/" + arn + "?tagKeys=env")
                .then().statusCode(204);

        given().contentType(JSON)
                .when().get("/tags/" + arn)
                .then().statusCode(200)
                .body("Tags.team", equalTo("data"))
                .body("Tags.env", nullValue());
    }

    @Test
    @Order(8)
    void createWebLoginTokenAndCliTokenReturnOpaqueTokens() {
        given().contentType(JSON)
                .when().post("/webtoken/" + ENV)
                .then().statusCode(200)
                .body("WebToken", notNullValue())
                .body("WebServerHostname", notNullValue());

        given().contentType(JSON)
                .when().post("/clitoken/" + ENV)
                .then().statusCode(200)
                .body("CliToken", notNullValue())
                .body("WebServerHostname", notNullValue());
    }

    @Test
    @Order(9)
    void deleteEnvironment() {
        given().contentType(JSON)
                .when().delete("/environments/" + ENV)
                .then().statusCode(200);

        given().contentType(JSON)
                .when().get("/environments/" + ENV)
                .then().statusCode(404);
    }

    @Test
    @Order(10)
    void everySupportedAirflowVersionRoundTrips() {
        String[] versions = {"2.10.5", "2.9.3", "2.8.4"};
        for (String version : versions) {
            String name = "mwaa-it-v" + version.replace(".", "");
            given().contentType(JSON)
                    .body(createBody("\"AirflowVersion\":\"" + version + "\""))
                    .when().put("/environments/" + name)
                    .then().statusCode(200);

            given().contentType(JSON)
                    .when().get("/environments/" + name)
                    .then().statusCode(200)
                    .body("Environment.AirflowVersion", equalTo(version));

            given().contentType(JSON)
                    .when().delete("/environments/" + name)
                    .then().statusCode(200);
        }
    }

    @Test
    @Order(11)
    void unsupportedAirflowVersionIsRejected() {
        given().contentType(JSON)
                .body(createBody("\"AirflowVersion\":\"1.10.15\""))
                .when().put("/environments/mwaa-it-bad-version")
                .then().statusCode(400);

        given().contentType(JSON)
                .when().get("/environments/mwaa-it-bad-version")
                .then().statusCode(404);
    }
}
