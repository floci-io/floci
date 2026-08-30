package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The six functions the Step Functions JSONata dialect adds on top of the JSONata language:
 * $parse, $partition, $range, $hash, $random and $uuid. Every expected value here was captured
 * from real AWS. Unit coverage of the edge cases lives in {@link JsonataEvaluatorTest}; these
 * cases run the functions through CreateStateMachine and StartExecution, which is how a state
 * machine reaches them.
 */
@QuarkusTest
class StepFunctionsJsonataFunctionsIntegrationTest {

    private static final String SFN_CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/test-role";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void parseDeserializesAJsonStringAndNavigatesTheResult() throws Exception {
        // The dominant real-world shape: a nested execution returns its Output as a JSON string,
        // and the caller reads a field off it. AWS disables $eval, so $parse is the only way in.
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Read",
                    "States": {
                        "Read": {
                            "Type": "Pass",
                            "Output": {
                                "amount": "{% $parse($states.input.body).detail.amount %}",
                                "currency": "{% $parse($states.input.body).detail.currency %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-parse", definition);
        String input = "{\"body\": \"{\\\"detail\\\":{\\\"amount\\\":1250,\\\"currency\\\":\\\"ARS\\\"}}\"}";
        JsonNode output = objectMapper.readTree(waitForExecution(startExecution(smArn, input)));

        assertEquals(1250, output.get("amount").asInt());
        assertEquals("ARS", output.get("currency").asText());
    }

    @Test
    void partitionSplitsAnArrayIntoChunksWithAShorterLastChunk() throws Exception {
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Split",
                    "States": {
                        "Split": {
                            "Type": "Pass",
                            "Output": {
                                "chunks": "{% $partition([1, 2, 3, 4, 5], 2) %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-partition", definition);
        JsonNode output = objectMapper.readTree(waitForExecution(startExecution(smArn, "{}")));

        assertEquals("[[1,2],[3,4],[5]]", output.get("chunks").toString());
    }

    @Test
    void rangeIncludesTheEndValueWhenTheStepLandsOnIt() throws Exception {
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Generate",
                    "States": {
                        "Generate": {
                            "Type": "Pass",
                            "Output": {
                                "even": "{% $range(0, 6, 2) %}",
                                "descending": "{% $range(3, 1, -1) %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-range", definition);
        JsonNode output = objectMapper.readTree(waitForExecution(startExecution(smArn, "{}")));

        assertEquals("[0,2,4,6]", output.get("even").toString());
        assertEquals("[3,2,1]", output.get("descending").toString());
    }

    @Test
    void hashComputesTheHexDigestForEachSupportedAlgorithm() throws Exception {
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Digest",
                    "States": {
                        "Digest": {
                            "Type": "Pass",
                            "Output": {
                                "md5": "{% $hash('input', 'MD5') %}",
                                "sha1": "{% $hash('input', 'SHA-1') %}",
                                "sha256": "{% $hash('input', 'SHA-256') %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-hash", definition);
        JsonNode output = objectMapper.readTree(waitForExecution(startExecution(smArn, "{}")));

        assertEquals("a43c1b0aa53a0c908810c06ab1ff3967", output.get("md5").asText());
        assertEquals("140f86aae51ab9e1cda9b4254fe98a74eb54c1a1", output.get("sha1").asText());
        assertEquals("c96c6d5be8d08a12e7b5cdc1b207fa6b2430974c86803d8891675e76fd992c20",
                output.get("sha256").asText());
    }

    @Test
    void uuidGeneratesACanonicalV4Uuid() throws Exception {
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Mint",
                    "States": {
                        "Mint": {
                            "Type": "Pass",
                            "Output": {
                                "first": "{% $uuid() %}",
                                "second": "{% $uuid() %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-uuid", definition);
        JsonNode output = objectMapper.readTree(waitForExecution(startExecution(smArn, "{}")));

        String first = output.get("first").asText();
        String second = output.get("second").asText();
        assertTrue(first.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"),
                "not a canonical v4 UUID: " + first);
        assertTrue(!first.equals(second), "two calls returned the same UUID: " + first);
    }

    @Test
    void randomAcceptsTheSeedThatJsonatasOwnRandomRejects() throws Exception {
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Draw",
                    "States": {
                        "Draw": {
                            "Type": "Pass",
                            "Output": {
                                "seeded": "{% $random(42) %}",
                                "unseeded": "{% $random() %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-random", definition);
        JsonNode output = objectMapper.readTree(waitForExecution(startExecution(smArn, "{}")));

        assertEquals(0.7275636800328681, output.get("seeded").asDouble());
        assertTrue(output.get("unseeded").asDouble() >= 0 && output.get("unseeded").asDouble() < 1,
                "unseeded draw outside [0, 1): " + output.get("unseeded"));
    }

    @Test
    void anUnsupportedHashAlgorithmFailsTheExecution() throws Exception {
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Digest",
                    "States": {
                        "Digest": {
                            "Type": "Pass",
                            "Output": {
                                "digest": "{% $hash('input', 'SHA-9') %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-hash-unsupported", definition);
        Response resp = waitForExecutionFailure(startExecution(smArn, "{}"));

        assertEquals("States.QueryEvaluationError", resp.jsonPath().getString("error"));
        assertTrue(resp.jsonPath().getString("cause").contains("SHA-9"),
                "cause does not name the rejected algorithm: " + resp.jsonPath().getString("cause"));
    }

    @Test
    void aFailedJsonataExpressionReportsTheErrorCodeAndTheTypeItWanted() throws Exception {
        // The reproduction in #2668, end to end: DescribeExecution is where a caller reads the
        // cause, and the malformed one there said Object "n" and {{type}}.
        String definition = """
                {
                    "QueryLanguage": "JSONata",
                    "StartAt": "Sum",
                    "States": {
                        "Sum": {
                            "Type": "Pass",
                            "Output": {
                                "v": "{% $sum(['a']) %}"
                            },
                            "End": true
                        }
                    }
                }
                """;

        String smArn = createStateMachine("jsonata-error-cause", definition);
        Response resp = waitForExecutionFailure(startExecution(smArn, "{}"));

        assertEquals("States.QueryEvaluationError", resp.jsonPath().getString("error"));
        assertEquals("T0412: Argument [\"a\"] must be an array of \"numbers\"",
                resp.jsonPath().getString("cause"));
    }

    // ---- helpers ----

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
