package io.github.hectorvent.floci.services.stepfunctions;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.config.EncoderConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class StepFunctionsJsonataIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.config = RestAssured.config().encoderConfig(
                EncoderConfig.encoderConfig()
                        .encodeContentTypeAs(SFN_CONTENT_TYPE, ContentType.TEXT));
    }

    @Test
    void passStateWithJsonataOutput() throws Exception {
        // A Pass state that transforms input using JSONata Output field
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Transform",
                    "States": {
                        "Transform": {
                            "Type": "Pass",
                            "Output": {
                                "greeting": "{% 'Hello ' & $states.input.name %}",
                                "doubled": "{% $states.input.value * 2 %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-pass-test", definition);
        String execArn = startExecution(smArn, "{\"name\": \"World\", \"value\": 21}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("Hello World"));
        assertTrue(output.contains("42"));
    }

    @Test
    void choiceStateWithJsonataCondition() throws Exception {
        // Choice state using JSONata Condition instead of Variable/StringEquals
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "CheckType",
                    "States": {
                        "CheckType": {
                            "Type": "Choice",
                            "Choices": [
                                {
                                    "Condition": "{% $states.input.type = 'premium' %}",
                                    "Next": "PremiumPath"
                                },
                                {
                                    "Condition": "{% $states.input.type = 'basic' %}",
                                    "Next": "BasicPath"
                                }
                            ],
                            "Default": "DefaultPath"
                        },
                        "PremiumPath": {
                            "Type": "Pass",
                            "Output": {"result": "premium"},
                            "End": true
                        },
                        "BasicPath": {
                            "Type": "Pass",
                            "Output": {"result": "basic"},
                            "End": true
                        },
                        "DefaultPath": {
                            "Type": "Pass",
                            "Output": {"result": "default"},
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-choice-test", definition);

        // Test premium path
        String execArn = startExecution(smArn, "{\"type\": \"premium\"}");
        String output = waitForExecution(execArn);
        assertTrue(output.contains("premium"));

        // Test basic path
        execArn = startExecution(smArn, "{\"type\": \"basic\"}");
        output = waitForExecution(execArn);
        assertTrue(output.contains("basic"));

        // Test default path
        execArn = startExecution(smArn, "{\"type\": \"unknown\"}");
        output = waitForExecution(execArn);
        assertTrue(output.contains("default"));
    }

    @Test
    void mapStateWithJsonataItems() throws Exception {
        // Map state using JSONata Items field instead of ItemsPath
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "MapItems",
                    "States": {
                        "MapItems": {
                            "Type": "Map",
                            "Items": "{% $states.input.numbers %}",
                            "ItemProcessor": {
                                "StartAt": "Double",
                                "States": {
                                    "Double": {
                                        "Type": "Pass",
                                        "End": true
                                    }
                                }
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-map-test", definition);
        String execArn = startExecution(smArn, "{\"numbers\": [1, 2, 3]}");
        String output = waitForExecution(execArn);
        // Map passes each item through, result is array [1, 2, 3]
        assertTrue(output.contains("[1,2,3]"));
    }

    @Test
    void statesInputVariableAccess() throws Exception {
        // Verify $states.input gives access to the state's input
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Extract",
                    "States": {
                        "Extract": {
                            "Type": "Pass",
                            "Output": {
                                "firstName": "{% $states.input.user.first %}",
                                "lastName": "{% $states.input.user.last %}",
                                "fullName": "{% $states.input.user.first & ' ' & $states.input.user.last %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-states-input-test", definition);
        String execArn = startExecution(smArn, "{\"user\": {\"first\": \"Jane\", \"last\": \"Doe\"}}");
        String output = waitForExecution(execArn);
        assertTrue(output.contains("Jane"));
        assertTrue(output.contains("Doe"));
        assertTrue(output.contains("Jane Doe"));
    }

    @Test
    void mixedModeDefaultJsonPathWithPerStateJsonata() throws Exception {
        // Default JSONPath (no top-level QueryLanguage) with one state overriding to JSONata
        String definition = """
                {
                    "StartAt": "JsonPathState",
                    "States": {
                        "JsonPathState": {
                            "Type": "Pass",
                            "Next": "JsonataState"
                        },
                        "JsonataState": {
                            "Type": "Pass",
                            "QueryLanguage": "JSONata",
                            "Output": {
                                "value": "{% $states.input.x + $states.input.y %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-mixed-test", definition);
        String execArn = startExecution(smArn, "{\"x\": 10, \"y\": 20}");
        String output = waitForExecution(execArn);
        assertTrue(output.contains("30"));
    }

    @Test
    void backwardCompatibility_jsonPathStillWorks() throws Exception {
        // No QueryLanguage field — default JSONPath behavior must work
        String definition = """
                {
                    "StartAt": "PassThrough",
                    "States": {
                        "PassThrough": {
                            "Type": "Pass",
                            "InputPath": "$.data",
                            "ResultPath": "$.result",
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonpath-compat-test", definition);
        String execArn = startExecution(smArn, "{\"data\": {\"key\": \"value\"}}");
        String output = waitForExecution(execArn);
        assertTrue(output.contains("key"));
        assertTrue(output.contains("value"));
    }

    @Test
    void jsonataPassState_withResult_accepted() throws Exception {
        // JSONata Pass states may use "Result" (static pass-through value) — must not be rejected at validation.
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "SetResult",
                    "States": {
                        "SetResult": {
                            "Type": "Pass",
                            "Result": {"status": "ok", "code": 200},
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-result-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);
        assertTrue(output.contains("ok"));
        assertTrue(output.contains("200"));
    }

    @Test
    void jsonataPassState_withParameters_accepted() throws Exception {
        // JSONata states that include "Parameters" must be accepted (not validation-rejected).
        // AWS allows Parameters in JSONata states even though it is a JSONPath concept;
        // Floci ignores it at execution time, matching AWS behavior.
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "PrepareData",
                    "States": {
                        "PrepareData": {
                            "Type": "Pass",
                            "Parameters": {
                                "created_at.$": "$$.Execution.StartTime"
                            },
                            "Output": {"processed": true},
                            "End": true
                        }
                    }
                }
                """;

        // Should not throw InvalidDefinition — state machine must be created successfully.
        String smArn = createStateMachine("jsonata-parameters-test", definition);
        assertNotNull(smArn);
    }

    // ──────────────── Helpers ────────────────

    private String createStateMachine(String name, String definition) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {
                            "name": "%s",
                            "definition": %s,
                            "roleArn": "%s"
                        }
                        """, name, quote(definition), ROLE_ARN))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("stateMachineArn");
    }

    private String startExecution(String smArn, String input) {
        Response resp = given()
                .header("X-Amz-Target", "AWSStepFunctions.StartExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {
                            "stateMachineArn": "%s",
                            "input": %s
                        }
                        """, smArn, quote(input)))
                .when()
                .post("/");
        resp.then().statusCode(200);
        return resp.jsonPath().getString("executionArn");
    }

    private String waitForExecution(String execArn) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Response resp = given()
                    .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                    .contentType(SFN_CONTENT_TYPE)
                    .body(String.format("""
                            { "executionArn": "%s" }
                            """, execArn))
                    .when()
                    .post("/");
            String status = resp.jsonPath().getString("status");
            if ("SUCCEEDED".equals(status)) {
                return resp.jsonPath().getString("output");
            }
            if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                fail("Execution " + status + ": " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not complete within timeout");
        return null;
    }

    /**
     * JSON-encode a string value (escape and wrap in quotes) for embedding
     * inside a JSON body where the field expects a string.
     */
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
