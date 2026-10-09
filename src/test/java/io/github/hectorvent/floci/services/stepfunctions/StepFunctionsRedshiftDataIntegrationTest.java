package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.redshiftdata.RedshiftDataService;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@QuarkusTest
class StepFunctionsRedshiftDataIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String EXECUTE = "arn:aws:states:::aws-sdk:redshiftdata:executeStatement";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";
    private static final String ISO_INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @InjectMock
    RedshiftDataService redshiftDataService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void stubService() {
        when(redshiftDataService.executeStatement(any(JsonNode.class), anyString())).thenAnswer(invocation -> {
            JsonNode request = invocation.getArgument(0);
            if ("fail".equals(request.path("Sql").asText())) {
                throw new AwsException("ValidationException", "synthetic failure", 400);
            }
            ObjectNode response = OBJECT_MAPPER.createObjectNode();
            response.put("Id", "stmt-1");
            response.put("CreatedAt", 1_700_000_000.5);
            response.put("Database", request.path("Database").asText());
            response.put("HasResultSet", true);
            return response;
        });
        when(redshiftDataService.listStatements(any(JsonNode.class))).thenAnswer(invocation -> {
            ObjectNode response = OBJECT_MAPPER.createObjectNode();
            ObjectNode statement = response.putArray("Statements").addObject();
            statement.put("Id", "stmt-1");
            statement.put("CreatedAt", 1_700_000_000.5);
            statement.put("UpdatedAt", 1_700_000_001.0);
            return response;
        });
    }

    @Test
    void executeStatementForwardsPascalCaseInputAndReturnsIsoCreatedAt() throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Run",
                  "States": {
                    "Run": {
                      "Type": "Task",
                      "Resource": "RESOURCE",
                      "Arguments": {
                        "ClusterIdentifier": "analytics",
                        "Database": "dev",
                        "DbUser": "admin",
                        "Sql": "select 1"
                      },
                      "End": true
                    }
                  }
                }
                """.replace("RESOURCE", EXECUTE);

        JsonNode result = OBJECT_MAPPER.readTree(succeedingOutput(definition, "{}"));
        assertEquals("stmt-1", result.path("Id").asText());
        assertEquals("dev", result.path("Database").asText());
        assertTrue(result.path("HasResultSet").asBoolean());
        assertTrue(result.path("CreatedAt").asText().matches(ISO_INSTANT),
                "CreatedAt is not the SDK's ISO-8601 rendering: " + result.path("CreatedAt"));

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<String> region = ArgumentCaptor.forClass(String.class);
        verify(redshiftDataService).executeStatement(request.capture(), region.capture());
        assertEquals("select 1", request.getValue().path("Sql").asText());
        assertEquals("analytics", request.getValue().path("ClusterIdentifier").asText());
        assertEquals("us-east-1", region.getValue());
    }

    @Test
    void workgroupNameReachesTheService() throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Run",
                  "States": {
                    "Run": {
                      "Type": "Task",
                      "Resource": "RESOURCE",
                      "Arguments": {"WorkgroupName": "wg-1", "Database": "dev", "Sql": "select 1"},
                      "End": true
                    }
                  }
                }
                """.replace("RESOURCE", EXECUTE);

        succeedingOutput(definition, "{}");

        ArgumentCaptor<JsonNode> request = ArgumentCaptor.forClass(JsonNode.class);
        verify(redshiftDataService).executeStatement(request.capture(), anyString());
        assertEquals("wg-1", request.getValue().path("WorkgroupName").asText());
        assertTrue(request.getValue().path("ClusterIdentifier").isMissingNode());
    }

    @Test
    void listStatementsRendersNestedTimestampsAsIso() throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "List",
                  "States": {
                    "List": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:redshiftdata:listStatements",
                      "Arguments": {},
                      "End": true
                    }
                  }
                }
                """;

        ObjectNode source = (ObjectNode) OBJECT_MAPPER.readTree("""
                {"Statements":[{"Id":"stmt-1","CreatedAt":1700000000.5,"UpdatedAt":1700000001,
                  "SubStatements":[{"CreatedAt":1700000002.25,"ResultRows":7,
                    "Metadata":{"Items":[{"UpdatedAt":1700000003.125,"Sql":"select 1",
                      "Nested":[{"CreatedAt":"already textual","UpdatedAt":null,"Count":42}]}]}}]}],
                 "NextToken":"retained"}
                """);
        JsonNode original = source.deepCopy();
        when(redshiftDataService.listStatements(any(JsonNode.class))).thenReturn(source);
        JsonNode result = OBJECT_MAPPER.readTree(succeedingOutput(definition, "{}"));
        JsonNode statement = result.path("Statements").path(0);
        assertEquals("2023-11-14T22:13:20.500Z", statement.path("CreatedAt").asText());
        assertEquals("2023-11-14T22:13:21.000Z", statement.path("UpdatedAt").asText());
        JsonNode subStatement = statement.path("SubStatements").path(0);
        assertEquals("2023-11-14T22:13:22.250Z", subStatement.path("CreatedAt").asText());
        assertEquals(7, subStatement.path("ResultRows").asInt());
        JsonNode item = subStatement.path("Metadata").path("Items").path(0);
        assertEquals("2023-11-14T22:13:23.125Z", item.path("UpdatedAt").asText());
        assertEquals("select 1", item.path("Sql").asText());
        assertEquals(original.path("Statements").path(0).path("SubStatements").path(0)
                .path("Metadata").path("Items").path(0).path("Nested"), item.path("Nested"));
        assertEquals("retained", result.path("NextToken").asText());
        assertEquals(original, source, "Rendering must not mutate the Data API response");
        assertTrue(source.path("Statements").path(0).path("CreatedAt").isNumber());
        assertTrue(source.path("Statements").path(0).path("SubStatements").path(0)
                .path("Metadata").path("Items").path(0).path("UpdatedAt").isNumber());
    }

    @Test
    void serviceFailureCanBeCaughtByItsSdkExceptionName() throws Exception {
        String definition = """
                {
                  "QueryLanguage": "JSONata",
                  "StartAt": "Run",
                  "States": {
                    "Run": {
                      "Type": "Task",
                      "Resource": "RESOURCE",
                      "Arguments": {"Sql": "fail"},
                      "Catch": [{
                        "ErrorEquals": ["RedshiftData.ValidationException"],
                        "Next": "Recovered",
                        "Output": {"Error": "{% $states.errorOutput.Error %}"}
                      }],
                      "End": true
                    },
                    "Recovered": {"Type": "Pass", "End": true}
                  }
                }
                """.replace("RESOURCE", EXECUTE);

        JsonNode result = OBJECT_MAPPER.readTree(succeedingOutput(definition, "{}"));
        assertEquals("RedshiftData.ValidationException", result.path("Error").asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {".sync", ".sync:2"})
    void unsupportedSyncResourceFailsBeforeExecutingSql(String suffix) {
        String resource = EXECUTE + suffix;
        String definition = """
                {"StartAt":"Run","States":{"Run":{"Type":"Task","Resource":"RESOURCE",
                  "Parameters":{"Sql":"select 1"},"End":true}}}
                """.replace("RESOURCE", resource);
        Response describe = terminalExecution(definition, "{}");
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("States.TaskFailed", describe.jsonPath().getString("error"));
        assertEquals("Unsupported resource: " + resource, describe.jsonPath().getString("cause"));
        verifyNoInteractions(redshiftDataService);
    }

    @Test
    void deprecatedExecuteSqlFailsWithValidationException() {
        String definition = """
                {
                  "StartAt": "Run",
                  "States": {
                    "Run": {
                      "Type": "Task",
                      "Resource": "arn:aws:states:::aws-sdk:redshiftdata:executeSql",
                      "Parameters": {"Sql": "select 1"},
                      "End": true
                    }
                  }
                }
                """;

        Response describe = terminalExecution(definition, "{}");
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("RedshiftData.ValidationException", describe.jsonPath().getString("error"));
        verify(redshiftDataService, never()).executeStatement(any(JsonNode.class), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1, 2]", "\"scalar\"", "123"})
    void nonObjectInputFailsWithValidationException(String input) {
        String definition = """
                {
                  "StartAt": "Run",
                  "States": {
                    "Run": {
                      "Type": "Task",
                      "Resource": "RESOURCE",
                      "End": true
                    }
                  }
                }
                """.replace("RESOURCE", EXECUTE);

        Response describe = terminalExecution(definition, input);
        assertEquals("FAILED", describe.jsonPath().getString("status"));
        assertEquals("RedshiftData.ValidationException", describe.jsonPath().getString("error"));
        assertEquals("The task input must be a JSON object.", describe.jsonPath().getString("cause"));
        verifyNoInteractions(redshiftDataService);
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
                        {"name": "redshiftdata-%s", "roleArn": "%s", "definition": %s}
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
