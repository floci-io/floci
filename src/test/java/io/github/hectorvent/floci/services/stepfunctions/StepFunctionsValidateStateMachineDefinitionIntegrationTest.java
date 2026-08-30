package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

/**
 * Integration tests for SFN ValidateStateMachineDefinition via the JSON 1.0 wire path.
 * All wire fields are lowercase per the official AWS spec.
 */
@QuarkusTest
class StepFunctionsValidateStateMachineDefinitionIntegrationTest {

    private static final String CT = "application/x-amz-json-1.0";
    private static final String TARGET = "AWSStepFunctions.ValidateStateMachineDefinition";
    private static final String LIST_TARGET = "AWSStepFunctions.ListStateMachines";
    private static final String CREATE_TARGET = "AWSStepFunctions.CreateStateMachine";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/service-role/sfn";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ASL with the inner double-quotes already JSON-escaped, so it embeds cleanly
    // inside the outer JSON request body as the value of "definition".
    private static final String VALID_ASL =
            "{\\\"StartAt\\\":\\\"Done\\\",\\\"States\\\":{\\\"Done\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}}";

    // JSONata state declaring three JSONPath-only fields → 3 distinct errors.
    private static final String JSONATA_WITH_3_JSONPATH_FIELDS =
            "{\\\"QueryLanguage\\\":\\\"JSONata\\\",\\\"StartAt\\\":\\\"X\\\","
                    + "\\\"States\\\":{\\\"X\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true,"
                    + "\\\"InputPath\\\":\\\"$.a\\\","
                    + "\\\"OutputPath\\\":\\\"$.b\\\","
                    + "\\\"ResultPath\\\":\\\"$.c\\\"}}}";
    private static final String MAP_WITH_UNSUPPORTED_ITEM_READER_RESOURCE =
            "{\\\"StartAt\\\":\\\"ProcessItems\\\",\\\"States\\\":{\\\"ProcessItems\\\":{"
                    + "\\\"Type\\\":\\\"Map\\\","
                    + "\\\"ItemReader\\\":{"
                    + "\\\"Resource\\\":\\\"arn:aws:states:::s3:unknownOperation\\\","
                    + "\\\"ReaderConfig\\\":{\\\"InputType\\\":\\\"JSON\\\"},"
                    + "\\\"Parameters\\\":{\\\"Bucket\\\":\\\"map-inputs\\\",\\\"Key\\\":\\\"workers.json\\\"}"
                    + "},"
                    + "\\\"ItemProcessor\\\":{"
                    + "\\\"ProcessorConfig\\\":{\\\"Mode\\\":\\\"DISTRIBUTED\\\",\\\"ExecutionType\\\":\\\"STANDARD\\\"},"
                    + "\\\"StartAt\\\":\\\"PassItem\\\","
                    + "\\\"States\\\":{\\\"PassItem\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}"
                    + "},"
                    + "\\\"End\\\":true"
                    + "}}}";
    private static final String MAP_WITH_UNSUPPORTED_ITEM_READER_INPUT_TYPE =
            "{\\\"StartAt\\\":\\\"ProcessItems\\\",\\\"States\\\":{\\\"ProcessItems\\\":{"
                    + "\\\"Type\\\":\\\"Map\\\","
                    + "\\\"ItemReader\\\":{"
                    + "\\\"Resource\\\":\\\"arn:aws:states:::s3:getObject\\\","
                    + "\\\"ReaderConfig\\\":{\\\"InputType\\\":\\\"UNSUPPORTED\\\"},"
                    + "\\\"Parameters\\\":{\\\"Bucket\\\":\\\"map-inputs\\\",\\\"Key\\\":\\\"workers.json\\\"}"
                    + "},"
                    + "\\\"ItemProcessor\\\":{"
                    + "\\\"ProcessorConfig\\\":{\\\"Mode\\\":\\\"DISTRIBUTED\\\",\\\"ExecutionType\\\":\\\"STANDARD\\\"},"
                    + "\\\"StartAt\\\":\\\"PassItem\\\","
                    + "\\\"States\\\":{\\\"PassItem\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}"
                    + "},"
                    + "\\\"End\\\":true"
                    + "}}}";
    private static final String MAP_WITH_ITEM_READER_WITHOUT_DISTRIBUTED_MODE =
            "{\\\"StartAt\\\":\\\"ProcessItems\\\",\\\"States\\\":{\\\"ProcessItems\\\":{"
                    + "\\\"Type\\\":\\\"Map\\\","
                    + "\\\"ItemReader\\\":{"
                    + "\\\"Resource\\\":\\\"arn:aws:states:::s3:getObject\\\","
                    + "\\\"ReaderConfig\\\":{\\\"InputType\\\":\\\"JSON\\\"},"
                    + "\\\"Parameters\\\":{\\\"Bucket\\\":\\\"map-inputs\\\",\\\"Key\\\":\\\"workers.json\\\"}"
                    + "},"
                    + "\\\"ItemProcessor\\\":{"
                    + "\\\"StartAt\\\":\\\"PassItem\\\","
                    + "\\\"States\\\":{\\\"PassItem\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true}}"
                    + "},"
                    + "\\\"End\\\":true"
                    + "}}}";

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response validateDefinition(String definition) {
        String body = OBJECT_MAPPER.createObjectNode().put("definition", definition).toString();
        return given().contentType(CT).header("X-Amz-Target", TARGET)
                .body(body)
                .when().post("/");
    }

    private static Response createStateMachine(String name, String definition) {
        String body = OBJECT_MAPPER.createObjectNode()
                .put("name", name)
                .put("definition", definition)
                .put("roleArn", ROLE_ARN)
                .toString();
        return given().contentType(CT).header("X-Amz-Target", CREATE_TARGET)
                .body(body)
                .when().post("/");
    }

    private static String distributedMapWithResultWriter(String resultWriter) {
        return """
                {
                  "StartAt":"ProcessItems",
                  "States":{
                    "ProcessItems":{
                      "Type":"Map",
                      "ItemsPath":"$.items",
                      "ItemProcessor":{
                        "ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                        "StartAt":"PassItem",
                        "States":{"PassItem":{"Type":"Pass","End":true}}
                      },
                      "ResultWriter":__RESULT_WRITER__,
                      "End":true
                    }
                  }
                }
                """.replace("__RESULT_WRITER__", resultWriter);
    }

    private static String jsonataDistributedMapWithResultWriter(String resultWriter) {
        return """
                {
                  "QueryLanguage":"JSONata",
                  "StartAt":"ProcessItems",
                  "States":{
                    "ProcessItems":{
                      "Type":"Map",
                      "Items":"{% $states.input.items %}",
                      "ItemProcessor":{
                        "ProcessorConfig":{"Mode":"DISTRIBUTED","ExecutionType":"STANDARD"},
                        "StartAt":"PassItem",
                        "States":{"PassItem":{"Type":"Pass","End":true}}
                      },
                      "ResultWriter":__RESULT_WRITER__,
                      "End":true
                    }
                  }
                }
                """.replace("__RESULT_WRITER__", resultWriter);
    }

    @Test
    void validDefinition_returnsOK() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + VALID_ASL + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0))
                .body("truncated", is(false));
    }

    @Test
    void malformedJson_returnsFailWithInvalidJson() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"{not json\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("INVALID_JSON_DESCRIPTION"))
                // No location for JSON parse errors — there's no state path to point to yet.
                .body("diagnostics[0].location", nullValue());
    }

    @Test
    void jsonataStateWithJsonpathField_returnsFailWithSchemaError() {
        // A single JSONata state declaring InputPath → exactly 1 error.
        String def = "{\\\"QueryLanguage\\\":\\\"JSONata\\\",\\\"StartAt\\\":\\\"X\\\","
                + "\\\"States\\\":{\\\"X\\\":{\\\"Type\\\":\\\"Pass\\\",\\\"End\\\":true,"
                + "\\\"InputPath\\\":\\\"$.a\\\"}}}";
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + def + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].location", equalTo("/States/X/InputPath"));
    }

    @Test
    void mapCannotSpecifyBothMaxConcurrencyFields() {
        String def = mapDefinition("", "\"MaxConcurrency\":2,"
                + "\"MaxConcurrencyPath\":\"$.limit\",");

        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body(definitionRequest(def))
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo("/States/M/MaxConcurrency"));
    }

    @Test
    void mapMaxConcurrencyMustBeANonNegativeInteger() {
        String def = mapDefinition("", "\"MaxConcurrency\":-1,");

        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body(definitionRequest(def))
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo("/States/M/MaxConcurrency"));
    }

    @Test
    void jsonataMapRejectsMaxConcurrencyPath() {
        String def = mapDefinition("\"QueryLanguage\":\"JSONata\",",
                "\"MaxConcurrencyPath\":\"$.limit\",");

        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body(definitionRequest(def))
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo("/States/M/MaxConcurrencyPath"));
    }

    @Test
    void jsonataMapAcceptsMaxConcurrencyExpression() {
        String def = mapDefinition("\"QueryLanguage\":\"JSONata\",",
                "\"MaxConcurrency\":\"{% $states.input.limit %}\",");

        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body(definitionRequest(def))
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void nestedMapMaxConcurrencyIsValidatedAtItsStructuredPath() {
        String def = "{\"StartAt\":\"Outer\",\"States\":{\"Outer\":{\"Type\":\"Map\","
                + "\"ItemProcessor\":{\"StartAt\":\"Inner\",\"States\":{"
                + "\"Inner\":{\"Type\":\"Map\",\"MaxConcurrency\":-1,"
                + "\"ItemProcessor\":{\"StartAt\":\"P\",\"States\":{"
                + "\"P\":{\"Type\":\"Pass\",\"End\":true}}},\"End\":true}}},"
                + "\"End\":true}}}";

        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body(definitionRequest(def))
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo(
                        "/States/Outer/ItemProcessor/States/Inner/MaxConcurrency"));
    }

    @Test
    void maxConcurrencyPathMustSelectASingleNode() {
        String def = mapDefinition("", "\"MaxConcurrencyPath\":\"$.limits[*]\",");

        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body(definitionRequest(def))
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo("/States/M/MaxConcurrencyPath"));
    }

    @Test
    void maxConcurrencyLargerThanIntegerRangeIsAcceptedAndRuntimeCapped() {
        String def = mapDefinition("", "\"MaxConcurrency\":9223372036854775807,");

        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body(definitionRequest(def))
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void unsupportedItemReaderResource_returnsFailWithSchemaError() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + MAP_WITH_UNSUPPORTED_ITEM_READER_RESOURCE + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].location", equalTo("/States/ProcessItems/ItemReader/Resource"));
    }

    @Test
    void unsupportedItemReaderInputType_returnsFailWithSchemaError() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + MAP_WITH_UNSUPPORTED_ITEM_READER_INPUT_TYPE + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].location", equalTo("/States/ProcessItems/ItemReader/ReaderConfig/InputType"));
    }

    @Test
    void validResultWriterResourceAndParameters_returnsOK() {
        String definition = distributedMapWithResultWriter("""
                {
                  "Resource":"arn:aws:states:::s3:putObject",
                  "WriterConfig":{"Transformation":"FLATTEN","OutputType":"JSONL"},
                  "Parameters":{"Bucket.$":"$.destination.bucket","Prefix":"results"}
                }
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void jsonataResultWriterAcceptsExpressionFormArguments() {
        String definition = jsonataDistributedMapWithResultWriter("""
                {
                  "Resource":"arn:aws:states:::s3:putObject",
                  "Arguments":"{% $states.input.destination %}"
                }
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void jsonataResultWriterRejectsJsonpathParameters() {
        String definition = jsonataDistributedMapWithResultWriter("""
                {
                  "Resource":"arn:aws:states:::s3:putObject",
                  "Arguments":{"Bucket":"results-bucket"},
                  "Parameters":{"Bucket":"ignored-bucket"}
                }
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo(
                        "/States/ProcessItems/ResultWriter/Parameters"));
    }

    @Test
    void jsonpathResultWriterRejectsJsonataArguments() {
        String definition = distributedMapWithResultWriter("""
                {
                  "Resource":"arn:aws:states:::s3:putObject",
                  "Parameters":{"Bucket":"results-bucket"},
                  "Arguments":{"Bucket":"ignored-bucket"}
                }
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo(
                        "/States/ProcessItems/ResultWriter/Arguments"));
    }

    @Test
    void writerConfigRequiresTransformationAndOutputTypeTogether() {
        String definition = distributedMapWithResultWriter("""
                {"WriterConfig":{"Transformation":"COMPACT"}}
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo(
                        "/States/ProcessItems/ResultWriter/WriterConfig"));
    }

    @Test
    void writerConfigValuesMustBeStrings() {
        String definition = distributedMapWithResultWriter("""
                {"WriterConfig":{"Transformation":{},"OutputType":[]}}
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo(
                        "/States/ProcessItems/ResultWriter/WriterConfig"));
    }

    @Test
    void resultWriterDestinationFieldsMustBeStrings() {
        String definition = distributedMapWithResultWriter("""
                {
                  "Resource":"arn:aws:states:::s3:putObject",
                  "Parameters":{"Bucket":42,"Prefix":{}}
                }
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2));
    }

    @Test
    void emptyResultWriter_returnsFailWithSchemaError() {
        validateDefinition(distributedMapWithResultWriter("{}"))
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo("/States/ProcessItems/ResultWriter"));
    }

    @Test
    void resultWriterResourceWithoutParameters_returnsFailWithSchemaError() {
        String definition = distributedMapWithResultWriter("""
                {"Resource":"arn:aws:states:::s3:putObject"}
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo("/States/ProcessItems/ResultWriter"));
    }

    @Test
    void nullResultWriterResourceIsNotTreatedAsAbsent() {
        String definition = distributedMapWithResultWriter("""
                {
                  "Resource":null,
                  "Parameters":{"Bucket":"results-bucket"},
                  "WriterConfig":{"Transformation":"COMPACT","OutputType":"JSON"}
                }
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo(
                        "/States/ProcessItems/ResultWriter/Resource"));
    }

    @Test
    void unsupportedResultWriterResource_returnsFailWithSchemaError() {
        String definition = distributedMapWithResultWriter("""
                {
                  "Resource":"arn:aws:states:::s3:unknownOperation",
                  "Parameters":{"Bucket":"results-bucket"}
                }
                """);

        validateDefinition(definition)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].location", equalTo("/States/ProcessItems/ResultWriter/Resource"));
    }

    @Test
    void itemReaderWithoutDistributedMode_isAcceptedAtDefinitionTime() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + MAP_WITH_ITEM_READER_WITHOUT_DISTRIBUTED_MODE + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0))
                .body("truncated", is(false));
    }

    @Test
    void emptyDefinition_returns400() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void missingDefinition_returns400() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void maxResultsTruncates() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + JSONATA_WITH_3_JSONPATH_FIELDS + "\",\"maxResults\":1}")
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("truncated", is(true));
    }

    @Test
    void maxResultsZeroUsesDefault() {
        // Per AWS spec: maxResults=0 means "use default of 100", not "return zero".
        // The 3 errors from JSONATA_WITH_3_JSONPATH_FIELDS all fit under 100 → no truncation.
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + JSONATA_WITH_3_JSONPATH_FIELDS + "\",\"maxResults\":0}")
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(3))
                .body("truncated", is(false));
    }

    @Test
    void validDefinition_doesNotTouchStorage() {
        // Snapshot the state-machine list, validate a definition, snapshot again — must match.
        int before = given().contentType(CT).header("X-Amz-Target", LIST_TARGET)
                .body("{}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getList("stateMachines").size();

        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + VALID_ASL + "\"}")
                .when().post("/")
                .then().statusCode(200);

        int after = given().contentType(CT).header("X-Amz-Target", LIST_TARGET)
                .body("{}")
                .when().post("/")
                .then().statusCode(200)
                .extract().jsonPath().getList("stateMachines").size();

        // Same count proves validate didn't create a state machine.
        Assertions.assertEquals(before, after,
                "validate must not touch storage (before=" + before + " after=" + after + ")");
    }

    @Test
    void typeParameterAccepted() {
        // Floci's validator is type-agnostic; the param round-trips without changing behavior.
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + VALID_ASL + "\",\"type\":\"EXPRESS\"}")
                .when().post("/")
                .then().statusCode(200)
                .body("result", equalTo("OK"));
    }

    @Test
    void maxResultsAbove100Rejected() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + VALID_ASL + "\",\"maxResults\":101}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void maxResultsBelowZeroRejected() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + VALID_ASL + "\",\"maxResults\":-1}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void severityInvalidEnumRejected() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + VALID_ASL + "\",\"severity\":\"GARBAGE\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void typeInvalidEnumRejected() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + VALID_ASL + "\",\"type\":\"BOGUS\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void maxResultsNonIntegerRejected() {
        // JsonNode.asInt() would silently coerce "abc" to 0, which the service then
        // treats as "use default". Reject at the handler boundary instead.
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + VALID_ASL + "\",\"maxResults\":\"abc\"}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void maxResultsFractionalRejected() {
        given().contentType(CT).header("X-Amz-Target", TARGET)
                .body("{\"definition\":\"" + VALID_ASL + "\",\"maxResults\":1.7}")
                .when().post("/")
                .then().statusCode(400)
                .body("__type", containsString("ValidationException"));
    }

    @Test
    void loopWithNoTerminalState_returnsFailWithMissingEndState() {
        String def = """
                {"StartAt":"A","States":{
                  "A":{"Type":"Pass","Next":"B"},
                  "B":{"Type":"Pass","Next":"A"}
                }}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_END_STATE"))
                .body("diagnostics[0].message", equalTo("Workflow has no terminal state"))
                .body("diagnostics[0].location", nullValue());
    }

    @Test
    void missingEndStateSuppressesUnreachableStateDiagnosticForOtherStates() {
        // Same loop as above, plus a state nothing routes to. If MISSING_END_STATE did not
        // suppress the reachability walk, "Unused" would also be reported as not reachable.
        String def = """
                {"StartAt":"A","States":{
                  "A":{"Type":"Pass","Next":"B"},
                  "B":{"Type":"Pass","Next":"A"},
                  "Unused":{"Type":"Pass","Next":"A"}
                }}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_END_STATE"));
    }

    @Test
    void missingEndStateCoexistsWithADanglingTargetInAWSOrder() {
        // MISSING_END_STATE only suppresses the unreachable-state half of the reachability walk:
        // a dangling Next target is a separate, independent check and still fires. Measured
        // against real AWS: both diagnostics come back in the same response, dangling target
        // first and MISSING_END_STATE second.
        String def = """
                {"StartAt":"A","States":{"A":{"Type":"Pass","Next":"NOPE"}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("Missing 'Next' target: NOPE"))
                .body("diagnostics[0].location", equalTo("/States/A/Next"))
                .body("diagnostics[1].code", equalTo("MISSING_END_STATE"))
                .body("diagnostics[1].message", equalTo("Workflow has no terminal state"))
                .body("diagnostics[1].location", nullValue());
    }

    @Test
    void unreachableTerminalStateAddedToLoop_returnsFailWithMissingTransitionTarget() {
        // Adding an unreachable terminal to the same loop gives a state a top-level state has:
        // MISSING_END_STATE no longer applies, and the reachability walk now runs and catches C.
        String def = """
                {"StartAt":"A","States":{
                  "A":{"Type":"Pass","Next":"B"},
                  "B":{"Type":"Pass","Next":"A"},
                  "C":{"Type":"Pass","End":true}
                }}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("State \"C\" is not reachable."))
                .body("diagnostics[0].location", equalTo("/States/C"));
    }

    @Test
    void nestedLoopWithNoTerminalState_isAcceptedBecauseMissingEndStateIsTopLevelOnly() {
        String def = """
                {"StartAt":"M","States":{"M":{"Type":"Map","ItemsPath":"$.items",
                  "ItemProcessor":{"StartAt":"A","States":{
                    "A":{"Type":"Pass","Next":"B"},
                    "B":{"Type":"Pass","Next":"A"}
                  }},
                  "End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void timeoutSecondsOnMap_returnsFailWithSchemaError() {
        String def = """
                {"StartAt":"M","States":{"M":{"Type":"Map","TimeoutSeconds":5,"ItemsPath":"$.items",
                  "ItemProcessor":{"ProcessorConfig":{"Mode":"INLINE"},
                    "StartAt":"W","States":{"W":{"Type":"Wait","Seconds":25,"End":true}}},
                  "End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Field 'TimeoutSeconds' is not supported"))
                .body("diagnostics[0].location", equalTo("/States/M"));
    }

    @Test
    void timeoutSecondsOnParallel_returnsFailWithSchemaError() {
        String def = """
                {"StartAt":"P","States":{"P":{"Type":"Parallel","TimeoutSeconds":5,
                  "Branches":[{"StartAt":"W","States":{"W":{"Type":"Wait","Seconds":25,"End":true}}}],
                  "End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Field 'TimeoutSeconds' is not supported"))
                .body("diagnostics[0].location", equalTo("/States/P"));
    }

    @Test
    void timeoutSecondsAcceptedOnTask() {
        String def = """
                {"StartAt":"T","States":{"T":{"Type":"Task",
                  "Resource":"arn:aws:states:::lambda:invoke","TimeoutSeconds":5,"End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void timeoutSecondsRejectedOnPass() {
        // Same rule as Map and Parallel above, generalized to a state type that never carries it.
        String def = """
                {"StartAt":"X","States":{"X":{"Type":"Pass","TimeoutSeconds":5,"End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Field 'TimeoutSeconds' is not supported"))
                .body("diagnostics[0].location", equalTo("/States/X"));
    }

    @Test
    void catchRejectedOnPass() {
        String def = """
                {"StartAt":"X","States":{"X":{"Type":"Pass","End":true,
                  "Catch":[{"ErrorEquals":["States.ALL"],"Next":"X"}]}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Field 'Catch' is not supported"))
                .body("diagnostics[0].location", equalTo("/States/X"));
    }

    @Test
    void retryRejectedOnWait() {
        String def = """
                {"StartAt":"X","States":{"X":{"Type":"Wait","Seconds":1,"End":true,
                  "Retry":[{"ErrorEquals":["States.ALL"]}]}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Field 'Retry' is not supported"))
                .body("diagnostics[0].location", equalTo("/States/X"));
    }

    @Test
    void catchAndRetryAcceptedOnTaskParallelAndMap() {
        String def = """
                {"StartAt":"T","States":{
                  "T":{"Type":"Task","Resource":"arn:aws:states:::lambda:invoke",
                       "Catch":[{"ErrorEquals":["States.ALL"],"Next":"Done"}],
                       "Retry":[{"ErrorEquals":["States.ALL"]}],"Next":"Par"},
                  "Par":{"Type":"Parallel",
                       "Catch":[{"ErrorEquals":["States.ALL"],"Next":"Done"}],
                       "Retry":[{"ErrorEquals":["States.ALL"]}],
                       "Branches":[{"StartAt":"B","States":{"B":{"Type":"Pass","End":true}}}],
                       "Next":"M"},
                  "M":{"Type":"Map","ItemsPath":"$.items",
                       "Catch":[{"ErrorEquals":["States.ALL"],"Next":"Done"}],
                       "Retry":[{"ErrorEquals":["States.ALL"]}],
                       "ItemProcessor":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                       "Next":"Done"},
                  "Done":{"Type":"Succeed"}
                }}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void catchRejectedOnPassEvenWhenJsonata() {
        // QueryLanguage does not change which state types accept Catch: measured against real
        // AWS, a JSONata Pass state with a Catch still fails SCHEMA_VALIDATION_FAILED, the same
        // as in JSONPath mode.
        String def = """
                {"QueryLanguage":"JSONata","StartAt":"X","States":{"X":{"Type":"Pass","End":true,
                  "Catch":[{"ErrorEquals":["States.ALL"],"Next":"X"}]}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Field 'Catch' is not supported"))
                .body("diagnostics[0].location", equalTo("/States/X"));
    }

    @Test
    void danglingNextTarget_returnsFailAtNextLocation() {
        String def = """
                {"StartAt":"A","States":{
                  "A":{"Type":"Task","Resource":"arn:aws:states:::lambda:invoke","Next":"Ghost",
                       "Catch":[{"ErrorEquals":["States.ALL"],"Next":"Z"}]},
                  "Z":{"Type":"Pass","End":true}
                }}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("Missing 'Next' target: Ghost"))
                .body("diagnostics[0].location", equalTo("/States/A/Next"));
    }

    @Test
    void danglingChoiceNextTarget_returnsFailAtChoicesLocation() {
        String def = """
                {"StartAt":"C","States":{
                  "C":{"Type":"Choice",
                       "Choices":[{"Variable":"$.x","IsPresent":true,"Next":"Ghost"}],
                       "Default":"Z"},
                  "Z":{"Type":"Pass","End":true}
                }}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("Missing 'Next' target: Ghost"))
                .body("diagnostics[0].location", equalTo("/States/C/Choices[0]/Next"));
    }

    @Test
    void danglingChoiceDefaultTarget_returnsFailAtDefaultLocation() {
        String def = """
                {"StartAt":"A","States":{
                  "A":{"Type":"Choice",
                       "Choices":[{"Variable":"$.x","IsPresent":true,"Next":"Z"}],
                       "Default":"Ghost"},
                  "Z":{"Type":"Pass","End":true}
                }}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("Missing 'Next' target: Ghost"))
                .body("diagnostics[0].location", equalTo("/States/A/Default"));
    }

    @Test
    void danglingCatchNextTarget_returnsFailAtCatchLocation() {
        String def = """
                {"StartAt":"T","States":{"T":{"Type":"Task",
                  "Resource":"arn:aws:states:::lambda:invoke",
                  "Catch":[{"ErrorEquals":["States.ALL"],"Next":"Ghost"}],
                  "End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("Missing 'Next' target: Ghost"))
                .body("diagnostics[0].location", equalTo("/States/T/Catch[0]/Next"));
    }

    @Test
    void unreachableStateInsideItemProcessor_returnsFailAtStructuredPath() {
        String def = """
                {"StartAt":"M","States":{"M":{"Type":"Map","ItemsPath":"$.items",
                  "ItemProcessor":{"StartAt":"W","States":{
                    "W":{"Type":"Pass","End":true},
                    "Ghost":{"Type":"Pass","End":true}
                  }},
                  "End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("State \"Ghost\" is not reachable."))
                .body("diagnostics[0].location", equalTo("/States/M/ItemProcessor/States/Ghost"));
    }

    @Test
    void unreachableStateInsideParallelBranch_returnsFailAtStructuredPath() {
        String def = """
                {"StartAt":"P","States":{"P":{"Type":"Parallel",
                  "Branches":[{"StartAt":"W","States":{
                    "W":{"Type":"Pass","End":true},
                    "Ghost":{"Type":"Pass","End":true}
                  }}],
                  "End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("State \"Ghost\" is not reachable."))
                .body("diagnostics[0].location", equalTo("/States/P/Branches[0]/States/Ghost"));
    }

    @Test
    void createStateMachineRefusesLoopWithNoTerminalState() {
        String def = """
                {"StartAt":"A","States":{
                  "A":{"Type":"Pass","Next":"B"},
                  "B":{"Type":"Pass","Next":"A"}
                }}
                """;
        createStateMachine("no-terminal-" + System.nanoTime(), def)
                .then().statusCode(400)
                .body("__type", equalTo("InvalidDefinition"))
                .body("message", equalTo("Invalid State Machine Definition: "
                        + "'MISSING_END_STATE: Workflow has no terminal state at null'"));
    }

    @Test
    void createStateMachineRefusesTimeoutSecondsOnParallel() {
        // Once this is refused at CreateStateMachine, no definition that reaches execution can
        // carry a Parallel TimeoutSeconds — the AslExecutor branch that read it is unreachable.
        String def = """
                {"StartAt":"P","States":{"P":{"Type":"Parallel","TimeoutSeconds":5,
                  "Branches":[{"StartAt":"W","States":{"W":{"Type":"Wait","Seconds":25,"End":true}}}],
                  "End":true}}}
                """;
        createStateMachine("parallel-timeout-" + System.nanoTime(), def)
                .then().statusCode(400)
                .body("__type", equalTo("InvalidDefinition"))
                .body("message", equalTo("Invalid State Machine Definition: "
                        + "'SCHEMA_VALIDATION_FAILED: Field 'TimeoutSeconds' is not supported "
                        + "at /States/P'"));
    }

    private static String mapDefinition(String topLevelFields, String concurrencyFields) {
        return "{" + topLevelFields
                + "\"StartAt\":\"M\",\"States\":{\"M\":{\"Type\":\"Map\","
                + concurrencyFields
                + "\"ItemProcessor\":{\"StartAt\":\"P\",\"States\":{"
                + "\"P\":{\"Type\":\"Pass\",\"End\":true}}},\"End\":true}}}";
    }

    private static String definitionRequest(String definition) {
        String escaped = definition.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "{\"definition\":\"" + escaped + "\"}";
    }
}
