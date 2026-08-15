package io.github.hectorvent.floci.services.lambda;

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

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaCapacityProviderIntegrationTest {

    private static final String NAME = "cp-integration";
    private static String arn;

    @Test
    @Order(1)
    void createCapacityProviderReportsActiveImmediately() {
        arn = given()
            .contentType("application/json")
            .body("""
                {
                  "CapacityProviderName": "cp-integration",
                  "VpcConfig": {
                    "SubnetIds": ["subnet-11111111", "subnet-22222222"],
                    "SecurityGroupIds": ["sg-33333333"]
                  },
                  "PermissionsConfig": {
                    "CapacityProviderOperatorRoleArn": "arn:aws:iam::000000000000:role/cp-operator"
                  },
                  "InstanceRequirements": {
                    "Architectures": ["x86_64"],
                    "AllowedInstanceTypes": ["m5.large"]
                  },
                  "CapacityProviderScalingConfig": {
                    "MaxVCpuCount": 64,
                    "ScalingMode": "Auto"
                  },
                  "Tags": {"team": "platform"}
                }
                """)
        .when()
            .post("/2025-11-30/capacity-providers")
        .then()
            .statusCode(202)
            .body("CapacityProvider.CapacityProviderArn", containsString(":lambda:"))
            .body("CapacityProvider.CapacityProviderArn", containsString(":capacity-provider:" + NAME))
            .body("CapacityProvider.State", equalTo("Active"))
            .body("CapacityProvider.VpcConfig.SubnetIds", hasItem("subnet-11111111"))
            .body("CapacityProvider.VpcConfig.SecurityGroupIds", hasItem("sg-33333333"))
            .body("CapacityProvider.PermissionsConfig.CapacityProviderOperatorRoleArn",
                    equalTo("arn:aws:iam::000000000000:role/cp-operator"))
            .body("CapacityProvider.InstanceRequirements.AllowedInstanceTypes", hasItem("m5.large"))
            .body("CapacityProvider.CapacityProviderScalingConfig.MaxVCpuCount", equalTo(64))
            .body("CapacityProvider.LastModified", notNullValue())
            .extract().path("CapacityProvider.CapacityProviderArn");
    }

    @Test
    @Order(2)
    void getCapacityProviderReturnsTheStoredConfiguration() {
        given()
        .when()
            .get("/2025-11-30/capacity-providers/" + NAME)
        .then()
            .statusCode(200)
            .body("CapacityProvider.CapacityProviderArn", equalTo(arn))
            .body("CapacityProvider.State", equalTo("Active"))
            .body("CapacityProvider.VpcConfig.SubnetIds", hasItem("subnet-22222222"))
            .body("CapacityProvider.CapacityProviderScalingConfig.ScalingMode", equalTo("Auto"));
    }

    @Test
    @Order(3)
    void listCapacityProvidersIncludesTheNewOne() {
        given()
        .when()
            .get("/2025-11-30/capacity-providers")
        .then()
            .statusCode(200)
            .body("CapacityProviders.CapacityProviderArn", hasItem(arn));
    }

    @Test
    @Order(4)
    void listTagsReturnsTheTagsPassedOnCreate() {
        given()
        .when()
            .get("/2017-03-31/tags/" + arn)
        .then()
            .statusCode(200)
            .body("Tags.team", equalTo("platform"));
    }

    @Test
    @Order(5)
    void tagAndUntagRoundTrip() {
        given()
            .contentType("application/json")
            .body("{\"Tags\": {\"env\": \"dev\"}}")
        .when()
            .post("/2017-03-31/tags/" + arn)
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/2017-03-31/tags/" + arn)
        .then()
            .statusCode(200)
            .body("Tags.env", equalTo("dev"))
            .body("Tags.team", equalTo("platform"));

        given()
        .when()
            .delete("/2017-03-31/tags/" + arn + "?tagKeys=env")
        .then()
            .statusCode(204);

        given()
        .when()
            .get("/2017-03-31/tags/" + arn)
        .then()
            .statusCode(200)
            .body("Tags.env", equalTo(null))
            .body("Tags.team", equalTo("platform"));
    }

    @Test
    @Order(6)
    void updateReplacesTheScalingConfiguration() {
        given()
            .contentType("application/json")
            .body("""
                {"CapacityProviderScalingConfig": {"MaxVCpuCount": 128, "ScalingMode": "Manual"}}
                """)
        .when()
            .put("/2025-11-30/capacity-providers/" + NAME)
        .then()
            .statusCode(202)
            .body("CapacityProvider.CapacityProviderScalingConfig.MaxVCpuCount", equalTo(128))
            .body("CapacityProvider.CapacityProviderScalingConfig.ScalingMode", equalTo("Manual"))
            .body("CapacityProvider.State", equalTo("Active"));
    }

    @Test
    @Order(7)
    void createWithoutVpcConfigIsRejected() {
        given()
            .contentType("application/json")
            .body("""
                {"CapacityProviderName": "cp-missing-vpc",
                 "PermissionsConfig": {"CapacityProviderOperatorRoleArn": "arn:aws:iam::000000000000:role/x"}}
                """)
        .when()
            .post("/2025-11-30/capacity-providers")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"));
    }

    @Test
    @Order(8)
    void duplicateNameIsRejected() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "CapacityProviderName": "cp-integration",
                  "VpcConfig": {"SubnetIds": ["subnet-11111111"], "SecurityGroupIds": ["sg-33333333"]},
                  "PermissionsConfig": {"CapacityProviderOperatorRoleArn": "arn:aws:iam::000000000000:role/x"}
                }
                """)
        .when()
            .post("/2025-11-30/capacity-providers")
        .then()
            .statusCode(409)
            .body("__type", equalTo("ResourceConflictException"));
    }

    @Test
    @Order(9)
    void deleteRemovesItAndASubsequentGetIsNotFound() {
        given()
        .when()
            .delete("/2025-11-30/capacity-providers/" + NAME)
        .then()
            .statusCode(202)
            .body("CapacityProvider.State", equalTo("Deleting"));

        given()
        .when()
            .get("/2025-11-30/capacity-providers/" + NAME)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    @Order(10)
    void getUnknownCapacityProviderIsNotFound() {
        given()
        .when()
            .get("/2025-11-30/capacity-providers/does-not-exist")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
