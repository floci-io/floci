package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

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

    // A one-state Pass machine whose Output holds the given JSONata expression. The ObjectMapper
    // escapes the quotes and braces the expressions carry into a valid JSON string.
    private static String outputExpressionDefinition(String expression) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("QueryLanguage", "JSONata");
        root.put("StartAt", "Main");
        ObjectNode main = root.putObject("States").putObject("Main");
        main.put("Type", "Pass");
        main.put("Output", "{% " + expression + " %}");
        main.put("End", true);
        return root.toString();
    }

    // A Task whose Catch entry holds the given JSONata expression in its own Output or Assign,
    // the one place AWS resolves $states.errorOutput.
    private static String catchDefinition(String catcherField, String expression) {
        ObjectNode root = OBJECT_MAPPER.createObjectNode();
        root.put("QueryLanguage", "JSONata");
        root.put("StartAt", "Main");
        ObjectNode states = root.putObject("States");
        ObjectNode main = states.putObject("Main");
        main.put("Type", "Task");
        main.put("Resource", "arn:aws:states:::lambda:invoke");
        main.put("Next", "Handled");
        ObjectNode catcher = main.putArray("Catch").addObject();
        catcher.putArray("ErrorEquals").add("States.ALL");
        catcher.put("Next", "Handled");
        if ("Assign".equals(catcherField)) {
            catcher.putObject("Assign").put("lastError", "{% " + expression + " %}");
        } else {
            catcher.put(catcherField, "{% " + expression + " %}");
        }
        states.putObject("Handled").put("Type", "Pass").put("End", true);
        return root.toString();
    }

    private static Response validateDefinition(String definition) {
        String body = OBJECT_MAPPER.createObjectNode().put("definition", definition).toString();
        return given().contentType(CT).header("X-Amz-Target", TARGET)
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

    // Every (expression, message) pair here was run through
    // `aws stepfunctions validate-state-machine-definition --region us-east-1`: the message is
    // AWS's INVALID_JSONATA_EXPRESSION text, which is floci's own parser message with its
    // "S0xxx: " code prefix stripped.
    private static Stream<Arguments> invalidJsonataExpressions() {
        return Stream.of(
                Arguments.of("a[1,2)", "Expected \"]\", got \",\""),
                Arguments.of("\"unterminated", "String literal must be terminated by a matching quote"),
                Arguments.of("1 +", "Unexpected end of expression"),
                Arguments.of("{\"a\":}", "The symbol \"}\" cannot be used as a unary operator"),
                Arguments.of("$x :=", "Unexpected end of expression"),
                Arguments.of("$match(\"a\", /abc", "No terminating / in regular expression"),
                Arguments.of("phone %.other", "The symbol \".\" cannot be used as a unary operator"),
                Arguments.of("", "Unexpected end of expression"));
    }

    @ParameterizedTest
    @MethodSource("invalidJsonataExpressions")
    void unparsableJsonataExpression_returnsFailWithInvalidJsonataExpression(
            String expression, String expectedMessage) {
        validateDefinition(outputExpressionDefinition(expression))
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("INVALID_JSONATA_EXPRESSION"))
                .body("diagnostics[0].message", equalTo(expectedMessage))
                .body("diagnostics[0].location", equalTo("/States/Main/Output"));
    }

    @Test
    void doubleDollarReference_returnsFailWithUnsupportedJsonataExpression() {
        validateDefinition(outputExpressionDefinition("$$"))
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("UNSUPPORTED_JSONATA_EXPRESSION"))
                .body("diagnostics[0].message", equalTo("Reference to '$$' is not supported."))
                .body("diagnostics[0].location", equalTo("/States/Main/Output"));
    }

    @Test
    void statesErrorOutputOutsideCatch_returnsFailWithUnsupportedJsonataExpression() {
        validateDefinition(outputExpressionDefinition("$states.errorOutput"))
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("UNSUPPORTED_JSONATA_EXPRESSION"))
                .body("diagnostics[0].message", equalTo("Field '$states.errorOutput' does not exist."))
                .body("diagnostics[0].location", equalTo("/States/Main/Output"));
    }

    @Test
    void statesErrorOutputInsideCatchOutput_isAccepted() {
        validateDefinition(catchDefinition("Output", "$states.errorOutput"))
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void statesErrorOutputInsideCatchAssign_isAccepted() {
        validateDefinition(catchDefinition("Assign", "$states.errorOutput"))
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
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
