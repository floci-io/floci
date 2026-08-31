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
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BedrockAgentCoreMemoryIntegrationTest {

    private static String memoryId;

    @Test
    @Order(1)
    void createMemory() {
        memoryId = given().contentType("application/json")
                .body("""
                        {
                          "name": "myMemory",
                          "eventExpiryDuration": 30,
                          "encryptionKeyArn": "arn:aws:kms:us-east-1:000000000000:key/mem-key",
                          "memoryExecutionRoleArn": "arn:aws:iam::000000000000:role/mem-role",
                          "tags": {"team": "core"}
                        }""")
                .when().post("/memories/create")
                .then().statusCode(202)
                .body("memory.id", notNullValue())
                .body("memory.name", equalTo("myMemory"))
                .body("memory.status", equalTo("ACTIVE"))
                .body("memory.arn", containsString(":memory/"))
                .body("memory.encryptionKeyArn", equalTo("arn:aws:kms:us-east-1:000000000000:key/mem-key"))
                .body("memory.memoryExecutionRoleArn", equalTo("arn:aws:iam::000000000000:role/mem-role"))
                // The AWS Memory shape has no tags field; tags surface only via ListTagsForResource.
                .body("memory.tags", nullValue())
                .extract().path("memory.id");
    }

    @Test
    @Order(2)
    void getAndListMemory() {
        given().when().get("/memories/" + memoryId + "/details")
                .then().statusCode(200)
                .body("memory.name", equalTo("myMemory"))
                .body("memory.eventExpiryDuration", equalTo(30))
                .body("memory.encryptionKeyArn", equalTo("arn:aws:kms:us-east-1:000000000000:key/mem-key"))
                .body("memory.memoryExecutionRoleArn", equalTo("arn:aws:iam::000000000000:role/mem-role"));

        given().contentType("application/json").body("{}")
                .when().post("/memories/")
                .then().statusCode(200)
                .body("memories.id", hasItem(memoryId))
                // ListMemories returns slim summaries without the create-time fields.
                .body("memories.encryptionKeyArn", hasItem(nullValue()));
    }

    @Test
    @Order(3)
    void updateMemory() {
        given().contentType("application/json")
                .body("{\"description\":\"updated\",\"eventExpiryDuration\":60,"
                        + "\"memoryExecutionRoleArn\":\"arn:aws:iam::000000000000:role/mem-role-2\"}")
                .when().put("/memories/" + memoryId + "/update")
                .then().statusCode(202)
                .body("memory.description", equalTo("updated"))
                .body("memory.eventExpiryDuration", equalTo(60))
                .body("memory.memoryExecutionRoleArn", equalTo("arn:aws:iam::000000000000:role/mem-role-2"));

        given().when().get("/memories/" + memoryId + "/details")
                .then().statusCode(200)
                .body("memory.eventExpiryDuration", equalTo(60))
                .body("memory.memoryExecutionRoleArn", equalTo("arn:aws:iam::000000000000:role/mem-role-2"))
                .body("memory.encryptionKeyArn", equalTo("arn:aws:kms:us-east-1:000000000000:key/mem-key"));
    }

    @Test
    @Order(4)
    void updateMemoryRejectsOutOfRangeExpiryWithoutPartialMutation() {
        given().contentType("application/json")
                .body("{\"description\":\"should-not-stick\",\"eventExpiryDuration\":2}")
                .when().put("/memories/" + memoryId + "/update")
                .then().statusCode(400);

        // The rejected update must not have applied any of its fields.
        given().when().get("/memories/" + memoryId + "/details")
                .then().statusCode(200)
                .body("memory.description", equalTo("updated"))
                .body("memory.eventExpiryDuration", equalTo(60));
    }

    @Test
    @Order(5)
    void deleteMemory() {
        given().when().delete("/memories/" + memoryId + "/delete")
                .then().statusCode(202)
                .body("status", equalTo("DELETING"));

        given().when().get("/memories/" + memoryId + "/details")
                .then().statusCode(404);
    }

    @Test
    @Order(6)
    void createMemoryRequiresExpiry() {
        given().contentType("application/json").body("{\"name\":\"noExpiry\"}")
                .when().post("/memories/create")
                .then().statusCode(400);
    }

    @Test
    @Order(7)
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
