package io.github.hectorvent.floci.services.apigateway;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class ApiGatewayMethodResponseParametersIntegrationTest {

    @Test
    void putGetAndPatchResponseParameters() {
        String apiId = given().contentType("application/json")
                .body("{\"name\":\"method-response-parameters\"}")
                .when().post("/restapis").then().statusCode(201).extract().path("id");
        String resourceId = given().when().get("/restapis/" + apiId + "/resources")
                .then().statusCode(200).extract().path("item[0].id");
        given().contentType("application/json").body("{\"authorizationType\":\"NONE\"}")
                .when().put("/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET")
                .then().statusCode(201);

        String path = "/restapis/" + apiId + "/resources/" + resourceId + "/methods/GET/responses/200";
        given().contentType("application/json")
                .body("{\"responseParameters\":{\"method.response.header.X-First\":false}}")
                .when().put(path).then().statusCode(201)
                .body("responseParameters.'method.response.header.X-First'", equalTo(false));
        given().when().get(path).then().statusCode(200)
                .body("responseParameters.'method.response.header.X-First'", equalTo(false));

        given().contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"add\","
                        + "\"path\":\"/responseParameters/method.response.header.X-Second\","
                        + "\"value\":\"true\"}]}")
                .when().patch(path).then().statusCode(201)
                .body("responseParameters.'method.response.header.X-First'", equalTo(false))
                .body("responseParameters.'method.response.header.X-Second'", equalTo(true));
        given().contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/responseParameters/method.response.header.X-First\","
                        + "\"value\":\"true\"},{\"op\":\"remove\","
                        + "\"path\":\"/responseParameters/method.response.header.X-Second\"}]}")
                .when().patch(path).then().statusCode(201)
                .body("responseParameters.'method.response.header.X-First'", equalTo(true))
                .body("responseParameters", not(hasKey("method.response.header.X-Second")));
        given().when().get(path).then().statusCode(200)
                .body("responseParameters.'method.response.header.X-First'", equalTo(true));

        given().contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"add\","
                        + "\"path\":\"/responseParameters/method.response.header.X~0Tag\","
                        + "\"value\":\"true\"}]}")
                .when().patch(path).then().statusCode(201)
                .body("responseParameters.'method.response.header.X~Tag'", equalTo(true))
                .body("responseParameters", not(hasKey("method.response.header.X~0Tag")));
        given().when().get(path).then().statusCode(200)
                .body("responseParameters.'method.response.header.X~Tag'", equalTo(true));

        IntStream.range(0, 16).parallel().forEach(index -> given().contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"add\","
                        + "\"path\":\"/responseParameters/method.response.header.X-Concurrent-"
                        + index + "\",\"value\":\"true\"}]}")
                .when().patch(path).then().statusCode(201));
        for (int index = 0; index < 16; index++) {
            given().when().get(path).then().statusCode(200)
                    .body("responseParameters.'method.response.header.X-Concurrent-" + index + "'",
                            equalTo(true));
        }

        given().contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"replace\","
                        + "\"path\":\"/responseParameters/method.response.header.X-First\","
                        + "\"value\":\"false\"},{\"op\":\"add\","
                        + "\"path\":\"/responseParameters/method.response.header.X-Bad\","
                        + "\"value\":\"maybe\"}]}")
                .when().patch(path).then().statusCode(400);
        given().when().get(path).then().statusCode(200)
                .body("responseParameters.'method.response.header.X-First'", equalTo(true));
        given().contentType("application/json")
                .body("{\"patchOperations\":[{\"op\":\"add\","
                        + "\"path\":\"/responseParameters/method.response.header.X-First\","
                        + "\"value\":\"true\"}]}")
                .when().patch(path.replace("/200", "/404")).then().statusCode(404);
    }
}
