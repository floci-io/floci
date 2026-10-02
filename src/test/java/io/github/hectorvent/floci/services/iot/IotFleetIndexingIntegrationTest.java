package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Drives fleet indexing the way the AWS SDKs do: UpdateIndexingConfiguration and
 * GetIndexingConfiguration on {@code /indexing/config}, DescribeIndex on {@code /indices/{indexName}}
 * and SearchIndex on {@code /indices/search}. Every test signs as its own account, so the
 * configurations and things they store never meet.
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

    private static void post(String auth, String path, String body) {
        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body(body)
        .when()
            .post(path)
        .then()
            .statusCode(200);
    }

    private static ValidatableResponse search(String auth, String body) {
        return given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body(body)
        .when()
            .post("/indices/search")
        .then();
    }

    @Test
    void searchIndexReturnsTheSdkWireShape() {
        String auth = auth("111100000007", "us-east-1");
        update(auth, """
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY", "thingConnectivityIndexingMode": "STATUS"}}
            """);
        post(auth, "/thing-types/search-sensor", "{}");
        post(auth, "/thing-groups/search-north", "{}");
        post(auth, "/things/search-a", """
            {"thingTypeName": "search-sensor", "attributePayload": {"attributes": {"provider": "Acme", "site": "north"}}}
            """);
        post(auth, "/things/search-b", """
            {"attributePayload": {"attributes": {"provider": "acme"}}}
            """);
        post(auth, "/things/search-c", "{}");
        given()
            .header("Authorization", auth)
            .contentType("application/json")
            .body("""
                {"thingGroupName": "search-north", "thingName": "search-a"}
                """)
        .when()
            .put("/thing-groups/addThingToThingGroup")
        .then()
            .statusCode(200);

        search(auth, """
            {"queryString": "attributes.provider:acme AND thingTypeName:search-sensor"}
            """)
            .statusCode(200)
            .body("$", aMapWithSize(1))
            .body("things", hasSize(1))
            .body("things[0]", aMapWithSize(6))
            .body("things[0].thingName", equalTo("search-a"))
            .body("things[0].thingId", not(emptyOrNullString()))
            .body("things[0].thingTypeName", equalTo("search-sensor"))
            .body("things[0].thingGroupNames", contains("search-north"))
            .body("things[0].attributes.provider", equalTo("Acme"))
            .body("things[0].attributes.site", equalTo("north"))
            .body("things[0].connectivity", aMapWithSize(3))
            .body("things[0].connectivity.clientId", equalTo("search-a"))
            .body("things[0].connectivity.connected", equalTo(false))
            .body("things[0].connectivity.timestamp", equalTo(0));
        search(auth, """
            {"queryString": "thingName:search-c", "queryVersion": "2017-09-30", "indexName": "AWS_Things"}
            """)
            .statusCode(200)
            .body("things[0]", aMapWithSize(3))
            .body("things[0].thingName", equalTo("search-c"))
            .body("things[0]", hasKey("connectivity"));

        String nextToken = search(auth, """
            {"queryString": "thingName:search-*", "maxResults": 2}
            """)
            .statusCode(200)
            .body("things.thingName", contains("search-a", "search-b"))
            .body("nextToken", notNullValue())
            .extract().path("nextToken");
        search(auth, "{\"queryString\": \"thingName:search-*\", \"maxResults\": 2, \"nextToken\": \"" + nextToken + "\"}")
            .statusCode(200)
            .body("things.thingName", contains("search-c"))
            .body("$", not(hasKey("nextToken")));
    }

    @Test
    void searchIndexRejectsBadRequestsWithAwsErrors() {
        String auth = auth("111100000008", "us-east-1");
        update(auth, """
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"}}
            """);

        search(auth, "{}")
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("1 validation error detected: Value null at 'queryString' failed to satisfy "
                    + "constraint: Member must not be null"));
        search(auth, """
            {"queryString": "*", "queryVersion": "2017-09-31"}
            """)
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("Invalid queryVersion. Expected one of: [2017-09-30]"));
        search(auth, """
            {"queryString": "*", "nextToken": "bogus"}
            """)
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("Invalid nextToken"));
        search(auth, """
            {"queryString": "ThingName:x"}
            """)
            .statusCode(400)
            .body("__type", equalTo("InvalidQueryException"))
            .body("message", equalTo("Unable to parse query, invalid field name, field name: ThingName, "
                    + "query string: ThingName:x"));
        search(auth, """
            {"queryString": "thingName>a"}
            """)
            .statusCode(400)
            .body("__type", equalTo("InvalidQueryException"))
            .body("message", equalTo("Floci does not support comparisons in fleet index queries, "
                    + "query string: thingName>a"));
        search(auth, """
            {"queryString": "connectivity.connected:true"}
            """)
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("Query includes one or more constraints for Connectivity attribute, "
                    + "but Connectivity indexing is not enabled for AWS_Things index"));
    }

    @Test
    void searchIndexOfADisabledIndexIsNotFound() {
        String auth = auth("111100000009", "us-east-1");

        search(auth, """
            {"queryString": "*"}
            """)
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("Index AWS_Things does not exist. "
                    + "Please enable index by calling UpdateIndexingConfiguration"));
        search(auth, """
            {"queryString": "*", "indexName": "AWS_ThingGroups"}
            """)
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"))
            .body("message", equalTo("Index AWS_ThingGroups does not exist. "
                    + "Please enable index by calling UpdateIndexingConfiguration"));

        update(auth, """
            {"thingGroupIndexingConfiguration": {"thingGroupIndexingMode": "ON"}}
            """);
        search(auth, """
            {"queryString": "*", "indexName": "AWS_ThingGroups"}
            """)
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"))
            .body("message", equalTo("Floci does not support searching AWS_ThingGroups"));
    }

    @Test
    void searchIndexIsIsolatedPerAccountAndRegion() {
        String home = auth("111100000010", "us-east-1");
        String otherAccount = auth("111100000011", "us-east-1");
        String otherRegion = auth("111100000010", "eu-west-1");
        String indexing = """
            {"thingIndexingConfiguration": {"thingIndexingMode": "REGISTRY"}}
            """;
        update(home, indexing);
        update(otherAccount, indexing);
        update(otherRegion, indexing);
        post(home, "/things/isolated-thing", "{}");
        String query = """
            {"queryString": "thingName:isolated-thing"}
            """;

        search(home, query)
            .statusCode(200)
            .body("things.thingName", contains("isolated-thing"));
        search(otherAccount, query)
            .statusCode(200)
            .body("things", empty());
        search(otherRegion, query)
            .statusCode(200)
            .body("things", empty());
    }
}
