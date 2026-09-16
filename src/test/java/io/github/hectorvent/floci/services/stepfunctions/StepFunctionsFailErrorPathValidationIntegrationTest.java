package io.github.hectorvent.floci.services.stepfunctions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/**
 * A Fail state must not declare both {@code Error} and {@code ErrorPath}, nor both {@code Cause}
 * and {@code CausePath}: real AWS refuses such a definition at CreateStateMachine time. Fixes
 * issue #3255.
 */
@QuarkusTest
class StepFunctionsFailErrorPathValidationIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String VALIDATE_TARGET = "AWSStepFunctions.ValidateStateMachineDefinition";
    private static final String CREATE_TARGET = "AWSStepFunctions.CreateStateMachine";
    private static final String ROLE_ARN = "arn:aws:iam::000000000000:role/service-role/sfn";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response validate(String definition) {
        return given().contentType(CONTENT_TYPE).header("X-Amz-Target", VALIDATE_TARGET)
                .body(OBJECT_MAPPER.createObjectNode().put("definition", definition).toString())
                .when().post("/");
    }

    private static Response create(String name, String definition) {
        return given().contentType(CONTENT_TYPE).header("X-Amz-Target", CREATE_TARGET)
                .body(OBJECT_MAPPER.createObjectNode()
                        .put("name", name)
                        .put("definition", definition)
                        .put("roleArn", ROLE_ARN).toString())
                .when().post("/");
    }

    @Test
    void validateRejectsErrorAndErrorPathTogether() {
        String definition = """
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","Error":"OrderFailed","ErrorPath":"$.e"}}}
                """;

        validate(definition).then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo(
                        "A Fail state cannot include both field 'Error' and 'ErrorPath'"));
    }

    @Test
    void validateRejectsCauseAndCausePathTogether() {
        String definition = """
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","Cause":"boom","CausePath":"$.c"}}}
                """;

        validate(definition).then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo(
                        "A Fail state cannot include both field 'Cause' and 'CausePath'"));
    }

    @Test
    void validateAcceptsErrorPathAndCausePathAlone() {
        String definition = """
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","ErrorPath":"$.e","CausePath":"$.c"}}}
                """;

        validate(definition).then().statusCode(200)
                .body("result", equalTo("OK"))
                .body("diagnostics", hasSize(0));
    }

    @Test
    void createStateMachineRefusesErrorAndErrorPathTogether() {
        String definition = """
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","Error":"OrderFailed","ErrorPath":"$.e"}}}
                """;

        create("fail-error-and-errorpath-" + System.nanoTime(), definition)
                .then().statusCode(400)
                .body("__type", equalTo("InvalidDefinition"));
    }

    @Test
    void createStateMachineRefusesCauseAndCausePathTogether() {
        String definition = """
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","Cause":"boom","CausePath":"$.c"}}}
                """;

        create("fail-cause-and-causepath-" + System.nanoTime(), definition)
                .then().statusCode(400)
                .body("__type", equalTo("InvalidDefinition"));
    }

    @Test
    void validateRejectsNonStringErrorPath() {
        String definition = """
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","ErrorPath":123}}}
                """;

        validate(definition).then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Expected value of type [STRING]"))
                .body("diagnostics[0].location", equalTo("/States/Boom/ErrorPath"));
    }

    @Test
    void validateRejectsNonStringCausePath() {
        String definition = """
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","CausePath":{}}}}
                """;

        validate(definition).then().statusCode(200)
                .body("result", equalTo("FAIL"))
                .body("diagnostics", hasSize(1))
                .body("diagnostics[0].severity", equalTo("ERROR"))
                .body("diagnostics[0].code", equalTo("SCHEMA_VALIDATION_FAILED"))
                .body("diagnostics[0].message", equalTo("Expected value of type [STRING]"))
                .body("diagnostics[0].location", equalTo("/States/Boom/CausePath"));
    }

    @Test
    void createStateMachineRefusesNonStringErrorPath() {
        String definition = """
                {"StartAt":"Boom","States":{
                  "Boom":{"Type":"Fail","ErrorPath":123}}}
                """;

        create("fail-non-string-errorpath-" + System.nanoTime(), definition)
                .then().statusCode(400)
                .body("__type", equalTo("InvalidDefinition"));
    }
}
