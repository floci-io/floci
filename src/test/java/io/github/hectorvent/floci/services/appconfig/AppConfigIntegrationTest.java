package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AppConfigIntegrationTest {

    private static String appId;
    private static String envId;
    private static String profileId;
    private static String strategyId;
    private static String configToken;
    private static String nextConfigToken;
    private static String intervalToken;
    private static String emptyAppId;
    private static String emptyEnvId;
    private static String emptyProfileId;

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test @Order(1)
    void createApplication() {
        appId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"test-app\", \"Description\": \"Test App\"}")
                .when().post("/applications")
                .then()
                .statusCode(201)
                .body("Name", equalTo("test-app"))
                .extract().path("Id");
    }

    @Test @Order(2)
    void createEnvironment() {
        envId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"test-env\"}")
                .when().post("/applications/" + appId + "/environments")
                .then()
                .statusCode(201)
                .body("Name", equalTo("test-env"))
                .extract().path("Id");
    }

    @Test @Order(3)
    void createConfigurationProfile() {
        profileId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"test-profile\", \"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"}")
                .when().post("/applications/" + appId + "/configurationprofiles")
                .then()
                .statusCode(201)
                .body("Name", equalTo("test-profile"))
                .extract().path("Id");
    }

    @Test @Order(4)
    void createHostedConfigurationVersion() {
        given()
                .header("Content-Type", "application/json")
                .header("Description", "v1")
                .body("{\"foo\": \"bar\"}".getBytes())
                .when().post("/applications/" + appId + "/configurationprofiles/" + profileId + "/hostedconfigurationversions")
                .then()
                .statusCode(201)
                .header("Version-Number", equalTo("1"));
    }

    @Test @Order(5)
    void createDeploymentStrategy() {
        strategyId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"immediate\", \"DeploymentDurationInMinutes\": 0, \"GrowthFactor\": 100, \"FinalBakeTimeInMinutes\": 0}")
                .when().post("/deploymentstrategies")
                .then()
                .statusCode(201)
                .body("Name", equalTo("immediate"))
                .extract().path("Id");
    }

    @Test @Order(6)
    void startDeployment() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"ConfigurationProfileId\": \"" + profileId + "\", \"ConfigurationVersion\": \"1\", \"DeploymentStrategyId\": \"" + strategyId + "\"}")
                .when().post("/applications/" + appId + "/environments/" + envId + "/deployments")
                .then()
                .statusCode(201)
                .body("State", equalTo("COMPLETE"));
    }

    @Test @Order(7)
    void startConfigurationSession() {
        configToken = given()
                .contentType(ContentType.JSON)
                .body("{\"ApplicationIdentifier\": \"" + appId + "\", \"EnvironmentIdentifier\": \"" + envId + "\", \"ConfigurationProfileIdentifier\": \"" + profileId + "\"}")
                .when().post("/configurationsessions")
                .then()
                .statusCode(201)
                .body("InitialConfigurationToken", notNullValue())
                .extract().path("InitialConfigurationToken");
    }

    @Test @Order(8)
    void getLatestConfiguration() {
        nextConfigToken = given()
                .queryParam("configuration_token", configToken)
                .when().get("/configuration")
                .then()
                .statusCode(200)
                .header("Content-Type", startsWith("application/json"))
                .header("Version-Label", equalTo("1"))
                .header("Next-Poll-Configuration-Token", notNullValue())
                .header("Next-Poll-Interval-In-Seconds", equalTo("15"))
                .body("foo", equalTo("bar"))
                .extract().header("Next-Poll-Configuration-Token");
    }

    @Test @Order(9)
    void staleConfigurationTokenIsRejected() {
        given()
                .queryParam("configuration_token", configToken)
                .when().get("/configuration")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid configuration token"));
    }

    @Test @Order(10)
    void invalidConfigurationTokenIsRejected() {
        given()
                .queryParam("configuration_token", "not-a-real-token")
                .when().get("/configuration")
                .then()
                .statusCode(400)
                .body("__type", equalTo("BadRequestException"))
                .body("message", equalTo("Invalid configuration token"));
    }

    @Test @Order(11)
    void updatedDeploymentIsVisibleOnNextPollToken() {
        given()
                .header("Content-Type", "application/json")
                .header("Description", "v2")
                .body("{\"foo\": \"baz\"}".getBytes())
                .when().post("/applications/" + appId + "/configurationprofiles/" + profileId + "/hostedconfigurationversions")
                .then()
                .statusCode(201)
                .header("Version-Number", equalTo("2"));

        given()
                .contentType(ContentType.JSON)
                .body("{\"ConfigurationProfileId\": \"" + profileId + "\", \"ConfigurationVersion\": \"2\", \"DeploymentStrategyId\": \"" + strategyId + "\"}")
                .when().post("/applications/" + appId + "/environments/" + envId + "/deployments")
                .then()
                .statusCode(201)
                .body("State", equalTo("COMPLETE"));

        given()
                .queryParam("configuration_token", nextConfigToken)
                .when().get("/configuration")
                .then()
                .statusCode(200)
                .header("Version-Label", equalTo("2"))
                .body("foo", equalTo("baz"));
    }

    @Test @Order(12)
    void requiredMinimumPollIntervalIsStoredButNotEnforced() {
        intervalToken = given()
                .contentType(ContentType.JSON)
                .body("{\"ApplicationIdentifier\": \"" + appId + "\", \"EnvironmentIdentifier\": \"" + envId + "\", \"ConfigurationProfileIdentifier\": \"" + profileId + "\", \"RequiredMinimumPollIntervalInSeconds\": 60}")
                .when().post("/configurationsessions")
                .then()
                .statusCode(201)
                .body("InitialConfigurationToken", notNullValue())
                .extract().path("InitialConfigurationToken");

        String immediateNextToken = given()
                .queryParam("configuration_token", intervalToken)
                .when().get("/configuration")
                .then()
                .statusCode(200)
                .header("Next-Poll-Configuration-Token", notNullValue())
                .header("Next-Poll-Interval-In-Seconds", equalTo("15"))
                .extract().header("Next-Poll-Configuration-Token");

        given()
                .queryParam("configuration_token", immediateNextToken)
                .when().get("/configuration")
                .then()
                .statusCode(200)
                .header("Next-Poll-Configuration-Token", notNullValue());
    }

    @Test @Order(13)
    void emptyConfigurationReturnsEmptyPayload() {
        emptyAppId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"empty-app\"}")
                .when().post("/applications")
                .then()
                .statusCode(201)
                .extract().path("Id");

        emptyEnvId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"empty-env\"}")
                .when().post("/applications/" + emptyAppId + "/environments")
                .then()
                .statusCode(201)
                .extract().path("Id");

        emptyProfileId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"empty-profile\", \"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"}")
                .when().post("/applications/" + emptyAppId + "/configurationprofiles")
                .then()
                .statusCode(201)
                .extract().path("Id");

        String emptyToken = given()
                .contentType(ContentType.JSON)
                .body("{\"ApplicationIdentifier\": \"" + emptyAppId + "\", \"EnvironmentIdentifier\": \"" + emptyEnvId + "\", \"ConfigurationProfileIdentifier\": \"" + emptyProfileId + "\"}")
                .when().post("/configurationsessions")
                .then()
                .statusCode(201)
                .extract().path("InitialConfigurationToken");

        given()
                .queryParam("configuration_token", emptyToken)
                .when().get("/configuration")
                .then()
                .statusCode(200)
                .header("Content-Type", equalTo("application/octet-stream"))
                .header("Version-Label", equalTo(""))
                .body(equalTo(""));
    }
}
