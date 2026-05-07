package io.github.hectorvent.floci.services.eventbridge;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.*;

import java.time.Instant;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EventBridgeReplayIntegrationTest {

    private static final String EB_CT = "application/x-amz-json-1.1";
    private static final String SQS_CT = "application/x-amz-json-1.0";

    private static String busArn;
    private static String archiveArn;
    private static String queueUrl;
    private static String queueArn;
    private static long beforePut;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    @Order(1)
    void createCustomBus() {
        busArn = given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateEventBus")
                .body("{\"Name\":\"replay-test-bus\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("EventBusArn", notNullValue())
                .extract().jsonPath().getString("EventBusArn");
    }

    @Test
    @Order(2)
    void createArchive() {
        archiveArn = given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.CreateArchive")
                .body("""
                        {
                          "ArchiveName": "replay-test-archive",
                          "EventSourceArn": "%s",
                          "Description": "Archive for replay integration test",
                          "RetentionDays": 7
                        }
                        """.formatted(busArn))
                .when().post("/")
                .then().statusCode(200)
                .body("ArchiveArn", notNullValue())
                .body("State", equalTo("ENABLED"))
                .extract().jsonPath().getString("ArchiveArn");
    }

    @Test
    @Order(3)
    void describeArchive() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeArchive")
                .body("{\"ArchiveName\":\"replay-test-archive\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("ArchiveName", equalTo("replay-test-archive"))
                .body("EventSourceArn", equalTo(busArn))
                .body("State", equalTo("ENABLED"))
                .body("EventCount", equalTo(0))
                .body("RetentionDays", equalTo(7));
    }

    @Test
    @Order(4)
    void createSinkQueueAndRule() {
        queueUrl = given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.CreateQueue")
                .body("{\"QueueName\":\"replay-sink-queue\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");

        queueArn = given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"AttributeNames\":[\"All\"]}")
                .when().post("/0000000000/replay-sink-queue")
                .then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutRule")
                .body("""
                        {
                          "Name": "replay-test-rule",
                          "EventBusName": "replay-test-bus",
                          "EventPattern": "{\\"source\\":[\\"replay.test\\"]}"
                        }
                        """)
                .when().post("/")
                .then().statusCode(200);

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutTargets")
                .body("""
                        {
                          "Rule": "replay-test-rule",
                          "EventBusName": "replay-test-bus",
                          "Targets": [{"Id": "1", "Arn": "%s"}]
                        }
                        """.formatted(queueArn))
                .when().post("/")
                .then().statusCode(200);
    }

    @Test
    @Order(5)
    void putEventsAndVerifyArchived() {
        beforePut = Instant.now().getEpochSecond() - 1;

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                        {
                          "Entries": [
                            {
                              "EventBusName": "replay-test-bus",
                              "Source": "replay.test",
                              "DetailType": "OrderCreated",
                              "Detail": "{\\"orderId\\":\\"001\\"}"
                            },
                            {
                              "EventBusName": "replay-test-bus",
                              "Source": "replay.test",
                              "DetailType": "OrderShipped",
                              "Detail": "{\\"orderId\\":\\"002\\"}"
                            }
                          ]
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeArchive")
                .body("{\"ArchiveName\":\"replay-test-archive\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("EventCount", equalTo(2));
    }

    @Test
    @Order(6)
    void listArchives() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListArchives")
                .body("{\"NamePrefix\":\"replay-test\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Archives", hasSize(1))
                .body("Archives[0].ArchiveName", equalTo("replay-test-archive"))
                .body("Archives[0].EventCount", equalTo(2));
    }

    @Test
    @Order(7)
    void startReplayAndPollUntilCompleted() throws InterruptedException {
        // drain any prior messages delivered by putEvents
        given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10}")
                .when().post("/0000000000/replay-sink-queue");

        long afterPut = Instant.now().getEpochSecond() + 1;

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.StartReplay")
                .body("""
                        {
                          "ReplayName": "test-replay-1",
                          "EventSourceArn": "%s",
                          "EventStartTime": %d,
                          "EventEndTime": %d,
                          "Destination": {"Arn": "%s"}
                        }
                        """.formatted(archiveArn, beforePut, afterPut, busArn))
                .when().post("/")
                .then().statusCode(200)
                .body("ReplayArn", notNullValue())
                .body("State", anyOf(equalTo("STARTING"), equalTo("RUNNING"), equalTo("COMPLETED")));

        // poll until COMPLETED (up to 5 s)
        String state = "STARTING";
        for (int i = 0; i < 50 && !"COMPLETED".equals(state) && !"FAILED".equals(state); i++) {
            Thread.sleep(100);
            state = given()
                    .contentType(EB_CT)
                    .header("X-Amz-Target", "AWSEvents.DescribeReplay")
                    .body("{\"ReplayName\":\"test-replay-1\"}")
                    .when().post("/")
                    .then().statusCode(200)
                    .extract().jsonPath().getString("State");
        }

        Assertions.assertEquals("COMPLETED", state, "Replay did not reach COMPLETED state");
    }

    @Test
    @Order(8)
    void verifyReplayedEventsArrivedInQueue() {
        given()
                .contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + queueUrl + "\",\"MaxNumberOfMessages\":10}")
                .when().post("/0000000000/replay-sink-queue")
                .then().statusCode(200)
                .body("Messages", hasSize(2));
    }

    @Test
    @Order(9)
    void listReplays() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListReplays")
                .body("{\"NamePrefix\":\"test-replay\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Replays", hasSize(1))
                .body("Replays[0].ReplayName", equalTo("test-replay-1"))
                .body("Replays[0].State", equalTo("COMPLETED"));
    }

    @Test
    @Order(10)
    void describeReplayShowsEndTime() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeReplay")
                .body("{\"ReplayName\":\"test-replay-1\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("ReplayName", equalTo("test-replay-1"))
                .body("State", equalTo("COMPLETED"))
                .body("ReplayEndTime", notNullValue())
                .body("EventLastReplayedTime", notNullValue());
    }

    @Test
    @Order(11)
    void updateArchive() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.UpdateArchive")
                .body("""
                        {
                          "ArchiveName": "replay-test-archive",
                          "Description": "Updated description",
                          "RetentionDays": 30
                        }
                        """)
                .when().post("/")
                .then().statusCode(200)
                .body("State", equalTo("ENABLED"));

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeArchive")
                .body("{\"ArchiveName\":\"replay-test-archive\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("Description", equalTo("Updated description"))
                .body("RetentionDays", equalTo(30));
    }

    @Test
    @Order(12)
    void deleteArchive() {
        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DeleteArchive")
                .body("{\"ArchiveName\":\"replay-test-archive\"}")
                .when().post("/")
                .then().statusCode(200);

        given()
                .contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeArchive")
                .body("{\"ArchiveName\":\"replay-test-archive\"}")
                .when().post("/")
                .then().statusCode(404);
    }
}
