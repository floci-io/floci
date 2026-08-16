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
class BedrockAgentCoreGatewayIntegrationTest {

    private static final String CREATE = """
            {"name":"myGateway","authorizerType":"AWS_IAM","roleArn":"arn:aws:iam::000000000000:role/gw"}""";

    private static String gatewayId;
    private static String targetId;

    @Test
    @Order(1)
    void createGateway() {
        gatewayId = given().contentType("application/json").body(CREATE)
                .when().post("/gateways/")
                .then().statusCode(202)
                .body("gatewayId", notNullValue())
                .body("gatewayArn", containsString(":gateway/"))
                .body("status", equalTo("READY"))
                .body("workloadIdentityDetails.workloadIdentityArn", notNullValue())
                .extract().path("gatewayId");
    }

    @Test
    @Order(2)
    void getAndListGateway() {
        given().when().get("/gateways/" + gatewayId + "/")
                .then().statusCode(200)
                .body("name", equalTo("myGateway"))
                .body("protocolType", equalTo("MCP"));

        given().when().get("/gateways/")
                .then().statusCode(200)
                .body("items.gatewayId", hasItem(gatewayId));
    }

    @Test
    @Order(3)
    void createAndGetTarget() {
        targetId = given().contentType("application/json")
                .body("{\"name\":\"t1\",\"targetConfiguration\":{\"mcp\":{\"lambda\":{}}}}")
                .when().post("/gateways/" + gatewayId + "/targets/")
                .then().statusCode(202)
                .body("targetId", notNullValue())
                .body("gatewayArn", containsString(":gateway/"))
                .extract().path("targetId");

        given().when().get("/gateways/" + gatewayId + "/targets/" + targetId + "/")
                .then().statusCode(200)
                .body("targetId", equalTo(targetId))
                .body("targetConfiguration.mcp.lambda", notNullValue());

        given().when().get("/gateways/" + gatewayId + "/targets/")
                .then().statusCode(200)
                .body("items.targetId", hasItem(targetId));
    }

    @Test
    @Order(4)
    void updateGatewayAndTarget() {
        given().contentType("application/json")
                .body("{\"name\":\"myGateway\",\"authorizerType\":\"AWS_IAM\","
                        + "\"roleArn\":\"arn:aws:iam::000000000000:role/gw2\",\"description\":\"updated gw\"}")
                .when().put("/gateways/" + gatewayId + "/")
                .then().statusCode(202);
        given().when().get("/gateways/" + gatewayId + "/")
                .then().statusCode(200)
                .body("roleArn", equalTo("arn:aws:iam::000000000000:role/gw2"))
                .body("description", equalTo("updated gw"));

        given().contentType("application/json")
                .body("{\"targetConfiguration\":{\"mcp\":{\"lambda\":{\"arn\":\"x\"}}},\"description\":\"t2\"}")
                .when().put("/gateways/" + gatewayId + "/targets/" + targetId + "/")
                .then().statusCode(202);
        given().when().get("/gateways/" + gatewayId + "/targets/" + targetId + "/")
                .then().statusCode(200)
                .body("description", equalTo("t2"))
                .body("targetConfiguration.mcp.lambda.arn", equalTo("x"));
    }

    @Test
    @Order(5)
    void deleteTargetAndGateway() {
        given().when().delete("/gateways/" + gatewayId + "/targets/" + targetId + "/")
                .then().statusCode(202)
                .body("status", equalTo("DELETING"));

        given().when().delete("/gateways/" + gatewayId + "/")
                .then().statusCode(202)
                .body("status", equalTo("DELETING"));

        given().when().get("/gateways/" + gatewayId + "/")
                .then().statusCode(404);
    }
}
