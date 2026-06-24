package io.github.hectorvent.floci.services.sns;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

@QuarkusTest
class SnsLambdaIntegrationTest {

    @Test
    void publish_toLambdaSubscriber() {
        // 1. Create a Lambda function using the REST API (skipping code to bypass validation)
        String functionName = "sns-subscriber-fn";

        String lambdaRequest = String.format(
            "{\"FunctionName\":\"%s\",\"Runtime\":\"nodejs18.x\",\"Handler\":\"index.handler\",\"Role\":\"arn:aws:iam::000000000000:role/lambda-role\"}",
            functionName
        );

        given()
            .contentType("application/json")
            .body(lambdaRequest)
        .when()
            .post("/2015-03-31/functions")
        .then()
            .statusCode(201);

        String functionArn = "arn:aws:lambda:us-east-1:000000000000:function:" + functionName;

        // 2. Create a Topic
        String topicArn = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateTopic")
            .formParam("Name", "lambda-test-topic")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("CreateTopicResponse.CreateTopicResult.TopicArn");

        // 3. Subscribe Lambda to Topic
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Subscribe")
            .formParam("TopicArn", topicArn)
            .formParam("Protocol", "lambda")
            .formParam("Endpoint", functionArn)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<SubscriptionArn>"));

        // 4. Publish message
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "Publish")
            .formParam("TopicArn", topicArn)
            .formParam("Message", "{\"foo\":\"bar\"}")
            .formParam("Subject", "Lambda Test")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(containsString("<MessageId>"));

        // Note: Actual invocation check would require mocking the Docker/Executor,
        // but here we just want to see if it reaches the delivery logic without crashing.
    }
}
