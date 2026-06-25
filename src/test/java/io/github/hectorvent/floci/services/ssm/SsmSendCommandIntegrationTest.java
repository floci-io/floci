package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SsmSendCommandIntegrationTest {

    private static final String SSM_CT = "application/x-amz-json-1.1";
    private static final String INSTANCE_ID = "i-0abc1234def56789a";
    private static String commandId;
    private static final SsmDirectCommandExecutor directCommandExecutor = mock(SsmDirectCommandExecutor.class);

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
        QuarkusMock.installMockForType(directCommandExecutor, SsmDirectCommandExecutor.class);
    }

    @BeforeEach
    void resetDirectExecution() {
        reset(directCommandExecutor);
        when(directCommandExecutor.executeIfSupported(anyString(), anyString(), any(), anyInt()))
                .thenReturn(Optional.empty());
    }

    // ── Agent registration ─────────────────────────────────────────────────

    @Test
    @Order(1)
    void agentRegistersViaUpdateInstanceInformation() {
        given()
            .header("X-Amz-Target", "AmazonSSM.UpdateInstanceInformation")
            .contentType(SSM_CT)
            .body("""
                {
                    "InstanceId": "%s",
                    "AgentName": "amazon-ssm-agent",
                    "AgentVersion": "3.2.2172.0",
                    "PlatformType": "Linux",
                    "PlatformName": "Amazon Linux",
                    "PlatformVersion": "2023",
                    "IPAddress": "172.31.10.20",
                    "Hostname": "ip-172-31-10-20",
                    "AgentStatus": "Active"
                }
                """.formatted(INSTANCE_ID))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void describeInstanceInformationShowsRegisteredAgent() {
        given()
            .header("X-Amz-Target", "AmazonSSM.DescribeInstanceInformation")
            .contentType(SSM_CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("InstanceInformationList", not(empty()))
            .body("InstanceInformationList[0].InstanceId", equalTo(INSTANCE_ID))
            .body("InstanceInformationList[0].PingStatus", equalTo("Online"))
            .body("InstanceInformationList[0].PlatformType", equalTo("Linux"));
    }

    // ── SendCommand ────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void sendCommandRejectsTimeoutBelowAwsMinimum() {
        given()
            .header("X-Amz-Target", "AmazonSSM.SendCommand")
            .contentType(SSM_CT)
            .body("""
                {
                    "InstanceIds": ["%s"],
                    "DocumentName": "AWS-RunShellScript",
                    "Parameters": {
                        "commands": ["echo hello"]
                    },
                    "TimeoutSeconds": 1
                }
                """.formatted(INSTANCE_ID))
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", containsString("Value '1' at 'timeoutSeconds' failed to satisfy constraint"))
            .body("message", containsString("greater than or equal to 30"));
    }

    @Test
    @Order(4)
    void sendCommandCreatesCommandRecord() {
        String response = given()
            .header("X-Amz-Target", "AmazonSSM.SendCommand")
            .contentType(SSM_CT)
            .body("""
                {
                    "InstanceIds": ["%s"],
                    "DocumentName": "AWS-RunShellScript",
                    "Parameters": {
                        "commands": ["echo hello"]
                    },
                    "Comment": "test run",
                    "TimeoutSeconds": 60
                }
                """.formatted(INSTANCE_ID))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Command.CommandId", notNullValue())
            .body("Command.DocumentName", equalTo("AWS-RunShellScript"))
            .body("Command.Status", equalTo("InProgress"))
            .body("Command.TargetCount", equalTo(1))
            .extract().body().asString();

        commandId = io.restassured.path.json.JsonPath.from(response).getString("Command.CommandId");
    }

    @Test
    @Order(5)
    void listCommandsReturnsCreatedCommand() {
        given()
            .header("X-Amz-Target", "AmazonSSM.ListCommands")
            .contentType(SSM_CT)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Commands", not(empty()))
            .body("Commands[0].DocumentName", equalTo("AWS-RunShellScript"));
    }

    @Test
    @Order(6)
    void listCommandInvocationsReturnsInvocation() {
        given()
            .header("X-Amz-Target", "AmazonSSM.ListCommandInvocations")
            .contentType(SSM_CT)
            .body("""
                { "CommandId": "%s" }
                """.formatted(commandId))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("CommandInvocations", hasSize(1))
            .body("CommandInvocations[0].InstanceId", equalTo(INSTANCE_ID))
            .body("CommandInvocations[0].Status", anyOf(equalTo("Pending"), equalTo("InProgress")));
    }

    // ── ec2messages agent protocol ─────────────────────────────────────────

    @Test
    @Order(7)
    void agentGetsEndpoint() {
        given()
            .header("X-Amz-Target", "AmazonSSMMessageDeliveryService.GetEndpoint")
            .contentType(SSM_CT)
            .body("""
                {
                    "Destination": "%s",
                    "Protocol": "ec2messages"
                }
                """.formatted(INSTANCE_ID))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Endpoint.Protocol", equalTo("ec2messages"))
            .body("Endpoint.Endpoint", notNullValue());
    }

    @Test
    @Order(8)
    void agentPollsAndGetsMessage() {
        given()
            .header("X-Amz-Target", "AmazonSSMMessageDeliveryService.GetMessages")
            .contentType(SSM_CT)
            .body("""
                {
                    "Destination": "%s",
                    "MessagesRequestId": "%s",
                    "VisibilityTimeoutInSeconds": 30
                }
                """.formatted(INSTANCE_ID, UUID.randomUUID()))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Messages", hasSize(1))
            .body("Messages[0].MessageId", notNullValue())
            .body("Messages[0].Topic", containsString("aws.ssm.sendCommand"))
            .body("Messages[0].Payload", notNullValue());
    }

    @Test
    @Order(9)
    void agentAcknowledgesMessage() {
        // First poll to get the message ID
        String msgId = given()
            .header("X-Amz-Target", "AmazonSSMMessageDeliveryService.GetMessages")
            .contentType(SSM_CT)
            .body("""
                {
                    "Destination": "%s",
                    "MessagesRequestId": "%s",
                    "VisibilityTimeoutInSeconds": 30
                }
                """.formatted(INSTANCE_ID, UUID.randomUUID()))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().jsonPath().getString("Messages[0].MessageId");

        // If no messages left (already polled in order 7), re-send another command
        if (msgId == null) {
            // Send another command and poll for it
            String resp = given()
                .header("X-Amz-Target", "AmazonSSM.SendCommand")
                .contentType(SSM_CT)
                .body("""
                    {
                        "InstanceIds": ["%s"],
                        "DocumentName": "AWS-RunShellScript",
                        "Parameters": { "commands": ["echo ack-test"] }
                    }
                    """.formatted(INSTANCE_ID))
            .when().post("/")
            .then().statusCode(200).extract().body().asString();

            String newCmdId = io.restassured.path.json.JsonPath.from(resp).getString("Command.CommandId");

            msgId = given()
                .header("X-Amz-Target", "AmazonSSMMessageDeliveryService.GetMessages")
                .contentType(SSM_CT)
                .body("""
                    {
                        "Destination": "%s",
                        "MessagesRequestId": "%s",
                        "VisibilityTimeoutInSeconds": 30
                    }
                    """.formatted(INSTANCE_ID, UUID.randomUUID()))
            .when().post("/")
            .then().statusCode(200).extract().jsonPath().getString("Messages[0].MessageId");
        }

        // Acknowledge
        given()
            .header("X-Amz-Target", "AmazonSSMMessageDeliveryService.AcknowledgeMessage")
            .contentType(SSM_CT)
            .body("""
                { "MessageId": "%s" }
                """.formatted(msgId))
        .when()
            .post("/")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(10)
    void agentSendsReplyAndCommandStatusUpdates() {
        // Send a fresh command
        String resp = given()
            .header("X-Amz-Target", "AmazonSSM.SendCommand")
            .contentType(SSM_CT)
            .body("""
                {
                    "InstanceIds": ["%s"],
                    "DocumentName": "AWS-RunShellScript",
                    "Parameters": { "commands": ["echo world"] }
                }
                """.formatted(INSTANCE_ID))
        .when().post("/")
        .then().statusCode(200).extract().body().asString();

        String cid = io.restassured.path.json.JsonPath.from(resp).getString("Command.CommandId");

        // Poll
        String msgId = given()
            .header("X-Amz-Target", "AmazonSSMMessageDeliveryService.GetMessages")
            .contentType(SSM_CT)
            .body("""
                {
                    "Destination": "%s",
                    "MessagesRequestId": "%s",
                    "VisibilityTimeoutInSeconds": 30
                }
                """.formatted(INSTANCE_ID, UUID.randomUUID()))
        .when().post("/")
        .then().statusCode(200).extract().jsonPath().getString("Messages[0].MessageId");

        // Build a SendReply payload (base64 encoded agent output)
        String replyPayload = buildReplyPayload("world\n", "Success", 0);

        // Send reply
        given()
            .header("X-Amz-Target", "AmazonSSMMessageDeliveryService.SendReply")
            .contentType(SSM_CT)
            .body("""
                {
                    "MessageId": "%s",
                    "Payload": "%s"
                }
                """.formatted(msgId, replyPayload))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        // Verify GetCommandInvocation reflects the result
        given()
            .header("X-Amz-Target", "AmazonSSM.GetCommandInvocation")
            .contentType(SSM_CT)
            .body("""
                {
                    "CommandId": "%s",
                    "InstanceId": "%s"
                }
                """.formatted(cid, INSTANCE_ID))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Status", equalTo("Success"))
            .body("ResponseCode", equalTo(0))
            .body("StandardOutputContent", containsString("world"));
    }

    @Test
    @Order(11)
    void cancelCommandUpdatesStatus() {
        String resp = given()
            .header("X-Amz-Target", "AmazonSSM.SendCommand")
            .contentType(SSM_CT)
            .body("""
                {
                    "InstanceIds": ["%s"],
                    "DocumentName": "AWS-RunShellScript",
                    "Parameters": { "commands": ["sleep 100"] }
                }
                """.formatted(INSTANCE_ID))
        .when().post("/")
        .then().statusCode(200).extract().body().asString();

        String cid = io.restassured.path.json.JsonPath.from(resp).getString("Command.CommandId");

        given()
            .header("X-Amz-Target", "AmazonSSM.CancelCommand")
            .contentType(SSM_CT)
            .body("""
                { "CommandId": "%s" }
                """.formatted(cid))
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .header("X-Amz-Target", "AmazonSSM.ListCommands")
            .contentType(SSM_CT)
            .body("""
                { "CommandId": "%s" }
                """.formatted(cid))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Commands[0].Status", equalTo("Cancelled"));
    }

    @Test
    @Order(12)
    void sendCommandDirectlyExecutesForContainerBackedInstance() {
        Instant start = Instant.parse("2026-06-07T00:00:00Z");
        Instant end = Instant.parse("2026-06-07T00:00:01Z");
        when(directCommandExecutor.supports(eq("i-direct-container"), eq("AWS-RunShellScript")))
                .thenReturn(true);
        when(directCommandExecutor.executeIfSupported(
                eq("i-direct-container"),
                eq("AWS-RunShellScript"),
                any(),
                eq(60)))
                .thenReturn(Optional.of(new SsmDirectCommandExecutor.ExecutionResult(
                        "Success", "direct-output\n", "", 0, start, end)));

        String response = given()
            .header("X-Amz-Target", "AmazonSSM.SendCommand")
            .contentType(SSM_CT)
            .body("""
                {
                    "InstanceIds": ["i-direct-container"],
                    "DocumentName": "AWS-RunShellScript",
                    "Parameters": { "commands": ["echo direct-output"] },
                    "TimeoutSeconds": 60
                }
                """)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Command.Status", equalTo("InProgress"))
            .body("Command.StatusDetails", equalTo("In Progress"))
            .body("Command.CompletedCount", equalTo(0))
            .body("Command.ErrorCount", equalTo(0))
            .extract().body().asString();

        String cid = io.restassured.path.json.JsonPath.from(response).getString("Command.CommandId");

        given()
            .header("X-Amz-Target", "AmazonSSM.GetCommandInvocation")
            .contentType(SSM_CT)
            .body("""
                {
                    "CommandId": "%s",
                    "InstanceId": "i-direct-container"
                }
                """.formatted(cid))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Status", equalTo("Success"))
            .body("StatusDetails", equalTo("Success"))
            .body("ResponseCode", equalTo(0))
            .body("StandardOutputContent", equalTo("direct-output\n"));

        given()
            .header("X-Amz-Target", "AmazonSSMMessageDeliveryService.GetMessages")
            .contentType(SSM_CT)
            .body("""
                {
                    "Destination": "i-direct-container",
                    "MessagesRequestId": "%s",
                    "VisibilityTimeoutInSeconds": 30
                }
                """.formatted(UUID.randomUUID()))
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Messages", empty());
    }

    private static String buildReplyPayload(String stdout, String status, int returnCode) {
        String json = """
            {
              "additionalInfo": {
                "agent": { "name": "amazon-ssm-agent", "version": "3.2.2172.0" }
              },
              "documentTraceOutput": "",
              "runtimeStatus": {
                "runShellScript": {
                  "status": "%s",
                  "returnCode": %d,
                  "standardOutput": "%s",
                  "standardError": "",
                  "startDateTime": "2026-01-01T00:00:00Z",
                  "endDateTime": "2026-01-01T00:00:01Z"
                }
              }
            }
            """.formatted(status, returnCode, stdout.replace("\n", "\\n").replace("\"", "\\\""));
        return Base64.getEncoder().encodeToString(json.getBytes());
    }
}
