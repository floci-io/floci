package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("Poll interval: requested 60s but emulator returns 15s (known deviation from AWS)")
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

    // ──────────────────────────── Hosted Configuration Version list ────────────────────────────

    @Test @Order(13)
    void listHostedConfigurationVersionsReturnsBothVersions() {
        given()
                .when().get("/applications/" + appId + "/configurationprofiles/" + profileId + "/hostedconfigurationversions")
                .then()
                .statusCode(200)
                .body("Items.size()", equalTo(2))
                .body("Items[0].VersionNumber", equalTo(1))
                .body("Items[0].ContentType", startsWith("application/json"))
                .body("Items[0].ApplicationId", equalTo(appId))
                .body("Items[0].ConfigurationProfileId", equalTo(profileId))
                .body("Items[1].VersionNumber", equalTo(2))
                .body("Items.Content", everyItem(nullValue()));
    }

    // ──────────────────────────── Builtin deployment strategies ────────────────────────────

    @Test @Order(14)
    void builtinStrategyAllAtOnceCanBeUsedWithoutCreating() {
        given()
                .contentType(ContentType.JSON)
                .body("{\"ConfigurationProfileId\": \"" + profileId + "\", \"ConfigurationVersion\": \"1\", \"DeploymentStrategyId\": \"AppConfig.AllAtOnce\"}")
                .when().post("/applications/" + appId + "/environments/" + envId + "/deployments")
                .then()
                .statusCode(201)
                .body("State", equalTo("COMPLETE"))
                .body("DeploymentStrategyId", equalTo("AppConfig.AllAtOnce"));
    }

    // ──────────────────────────── Application tagging ────────────────────────────

    @Test @Order(15)
    void listTagsOnNewApplicationIsEmpty() {
        String arn = "arn:aws:appconfig:us-east-1:000000000000:application/" + appId;
        given()
                .when().get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags", anEmptyMap());
    }

    @Test @Order(16)
    void tagApplication() {
        String arn = "arn:aws:appconfig:us-east-1:000000000000:application/" + appId;
        given()
                .contentType(ContentType.JSON)
                .body("{\"Tags\": {\"env\": \"local\", \"team\": \"platform\"}}")
                .when().post("/tags/" + arn)
                .then()
                .statusCode(204);
    }

    @Test @Order(17)
    void listTagsAfterTagging() {
        String arn = "arn:aws:appconfig:us-east-1:000000000000:application/" + appId;
        given()
                .when().get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags.env", equalTo("local"))
                .body("Tags.team", equalTo("platform"));
    }

    @Test @Order(18)
    void untagApplication() {
        String arn = "arn:aws:appconfig:us-east-1:000000000000:application/" + appId;
        given()
                .when().delete("/tags/" + arn + "?tagKeys=env")
                .then()
                .statusCode(204);
    }

    @Test @Order(19)
    void listTagsAfterUntagging() {
        String arn = "arn:aws:appconfig:us-east-1:000000000000:application/" + appId;
        given()
                .when().get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags", not(hasKey("env")))
                .body("Tags.team", equalTo("platform"));
    }

    // ──────────────────────────── Tags on non-application resources (no-op) ────────────────────────────

    @Test @Order(20)
    void listTagsForEnvironmentArnReturnsEmpty() {
        String arn = "arn:aws:appconfig:us-east-1:000000000000:application/" + appId + "/environment/" + envId;
        given()
                .when().get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags", anEmptyMap());
    }

    @Test @Order(21)
    void listTagsForDeploymentArnReturnsEmpty() {
        String arn = "arn:aws:appconfig:us-east-1:000000000000:application/" + appId + "/environment/" + envId + "/deployment/1";
        given()
                .when().get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags", anEmptyMap());
    }

    // ──────────── Tags on top-level (non-application-nested) resource ARNs ────────────
    // deploymentstrategy/extension/extensionassociation are real, taggable AppConfig resource
    // types per the API - unlike environment/deployment above, their ARNs don't nest under
    // application/..., so they need their own top-level branch in AppConfigTagHandler#parseArn.
    // Regression coverage for that ARN-shape acceptance; no tag storage exists for these types
    // yet (same no-op precedent as environment/deployment above), so only 200-not-400 is asserted.

    @Test @Order(22)
    void listTagsForDeploymentStrategyArnIsAcceptedNotRejected() {
        String arn = "arn:aws:appconfig:us-east-1:000000000000:deploymentstrategy/" + strategyId;
        given()
                .when().get("/tags/" + arn)
                .then()
                .statusCode(200)
                .body("Tags", anEmptyMap());
    }

    @Test @Order(23)
    void tagDeploymentStrategyArnIsAcceptedNotRejected() {
        String arn = "arn:aws:appconfig:us-east-1:000000000000:deploymentstrategy/" + strategyId;
        given()
                .contentType(ContentType.JSON)
                .body("{\"Tags\": {\"env\": \"local\"}}")
                .when().post("/tags/" + arn)
                .then()
                .statusCode(204);
    }

    @Test @Order(24)
    void listTagsForExtensionAndExtensionAssociationArnsIsAcceptedNotRejected() {
        String extensionArn = "arn:aws:appconfig:us-east-1:000000000000:extension/some-extension-id";
        String associationArn = "arn:aws:appconfig:us-east-1:000000000000:extensionassociation/some-association-id";
        given()
                .when().get("/tags/" + extensionArn)
                .then()
                .statusCode(200)
                .body("Tags", anEmptyMap());
        given()
                .when().get("/tags/" + associationArn)
                .then()
                .statusCode(200)
                .body("Tags", anEmptyMap());
    }

    @Test @Order(25)
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
                // HTTP transport returns "" for empty Version-Label.
                // SDK deserializes this as null (see AppConfigTest).
                .header("Version-Label", equalTo(""))
                .body(equalTo(""));
    }

    // ──────────────────────────── Delete/list operations that previously 404'd ────────────────────────────
    // DeleteConfigurationProfile, DeleteHostedConfigurationVersion, ListDeploymentStrategies, and
    // DeleteDeploymentStrategy had no route at all, so requests fell through to S3's generic
    // path-style catch-all (GET/DELETE /{bucket}[/{key}]) and returned a misleading NoSuchBucket
    // 404 instead of a real AppConfig response.

    @Test @Order(26)
    void deleteConfigurationProfileRemovesIt() {
        String throwawayProfileId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"throwaway-profile\", \"LocationUri\": \"hosted\", \"Type\": \"AWS.Freeform\"}")
                .when().post("/applications/" + appId + "/configurationprofiles")
                .then()
                .statusCode(201)
                .extract().path("Id");

        given()
                .when().delete("/applications/" + appId + "/configurationprofiles/" + throwawayProfileId)
                .then()
                .statusCode(204);

        given()
                .when().get("/applications/" + appId + "/configurationprofiles/" + throwawayProfileId)
                .then()
                .statusCode(404);
    }

    @Test @Order(27)
    void deleteHostedConfigurationVersionRemovesIt() {
        String versionNumberHeader = given()
                .header("Content-Type", "application/json")
                .body("{\"throwaway\": true}".getBytes())
                .when().post("/applications/" + appId + "/configurationprofiles/" + profileId + "/hostedconfigurationversions")
                .then()
                .statusCode(201)
                .extract().header("Version-Number");
        int versionNumber = Integer.parseInt(versionNumberHeader);

        given()
                .when().delete("/applications/" + appId + "/configurationprofiles/" + profileId
                        + "/hostedconfigurationversions/" + versionNumber)
                .then()
                .statusCode(204);

        given()
                .when().get("/applications/" + appId + "/configurationprofiles/" + profileId
                        + "/hostedconfigurationversions/" + versionNumber)
                .then()
                .statusCode(404);
    }

    @Test @Order(28)
    void listDeploymentStrategiesIncludesBuiltinsAndCustom() {
        given()
                .when().get("/deploymentstrategies")
                .then()
                .statusCode(200)
                .body("Items.Id", hasItems("AppConfig.AllAtOnce", "AppConfig.Linear50PercentEvery30Seconds",
                        "AppConfig.Canary10Percent20Minutes", strategyId));
    }

    @Test @Order(29)
    void deleteDeploymentStrategyRemovesIt() {
        String throwawayStrategyId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\": \"throwaway-strategy\", \"DeploymentDurationInMinutes\": 0, \"GrowthFactor\": 100, \"FinalBakeTimeInMinutes\": 0}")
                .when().post("/deploymentstrategies")
                .then()
                .statusCode(201)
                .extract().path("Id");

        // AWS's own API model spells DeleteDeploymentStrategy's path "deployementstrategies"
        // (extra "e") - every other deployment-strategy operation correctly uses
        // "deploymentstrategies". Confirmed against the real API reference and reproduced
        // against the real AWS SDK for Java v2 (see AppConfigController#deleteDeploymentStrategy).
        given()
                .when().delete("/deployementstrategies/" + throwawayStrategyId)
                .then()
                .statusCode(204);

        given()
                .when().get("/deploymentstrategies/" + throwawayStrategyId)
                .then()
                .statusCode(404);

        // Deleting an already-deleted (or never-existing) strategy is idempotent, matching
        // DeleteApplication's existing convention - not an error.
        given()
                .when().delete("/deployementstrategies/" + throwawayStrategyId)
                .then()
                .statusCode(204);
    }

    @Test @Order(30)
    void deleteConfigurationProfileUnderWrongApplicationIsRejectedNotDeleted() {
        // emptyProfileId belongs to emptyAppId (created in @Order(25)), not appId - a caller
        // guessing/reusing a profileId under the wrong application must not be able to delete it.
        given()
                .when().delete("/applications/" + appId + "/configurationprofiles/" + emptyProfileId)
                .then()
                .statusCode(404);

        given()
                .when().get("/applications/" + emptyAppId + "/configurationprofiles/" + emptyProfileId)
                .then()
                .statusCode(200);
    }

    @Test @Order(31)
    void deleteDeploymentStrategyOnPredefinedStrategyIsRejected() {
        given()
                .when().delete("/deployementstrategies/AppConfig.AllAtOnce")
                .then()
                .statusCode(400);

        given()
                .when().get("/deploymentstrategies/AppConfig.AllAtOnce")
                .then()
                .statusCode(200);
    }
}
