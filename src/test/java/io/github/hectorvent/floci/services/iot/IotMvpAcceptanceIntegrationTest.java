package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;

@QuarkusTest
class IotMvpAcceptanceIntegrationTest {

    @Test
    void provisionCommunicateStoreStateAndRouteTelemetry() {
        given()
            .queryParam("endpointType", "iot:Data-ATS")
        .when()
            .get("/endpoint")
        .then()
            .statusCode(200)
            .body("endpointAddress", notNullValue());

        String thingName = "mvp-acceptance-thing";
        given()
            .contentType("application/json")
            .body("""
                {
                  "attributePayload": {
                    "attributes": {
                      "env": "mvp"
                    }
                  }
                }
                """)
        .when()
            .post("/things/" + thingName)
        .then()
            .statusCode(200)
            .body("thingName", equalTo(thingName));

        String certificateArn = given()
            .queryParam("setAsActive", true)
        .when()
            .post("/keys-and-certificate")
        .then()
            .statusCode(200)
            .body("certificatePem", containsString("BEGIN CERTIFICATE"))
            .extract()
            .path("certificateArn");

        String policyName = "mvp-acceptance-policy";
        given()
            .contentType("application/json")
            .body("""
                {
                  "policyDocument": "{\\\"Version\\\":\\\"2012-10-17\\\",\\\"Statement\\\":[]}"
                }
                """)
        .when()
            .post("/policies/" + policyName)
        .then()
            .statusCode(200)
            .body("policyName", equalTo(policyName));

        given()
            .queryParam("target", certificateArn)
        .when()
            .put("/target-policies/" + policyName)
        .then()
            .statusCode(200);

        given()
            .queryParam("principal", certificateArn)
        .when()
            .put("/things/" + thingName + "/principals")
        .then()
            .statusCode(200);

        given()
        .when()
            .get("/things/" + thingName + "/principals")
        .then()
            .statusCode(200)
            .body("principals", hasItem(certificateArn));

        String queueUrl = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "mvp-iot-rule-queue")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateQueueResponse.CreateQueueResult.QueueUrl");

        given()
            .contentType("application/json")
            .body("""
                {
                  "topicRulePayload": {
                    "sql": "SELECT * FROM 'devices/mvp/telemetry'",
                    "ruleDisabled": false,
                    "actions": [
                      {
                        "sqs": {
                          "roleArn": "arn:aws:iam::000000000000:role/iot-rule-role",
                          "queueUrl": "%s"
                        }
                      }
                    ]
                  }
                }
                """.formatted(queueUrl))
        .when()
            .put("/rules/mvpAcceptanceRule")
        .then()
            .statusCode(200);

        given()
            .contentType("application/json")
            .body("{\"state\":{\"desired\":{\"mode\":\"auto\"}}}")
        .when()
            .post("/things/" + thingName + "/shadow")
        .then()
            .statusCode(200)
            .body("state.desired.mode", equalTo("auto"));

        given()
        .when()
            .get("/things/" + thingName + "/shadow")
        .then()
            .statusCode(200)
            .body("state.desired.mode", equalTo("auto"));

        given()
            .contentType("text/plain")
            .body("mvp-telemetry")
        .when()
            .post("/topics/devices/mvp/telemetry")
        .then()
            .statusCode(200);

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("mvp-telemetry"));
    }
}
