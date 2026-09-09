package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class EventBridgeInputTransformerIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void putTargetsInputTransformer_deliversTransformedBodyToSqs() {
        // Queue to receive the transformed event.
        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "CreateQueue")
            .formParam("QueueName", "eb-it-native-queue")
        .when().post("/").then().statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.PutRule")
            .body("{\"Name\":\"eb-it-native-rule\",\"EventPattern\":\"{\\\"source\\\":[\\\"eb.native.test\\\"]}\"}")
        .when().post("/").then().statusCode(200);

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.PutTargets")
            .body("""
                {"Rule":"eb-it-native-rule","Targets":[{
                  "Id":"T0",
                  "Arn":"arn:aws:sqs:us-east-1:000000000000:eb-it-native-queue",
                  "InputTransformer":{
                    "InputPathsMap":{"e":"$.detail.eventName"},
                    "InputTemplate":"{\\"e\\":<e>}"
                  }
                }]}
                """)
        .when().post("/").then().statusCode(200).body("FailedEntryCount", equalTo(0));

        given()
            .contentType("application/x-amz-json-1.1")
            .header("X-Amz-Target", "AWSEvents.PutEvents")
            .body("""
                {"Entries":[{"Source":"eb.native.test","DetailType":"t",
                 "Detail":"{\\"eventName\\":\\"site.created\\"}"}]}
                """)
        .when().post("/").then().statusCode(200).body("FailedEntryCount", equalTo(0));

        String getUrlXml = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "GetQueueUrl")
            .formParam("QueueName", "eb-it-native-queue")
        .when().post("/").then().statusCode(200).extract().body().asString();
        String queueUrl = getUrlXml.substring(
                getUrlXml.indexOf("<QueueUrl>") + "<QueueUrl>".length(),
                getUrlXml.indexOf("</QueueUrl>"));

        given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("Action", "ReceiveMessage")
            .formParam("QueueUrl", queueUrl)
            .formParam("MaxNumberOfMessages", "1")
            .formParam("WaitTimeSeconds", "0")
        .when().post("/")
        .then().statusCode(200)
            .body(containsString("{&quot;e&quot;:&quot;site.created&quot;}"));
    }
}
