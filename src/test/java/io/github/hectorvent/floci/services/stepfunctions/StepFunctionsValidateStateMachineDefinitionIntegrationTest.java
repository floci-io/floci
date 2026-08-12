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
}
