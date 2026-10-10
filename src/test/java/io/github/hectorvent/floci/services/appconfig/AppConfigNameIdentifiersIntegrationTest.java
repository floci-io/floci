package io.github.hectorvent.floci.services.appconfig;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class AppConfigNameIdentifiersIntegrationTest {

    private static final String APPLICATION_NAME = "management-name-app";
    private static final String ENVIRONMENT_NAME = "management-name-env";
    private static final String PROFILE_NAME = "management-name-profile";

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void managementOperationsAcceptResourceNames() {
        String applicationId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"" + APPLICATION_NAME + "\"}")
                .when().post("/applications")
                .then().statusCode(201)
                .extract().path("Id");

        given()
                .when().get("/applications/" + APPLICATION_NAME)
                .then().statusCode(200)
                .body("Id", equalTo(applicationId));

        String environmentId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"" + ENVIRONMENT_NAME + "\"}")
                .when().post("/applications/" + APPLICATION_NAME + "/environments")
                .then().statusCode(201)
                .body("ApplicationId", equalTo(applicationId))
                .extract().path("Id");

        given()
                .when().get("/applications/" + APPLICATION_NAME + "/environments/" + ENVIRONMENT_NAME)
                .then().statusCode(200)
                .body("Id", equalTo(environmentId));

        given()
                .when().get("/applications/" + APPLICATION_NAME + "/environments")
                .then().statusCode(200)
                .body("Items.Id", hasItem(environmentId));

        String profileId = given()
                .contentType(ContentType.JSON)
                .body("{\"Name\":\"" + PROFILE_NAME
                        + "\",\"LocationUri\":\"hosted\",\"Type\":\"AWS.Freeform\"}")
                .when().post("/applications/" + APPLICATION_NAME + "/configurationprofiles")
                .then().statusCode(201)
                .body("ApplicationId", equalTo(applicationId))
                .extract().path("Id");

        given()
                .when().get("/applications/" + APPLICATION_NAME + "/configurationprofiles/" + PROFILE_NAME)
                .then().statusCode(200)
                .body("Id", equalTo(profileId));

        given()
                .when().get("/applications/" + APPLICATION_NAME + "/configurationprofiles")
                .then().statusCode(200)
                .body("Items.Id", hasItem(profileId));

        given()
                .header("Content-Type", "application/json")
                .body("{\"enabled\":true}")
                .when().post("/applications/" + APPLICATION_NAME + "/configurationprofiles/"
                        + PROFILE_NAME + "/hostedconfigurationversions")
                .then().statusCode(201)
                .header("Application-Id", equalTo(applicationId))
                .header("Configuration-Profile-Id", equalTo(profileId))
                .header("Version-Number", equalTo("1"));

        given()
                .when().get("/applications/" + APPLICATION_NAME + "/configurationprofiles/"
                        + PROFILE_NAME + "/hostedconfigurationversions/1")
                .then().statusCode(200)
                .header("Application-Id", equalTo(applicationId))
                .header("Configuration-Profile-Id", equalTo(profileId));

        given()
                .when().get("/applications/" + APPLICATION_NAME + "/configurationprofiles/"
                        + PROFILE_NAME + "/hostedconfigurationversions")
                .then().statusCode(200)
                .body("Items[0].ApplicationId", equalTo(applicationId))
                .body("Items[0].ConfigurationProfileId", equalTo(profileId));

        int deploymentNumber = given()
                .contentType(ContentType.JSON)
                .body("{\"ConfigurationProfileId\":\"" + PROFILE_NAME
                        + "\",\"ConfigurationVersion\":\"1\","
                        + "\"DeploymentStrategyId\":\"AppConfig.AllAtOnce\"}")
                .when().post("/applications/" + APPLICATION_NAME + "/environments/"
                        + ENVIRONMENT_NAME + "/deployments")
                .then().statusCode(201)
                .body("ApplicationId", equalTo(applicationId))
                .body("EnvironmentId", equalTo(environmentId))
                .body("ConfigurationProfileId", equalTo(profileId))
                .extract().path("DeploymentNumber");

        given()
                .when().get("/applications/" + APPLICATION_NAME + "/environments/"
                        + ENVIRONMENT_NAME + "/deployments/" + deploymentNumber)
                .then().statusCode(200)
                .body("ApplicationId", equalTo(applicationId))
                .body("EnvironmentId", equalTo(environmentId));

        given()
                .when().get("/applications/" + APPLICATION_NAME + "/environments/"
                        + ENVIRONMENT_NAME + "/deployments")
                .then().statusCode(200)
                .body("Items.DeploymentNumber", hasItem(deploymentNumber));

        given()
                .when().delete("/applications/" + APPLICATION_NAME + "/configurationprofiles/"
                        + PROFILE_NAME + "/hostedconfigurationversions/1")
                .then().statusCode(204);

        given()
                .when().delete("/applications/" + APPLICATION_NAME + "/configurationprofiles/" + PROFILE_NAME)
                .then().statusCode(204);

        given()
                .when().delete("/applications/" + APPLICATION_NAME)
                .then().statusCode(204);

        given()
                .when().get("/applications/" + applicationId)
                .then().statusCode(404);
    }
}
