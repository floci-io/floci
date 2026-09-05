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

import java.util.List;
import java.util.Map;
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
    void jsonataParseErrorMessageContainingAtStatesSlash_keepsLiteralTextAndFieldLocation() {
        // The malformed literal itself contains " at /States/GHOST". Splitting the flat marker on
        // the FIRST " at /States/..." match would truncate the message there and invent a location
        // from the literal text instead of the field's own.
        validateDefinition(outputExpressionDefinition("[1 \"zz at /States/GHOST\"]"))
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("INVALID_JSONATA_EXPRESSION"))
                .body("diagnostics[0].message", containsString("zz at /States/GHOST"))
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
                // AWS locates this message family at the state, with no field suffix.
                .body("diagnostics[0].location", equalTo("/States/X"));
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
                // Measured on AWS: /States/M, the state, not the field.
                .body("diagnostics[0].location", equalTo("/States/M"));
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
    void passWithTimeoutSecondsCatchAndRetry_returnsDiagnosticsInFixedOrder() {
        // Three disallowed fields on the same state, declared here in yet another order, must
        // still come back TimeoutSeconds, Catch, Retry: the old Map.of-backed rule table iterated
        // in a JVM-salted order, so this sequence was only probabilistically correct before.
        String def = """
                {"StartAt":"X","States":{"X":{"Type":"Pass","End":true,
                  "Retry":[{"ErrorEquals":["States.ALL"]}],
                  "TimeoutSeconds":5,
                  "Catch":[{"ErrorEquals":["States.ALL"],"Next":"X"}]}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(3))
                .body("diagnostics[0].message", equalTo("Field 'TimeoutSeconds' is not supported"))
                .body("diagnostics[1].message", equalTo("Field 'Catch' is not supported"))
                .body("diagnostics[2].message", equalTo("Field 'Retry' is not supported"));
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
    void unreachableStateWithSlashInName_returnsFullNameInMessage() {
        // Recovering the state name by taking the text after the last '/' in the location
        // truncates a name that itself contains a slash. The name must survive whole.
        String def = """
                {"StartAt":"Start","States":{
                  "Start":{"Type":"Pass","End":true},
                  "a/b":{"Type":"Pass","End":true}
                }}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("State \"a/b\" is not reachable."))
                .body("diagnostics[0].location", equalTo("/States/a/b"));
    }

    @Test
    void danglingStartAtTopLevel_returnsMissingTransitionTargetAndUnreachableStates() {
        // StartAt is a transition target like any other: a name that resolves to no state is a
        // MISSING_TRANSITION_TARGET at /StartAt, and the walk then starts from nothing, so every
        // other state in the container is reported unreachable too.
        String def = """
                {"StartAt":"Nope","States":{"A":{"Type":"Pass","End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("Missing 'Next' target: Nope"))
                .body("diagnostics[0].location", equalTo("/StartAt"))
                .body("diagnostics[1].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[1].message", equalTo("State \"A\" is not reachable."))
                .body("diagnostics[1].location", equalTo("/States/A"));
    }

    @Test
    void danglingStartAtInsideItemProcessor_returnsFailAtStructuredPaths() {
        String def = """
                {"StartAt":"M","States":{"M":{"Type":"Map","ItemsPath":"$.items",
                  "ItemProcessor":{"StartAt":"Nope","States":{
                    "A":{"Type":"Pass","End":true}
                  }},
                  "End":true}}}
                """;
        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                .body("diagnostics[0].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[0].message", equalTo("Missing 'Next' target: Nope"))
                .body("diagnostics[0].location", equalTo("/States/M/ItemProcessor/StartAt"))
                .body("diagnostics[1].code", equalTo("MISSING_TRANSITION_TARGET"))
                .body("diagnostics[1].message", equalTo("State \"A\" is not reachable."))
                .body("diagnostics[1].location", equalTo("/States/M/ItemProcessor/States/A"));
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

    // ─────────────── QueryLanguage compatibility, measured against real AWS ───────────────
    // Output, Arguments and Items exist only in JSONata. AWS rejects each of them on a JSONPath
    // state with "The QueryLanguage is set to 'JSONPath', but field '<f>' is only supported for
    // the 'JSONata' QueryLanguage", located at the state and not at the field.

    private static final String JSONATA_ONLY_FIELD_ON_A_JSONPATH_STATE =
            "The QueryLanguage is set to 'JSONPath', but field '%s' is only supported "
                    + "for the 'JSONata' QueryLanguage";
    private static final String JSONPATH_ONLY_FIELD_ON_A_JSONATA_STATE =
            "The QueryLanguage is set to 'JSONata', but field '%s' is only supported "
                    + "for the 'JSONPath' QueryLanguage";

    /**
     * A Map's own QueryLanguage is inert for the states inside its ItemProcessor: on AWS the two
     * definitions below fail identically, so the equality of the two diagnostic lists is the
     * assertion. A change that propagated the Map's language into ItemProcessor would separate
     * them.
     */
    @Test
    void mapDeclaringJsonataLeavesItsItemProcessorStateOnTheMachinesJsonPath() {
        List<Map<String, Object>> whenTheMapDeclaresJsonata =
                diagnosticsOf(mapOverAPassCarryingOutput("\"QueryLanguage\":\"JSONata\","));
        List<Map<String, Object>> whenTheMapDeclaresNothing =
                diagnosticsOf(mapOverAPassCarryingOutput(""));

        Assertions.assertEquals(whenTheMapDeclaresNothing, whenTheMapDeclaresJsonata,
                "the Map's QueryLanguage must not reach the states inside its ItemProcessor");
        assertOutputRefusedOnTheItemProcessorPass(whenTheMapDeclaresJsonata, "the Map declares JSONata");
        assertOutputRefusedOnTheItemProcessorPass(whenTheMapDeclaresNothing, "the Map declares nothing");
    }

    /**
     * The literal shape both sides of the pair are held to. Asserted on each list separately, so
     * neither side is pinned only by the other: two runs agreeing on the wrong answer is what the
     * equality above cannot see.
     */
    private static void assertOutputRefusedOnTheItemProcessorPass(
            List<Map<String, Object>> diagnostics, String when) {
        Assertions.assertEquals(1, diagnostics.size(),
                "expected exactly one diagnostic when " + when + ", got " + diagnostics);
        Map<String, Object> diagnostic = diagnostics.get(0);
        Assertions.assertEquals("ERROR", diagnostic.get("severity"), when);
        Assertions.assertEquals("SCHEMA_VALIDATION_FAILED", diagnostic.get("code"), when);
        Assertions.assertEquals(JSONATA_ONLY_FIELD_ON_A_JSONPATH_STATE.formatted("Output"),
                diagnostic.get("message"), when);
        Assertions.assertEquals("/States/M/ItemProcessor/States/P", diagnostic.get("location"), when);
    }

    @Test
    void parallelDeclaringJsonataLeavesItsBranchStateOnTheMachinesJsonPath() {
        String def = """
                {"QueryLanguage":"JSONPath","StartAt":"PAR","States":{
                  "PAR":{"Type":"Parallel","QueryLanguage":"JSONata",
                    "Branches":[{"StartAt":"P","States":{
                      "P":{"Type":"Pass","Output":{"doubled":"{% $states.input.n * 2 %}"},"End":true}}}],
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message",
                        equalTo(JSONATA_ONLY_FIELD_ON_A_JSONPATH_STATE.formatted("Output")))
                .body("diagnostics[0].location", equalTo("/States/PAR/Branches[0]/States/P"));
    }

    @Test
    void jsonPathStateRejectsArguments() {
        String def = """
                {"StartAt":"T","States":{
                  "T":{"Type":"Task","Resource":"arn:aws:states:::lambda:invoke",
                    "Arguments":{"payload":"literal"},"End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].message",
                        equalTo(JSONATA_ONLY_FIELD_ON_A_JSONPATH_STATE.formatted("Arguments")))
                .body("diagnostics[0].location", equalTo("/States/T"));
    }

    @Test
    void jsonPathMapRejectsItems() {
        String def = """
                {"StartAt":"M","States":{
                  "M":{"Type":"Map","Items":[{"n":1}],
                    "ItemProcessor":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].message",
                        equalTo(JSONATA_ONLY_FIELD_ON_A_JSONPATH_STATE.formatted("Items")))
                .body("diagnostics[0].location", equalTo("/States/M"));
    }

    /** AWS accepts Assign on a JSONPath state, so it is not a JSONata-only field. */
    @Test
    void jsonPathStateAcceptsAssign() {
        String def = """
                {"StartAt":"X","States":{
                  "X":{"Type":"Pass","Assign":{"counter":1},"End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    /** The Map's own fields do use the Map's own language. */
    @Test
    void jsonataMapAcceptsOutputOnItself() {
        String def = """
                {"QueryLanguage":"JSONPath","StartAt":"M","States":{
                  "M":{"Type":"Map","QueryLanguage":"JSONata","Items":"{% [1, 2] %}",
                    "Output":{"count":"{% $count($states.result) %}"},
                    "ItemProcessor":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    /** An explicit declaration on the state itself still wins, at any depth. */
    @Test
    void itemProcessorStateDeclaringJsonataAcceptsOutput() {
        String def = """
                {"QueryLanguage":"JSONPath","StartAt":"M","States":{
                  "M":{"Type":"Map","QueryLanguage":"JSONata","Items":"{% [1, 2] %}",
                    "ItemProcessor":{"StartAt":"P","States":{
                      "P":{"Type":"Pass","QueryLanguage":"JSONata",
                        "Output":{"doubled":"{% $states.input * 2 %}"},"End":true}}},
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    // A JSONata state machine cannot be reverted to JSONPath state by state, and the ItemProcessor
    // or branch object is not a state: it carries no QueryLanguage of its own. Both measured.

    @Test
    void stateCannotDeclareJsonPathUnderAJsonataStateMachine() {
        String def = """
                {"QueryLanguage":"JSONata","StartAt":"M","States":{
                  "M":{"Type":"Map","QueryLanguage":"JSONPath",
                    "ItemProcessor":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("'QueryLanguage' can not be 'JSONPath' "
                        + "if set to 'JSONata' for whole state machine"))
                .body("diagnostics[0].location", equalTo("/States/M"));
    }

    /** The guard reads the machine's language, not the mere presence of the field. */
    @Test
    void stateMayDeclareJsonPathUnderAJsonPathStateMachine() {
        String def = """
                {"StartAt":"X","States":{
                  "X":{"Type":"Pass","QueryLanguage":"JSONPath","InputPath":"$.a","End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void stateMayDeclareJsonataUnderAJsonataStateMachine() {
        String def = """
                {"QueryLanguage":"JSONata","StartAt":"X","States":{
                  "X":{"Type":"Pass","QueryLanguage":"JSONata","Output":{"v":"{% 1 %}"},"End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void itemProcessorCannotDeclareQueryLanguage() {
        String def = """
                {"StartAt":"M","States":{
                  "M":{"Type":"Map",
                    "ItemProcessor":{"QueryLanguage":"JSONata","StartAt":"P",
                      "States":{"P":{"Type":"Pass","End":true}}},
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Field 'QueryLanguage' is not supported"))
                .body("diagnostics[0].location", equalTo("/States/M/ItemProcessor"));
    }

    @Test
    void parallelBranchCannotDeclareQueryLanguage() {
        String def = """
                {"StartAt":"PAR","States":{
                  "PAR":{"Type":"Parallel",
                    "Branches":[{"QueryLanguage":"JSONata","StartAt":"P",
                      "States":{"P":{"Type":"Pass","End":true}}}],
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].message", equalTo("Field 'QueryLanguage' is not supported"))
                .body("diagnostics[0].location", equalTo("/States/PAR/Branches[0]"));
    }

    // ─────────── The downgrade suppresses only the field check, and only for that state ───────────

    private static final String QUERY_LANGUAGE_DOWNGRADED =
            "'QueryLanguage' can not be 'JSONPath' if set to 'JSONata' for whole state machine";

    /**
     * Measured on AWS: the downgrade is the whole answer. The {@code Output} that the state's own
     * JSONPath forbids is not reported on top of it, so the mixing guard suppresses the
     * other-language field check for that state.
     */
    @Test
    void downgradedStateReportsTheMixingDiagnosticAndNothingAboutItsOutput() {
        String def = """
                {"QueryLanguage":"JSONata","StartAt":"X","States":{
                  "X":{"Type":"Pass","QueryLanguage":"JSONPath","Output":{"v":"{% 1 %}"},"End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo(QUERY_LANGUAGE_DOWNGRADED))
                .body("diagnostics[0].location", equalTo("/States/X"));
    }

    /**
     * The suppression is scoped to the other-language field check. Measured on AWS: the same
     * downgrade on a Map with a negative MaxConcurrency returns the mixing diagnostic and the range
     * error together. AWS words the range error {@code Minimum value is 0}; this emulator's own
     * wording predates this change and is not what this test is about.
     */
    @Test
    void downgradedMapStillHasItsMaxConcurrencyValidated() {
        String def = """
                {"QueryLanguage":"JSONata","StartAt":"M","States":{
                  "M":{"Type":"Map","QueryLanguage":"JSONPath","MaxConcurrency":-1,
                    "ItemProcessor":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                .body("diagnostics[0].message", equalTo(QUERY_LANGUAGE_DOWNGRADED))
                .body("diagnostics[0].location", equalTo("/States/M"))
                .body("diagnostics[1].message",
                        equalTo("The field 'MaxConcurrency' must be a non-negative integer"))
                .body("diagnostics[1].location", equalTo("/States/M/MaxConcurrency"));
    }

    // ─────────── A QueryLanguage outside the enum, measured against real AWS ───────────
    // AWS does two independent things with it: it reports the value against the enum, at the field
    // and not at the state, and it still resolves the effective language case-insensitively.

    private static final String QUERY_LANGUAGE_OUTSIDE_THE_ENUM =
            "Value should be one of the following: [JSONPath, JSONata]";

    @Test
    void lowercaseJsonataOnAStateResolvesToJsonataAndIsStillReportedAgainstTheEnum() {
        String def = """
                {"StartAt":"X","States":{
                  "X":{"Type":"Pass","QueryLanguage":"jsonata","Output":{"v":"{% 1 %}"},
                    "InputPath":"$.a","End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                // The state resolved to JSONata, so InputPath is the offending field and Output is
                // not mentioned at all.
                .body("diagnostics[0].message", equalTo("The QueryLanguage is set to 'JSONata', "
                        + "but field 'InputPath' is only supported for the 'JSONPath' QueryLanguage"))
                .body("diagnostics[0].location", equalTo("/States/X"))
                .body("diagnostics[1].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[1].message", equalTo(QUERY_LANGUAGE_OUTSIDE_THE_ENUM))
                .body("diagnostics[1].location", equalTo("/States/X/QueryLanguage"));
    }

    @Test
    void lowercaseJsonataUnderAJsonataMachineReportsOnlyTheEnumError() {
        String def = """
                {"QueryLanguage":"JSONata","StartAt":"X","States":{
                  "X":{"Type":"Pass","QueryLanguage":"jsonata","Output":{"v":"{% 1 %}"},"End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].message", equalTo(QUERY_LANGUAGE_OUTSIDE_THE_ENUM))
                .body("diagnostics[0].location", equalTo("/States/X/QueryLanguage"));
    }

    @Test
    void topLevelQueryLanguageOutsideTheEnumIsReportedAtItsOwnField() {
        String def = """
                {"QueryLanguage":"jsonata","StartAt":"X","States":{
                  "X":{"Type":"Pass","Output":{"v":"{% 1 %}"},"End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].message", equalTo(QUERY_LANGUAGE_OUTSIDE_THE_ENUM))
                .body("diagnostics[0].location", equalTo("/QueryLanguage"));
    }

    @Test
    void nonStringTopLevelQueryLanguageIsReportedAsATypeError() {
        String def = """
                {"QueryLanguage":5,"StartAt":"X","States":{"X":{"Type":"Pass","End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].message", equalTo("Expected value of type [STRING]"))
                .body("diagnostics[0].location", equalTo("/QueryLanguage"));
    }

    // ─────────── What a value outside the enum resolves to, measured against real AWS ───────────
    // The effective language is JSONPath only when the field is exactly "JSONPath". Absent, it is
    // inherited; present and anything else, wrong case, unknown string and non-string alike, the
    // state is JSONata. The enum and type diagnostics are independent of that resolution.

    @Test
    void unrecognisedQueryLanguageOnAStateResolvesToJsonataAndRejectsInputPath() {
        String def = """
                {"StartAt":"X","States":{
                  "X":{"Type":"Pass","QueryLanguage":"foo","InputPath":"$.a","End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                .body("diagnostics[0].message",
                        equalTo(JSONPATH_ONLY_FIELD_ON_A_JSONATA_STATE.formatted("InputPath")))
                .body("diagnostics[0].location", equalTo("/States/X"))
                .body("diagnostics[1].message", equalTo(QUERY_LANGUAGE_OUTSIDE_THE_ENUM))
                .body("diagnostics[1].location", equalTo("/States/X/QueryLanguage"));
    }

    @Test
    void unrecognisedQueryLanguageOnAStateAcceptsOutput() {
        String def = """
                {"StartAt":"X","States":{
                  "X":{"Type":"Pass","QueryLanguage":"foo","Output":{"v":"{% 1 %}"},"End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].message", equalTo(QUERY_LANGUAGE_OUTSIDE_THE_ENUM))
                .body("diagnostics[0].location", equalTo("/States/X/QueryLanguage"));
    }

    /**
     * Only the exact string is a downgrade. {@code "jsonpath"} resolves to JSONata, so there is
     * nothing to report but the spelling, and the mixing diagnostic must not appear.
     */
    @Test
    void lowercaseJsonPathUnderAJsonataMachineIsNotADowngrade() {
        String def = """
                {"QueryLanguage":"JSONata","StartAt":"X","States":{
                  "X":{"Type":"Pass","QueryLanguage":"jsonpath","End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].message", equalTo(QUERY_LANGUAGE_OUTSIDE_THE_ENUM))
                .body("diagnostics[0].location", equalTo("/States/X/QueryLanguage"));
    }

    @Test
    void lowercaseTopLevelQueryLanguageResolvesToJsonataAndAcceptsOutput() {
        String def = """
                {"QueryLanguage":"jsonpath","StartAt":"X","States":{
                  "X":{"Type":"Pass","Output":{"v":"{% 1 %}"},"End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].message", equalTo(QUERY_LANGUAGE_OUTSIDE_THE_ENUM))
                .body("diagnostics[0].location", equalTo("/QueryLanguage"));
    }

    @Test
    void lowercaseTopLevelQueryLanguageResolvesToJsonataAndRejectsInputPath() {
        String def = """
                {"QueryLanguage":"jsonpath","StartAt":"X","States":{
                  "X":{"Type":"Pass","InputPath":"$.a","End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                .body("diagnostics[0].message", equalTo(QUERY_LANGUAGE_OUTSIDE_THE_ENUM))
                .body("diagnostics[0].location", equalTo("/QueryLanguage"))
                .body("diagnostics[1].message",
                        equalTo(JSONPATH_ONLY_FIELD_ON_A_JSONATA_STATE.formatted("InputPath")))
                .body("diagnostics[1].location", equalTo("/States/X"));
    }

    @Test
    void nonStringQueryLanguageOnAStateResolvesToJsonataAndRejectsInputPath() {
        String def = """
                {"StartAt":"X","States":{
                  "X":{"Type":"Pass","QueryLanguage":5,"InputPath":"$.a","End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                .body("diagnostics[0].message",
                        equalTo(JSONPATH_ONLY_FIELD_ON_A_JSONATA_STATE.formatted("InputPath")))
                .body("diagnostics[0].location", equalTo("/States/X"))
                .body("diagnostics[1].message", equalTo("Expected value of type [STRING]"))
                .body("diagnostics[1].location", equalTo("/States/X/QueryLanguage"));
    }

    // ─────────── One state, two disallowed fields: the emission order is fixed ───────────
    // The order below is not AWS-observable, it is the one this validator commits to. What matters
    // is that it is the same on every JVM: a salted iteration order makes a maxResults=1 caller
    // receive a different diagnostic on each run.

    @Test
    void twoJsonataOnlyFieldsOnAJsonPathStateEmitInTheOrderTheListDeclares() {
        String def = """
                {"StartAt":"T","States":{
                  "T":{"Type":"Task","Resource":"arn:aws:states:::lambda:invoke",
                    "Output":{"v":1},"Arguments":{"payload":"literal"},"End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(2))
                .body("diagnostics[0].message",
                        equalTo(JSONATA_ONLY_FIELD_ON_A_JSONPATH_STATE.formatted("Output")))
                .body("diagnostics[0].location", equalTo("/States/T"))
                .body("diagnostics[1].message",
                        equalTo(JSONATA_ONLY_FIELD_ON_A_JSONPATH_STATE.formatted("Arguments")))
                .body("diagnostics[1].location", equalTo("/States/T"));
    }

    @Test
    void threeJsonPathOnlyFieldsOnAJsonataStateEmitInTheOrderTheListDeclares() {
        String def = """
                {"QueryLanguage":"JSONata","StartAt":"X","States":{
                  "X":{"Type":"Pass","InputPath":"$.a","OutputPath":"$.b","ResultPath":"$.c",
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(3))
                .body("diagnostics[0].message",
                        equalTo(JSONPATH_ONLY_FIELD_ON_A_JSONATA_STATE.formatted("InputPath")))
                .body("diagnostics[1].message",
                        equalTo(JSONPATH_ONLY_FIELD_ON_A_JSONATA_STATE.formatted("OutputPath")))
                .body("diagnostics[2].message",
                        equalTo(JSONPATH_ONLY_FIELD_ON_A_JSONATA_STATE.formatted("ResultPath")));
    }

    // ─────────── The legacy Iterator spelling, and a bare ItemProcessor Pass ───────────

    /** Measured on AWS: Iterator carries no QueryLanguage either, and the location names Iterator. */
    @Test
    void legacyIteratorCannotDeclareQueryLanguage() {
        String def = """
                {"StartAt":"M","States":{
                  "M":{"Type":"Map",
                    "Iterator":{"QueryLanguage":"JSONata","StartAt":"P",
                      "States":{"P":{"Type":"Pass","End":true}}},
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Field 'QueryLanguage' is not supported"))
                .body("diagnostics[0].location", equalTo("/States/M/Iterator"));
    }

    /** The control: the same Iterator without the field is accepted, as AWS accepts it. */
    @Test
    void legacyIteratorWithoutQueryLanguageIsAccepted() {
        String def = """
                {"StartAt":"M","States":{
                  "M":{"Type":"Map",
                    "Iterator":{"StartAt":"P","States":{"P":{"Type":"Pass","End":true}}},
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    /**
     * A JSONata machine, a Map declaring nothing, an ItemProcessor Pass declaring nothing and
     * carrying Output: the inner state inherits the machine's JSONata, so AWS accepts it. The
     * mirror of {@link #mapDeclaringJsonataLeavesItsItemProcessorStateOnTheMachinesJsonPath} from
     * the other side of the two-level rule.
     */
    @Test
    void jsonataMachineAcceptsOutputOnABareItemProcessorPass() {
        String def = """
                {"QueryLanguage":"JSONata","StartAt":"M","States":{
                  "M":{"Type":"Map",
                    "ItemProcessor":{"StartAt":"P","States":{
                      "P":{"Type":"Pass","Output":{"doubled":"{% $states.input.n * 2 %}"},
                        "End":true}}},
                    "End":true}}}
                """;

        validateDefinition(def)
                .then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    private static String mapOverAPassCarryingOutput(String mapQueryLanguage) {
        return """
                {"QueryLanguage":"JSONPath","StartAt":"M","States":{
                  "M":{"Type":"Map",__MAP_QUERY_LANGUAGE__
                    "ItemProcessor":{"StartAt":"P","States":{
                      "P":{"Type":"Pass","Output":{"doubled":"{% $states.input.n * 2 %}"},"End":true}}},
                    "End":true}}}
                """.replace("__MAP_QUERY_LANGUAGE__", mapQueryLanguage);
    }

    private static List<Map<String, Object>> diagnosticsOf(String definition) {
        return validateDefinition(definition).jsonPath().getList("diagnostics");
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
