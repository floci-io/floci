package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class IotThingOrganizationIntegrationTest {

    @Test
    void thingTypesRoundTripAndTypedThingsDescribeTypeName() {
        given()
            .contentType("application/json")
            .body("""
                {
                  "thingTypeProperties": {
                    "thingTypeDescription": "sensor devices",
                    "searchableAttributes": ["model"]
                  }
                }
                """)
        .when()
            .post("/thing-types/mvp2-sensor")
        .then()
            .statusCode(200)
            .body("thingTypeName", equalTo("mvp2-sensor"))
            .body("thingTypeArn", notNullValue())
            .body("thingTypeId", notNullValue());

        given()
        .when()
            .get("/thing-types/mvp2-sensor")
        .then()
            .statusCode(200)
            .body("thingTypeName", equalTo("mvp2-sensor"))
            .body("thingTypeProperties.thingTypeDescription", equalTo("sensor devices"))
            .body("thingTypeProperties.searchableAttributes", hasItem("model"))
            .body("thingTypeMetadata.deprecated", equalTo(false));

        given()
        .when()
            .get("/thing-types")
        .then()
            .statusCode(200)
            .body("thingTypes.thingTypeName", hasItem("mvp2-sensor"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "thingTypeProperties": {
                    "thingTypeDescription": "updated sensors",
                    "searchableAttributes": ["model", "firmware"]
                  }
                }
                """)
        .when()
            .patch("/thing-types/mvp2-sensor")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                {
                  "thingTypeName": "mvp2-sensor",
                  "attributePayload": {"attributes": {"model": "a1"}}
                }
                """)
        .when()
            .post("/things/mvp2-typed-thing")
        .then()
            .statusCode(200)
            .body("thingTypeName", equalTo("mvp2-sensor"));

        given()
        .when()
            .get("/things/mvp2-typed-thing")
        .then()
            .statusCode(200)
            .body("thingTypeName", equalTo("mvp2-sensor"));

        given()
        .when()
            .post("/thing-types/mvp2-sensor/deprecate")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/thing-types/mvp2-sensor")
        .then()
            .statusCode(200)
            .body("thingTypeMetadata.deprecated", equalTo(true));

        given()
        .when()
            .delete("/things/mvp2-typed-thing")
        .then()
            .statusCode(200);

        given()
        .when()
            .delete("/thing-types/mvp2-sensor")
        .then()
            .statusCode(200);
    }

    @Test
    void updateThingSetsAndRemovesTheThingType() {
        String type = "update-type-sensor";
        String thing = "update-type-thing";
        given()
            .contentType("application/json")
            .body("{\"thingTypeProperties\": {\"thingTypeDescription\": \"sensors\"}}")
        .when()
            .post("/thing-types/" + type)
        .then()
            .statusCode(200);
        given()
            .contentType("application/json")
            .body("{\"attributePayload\": {\"attributes\": {\"model\": \"a1\"}}}")
        .when()
            .post("/things/" + thing)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"thingTypeName\": \"" + type + "\"}")
        .when()
            .patch("/things/" + thing)
        .then()
            .statusCode(200);
        given()
        .when()
            .get("/things/" + thing)
        .then()
            .statusCode(200)
            .body("thingTypeName", equalTo(type))
            .body("attributes.model", equalTo("a1"));

        given()
            .contentType("application/json")
            .body("{\"removeThingType\": true}")
        .when()
            .patch("/things/" + thing)
        .then()
            .statusCode(200);
        given()
        .when()
            .get("/things/" + thing)
        .then()
            .statusCode(200)
            .body("thingTypeName", nullValue())
            .body("attributes.model", equalTo("a1"));
        given()
        .when()
            .delete("/thing-types/" + type)
        .then()
            .statusCode(200);

        given()
        .when()
            .delete("/things/" + thing)
        .then()
            .statusCode(200);
    }

    @Test
    void updateThingRefusesAMissingOrDeprecatedThingTypeAndAConflictingRemoval() {
        String type = "update-type-deprecated";
        String thing = "update-type-refused";
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/things/" + thing)
        .then()
            .statusCode(200);
        given()
            .contentType("application/json")
            .body("{\"thingTypeProperties\": {}}")
        .when()
            .post("/thing-types/" + type)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"thingTypeName\": \"update-type-missing\"}")
        .when()
            .patch("/things/" + thing)
        .then()
            .statusCode(404)
            .body("__type", equalTo("ResourceNotFoundException"));
        given()
            .contentType("application/json")
            .body("{\"thingTypeName\": \"" + type + "\", \"removeThingType\": true}")
        .when()
            .patch("/things/" + thing)
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        given()
        .when()
            .post("/thing-types/" + type + "/deprecate")
        .then()
            .statusCode(200);
        given()
            .contentType("application/json")
            .body("{\"thingTypeName\": \"" + type + "\"}")
        .when()
            .patch("/things/" + thing)
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidRequestException"));
        given()
        .when()
            .get("/things/" + thing)
        .then()
            .statusCode(200)
            .body("thingTypeName", nullValue())
            .body("version", equalTo(1));

        given()
        .when()
            .delete("/things/" + thing)
        .then()
            .statusCode(200);
        given()
        .when()
            .delete("/thing-types/" + type)
        .then()
            .statusCode(200);
    }

    @Test
    void updateThingGroupKeepsOrMergesAttributesAsTheAttributePayloadAsks() {
        String group = "update-semantics-group";
        given()
            .contentType("application/json")
            .body("{\"thingGroupProperties\": {\"attributePayload\": {\"attributes\": {\"fleet\": \"alpha\", \"site\": \"north\"}}}}")
        .when()
            .post("/thing-groups/" + group)
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"thingGroupProperties\": {\"thingGroupDescription\": \"renamed\"}}")
        .when()
            .patch("/thing-groups/" + group)
        .then()
            .statusCode(200)
            .body("version", equalTo(2));
        given()
        .when()
            .get("/thing-groups/" + group)
        .then()
            .statusCode(200)
            .body("thingGroupProperties.attributePayload.attributes", equalTo(Map.of("fleet", "alpha", "site", "north")));

        given()
            .contentType("application/json")
            .body("""
                {"thingGroupProperties": {"attributePayload": {"attributes": {"fleet": "beta", "site": ""}, "merge": true}}}
                """)
        .when()
            .patch("/thing-groups/" + group)
        .then()
            .statusCode(200)
            .body("version", equalTo(3));
        given()
        .when()
            .get("/thing-groups/" + group)
        .then()
            .statusCode(200)
            .body("thingGroupProperties.attributePayload.attributes", equalTo(Map.of("fleet", "beta")));

        given()
        .when()
            .delete("/thing-groups/" + group)
        .then()
            .statusCode(200);
    }

    @Test
    void thingGroupsRoundTripAndMembershipIsObservable() {
        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post("/things/mvp2-group-thing")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("""
                {
                  "thingGroupProperties": {
                    "thingGroupDescription": "mvp2 group",
                    "attributePayload": {"attributes": {"fleet": "alpha"}}
                  }
                }
                """)
        .when()
            .post("/thing-groups/mvp2-static-group")
        .then()
            .statusCode(200)
            .body("thingGroupName", equalTo("mvp2-static-group"))
            .body("thingGroupArn", notNullValue())
            .body("thingGroupId", notNullValue());

        given()
        .when()
            .get("/thing-groups/mvp2-static-group")
        .then()
            .statusCode(200)
            .body("thingGroupName", equalTo("mvp2-static-group"))
            .body("thingGroupProperties.thingGroupDescription", equalTo("mvp2 group"))
            .body("thingGroupProperties.attributePayload.attributes.fleet", equalTo("alpha"));

        given()
        .when()
            .get("/thing-groups")
        .then()
            .statusCode(200)
            .body("thingGroups.groupName", hasItem("mvp2-static-group"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "thingGroupName": "mvp2-static-group",
                  "thingName": "mvp2-group-thing"
                }
                """)
        .when()
            .put("/thing-groups/addThingToThingGroup")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/thing-groups/mvp2-static-group/things")
        .then()
            .statusCode(200)
            .body("things", hasItem("mvp2-group-thing"));

        given()
        .when()
            .get("/things/mvp2-group-thing/thing-groups")
        .then()
            .statusCode(200)
            .body("thingGroups.groupName", hasItem("mvp2-static-group"));

        given()
            .contentType("application/json")
            .body("""
                {
                  "thingGroupProperties": {
                    "thingGroupDescription": "updated group",
                    "attributePayload": {"attributes": {"fleet": "beta"}}
                  },
                  "expectedVersion": 1
                }
                """)
        .when()
            .patch("/thing-groups/mvp2-static-group")
        .then()
            .statusCode(200)
            .body("version", equalTo(2));

        given()
            .contentType("application/json")
            .body("""
                {
                  "thingGroupName": "mvp2-static-group",
                  "thingName": "mvp2-group-thing"
                }
                """)
        .when()
            .put("/thing-groups/removeThingFromThingGroup")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/thing-groups/mvp2-static-group/things")
        .then()
            .statusCode(200)
            .body("things", not(hasItem("mvp2-group-thing")));

        given()
        .when()
            .delete("/thing-groups/mvp2-static-group")
        .then()
            .statusCode(200);
    }
}
