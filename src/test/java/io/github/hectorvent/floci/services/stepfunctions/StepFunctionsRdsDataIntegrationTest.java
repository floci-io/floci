package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.rdsdata.RdsDataService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@QuarkusTest
class StepFunctionsRdsDataIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String RESOURCE = "arn:aws:states:::aws-sdk:rdsdata:executeStatement";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @InjectMock
    RdsDataService rdsDataService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubExecuteStatement() {
        when(rdsDataService.executeStatement(any(JsonNode.class), anyString())).thenAnswer(invocation -> {
            JsonNode request = invocation.getArgument(0);
            if ("fail".equals(request.path("sql").asText())) {
                throw new AwsException("BadRequestException", "synthetic failure", 400);
            }

            ObjectNode response = OBJECT_MAPPER.createObjectNode();
            ArrayNode records = response.putArray("records");
            ArrayNode row = records.addArray();
            row.addObject().put("longValue", request.path("parameters").path(0)
                    .path("value").path("longValue").asLong());
            row.addObject().put("stringValue", "quoted \"value\"");
            response.putArray("columnMetadata")
                    .addObject()
                    .put("name", "id")
                    .put("typeName", "BIGINT");
            response.put("numberOfRecordsUpdated", 0);
            return response;
        });
    }

    @Test
    void jsonataArgumentsReachRdsDataAndReturnSdkFieldCasing() throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Execute",
                  "States": {
                    "Execute": {
                      "Type": "Task",
                      "Resource": "RESOURCE",
                      "Arguments": {
                        "ResourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:test",
                        "SecretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:test",
                        "Database": "app",
                        "Sql": "select :id",
                        "Parameters": [{"Name": "id", "Value": {"LongValue": 7}}],
                        "IncludeResultMetadata": true
                      },
                      "End": true
                    }
                  }
                }
                """.replace("RESOURCE", RESOURCE);

        JsonNode result = OBJECT_MAPPER.readTree(succeedingOutput(definition, "{}"));
        assertEquals(7, result.path("Records").path(0).path(0).path("LongValue").asInt());
        assertEquals("quoted \"value\"",
                result.path("Records").path(0).path(1).path("StringValue").asText());
        assertEquals("BIGINT", result.path("ColumnMetadata").path(0).path("TypeName").asText());
        assertEquals(0, result.path("NumberOfRecordsUpdated").asInt());
        assertTrue(result.path("records").isMissingNode());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(rdsDataService).executeStatement(request.capture(), anyString());
        assertEquals("select :id", request.getValue().path("sql").asText());
        assertEquals(7, request.getValue().path("parameters").path(0)
                .path("value").path("longValue").asInt());
        assertTrue(request.getValue().path("includeResultMetadata").asBoolean());
    }

    @Test
    void jsonPathParametersUseExecutionInputAndExecutionRegion() throws Exception {
        String definition = """
                {
                  "StartAt": "Execute",
                  "States": {
                    "Execute": {
                      "Type": "Task",
                      "Resource": "RESOURCE",
                      "Parameters": {
                        "ResourceArn.$": "$.resourceArn",
                        "SecretArn.$": "$.secretArn",
                        "Sql.$": "$.sql",
                        "Parameters.$": "$.parameters"
                      },
                      "End": true
                    }
                  }
                }
                """.replace("RESOURCE", RESOURCE);
        String input = """
                {
                  "resourceArn": "arn:aws:rds:us-east-1:000000000000:cluster:test",
                  "secretArn": "arn:aws:secretsmanager:us-east-1:000000000000:secret:test",
                  "sql": "select :id",
                  "parameters": [{"Name": "id", "Value": {"LongValue": 11}}]
                }
                """;

        JsonNode result = OBJECT_MAPPER.readTree(succeedingOutput(definition, input));
        assertEquals(11, result.path("Records").path(0).path(0).path("LongValue").asInt());

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<String> region = ArgumentCaptor.forClass(String.class);
        verify(rdsDataService).executeStatement(request.capture(), region.capture());
        assertEquals("select :id", request.getValue().path("sql").asText());
        assertEquals(11, request.getValue().path("parameters").path(0)
                .path("value").path("longValue").asInt());
        assertEquals("us-east-1", region.getValue());
    }

    @Test
    void serviceFailureCanBeCaughtByItsSdkExceptionName() throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Execute",
                  "States": {
                    "Execute": {
                      "Type": "Task",
                      "Resource": "RESOURCE",
                      "Arguments": {"Sql": "fail"},
                      "Catch": [{
                        "ErrorEquals": ["RdsData.BadRequestException"],
                        "Next": "Recovered",
                        "Output": {"Error": "{% $states.errorOutput.Error %}"}
                      }],
                      "End": true
                    },
                    "Recovered": {"Type": "Pass", "End": true}
                  }
                }
                """.replace("RESOURCE", RESOURCE);

        JsonNode result = OBJECT_MAPPER.readTree(succeedingOutput(definition, "{}"));
        assertEquals("RdsData.BadRequestException", result.path("Error").asText());
    }

    @Test
    void unsupportedRdsDataActionFailsWithoutCallingTheService() {
        String definition = """
                {
                  "StartAt": "Execute",
                  "States": {
                    "Execute": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:rdsdata:beginTransaction",
                      "Parameters": {},
                      "End": true
                    }
                  }
                }
                """;

        Response describe = terminalExecution(definition, "{}");
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("States.TaskFailed", describe.jsonPath().getString("error"));
        verify(rdsDataService, never()).executeStatement(any(JsonNode.class), anyString());
    }

    private static String succeedingOutput(String definition, String input) {
        Response describe = terminalExecution(definition, input);
        assertEquals("SUCCEEDED", describe.jsonPath().getString("status"),
                "cause: " + describe.jsonPath().getString("cause"));
        return describe.jsonPath().getString("output");
    }

    private static Response terminalExecution(String definition, String input) {
        String stateMachineArn = createStateMachine(definition);
        String executionArn = startExecution(stateMachineArn, input);
        for (int i = 0; i < 150; i++) {
            Response response = describeExecution(executionArn);
            if (!"RUNNING".equals(response.jsonPath().getString("status"))) {
                return response;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("Interrupted while waiting for execution " + executionArn);
            }
        }
        fail("Execution did not complete within timeout: " + executionArn);
        return null;
    }

    private static String createStateMachine(String definition) {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"name": "rdsdata-%s", "roleArn": "%s", "definition": %s}
                        """.formatted(System.nanoTime(), ROLE_ARN, quote(definition)))
                .when().post("/");
        response.then().statusCode(200);
        return response.jsonPath().getString("stateMachineArn");
    }

    private static String startExecution(String stateMachineArn, String input) {
        Response response = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(CONTENT_TYPE)
                .body("""
                        {"stateMachineArn": "%s", "input": %s}
                        """.formatted(stateMachineArn, quote(input)))
                .when().post("/");
        response.then().statusCode(200);
        return response.jsonPath().getString("executionArn");
    }

    private static Response describeExecution(String executionArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(CONTENT_TYPE)
                .body("{\"executionArn\":\"%s\"}".formatted(executionArn))
                .when().post("/");
    }

    private static String quote(String raw) {
        return "\"" + raw
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                + "\"";
    }
}
