package io.github.hectorvent.floci.services.bedrockagentcorecontrol;

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
class BedrockAgentCoreMemoryIntegrationTest {

    private static String memoryId;

    @Test
    @Order(1)
    void createMemory() {
        memoryId = given().contentType("application/json")
                .body("{\"name\":\"myMemory\",\"eventExpiryDuration\":30}")
                .when().post("/memories/create")
                .then().statusCode(202)
                .body("memory.id", notNullValue())
                .body("memory.name", equalTo("myMemory"))
                .body("memory.status", equalTo("ACTIVE"))
                .body("memory.arn", containsString(":memory/"))
                .extract().path("memory.id");
    }

    @Test
    @Order(2)
    void getAndListMemory() {
        given().when().get("/memories/" + memoryId + "/details")
                .then().statusCode(200)
                .body("memory.name", equalTo("myMemory"))
                .body("memory.eventExpiryDuration", equalTo(30));

        given().contentType("application/json").body("{}")
                .when().post("/memories/")
                .then().statusCode(200)
                .body("memories.id", hasItem(memoryId));
    }

    @Test
    @Order(3)
    void updateMemory() {
        given().contentType("application/json").body("{\"description\":\"updated\"}")
                .when().put("/memories/" + memoryId + "/update")
                .then().statusCode(202)
                .body("memory.description", equalTo("updated"));
    }

    @Test
    @Order(4)
    void deleteMemory() {
        given().when().delete("/memories/" + memoryId + "/delete")
                .then().statusCode(202)
                .body("status", equalTo("DELETING"));

        given().when().get("/memories/" + memoryId + "/details")
                .then().statusCode(404);
    }

    @Test
    @Order(5)
    void createMemoryRequiresExpiry() {
        given().contentType("application/json").body("{\"name\":\"noExpiry\"}")
                .when().post("/memories/create")
                .then().statusCode(400);
    }

    @Test
    @Order(6)
    void eventExpiryDurationBoundaries() {
        // In range (3..365) → accepted.
        for (int v : new int[]{3, 365}) {
            given().contentType("application/json")
                    .body("{\"name\":\"memOk" + v + "\",\"eventExpiryDuration\":" + v + "}")
                    .when().post("/memories/create").then().statusCode(202);
        }
        // Out of range → 400.
        for (int v : new int[]{2, 366}) {
            given().contentType("application/json")
                    .body("{\"name\":\"memBad" + v + "\",\"eventExpiryDuration\":" + v + "}")
                    .when().post("/memories/create").then().statusCode(400);
        }
    }
}
