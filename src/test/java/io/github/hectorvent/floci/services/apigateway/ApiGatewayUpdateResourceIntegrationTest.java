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

    private String createApi(String name) {
        return given()
                .contentType("application/json")
                .body("{\"name\":\"" + name + "\"}")
                .when()
                .post("/restapis")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private String rootResourceId(String apiId) {
        return given()
                .pathParam("apiId", apiId)
                .when()
                .get("/restapis/{apiId}/resources")
                .then()
                .statusCode(200)
                .extract()
                .path("item[0].id");
    }

    private String createChild(String apiId, String parentId, String pathPart) {
        return given()
                .pathParam("apiId", apiId)
                .pathParam("parentId", parentId)
                .contentType("application/json")
                .body("{\"pathPart\":\"" + pathPart + "\"}")
                .when()
                .post("/restapis/{apiId}/resources/{parentId}")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    /** AWS returns 400 when /parentId names an id that does not exist, not a 404 about the patched resource. */
    @Test
    void testUpdateResourceWithUnknownParentIdReturnsBadRequest() {
        String apiId = createApi("resource-bad-parent");
        String rootId = rootResourceId(apiId);
        String childId = createChild(apiId, rootId, "thing");

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", childId)
                .contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/parentId\",\"value\":\"nosuchid\"}]}")
                .when()
                .patch("/restapis/{apiId}/resources/{resourceId}")
                .then()
                .statusCode(400);
    }

    /** Two siblings may not share a pathPart; AWS returns ConflictException. */
    @Test
    void testUpdateResourceRejectsSiblingPathCollision() {
        String apiId = createApi("resource-collision-patch");
        String rootId = rootResourceId(apiId);
        createChild(apiId, rootId, "alpha");
        String betaId = createChild(apiId, rootId, "beta");

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", betaId)
                .contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/pathPart\",\"value\":\"alpha\"}]}")
                .when()
                .patch("/restapis/{apiId}/resources/{resourceId}")
                .then()
                .statusCode(409);

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", betaId)
                .when()
                .get("/restapis/{apiId}/resources/{resourceId}")
                .then()
                .statusCode(200)
                .body("pathPart", equalTo("beta"))
                .body("path", equalTo("/beta"));
    }

    @Test
    void testUpdateResourceRejectsCollisionAfterReparenting() {
        String apiId = createApi("resource-collision-reparent");
        String rootId = rootResourceId(apiId);
        String leftId = createChild(apiId, rootId, "left");
        String rightId = createChild(apiId, rootId, "right");
        createChild(apiId, rightId, "shared");
        String movingId = createChild(apiId, leftId, "shared");

        given()
                .pathParam("apiId", apiId)
                .pathParam("resourceId", movingId)
                .contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"replace\",\"path\":\"/parentId\",\"value\":\"" + rightId + "\"}]}")
                .when()
                .patch("/restapis/{apiId}/resources/{resourceId}")
                .then()
                .statusCode(409);
    }

    @Test
    void testCreateResourceRejectsDuplicateSiblingPathPart() {
        String apiId = createApi("resource-collision-create");
        String rootId = rootResourceId(apiId);
        createChild(apiId, rootId, "dup");

        given()
                .pathParam("apiId", apiId)
                .pathParam("parentId", rootId)
                .contentType("application/json")
                .body("{\"pathPart\":\"dup\"}")
                .when()
                .post("/restapis/{apiId}/resources/{parentId}")
                .then()
                .statusCode(409);
    }
}
