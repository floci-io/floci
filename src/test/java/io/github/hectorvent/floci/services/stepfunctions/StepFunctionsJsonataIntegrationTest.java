package io.github.hectorvent.floci.services.stepfunctions;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class StepFunctionsJsonataIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
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
    void mapStateWithItemSelector_appliesTransformationAndContextVars() throws Exception {
        // ItemSelector (JSONPath Map state) should transform each item using parent-state
        // data and $$.Map.Item.Value / $$.Map.Item.Index context variables.
        // Regression test for: Map state ignores Parameters/ItemSelector (issue #675)
        String definition = """
                {
                    "StartAt": "ProcessItems",
                    "States": {
                        "ProcessItems": {
                            "Type": "Map",
                            "ItemsPath": "$.items",
                            "ItemSelector": {
                                "bucket.$": "$.bucket",
                                "item.$": "$$.Map.Item.Value",
                                "index.$": "$$.Map.Item.Index"
                            },
                            "ItemProcessor": {
                                "StartAt": "Pass",
                                "States": {
                                    "Pass": {
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

        String smArn = createStateMachine("map-itemselector-test", definition);
        String execArn = startExecution(smArn, "{\"bucket\": \"my-bucket\", \"items\": [\"a\", \"b\"]}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("my-bucket"), "bucket from parent input should be injected");
        assertTrue(output.contains("\"item\":\"a\"") || output.contains("\"item\": \"a\""),
                "item value should be the raw item");
        assertTrue(output.contains("\"index\":0") || output.contains("\"index\": 0"),
                "index should start at 0");
    }

    @Test
    void mapStateWithParameters_legacySyntax_appliesTransformation() throws Exception {
        // Parameters is the legacy equivalent of ItemSelector; both must be applied.
        String definition = """
                {
                    "StartAt": "ProcessItems",
                    "States": {
                        "ProcessItems": {
                            "Type": "Map",
                            "ItemsPath": "$.items",
                            "Parameters": {
                                "key.$": "$.key",
                                "value.$": "$$.Map.Item.Value"
                            },
                            "ItemProcessor": {
                                "StartAt": "Pass",
                                "States": {
                                    "Pass": {
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

        String smArn = createStateMachine("map-parameters-test", definition);
        String execArn = startExecution(smArn, "{\"key\": \"env\", \"items\": [1, 2]}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"key\":\"env\"") || output.contains("\"key\": \"env\""),
                "key from parent input should be injected via Parameters");
        assertTrue(output.contains("\"value\":1") || output.contains("\"value\": 1"),
                "value should be the raw item");
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
    void assignedVariablesSurviveBeyondNextStateOutput() throws Exception {
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "AssignVariables",
                    "States": {
                        "AssignVariables": {
                            "Type": "Pass",
                            "Assign": {
                                "CheckpointCount": "0",
                                "ExecutionWaitTimeInSeconds": "3"
                            },
                            "Output": {
                                "transient": 3
                            },
                            "Next": "UseAndReplaceOutput"
                        },
                        "UseAndReplaceOutput": {
                            "Type": "Pass",
                            "Output": {
                                "fromAssignedVariable": "{% $ExecutionWaitTimeInSeconds %}",
                                "fromPreviousOutput": "{% $states.input.transient %}"
                            },
                            "Next": "UseAssignedAgain"
                        },
                        "UseAssignedAgain": {
                            "Type": "Pass",
                            "Output": {
                                "checkpoint": "{% $CheckpointCount %}",
                                "fromAssignedVariable": "{% $ExecutionWaitTimeInSeconds %}",
                                "previousTransientStillPresent": "{% $exists($states.input.transient) %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-assign-vars-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"checkpoint\":\"0\"") || output.contains("\"checkpoint\": \"0\""));
        assertTrue(output.contains("\"fromAssignedVariable\":\"3\"") || output.contains("\"fromAssignedVariable\": \"3\""));
        assertTrue(output.contains("\"previousTransientStillPresent\":false")
                || output.contains("\"previousTransientStillPresent\": false"));
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
    void jsonataPassState_withResult_rejected() {
        // AWS rejects Result in JSONata states (SCHEMA_VALIDATION_FAILED).
        // Result is a JSONPath-only field; the JSONata equivalent is Output.
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

        given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {"name":"jsonata-result-test","definition":%s,"roleArn":"%s","type":"STANDARD"}
                        """, quote(definition), ROLE_ARN))
                .when().post("/")
                .then().statusCode(400);
    }

    @Test
    void jsonataPassState_withParameters_rejected() {
        // AWS rejects Parameters in JSONata states (SCHEMA_VALIDATION_FAILED).
        // Parameters is a JSONPath-only field; the JSONata equivalent is Arguments.
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

        given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {"name":"jsonata-parameters-test","definition":%s,"roleArn":"%s","type":"STANDARD"}
                        """, quote(definition), ROLE_ARN))
                .when().post("/")
                .then().statusCode(400);
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
