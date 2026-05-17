package io.github.hectorvent.floci.services.stepfunctions;

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

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
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
