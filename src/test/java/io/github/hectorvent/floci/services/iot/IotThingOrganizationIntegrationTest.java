package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

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
