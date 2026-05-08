package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeFifoSqsIntegrationTest {

    private static final String EB_CT  = "application/x-amz-json-1.1";
    private static final String SQS_CT = "application/x-amz-json-1.0";

    private static String fifoQueueUrl;
    private static String fifoQueueArn;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createFifoQueue() {
        fifoQueueUrl = given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("""
                        {
                          "QueueName": "eb-fifo-target-test.fifo",
                          "Attributes": {
                            "FifoQueue": "true",
                            "ContentBasedDeduplication": "true"
                          }
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");

        fifoQueueArn = given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + fifoQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when().post("/0000000000/eb-fifo-target-test.fifo")
                .then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");
    }

    @Test
    @Order(2)
    void createRuleAndPutTargetWithSqsParameters() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("""
                        {
                          "Name": "fifo-sqs-rule",
                          "EventBusName": "default",
                          "EventPattern": "{\\"source\\":[\\"test.fifo\\"]}"
                        }
                        """)
                .when().post("/")
                .then().statusCode(200);

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                        {
                          "Rule": "fifo-sqs-rule",
                          "EventBusName": "default",
                          "Targets": [{
                            "Id": "FifoTarget",
                            "Arn": "%s",
                            "SqsParameters": {
                              "MessageGroupId": "test-group-1"
                            }
                          }]
                        }
                        """.formatted(fifoQueueArn))
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));
    }

    @Test
    @Order(3)
    void listTargetsByRuleReturnsSqsParameters() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
                .body("{\"Rule\":\"fifo-sqs-rule\",\"EventBusName\":\"default\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Targets", hasSize(1))
                .body("Targets[0].Id", equalTo("FifoTarget"))
                .body("Targets[0].SqsParameters.MessageGroupId", equalTo("test-group-1"));
    }

    @Test
    @Order(4)
    void putEventsDeliversToFifoQueue() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                        {
                          "Entries": [{
                            "EventBusName": "default",
                            "Source": "test.fifo",
                            "DetailType": "FifoTest",
                            "Detail": "{\\"msg\\":\\"hello-fifo\\"}"
                          }]
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));
    }

    @Test
    @Order(5)
    void messageArrivesInFifoQueue() {
        given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + fifoQueueUrl + "\",\"MaxNumberOfMessages\":1,\"AttributeNames\":[\"All\"]}")
                .when().post("/0000000000/eb-fifo-target-test.fifo")
                .then().statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].Attributes.MessageGroupId", equalTo("test-group-1"));
    }

    @Test
    @Order(6)
    void standardSqsTargetWithoutSqsParametersStillWorks() {
        String stdQueueUrl = given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"eb-fifo-test-std-queue\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");

        String stdQueueArn = given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + stdQueueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when().post("/0000000000/eb-fifo-test-std-queue")
                .then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("""
                        {
                          "Name": "std-sqs-rule",
                          "EventBusName": "default",
                          "EventPattern": "{\\"source\\":[\\"test.std\\"]}"
                        }
                        """)
                .when().post("/")
                .then().statusCode(200);

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                        {
                          "Rule": "std-sqs-rule",
                          "EventBusName": "default",
                          "Targets": [{"Id": "StdTarget", "Arn": "%s"}]
                        }
                        """.formatted(stdQueueArn))
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                        {
                          "Entries": [{
                            "EventBusName": "default",
                            "Source": "test.std",
                            "DetailType": "StdTest",
                            "Detail": "{\\"msg\\":\\"hello-std\\"}"
                          }]
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + stdQueueUrl + "\",\"MaxNumberOfMessages\":1}")
                .when().post("/0000000000/eb-fifo-test-std-queue")
                .then().statusCode(200)
                .body("Messages", hasSize(1));
    }
}
