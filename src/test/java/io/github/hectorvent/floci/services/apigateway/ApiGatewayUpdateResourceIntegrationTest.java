package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class ApiGatewayUpdateResourceIntegrationTest {

    @Test
    void testUpdateResourcePathAndParent() {
        // 1. Create API
        String apiId = given()
                .contentType("application/json")
                .body("{\"name\":\"resource-update\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // 2. Get root resource ID
        String rootId = given()
                .pathParam("apiId", apiId)
                .when()
                .get("/restapis/{apiId}/resources")
                .then()
                .statusCode(200)
                .extract()
                .path("item[0].id");

        // 3. Create root children: "left" and "right"
        String leftId = given()
                .pathParam("apiId", apiId)
                .pathParam("parentId", rootId)
                .contentType("application/json")
                .body("{\"pathPart\":\"left\"}")
                .when()
                .post("/restapis/{apiId}/resources/{parentId}")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        String rightId = given()
                .pathParam("apiId", apiId)
                .pathParam("parentId", rootId)
                .contentType("application/json")
                .body("{\"pathPart\":\"right\"}")
                .when()
                .post("/restapis/{apiId}/resources/{parentId}")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // 4. Create child under left
        String childId = given()
                .pathParam("apiId", apiId)
                .pathParam("parentId", leftId)
                .contentType("application/json")
                .body("{\"pathPart\":\"child\"}")
                .when()
                .post("/restapis/{apiId}/resources/{parentId}")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // 5. Create grand under child
        String grandId = given()
                .pathParam("apiId", apiId)
                .pathParam("parentId", childId)
                .contentType("application/json")
                .body("{\"pathPart\":\"grand\"}")
                .when()
                .post("/restapis/{apiId}/resources/{parentId}")
                .then()
                .statusCode(201)
                .extract()
                .path("id");

        // 6. PATCH child: move to right, rename to "moved"
        String patchBody = String.format(
                "{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/pathPart\",\"value\":\"moved\"},{\"op\":\"replace\",\"path\":\"/parentId\",\"value\":\"%s\"}]}",
                rightId
        );

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", childId)
                .contentType("application/json")
                .body(patchBody)
                .when()
                .patch("/restapis/{apiId}/resources/{resourceId}")
                .then()
                .statusCode(200)
                .body("parentId", equalTo(rightId))
                .body("pathPart", equalTo("moved"))
                .body("path", equalTo("/right/moved"));

        // 7. GET grand: should now be under moved (which is under right)
        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", grandId)
                .when()
                .get("/restapis/{apiId}/resources/{resourceId}")
                .then()
                .statusCode(200)
                .body("parentId", equalTo(childId))
                .body("path", equalTo("/right/moved/grand"));

        // 8. GET child: verify updated state
        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", childId)
                .when()
                .get("/restapis/{apiId}/resources/{resourceId}")
                .then()
                .statusCode(200)
                .body("parentId", equalTo(rightId))
                .body("pathPart", equalTo("moved"))
                .body("path", equalTo("/right/moved"));
    }
}
