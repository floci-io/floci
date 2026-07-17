package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    void distributedMapWithS3JsonItemReader_readsItemsFromS3Object() throws Exception {
        createBucket("map-inputs");
        putObject("map-inputs", "workers.json", "[{\"workerId\":\"w1\"},{\"workerId\":\"w2\"}]");

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"workerId\":\"w1\"") || output.contains("\"workerId\": \"w1\""));
        assertTrue(output.contains("\"workerId\":\"w2\"") || output.contains("\"workerId\": \"w2\""));
    }

    @Test
    void distributedMapWithS3JsonItemReader_jsonataArgumentsReadsItemsFromS3Object() throws Exception {
        createBucket("map-inputs-arguments");
        putObject("map-inputs-arguments", "workers.json", "[{\"workerId\":\"w1\"},{\"workerId\":\"w2\"}]");

        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Arguments": {
                                    "Bucket": "map-inputs-arguments",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-arguments-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"workerId\":\"w1\"") || output.contains("\"workerId\": \"w1\""));
        assertTrue(output.contains("\"workerId\":\"w2\"") || output.contains("\"workerId\": \"w2\""));
    }

    @Test
    void distributedMapWithS3JsonItemReader_maxItemsLimitsArrayDataset() throws Exception {
        createBucket("map-inputs-max-items-array");
        putObject("map-inputs-max-items-array", "workers.json", """
                [{"workerId":"w1"},{"workerId":"w2"},{"workerId":"w3"}]
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON",
                                    "MaxItems": 2
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-max-items-array",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-max-items-array-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"workerId\":\"w1\"") || output.contains("\"workerId\": \"w1\""));
        assertTrue(output.contains("\"workerId\":\"w2\"") || output.contains("\"workerId\": \"w2\""));
        assertFalse(output.contains("\"workerId\":\"w3\"") || output.contains("\"workerId\": \"w3\""));
    }

    @Test
    void distributedMapWithS3JsonItemReader_maxItemsLimitsObjectDataset() throws Exception {
        createBucket("map-inputs-max-items-object");
        putObject("map-inputs-max-items-object", "workers.json", """
                {"a":{"workerId":"w1"},"b":{"workerId":"w2"},"c":{"workerId":"w3"}}
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON",
                                    "MaxItems": 2
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-max-items-object",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-max-items-object-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"Key\":\"a\"") || output.contains("\"Key\": \"a\""));
        assertTrue(output.contains("\"Key\":\"b\"") || output.contains("\"Key\": \"b\""));
        assertFalse(output.contains("\"Key\":\"c\"") || output.contains("\"Key\": \"c\""));
        assertFalse(output.contains("\"workerId\":\"w3\"") || output.contains("\"workerId\": \"w3\""));
    }

    @Test
    void distributedMapWithS3JsonItemReader_itemsPointerSelectsArrayDataset() throws Exception {
        createBucket("map-inputs-pointer-array");
        putObject("map-inputs-pointer-array", "workers.json", """
                {"records":[{"workerId":"w1"},{"workerId":"w2"}]}
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON",
                                    "ItemsPointer": "/records"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-pointer-array",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-pointer-array-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"workerId\":\"w1\"") || output.contains("\"workerId\": \"w1\""));
        assertTrue(output.contains("\"workerId\":\"w2\"") || output.contains("\"workerId\": \"w2\""));
        assertFalse(output.contains("\"records\""));
    }

    @Test
    void distributedMapWithS3JsonItemReader_itemsPointerSelectsObjectDataset() throws Exception {
        createBucket("map-inputs-pointer-object");
        putObject("map-inputs-pointer-object", "workers.json", """
                {"records":{"a":{"x":1},"b":{"x":2}}}
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON",
                                    "ItemsPointer": "/records"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-pointer-object",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemSelector": {
                                "key.$": "$$.Map.Item.Key",
                                "index.$": "$$.Map.Item.Index",
                                "value.$": "$$.Map.Item.Value"
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-pointer-object-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"key\":\"a\"") || output.contains("\"key\": \"a\""));
        assertTrue(output.contains("\"key\":\"b\"") || output.contains("\"key\": \"b\""));
        assertTrue(output.contains("\"index\":0") || output.contains("\"index\": 0"));
        assertTrue(output.contains("\"index\":1") || output.contains("\"index\": 1"));
        assertTrue(output.contains("\"value\":{\"x\":1}") || output.contains("\"value\": {\"x\": 1}")
                || output.contains("\"value\": { \"x\": 1 }"));
        assertTrue(output.contains("\"value\":{\"x\":2}") || output.contains("\"value\": {\"x\": 2}")
                || output.contains("\"value\": { \"x\": 2 }"));
    }

    @Test
    void distributedMapWithS3JsonItemReader_itemsPointerMissingPathFailsWithItemReaderError() throws Exception {
        createBucket("map-inputs-pointer-missing");
        putObject("map-inputs-pointer-missing", "workers.json", """
                {"records":[{"workerId":"w1"}]}
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON",
                                    "ItemsPointer": "/missing"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-pointer-missing",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-pointer-missing-test", definition);
        String execArn = startExecution(smArn, "{}");
        Response failure = waitForExecutionFailure(execArn);

        assertEquals("FAILED", failure.jsonPath().getString("status"));
        assertEquals("States.ItemReaderFailed", failure.jsonPath().getString("error"));
        assertEquals("The provided ReaderConfig.ItemsPointer does not match any valid path in the JSON structure.",
                failure.jsonPath().getString("cause"));
    }

    @Test
    void distributedMapWithS3JsonItemReader_itemsPointerScalarFailsWithNonIterableCause() throws Exception {
        createBucket("map-inputs-pointer-scalar");
        putObject("map-inputs-pointer-scalar", "workers.json", """
                {"records":123}
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON",
                                    "ItemsPointer": "/records"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-pointer-scalar",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-pointer-scalar-test", definition);
        String execArn = startExecution(smArn, "{}");
        Response failure = waitForExecutionFailure(execArn);

        assertEquals("FAILED", failure.jsonPath().getString("status"));
        assertEquals("States.ItemReaderFailed", failure.jsonPath().getString("error"));
        assertEquals("Attempting to map over non-iterable node.", failure.jsonPath().getString("cause"));
    }

    @Test
    void distributedMapWithS3JsonItemReader_emptyItemsPointerBehavesLikeOmittedPointer() throws Exception {
        createBucket("map-inputs-pointer-empty");
        putObject("map-inputs-pointer-empty", "workers.json", """
                [{"workerId":"w1"},{"workerId":"w2"}]
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON",
                                    "ItemsPointer": ""
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-pointer-empty",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-pointer-empty-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"workerId\":\"w1\"") || output.contains("\"workerId\": \"w1\""));
        assertTrue(output.contains("\"workerId\":\"w2\"") || output.contains("\"workerId\": \"w2\""));
    }

    @Test
    void distributedMapWithItemReaderAndInlineModeFailsAtRuntime() throws Exception {
        createBucket("map-inputs-inline");
        putObject("map-inputs-inline", "workers.json", """
                [{"workerId":"w1"}]
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-inline",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-inline-mode-test", definition);
        String execArn = startExecution(smArn, "{}");
        Response failure = waitForExecutionFailure(execArn);

        assertEquals("FAILED", failure.jsonPath().getString("status"));
        assertEquals("States.Runtime", failure.jsonPath().getString("error"));
        assertEquals("The ItemReader, ItemBatcher and ResultWriter fields are not supported for INLINE maps",
                failure.jsonPath().getString("cause"));
    }

    @Test
    void distributedMapWithS3JsonItemReader_exposesMapItemContextInsideProcessor() throws Exception {
        createBucket("map-inputs-context");
        putObject("map-inputs-context", "workers.json", "[{\"workerId\":\"w1\"},{\"workerId\":\"w2\"}]");

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-context",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "ProjectContext",
                                "States": {
                                    "ProjectContext": {
                                        "Type": "Pass",
                                        "QueryLanguage": "JSONata",
                                        "Output": {
                                            "index": "{% $states.context.Map.Item.Index %}",
                                            "workerId": "{% $states.context.Map.Item.Value.workerId %}"
                                        },
                                        "End": true
                                    }
                                }
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("map-itemreader-s3-context-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"index\":0") || output.contains("\"index\": 0"));
        assertTrue(output.contains("\"index\":1") || output.contains("\"index\": 1"));
        assertTrue(output.contains("\"workerId\":\"w1\"") || output.contains("\"workerId\": \"w1\""));
        assertTrue(output.contains("\"workerId\":\"w2\"") || output.contains("\"workerId\": \"w2\""));
    }

    @Test
    void distributedMapWithS3JsonArrayEntriesNamedKeyAndValue_keepsWholeElementAsMapItemValue() throws Exception {
        createBucket("map-inputs-array-key-value");
        putObject("map-inputs-array-key-value", "workers.json", """
                [{"Key":"k1","Value":42},{"Key":"k2","Value":84}]
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-array-key-value",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemSelector": {
                                "value.$": "$$.Map.Item.Value",
                                "index.$": "$$.Map.Item.Index"
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-array-key-value-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"index\":0") || output.contains("\"index\": 0"));
        assertTrue(output.contains("\"index\":1") || output.contains("\"index\": 1"));
        assertTrue(output.contains("\"value\":{\"Key\":\"k1\",\"Value\":42}")
                || output.contains("\"value\": {\"Key\": \"k1\", \"Value\": 42}")
                || output.contains("\"value\": { \"Key\": \"k1\", \"Value\": 42 }"));
        assertTrue(output.contains("\"value\":{\"Key\":\"k2\",\"Value\":84}")
                || output.contains("\"value\": {\"Key\": \"k2\", \"Value\": 84}")
                || output.contains("\"value\": { \"Key\": \"k2\", \"Value\": 84 }"));
        assertFalse(output.contains("\"key\":\"k1\"") || output.contains("\"key\": \"k1\""));
        assertFalse(output.contains("\"key\":\"k2\"") || output.contains("\"key\": \"k2\""));
    }

    @Test
    void distributedMapWithS3JsonItemReader_invalidJsonFailsWithItemReaderError() throws Exception {
        createBucket("map-inputs-invalid");
        putObject("map-inputs-invalid", "workers.json", "not-json");

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-invalid",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-invalid-json-test", definition);
        String execArn = startExecution(smArn, "{}");
        Response failure = waitForExecutionFailure(execArn);

        assertEquals("FAILED", failure.jsonPath().getString("status"));
        assertEquals("States.ItemReaderFailed", failure.jsonPath().getString("error"));
    }

    @Test
    void distributedMapWithS3JsonItemReader_objectIteratesKeyValuePairs() throws Exception {
        createBucket("map-inputs-object");
        putObject("map-inputs-object", "workers.json", """
                {"a":{"workerId":"w1"},"b":{"workerId":"w2"}}
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-object",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-non-array-json-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"Key\":\"a\"") || output.contains("\"Key\": \"a\""));
        assertTrue(output.contains("\"Key\":\"b\"") || output.contains("\"Key\": \"b\""));
        assertTrue(output.contains("\"workerId\":\"w1\"") || output.contains("\"workerId\": \"w1\""));
        assertTrue(output.contains("\"workerId\":\"w2\"") || output.contains("\"workerId\": \"w2\""));
    }

    @Test
    void distributedMapWithS3JsonItemReader_objectPassesKeyValueShapeToProcessor() throws Exception {
        createBucket("map-inputs-object-shape");
        putObject("map-inputs-object-shape", "workers.json", """
                {"a":{"x":1},"b":{"x":2}}
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-object-shape",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-object-shape-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"Key\":\"a\"") || output.contains("\"Key\": \"a\""));
        assertTrue(output.contains("\"Value\":{\"x\":1}") || output.contains("\"Value\": {\"x\": 1}")
                || output.contains("\"Value\": { \"x\": 1 }"));
        assertTrue(output.contains("\"Key\":\"b\"") || output.contains("\"Key\": \"b\""));
        assertTrue(output.contains("\"Value\":{\"x\":2}") || output.contains("\"Value\": {\"x\": 2}")
                || output.contains("\"Value\": { \"x\": 2 }"));
    }

    @Test
    void distributedMapWithS3JsonObjectItemReader_itemSelectorExposesKeyIndexAndValue() throws Exception {
        createBucket("map-inputs-object-selector");
        putObject("map-inputs-object-selector", "workers.json", """
                {"a":{"workerId":"w1"},"b":{"workerId":"w2"}}
                """);

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-object-selector",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemSelector": {
                                "key.$": "$$.Map.Item.Key",
                                "index.$": "$$.Map.Item.Index",
                                "workerId.$": "$$.Map.Item.Value.workerId"
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-object-selector-test", definition);
        String execArn = startExecution(smArn, "{}");
        String output = waitForExecution(execArn);

        assertTrue(output.contains("\"key\":\"a\"") || output.contains("\"key\": \"a\""));
        assertTrue(output.contains("\"key\":\"b\"") || output.contains("\"key\": \"b\""));
        assertTrue(output.contains("\"index\":0") || output.contains("\"index\": 0"));
        assertTrue(output.contains("\"index\":1") || output.contains("\"index\": 1"));
        assertTrue(output.contains("\"workerId\":\"w1\"") || output.contains("\"workerId\": \"w1\""));
        assertTrue(output.contains("\"workerId\":\"w2\"") || output.contains("\"workerId\": \"w2\""));
    }

    @Test
    void distributedMapWithS3JsonItemReader_missingKeyFailsWithItemReaderError() throws Exception {
        createBucket("map-inputs-missing-key");

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-missing-key",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-missing-key-test", definition);
        String execArn = startExecution(smArn, "{}");
        Response failure = waitForExecutionFailure(execArn);

        assertEquals("FAILED", failure.jsonPath().getString("status"));
        assertEquals("States.ItemReaderFailed", failure.jsonPath().getString("error"));
    }

    @Test
    void distributedMapWithS3JsonItemReader_missingBucketFailsWithItemReaderError() throws Exception {
        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-missing-bucket",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-missing-bucket-test", definition);
        String execArn = startExecution(smArn, "{}");
        Response failure = waitForExecutionFailure(execArn);

        assertEquals("FAILED", failure.jsonPath().getString("status"));
        assertEquals("States.ItemReaderFailed", failure.jsonPath().getString("error"));
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
        // Variables set via Assign persist across states even after a later state replaces the output.
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
    void assignedVariablesAreNotVisibleUntilTheNextState() throws Exception {
        // Every variable reference in a state resolves against the values held on state entry, so a
        // state's own Output never sees that state's Assign; the new values land in the next state.
        // Assignments within one Assign block are likewise independent of each other. This mirrors
        // the evaluation-order example in the AWS docs: starting from $x=3 and $a=6,
        // {"x": "{% $a %}", "nextX": "{% $x %}"} ends with $x=6 and $nextX=3.
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "SeedVariables",
                    "States": {
                        "SeedVariables": {
                            "Type": "Pass",
                            "Assign": {
                                "x": 3,
                                "a": 6
                            },
                            "Next": "ReassignAndEmit"
                        },
                        "ReassignAndEmit": {
                            "Type": "Pass",
                            "Assign": {
                                "x": "{% $a %}",
                                "nextX": "{% $x %}"
                            },
                            "Output": {
                                "xSeenByAssigningState": "{% $x %}"
                            },
                            "Next": "ObserveAfterAssign"
                        },
                        "ObserveAfterAssign": {
                            "Type": "Pass",
                            "Output": {
                                "xSeenByAssigningState": "{% $states.input.xSeenByAssigningState %}",
                                "xAfter": "{% $x %}",
                                "nextXAfter": "{% $nextX %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-assign-evaluation-order-test", definition);
        String execArn = startExecution(smArn, "{}");
        JsonNode output = objectMapper.readTree(waitForExecution(execArn));

        // The assigning state's own Output still sees the pre-assignment value of $x.
        assertEquals(3, output.get("xSeenByAssigningState").asInt());
        // The next state sees both new values: $x from $a, and $nextX from the old $x.
        assertEquals(6, output.get("xAfter").asInt());
        assertEquals(3, output.get("nextXAfter").asInt());
    }

    @Test
    void variablesAssignedInsideMapDoNotLeakToParentScope() throws Exception {
        // Map iterations can read outer-scope variables but keep their own workflow-local scope:
        // a variable assigned inside an iteration goes out of scope once the Map completes.
        // The inner variable deliberately uses a name distinct from the outer one — AWS rejects an
        // inner-scope assignment that reuses an outer-scope variable name.
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "SetOuter",
                    "States": {
                        "SetOuter": {
                            "Type": "Pass",
                            "Assign": {
                                "outerVar": 42
                            },
                            "Next": "MapState"
                        },
                        "MapState": {
                            "Type": "Map",
                            "Items": [1, 2],
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "INLINE"
                                },
                                "StartAt": "AssignInner",
                                "States": {
                                    "AssignInner": {
                                        "Type": "Pass",
                                        "Assign": {
                                            "innerVar": "{% $outerVar %}"
                                        },
                                        "Output": {
                                            "outerSeenFromIteration": "{% $outerVar %}"
                                        },
                                        "End": true
                                    }
                                }
                            },
                            "Next": "CheckScope"
                        },
                        "CheckScope": {
                            "Type": "Pass",
                            "Output": {
                                "iterations": "{% $states.input %}",
                                "outerStillInScope": "{% $outerVar %}",
                                "innerLeaked": "{% $exists($innerVar) %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-assign-map-scope-test", definition);
        String execArn = startExecution(smArn, "{}");
        JsonNode output = objectMapper.readTree(waitForExecution(execArn));

        // Each iteration could read the outer variable.
        assertEquals(42, output.get("iterations").get(0).get("outerSeenFromIteration").asInt());
        assertEquals(42, output.get("iterations").get(1).get("outerSeenFromIteration").asInt());
        // The outer variable survives the Map, and the iteration-local one does not escape it.
        assertEquals(42, output.get("outerStillInScope").asInt());
        assertFalse(output.get("innerLeaked").asBoolean());
    }

    @Test
    void assignInMatchedChoiceRuleApplies() throws Exception {
        // A Choice rule carries its own Assign, which applies when that rule matches. The state-level
        // Assign belongs to the Default path and must not run when a rule matches.
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "ChoiceState",
                    "States": {
                        "ChoiceState": {
                            "Type": "Choice",
                            "Choices": [
                                {
                                    "Condition": "{% $states.input.condition %}",
                                    "Next": "Report",
                                    "Assign": {
                                        "assignment": "Condition assignment"
                                    }
                                }
                            ],
                            "Default": "Report",
                            "Assign": {
                                "assignment": "Default Assignment"
                            }
                        },
                        "Report": {
                            "Type": "Pass",
                            "Output": {
                                "assignment": "{% $assignment %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-assign-in-choice-test", definition);

        JsonNode matched = objectMapper.readTree(
                waitForExecution(startExecution(smArn, "{\"condition\": true}")));
        assertEquals("Condition assignment", matched.get("assignment").asText());

        JsonNode defaulted = objectMapper.readTree(
                waitForExecution(startExecution(smArn, "{\"condition\": false}")));
        assertEquals("Default Assignment", defaulted.get("assignment").asText());
    }

    @Test
    void assignInWaitStateApplies() throws Exception {
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "WaitState",
                    "States": {
                        "WaitState": {
                            "Type": "Wait",
                            "Seconds": 0,
                            "Assign": {
                                "foo": "oof"
                            },
                            "Next": "Report"
                        },
                        "Report": {
                            "Type": "Pass",
                            "Output": {
                                "foo": "{% $foo %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-assign-in-wait-test", definition);
        JsonNode output = objectMapper.readTree(waitForExecution(startExecution(smArn, "{}")));

        assertEquals("oof", output.get("foo").asText());
    }

    @Test
    void assignInCatchAppliesAndSeesErrorOutput() throws Exception {
        // A Catch block supports Assign and Output, and $states.errorOutput is bound inside it.
        // The Task fails while evaluating its Arguments, which is a catchable States.QueryEvaluationError.
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Boom",
                    "States": {
                        "Boom": {
                            "Type": "Task",
                            "Resource": "arn:aws:states:::lambda:invoke",
                            "Arguments": {
                                "bad": "{% $number('not-a-number') %}"
                            },
                            "Catch": [
                                {
                                    "ErrorEquals": ["States.QueryEvaluationError"],
                                    "Next": "Report",
                                    "Assign": {
                                        "caughtError": "{% $states.errorOutput.Error %}"
                                    }
                                }
                            ],
                            "End": true
                        },
                        "Report": {
                            "Type": "Pass",
                            "Output": {
                                "caughtError": "{% $caughtError %}",
                                "catchOutputError": "{% $states.input.Error %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-assign-in-catch-test", definition);
        JsonNode output = objectMapper.readTree(waitForExecution(startExecution(smArn, "{}")));

        // The Catch's Assign ran, and it could read $states.errorOutput.
        assertEquals("States.QueryEvaluationError", output.get("caughtError").asText());
        // With no Output on the Catch, the error output becomes the state output.
        assertEquals("States.QueryEvaluationError", output.get("catchOutputError").asText());
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

    @Test
    void distributedMapWithUnsupportedItemReaderResource_rejectedAtCreateStateMachine() {
        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:unknownOperation",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {"name":"map-itemreader-unsupported-resource-test","definition":%s,"roleArn":"%s","type":"STANDARD"}
                        """, quote(definition), ROLE_ARN))
                .when().post("/")
                .then().statusCode(400);
    }

    @Test
    void distributedMapWithListObjectsV2ItemReader_failsWithNotImplementedItemReaderError() throws Exception {
        createBucket("map-inputs-list-objects");
        putObject("map-inputs-list-objects", "workers/a.json", "[]");

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:listObjectsV2",
                                "ReaderConfig": {
                                    "InputType": "JSON"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-list-objects",
                                    "Prefix": "workers/"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-list-objects-v2-test", definition);
        String execArn = startExecution(smArn, "{}");
        Response failure = waitForExecutionFailure(execArn);

        assertEquals("FAILED", failure.jsonPath().getString("status"));
        assertEquals("States.ItemReaderFailed", failure.jsonPath().getString("error"));
        assertTrue(failure.jsonPath().getString("cause").contains("not yet implemented by the emulator"));
    }

    @Test
    void distributedMapWithCsvItemReader_failsWithNotImplementedItemReaderError() throws Exception {
        createBucket("map-inputs-csv");
        putObject("map-inputs-csv", "workers.csv", "workerId\nw1\n");

        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "CSV"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs-csv",
                                    "Key": "workers.csv"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        String smArn = createStateMachine("map-itemreader-s3-csv-test", definition);
        String execArn = startExecution(smArn, "{}");
        Response failure = waitForExecutionFailure(execArn);

        assertEquals("FAILED", failure.jsonPath().getString("status"));
        assertEquals("States.ItemReaderFailed", failure.jsonPath().getString("error"));
        assertTrue(failure.jsonPath().getString("cause").contains("InputType CSV is not yet implemented by the emulator"));
    }

    @Test
    void distributedMapWithUnsupportedItemReaderInputType_rejectedAtCreateStateMachine() {
        String definition = """
                {
                    "StartAt": "ProcessWorkers",
                    "States": {
                        "ProcessWorkers": {
                            "Type": "Map",
                            "ItemReader": {
                                "Resource": "arn:aws:states:::s3:getObject",
                                "ReaderConfig": {
                                    "InputType": "UNSUPPORTED"
                                },
                                "Parameters": {
                                    "Bucket": "map-inputs",
                                    "Key": "workers.json"
                                }
                            },
                            "ItemProcessor": {
                                "ProcessorConfig": {
                                    "Mode": "DISTRIBUTED",
                                    "ExecutionType": "STANDARD"
                                },
                                "StartAt": "PassItem",
                                "States": {
                                    "PassItem": {
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

        given()
                .header("X-Amz-Target", "AWSStepFunctions.CreateStateMachine")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        {"name":"map-itemreader-unsupported-inputtype-test","definition":%s,"roleArn":"%s","type":"STANDARD"}
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
            Response resp = describeExecution(execArn);
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

    private Response waitForExecutionFailure(String execArn) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            Response resp = describeExecution(execArn);
            String status = resp.jsonPath().getString("status");
            if ("FAILED".equals(status) || "ABORTED".equals(status)) {
                return resp;
            }
            if ("SUCCEEDED".equals(status)) {
                fail("Execution SUCCEEDED: " + resp.body().asString());
            }
            Thread.sleep(100);
        }
        fail("Execution did not fail within timeout");
        return null;
    }

    private Response describeExecution(String execArn) {
        return given()
                .header("X-Amz-Target", "AWSStepFunctions.DescribeExecution")
                .contentType(SFN_CONTENT_TYPE)
                .body(String.format("""
                        { "executionArn": "%s" }
                        """, execArn))
                .when()
                .post("/");
    }

    private void createBucket(String bucket) {
        given()
                .when()
                .put("/" + bucket)
                .then()
                .statusCode(200);
    }

    private void putObject(String bucket, String key, String body) {
        given()
                .body(body)
                .when()
                .put("/" + bucket + "/" + key)
                .then()
                .statusCode(200);
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
