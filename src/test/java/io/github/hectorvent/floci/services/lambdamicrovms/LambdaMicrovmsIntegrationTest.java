package io.github.hectorvent.floci.services.lambdamicrovms;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LambdaMicrovmsIntegrationTest {

    private static final String MICROVMS = "/2025-09-09";
    private static final String CORE = "/2026-04-04";
    private static final String TAGS = "/2017-03-31";
    private static final String IMAGE = "microvm-it-image";

    private static String microvmId;
    private static String connectorId;
    private static String imageArn;

    @Test
    @Order(1)
    void createImage() {
        imageArn = given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "%s",
                          "baseImageArn": "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                          "buildRoleArn": "arn:aws:iam::000000000000:role/microvm-build",
                          "codeArtifact": { "uri": "s3://bucket/code.zip" }
                        }
                        """.formatted(IMAGE))
                .when()
                .post(MICROVMS + "/microvm-images")
                .then()
                .statusCode(201)
                .body("name", equalTo(IMAGE))
                .body("state", equalTo("CREATING"))
                .body("imageVersion", equalTo("1.0"))
                .extract().path("imageArn");
    }

    @Test
    @Order(2)
    void imageSettlesToCreated() {
        given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE)
                .then()
                .statusCode(200)
                .body("state", equalTo("CREATED"))
                .body("latestActiveImageVersion", equalTo("1.0"));
    }

    @Test
    @Order(3)
    void versionAndBuildConverge() {
        String buildId = given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE + "/versions/1.0/builds")
                .then()
                .statusCode(200)
                .body("items[0].buildState", equalTo("SUCCESSFUL"))
                .extract().path("items[0].buildId");

        given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE + "/versions/1.0/builds/" + buildId)
                .then()
                .statusCode(200)
                .body("buildState", equalTo("SUCCESSFUL"))
                .body("architecture", equalTo("ARM_64"));

        given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE + "/versions/1.0")
                .then()
                .statusCode(200)
                .body("state", equalTo("SUCCESSFUL"))
                .body("status", equalTo("ACTIVE"));
    }

    @Test
    @Order(4)
    void badImageNameIsRejected() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "name": "bad name!",
                          "baseImageArn": "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                          "buildRoleArn": "arn:aws:iam::000000000000:role/microvm-build",
                          "codeArtifact": { "uri": "s3://bucket/code.zip" }
                        }
                        """)
                .when()
                .post(MICROVMS + "/microvm-images")
                .then()
                .statusCode(400);
    }

    @Test
    @Order(5)
    void managedCatalogListsBaseImage() {
        given()
                .when()
                .get(MICROVMS + "/managed-microvm-images")
                .then()
                .statusCode(200)
                .body("items[0].imageArn", notNullValue());
    }

    @Test
    @Order(6)
    void runMicrovm() {
        microvmId = given()
                .contentType("application/json")
                .body("{\"imageIdentifier\": \"" + IMAGE + "\"}")
                .when()
                .post(MICROVMS + "/microvms")
                .then()
                .statusCode(200)
                .body("state", equalTo("PENDING"))
                .body("microvmId", startsWith("microvm-"))
                .body("maximumDurationInSeconds", equalTo(28800))
                .extract().path("microvmId");

        given()
                .when()
                .get(MICROVMS + "/microvms/" + microvmId)
                .then()
                .statusCode(200)
                .body("state", equalTo("RUNNING"));
    }

    @Test
    @Order(7)
    void runWithMissingImageIsNotFound() {
        given()
                .contentType("application/json")
                .body("{\"imageIdentifier\": \"no-such-image\"}")
                .when()
                .post(MICROVMS + "/microvms")
                .then()
                .statusCode(404);
    }

    @Test
    @Order(8)
    void imageInUseRefusesDelete() {
        // Recorded live: 400 with "Cannot delete microvm image with running microvms."
        given()
                .when()
                .delete(MICROVMS + "/microvm-images/" + IMAGE)
                .then()
                .statusCode(400);
    }

    @Test
    @Order(9)
    void tagImageArn() {
        given()
                .contentType("application/json")
                .body("{\"Tags\": {\"team\": \"conformance\"}}")
                .when()
                .post(TAGS + "/tags/" + imageArn)
                .then()
                .statusCode(204);

        given()
                .when()
                .get(TAGS + "/tags/" + imageArn)
                .then()
                .statusCode(200)
                .body("Tags.team", equalTo("conformance"));

        given()
                .queryParam("tagKeys", "team")
                .when()
                .delete(TAGS + "/tags/" + imageArn)
                .then()
                .statusCode(204);
    }

    @Test
    @Order(10)
    void terminateMicrovm() {
        given()
                .when()
                .delete(MICROVMS + "/microvms/" + microvmId)
                .then()
                .statusCode(200);

        given()
                .when()
                .get(MICROVMS + "/microvms/" + microvmId)
                .then()
                .statusCode(200)
                .body("state", equalTo("TERMINATED"));

        // Recorded live: terminal-state mutations are 400 ValidationException.
        given()
                .when()
                .delete(MICROVMS + "/microvms/" + microvmId)
                .then()
                .statusCode(400);
    }

    @Test
    @Order(11)
    void connectorLifecycle() {
        connectorId = given()
                .contentType("application/json")
                .body("""
                        {
                          "Name": "microvm-it-connector",
                          "ClientToken": "microvm-it-token-1",
                          "OperatorRole": "arn:aws:iam::000000000000:role/microvm-connector-operator",
                          "Configuration": { "VpcEgressConfiguration": {
                            "AssociatedComputeResourceTypes": ["MicroVm"],
                            "NetworkProtocol": "IPv4",
                            "SubnetIds": ["subnet-0000000000000it01"],
                            "SecurityGroupIds": ["sg-0000000000000it01"]
                          } }
                        }
                        """)
                .when()
                .post(CORE + "/network-connectors")
                .then()
                .statusCode(202)
                .body("State", equalTo("PENDING"))
                .extract().path("Id");

        given()
                .when()
                .get(CORE + "/network-connectors/" + connectorId)
                .then()
                .statusCode(200)
                .body("State", equalTo("ACTIVE"))
                .body("Name", equalTo("microvm-it-connector"));

        // UpdateNetworkConnector changes the operator role and protocol as well as the subnets.
        given()
                .contentType("application/json")
                .body("""
                        {
                          "OperatorRole": "arn:aws:iam::000000000000:role/microvm-connector-operator-v2",
                          "Configuration": { "VpcEgressConfiguration": { "NetworkProtocol": "DUAL_STACK" } }
                        }
                        """)
                .when()
                .put(CORE + "/network-connectors/" + connectorId)
                .then()
                .statusCode(202);
        given()
                .when()
                .get(CORE + "/network-connectors/" + connectorId)
                .then()
                .statusCode(200)
                .body("OperatorRole", equalTo("arn:aws:iam::000000000000:role/microvm-connector-operator-v2"))
                .body("Configuration.VpcEgressConfiguration.NetworkProtocol", equalTo("DUAL_STACK"))
                .body("Configuration.VpcEgressConfiguration.SubnetIds[0]", equalTo("subnet-0000000000000it01"));

        given()
                .when()
                .delete(CORE + "/network-connectors/" + connectorId)
                .then()
                .statusCode(202);

        given()
                .when()
                .get(CORE + "/network-connectors/" + connectorId)
                .then()
                .statusCode(404);
    }

    @Test
    @Order(12)
    void connectorSubnetLimit() {
        StringBuilder subnets = new StringBuilder();
        for (int i = 1; i <= 17; i++) {
            if (i > 1) subnets.append(",");
            subnets.append("\"subnet-00000000000000").append(String.format("%03d", i)).append("\"");
        }
        given()
                .contentType("application/json")
                .body("""
                        {
                          "Name": "microvm-it-too-many",
                          "ClientToken": "microvm-it-token-2",
                          "OperatorRole": "arn:aws:iam::000000000000:role/microvm-connector-operator",
                          "Configuration": { "VpcEgressConfiguration": {
                            "AssociatedComputeResourceTypes": ["MicroVm"],
                            "NetworkProtocol": "IPv4",
                            "SubnetIds": [%s]
                          } }
                        }
                        """.formatted(subnets))
                .when()
                .post(CORE + "/network-connectors")
                .then()
                .statusCode(400);
    }

    @Test
    @Order(13)
    void cleanupImage() {
        given()
                .when()
                .delete(MICROVMS + "/microvm-images/" + IMAGE + "/versions/1.0")
                .then()
                .statusCode(200);

        given()
                .when()
                .delete(MICROVMS + "/microvm-images/" + IMAGE)
                .then()
                .statusCode(200)
                .body("state", equalTo("DELETING"))
                .body("imageIdentifier", equalTo(imageArn));

        given()
                .when()
                .get(MICROVMS + "/microvm-images/" + IMAGE)
                .then()
                .statusCode(404);
    }

    @Test
    @Order(14)
    void connectorRequiredMembersAreEnforced() {
        // Recorded live: all three are required despite being modeled optional.
        String base = """
                {
                  "Name": "microvm-it-missing-%s"%s,
                  "Configuration": { "VpcEgressConfiguration": {
                    %s
                    "SubnetIds": ["subnet-0000000000000it09"]
                  } }
                }
                """;
        given().contentType("application/json")
                .body(base.formatted("token", ", \"OperatorRole\": \"arn:aws:iam::000000000000:role/op\"", "\"AssociatedComputeResourceTypes\": [\"MicroVm\"], \"NetworkProtocol\": \"IPv4\","))
                .when().post(CORE + "/network-connectors")
                .then().statusCode(400);
        given().contentType("application/json")
                .body(base.formatted("acrt", ", \"ClientToken\": \"t1\", \"OperatorRole\": \"arn:aws:iam::000000000000:role/op\"", "\"NetworkProtocol\": \"IPv4\","))
                .when().post(CORE + "/network-connectors")
                .then().statusCode(400);
        given().contentType("application/json")
                .body(base.formatted("proto", ", \"ClientToken\": \"t2\", \"OperatorRole\": \"arn:aws:iam::000000000000:role/op\"", "\"AssociatedComputeResourceTypes\": [\"MicroVm\"],"))
                .when().post(CORE + "/network-connectors")
                .then().statusCode(400);
    }

    private static String credentialFor(String accountId) {
        return "AWS4-HMAC-SHA256 Credential=" + accountId
                + "/20260801/us-east-1/lambda/aws4_request, SignedHeaders=host, Signature=x";
    }

    @Test
    @Order(15)
    void resourcesAreScopedToTheCallersAccount() {
        String accountA = "111122223333";
        String accountB = "444455556666";
        String name = "microvm-it-tenant-image";

        given()
                .header("Authorization", credentialFor(accountA))
                .contentType("application/json")
                .body("""
                        {
                          "name": "%s",
                          "baseImageArn": "arn:aws:lambda:us-east-1:aws:microvm-image:al2023-1",
                          "buildRoleArn": "arn:aws:iam::111122223333:role/microvm-build",
                          "codeArtifact": { "uri": "s3://bucket/code.zip" }
                        }
                        """.formatted(name))
                .when()
                .post(MICROVMS + "/microvm-images")
                .then()
                .statusCode(201)
                .body("imageArn", startsWith("arn:aws:lambda:us-east-1:" + accountA + ":"));

        // The ARN names account A, so account B must not reach the image by
        // identifier or see it in a list.
        given()
                .header("Authorization", credentialFor(accountB))
                .when()
                .get(MICROVMS + "/microvm-images/" + name)
                .then()
                .statusCode(404);

        given()
                .header("Authorization", credentialFor(accountB))
                .when()
                .get(MICROVMS + "/microvm-images")
                .then()
                .statusCode(200)
                .body("items.name", not(hasItem(name)));

        given()
                .header("Authorization", credentialFor(accountA))
                .when()
                .get(MICROVMS + "/microvm-images/" + name)
                .then()
                .statusCode(200)
                .body("name", equalTo(name));
    }

    @Test
    @Order(16)
    void anUpdateThatChangesTheConnectorMakesTheNextVersion() {
        String create = """
                {
                  "Name": "microvm-it-versions",
                  "ClientToken": "microvm-it-token-versions",
                  "OperatorRole": "arn:aws:iam::000000000000:role/microvm-connector-operator",
                  "Configuration": { "VpcEgressConfiguration": {
                    "AssociatedComputeResourceTypes": ["WebFunction"],
                    "NetworkProtocol": "IPv4",
                    "SubnetIds": ["subnet-0000000000000ver1"],
                    "SecurityGroupIds": ["sg-0000000000000ver1"]
                  } }
                }
                """;
        String arn = given().contentType("application/json").body(create)
                .when().post(CORE + "/network-connectors")
                .then().statusCode(202)
                .body("Version", equalTo(1))
                .body("State", equalTo("PENDING"))
                .extract().path("Arn");
        String id = arn.substring(arn.lastIndexOf(':') + 1);
        given().when().get(CORE + "/network-connectors/microvm-it-versions")
                .then().statusCode(200)
                .body("Id", equalTo(id))
                .body("LatestVersion", equalTo(1))
                .body("LatestVersionArn", equalTo(arn + ":1"))
                .body("$", not(hasKey("Version")));

        String same = """
                { "Configuration": { "VpcEgressConfiguration": {
                    "SubnetIds": ["subnet-0000000000000ver1"], "SecurityGroupIds": ["sg-0000000000000ver1"] } } }
                """;
        given().contentType("application/json").body(same)
                .when().put(CORE + "/network-connectors/" + id)
                .then().statusCode(202)
                .body("Version", equalTo(1))
                .body("LastUpdateStatus", equalTo("Successful"))
                .body("LastUpdateStatusReason", equalTo("No configuration changes detected"));
        String moreSubnets = """
                { "Configuration": { "VpcEgressConfiguration": {
                    "SubnetIds": ["subnet-0000000000000ver1", "subnet-0000000000000ver2"] } } }
                """;
        given().contentType("application/json").body(moreSubnets)
                .when().put(CORE + "/network-connectors/" + id)
                .then().statusCode(202)
                .body("Version", equalTo(2))
                .body("VersionArn", equalTo(arn + ":2"))
                .body("LastUpdateStatus", equalTo("InProgress"))
                .body("LastUpdateStatusReason", equalTo("Update in progress"));
        String reordered = """
                { "Configuration": { "VpcEgressConfiguration": {
                    "SubnetIds": ["subnet-0000000000000ver2", "subnet-0000000000000ver1"] } } }
                """;
        given().contentType("application/json").body(reordered)
                .when().put(CORE + "/network-connectors/" + id)
                .then().statusCode(202)
                .body("Version", equalTo(2))
                .body("LastUpdateStatusReason", equalTo("No configuration changes detected"))
                .body("Configuration.VpcEgressConfiguration.SubnetIds",
                        contains("subnet-0000000000000ver1", "subnet-0000000000000ver2"));
        given().contentType("application/json")
                .body("""
                        { "OperatorRole": "arn:aws:iam::000000000000:role/microvm-connector-operator-v2" }
                        """)
                .when().put(CORE + "/network-connectors/" + id)
                .then().statusCode(202)
                .body("Version", equalTo(3));

        given().when().get(CORE + "/network-connectors/" + arn)
                .then().statusCode(200)
                .body("LatestVersion", equalTo(3))
                .body("StateReason", equalTo("Update initiated"))
                .body("LastUpdateStatus", equalTo("Successful"))
                .body("LastUpdateStatusReason", equalTo("Update was successful"));
        given().when().get(CORE + "/network-connectors/" + id + ":1")
                .then().statusCode(200)
                .body("Version", equalTo(1))
                .body("VersionArn", equalTo(arn + ":1"))
                .body("StateReason", equalTo("Initial creation"))
                .body("OperatorRole", equalTo("arn:aws:iam::000000000000:role/microvm-connector-operator"))
                .body("Configuration.VpcEgressConfiguration.SubnetIds", contains("subnet-0000000000000ver1"))
                .body("$", not(hasKey("LatestVersion")))
                .body("$", not(hasKey("LastUpdateStatus")));
        given().when().get(CORE + "/network-connectors/" + arn + ":2")
                .then().statusCode(200)
                .body("Version", equalTo(2))
                .body("Configuration.VpcEgressConfiguration.SubnetIds",
                        contains("subnet-0000000000000ver1", "subnet-0000000000000ver2"));
        given().when().get(CORE + "/network-connectors/" + arn + ":4")
                .then().statusCode(404)
                .body("message", equalTo("Network connector not found: " + arn + ":4"));
        given().when().get(CORE + "/network-connectors/" + id + "/versions")
                .then().statusCode(200)
                .body("NetworkConnectorVersions.Version", contains(1, 2, 3))
                .body("NetworkConnectorVersions[1].VersionArn", equalTo(arn + ":2"))
                .body("NetworkConnectorVersions[1].StateReason", equalTo("Update initiated"));
        given().when().get(CORE + "/network-connectors/" + id + ":9/versions")
                .then().statusCode(400)
                .body("message", equalTo("Identifier must not include a version qualifier"));
        given().when().get(CORE + "/network-connectors")
                .then().statusCode(200)
                .body("NetworkConnectors.find { it.Id == '%s' }.LatestVersion".formatted(id), equalTo(3));

        given().contentType("application/json")
                .body("""
                        { "Configuration": { "VpcEgressConfiguration": { "AssociatedComputeResourceTypes": ["MicroVm"] } } }
                        """)
                .when().put(CORE + "/network-connectors/" + id)
                .then().statusCode(400)
                .body("message", equalTo("AssociatedComputeResourceTypes cannot be changed on an existing network"
                        + " connector. Current: [WebFunction], requested: [MicroVm]."));

        given().when().delete(CORE + "/network-connectors/" + id + ":3")
                .then().statusCode(400)
                .body("message", equalTo("Cannot delete the latest version of a network connector."));
        given().when().delete(CORE + "/network-connectors/" + id + ":1")
                .then().statusCode(202)
                .body("Version", equalTo(1))
                .body("State", equalTo("DELETING"))
                .body("Configuration.VpcEgressConfiguration.SubnetIds", contains("subnet-0000000000000ver1"));
        given().when().get(CORE + "/network-connectors/" + id + ":1")
                .then().statusCode(404)
                .body("message", equalTo("Network connector not found: " + arn + ":1"));
        given().when().get(CORE + "/network-connectors/" + id + "/versions")
                .then().statusCode(200)
                .body("NetworkConnectorVersions.Version", contains(2, 3));

        given().when().delete(CORE + "/network-connectors/" + id)
                .then().statusCode(202)
                .body("State", equalTo("DELETING"))
                .body("$", not(hasKey("Version")));
        given().when().get(CORE + "/network-connectors/" + id)
                .then().statusCode(404)
                .body("message", equalTo("Network connector not found: " + arn));
    }
}
