package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Drives fleet indexing the way the AWS SDKs do: UpdateIndexingConfiguration and
 * GetIndexingConfiguration on {@code /indexing/config}, DescribeIndex on {@code /indices/{indexName}}.
 * Every test signs as its own account, so the configurations they store never meet.
 */
@QuarkusTest
class IotFleetIndexingIntegrationTest {

    private static String auth(String account, String region) {
        return "AWS4-HMAC-SHA256 Credential=" + account + "/20260215/" + region
                + "/iot/aws4_request, SignedHeaders=host, Signature=abc";
    }

    private static void update(String auth, String body) {
        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body(body)
        .when()
            .post("/indexing/config")
        .then()
            .statusCode(200)
            .body(equalTo("{}"));
    }

    @Test
    void getBeforeAnyUpdateReturnsTheOffShape() {
        given()
            .header("Authorization", auth("111100000001", "us-east-1"))
        .when()
            .get("/indexing/config")
        .then()
            .statusCode(200)
            .body("thingIndexingConfiguration", aMapWithSize(5))
            .body("thingIndexingConfiguration.thingIndexingMode", equalTo("OFF"))
            .body("thingIndexingConfiguration.thingConnectivityIndexingMode", equalTo("OFF"))
            .body("thingIndexingConfiguration.deviceDefenderIndexingMode", equalTo("OFF"))
            .body("thingIndexingConfiguration.namedShadowIndexingMode", equalTo("OFF"))
            .body("thingIndexingConfiguration.filter", anEmptyMap())
            .body("thingGroupIndexingConfiguration", aMapWithSize(1))
            .body("thingGroupIndexingConfiguration.thingGroupIndexingMode", equalTo("OFF"));
    }

    @Test
    void updateThenGetRoundTripsTheWireShape() {
        String auth = auth("111100000002", "us-east-1");
        update(auth, """
            {
              "thingIndexingConfiguration": {
                "thingIndexingMode": "REGISTRY_AND_SHADOW",
                "thingConnectivityIndexingMode": "STATUS",
                "namedShadowIndexingMode": "ON",
                "customFields": [{"name": "attributes.site", "type": "String"}],
                "filter": {
                  "namedShadowNames": ["config"],
                  "geoLocations": [{"name": "shadow.reported.location", "order": "LatLon"}]
                }
              },
              "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}
            }
            """);

        given()
            .header("Authorization", auth)
        .when()
            .get("/indexing/config")
        .then()
            .statusCode(200)
            .body("thingIndexingConfiguration.thingIndexingMode", equalTo("REGISTRY_AND_SHADOW"))
            .body("thingIndexingConfiguration.thingConnectivityIndexingMode", equalTo("STATUS"))
            .body("thingIndexingConfiguration.deviceDefenderIndexingMode", equalTo("OFF"))
            .body("thingIndexingConfiguration.namedShadowIndexingMode", equalTo("ON"))
            .body("thingIndexingConfiguration.managedFields", hasSize(17))
            .body("thingIndexingConfiguration.managedFields.name", hasItems("thingName", "registry.thingGroupNames",
                    "shadow.hasDelta", "shadow.name.*.version", "connectivity.connected", "connectivity.sessionExpiry"))
            .body("thingIndexingConfiguration.managedFields.find { it.name == 'connectivity.connected' }.type",
                    equalTo("Boolean"))
            .body("thingIndexingConfiguration.customFields", hasSize(1))
            .body("thingIndexingConfiguration.customFields[0].name", equalTo("attributes.site"))
            .body("thingIndexingConfiguration.customFields[0].type", equalTo("String"))
            .body("thingIndexingConfiguration.filter.namedShadowNames", contains("config"))
            .body("thingIndexingConfiguration.filter.geoLocations", hasSize(1))
            .body("thingIndexingConfiguration.filter.geoLocations[0].name", equalTo("shadow.reported.location"))
            .body("thingIndexingConfiguration.filter.geoLocations[0].order", equalTo("LatLon"))
            .body("thingIndexingConfiguration.filter.connectivity.includeSocketInformation", empty())
            .body("thingGroupIndexingConfiguration.thingGroupIndexingMode", equalTo("ON"))
            .body("thingGroupIndexingConfiguration.managedFields.name", containsInAnyOrder("parentGroupNames",
                    "description", "version", "thingGroupName", "thingGroupId"));
    }

    @Test
    void describeIndexReportsAnActiveSchemaUntilIndexingIsTurnedOff() {
        String auth = auth("111100000003", "us-east-1");
        update(auth, """
            {
              "thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "thingConnectivityIndexingMode": "STATUS"},
              "thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}
            }
            """);

        given()
            .header("Authorization", auth)
        .when()
            .get("/indices/AWS_Things")
        .then()
            .statusCode(200)
            .body("indexName", equalTo("AWS_Things"))
            .body("indexStatus", equalTo("ACTIVE"))
            .body("schema", equalTo("REGISTRY_AND_CONNECTIVITY_STATUS"));
        given()
            .header("Authorization", auth)
        .when()
            .get("/indices/AWS_ThingGroups")
        .then()
            .statusCode(200)
            .body("indexName", equalTo("AWS_ThingGroups"))
            .body("indexStatus", equalTo("ACTIVE"))
            .body("schema", equalTo("REGISTRY"));

        update(auth, """
            {"thingIndexingConfiguration": {"thingIndexingMode": "OFF"}}
            """);

        given()
            .header("Authorization", auth)
        .when()
            .get("/indices/AWS_Things")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("Index AWS_Things does not exist"));
        given()
            .header("Authorization", auth)
        .when()
            .get("/indices/AWS_ThingGroups")
        .then()
            .statusCode(200)
            .body("schema", equalTo("REGISTRY"));
        given()
            .header("Authorization", auth)
        .when()
            .get("/indexing/config")
        .then()
            .statusCode(200)
            .body("thingIndexingConfiguration.thingIndexingMode", equalTo("OFF"))
            .body("thingIndexingConfiguration.thingConnectivityIndexingMode", equalTo("OFF"))
            .body("thingIndexingConfiguration", not(hasKey("managedFields")))
            .body("thingIndexingConfiguration.filter", anEmptyMap())
            .body("thingGroupIndexingConfiguration.thingGroupIndexingMode", equalTo("ON"));
        given()
            .header("Authorization", auth)
        .when()
            .get("/indices/Nope")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("Unrecognized indexName Nope"));
    }

    @Test
    void configurationIsIsolatedPerAccountAndRegion() {
        String home = auth("111100000004", "us-east-1");
        update(home, """
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"}}
            """);

        given()
            .header("Authorization", auth("111100000005", "us-east-1"))
        .when()
            .get("/indexing/config")
        .then()
            .statusCode(200)
            .body("thingIndexingConfiguration.thingIndexingMode", equalTo("OFF"));
        given()
            .header("Authorization", auth("111100000005", "us-east-1"))
        .when()
            .get("/indices/AWS_Things")
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
        given()
            .header("Authorization", auth("111100000004", "eu-west-1"))
        .when()
            .get("/indexing/config")
        .then()
            .statusCode(200)
            .body("thingIndexingConfiguration.thingIndexingMode", equalTo("OFF"));
        given()
            .header("Authorization", home)
        .when()
            .get("/indexing/config")
        .then()
            .statusCode(200)
            .body("thingIndexingConfiguration.thingIndexingMode", equalTo("REGISTRY"));
    }

    @Test
    void invalidUpdatesAreRejectedAndStoreNothing() {
        String auth = auth("111100000006", "us-east-1");
        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body("{\"thingIndexingConfiguration\": ")
        .when()
            .post("/indexing/config")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body("""
                {"thingIndexingConfiguration": {}}
                """)
        .when()
            .post("/indexing/config")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("1 validation error detected: Value null at "
                    + "'thingIndexingConfiguration.thingIndexingMode' failed to satisfy constraint: "
                    + "Member must not be null"));
        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body("""
                {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY",
                  "managedFields": [{"name": "shadow.name.building.reported.type", "type": "String"}]}}
                """)
        .when()
            .post("/indexing/config")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", startsWith("Only managed fields with expected types are allowed"));

        given()
            .header("Authorization", auth)
        .when()
            .get("/indexing/config")
        .then()
            .statusCode(200)
            .body("thingIndexingConfiguration.thingIndexingMode", equalTo("OFF"));
    }
}
