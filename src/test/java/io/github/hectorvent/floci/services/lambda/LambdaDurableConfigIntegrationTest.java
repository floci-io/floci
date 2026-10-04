package io.github.hectorvent.floci.services.lambda;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;

/**
 * DurableConfig marks a function as a durable function. It is accepted by CreateFunction and
 * UpdateFunctionConfiguration, snapshotted by PublishVersion, and returned by every read, while a
 * plain function never carries the member.
 */
@QuarkusTest
class LambdaDurableConfigIntegrationTest {

    private static final String BASE_PATH = "/2015-03-31";
    private static final String KMS_KEY_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/11111111-1111-1111-1111-111111111111";
    private static final String TEXT_LOGS_MESSAGE = "You cannot use plain text logs with a durable function. "
            + "Only JSON format logs are supported";

    /** Posts CreateFunction for {@code name}; {@code members} are extra JSON members, already comma separated. */
    private static ValidatableResponse createFunction(String name, String members) {
        String body = """
            {
                "FunctionName": "%s",
                "Runtime": "nodejs20.x",
                "Role": "arn:aws:iam::000000000000:role/lambda-role",
                "Handler": "index.handler"%s
            }
            """.formatted(name, members.isEmpty() ? "" : ",\n" + members);
        return given()
            .contentType("application/json")
            .body(body)
        .when()
            .post(BASE_PATH + "/functions")
        .then();
    }

    private static ValidatableResponse createDurableFunction(String name, String durableConfig) {
        return createFunction(name, "\"DurableConfig\": " + durableConfig);
    }

    private static ValidatableResponse updateConfiguration(String name, String body) {
        return given()
            .contentType("application/json")
            .body(body)
        .when()
            .put(BASE_PATH + "/functions/" + name + "/configuration")
        .then();
    }

    private static ValidatableResponse getConfiguration(String name) {
        return getConfiguration(name, "");
    }

    private static ValidatableResponse getConfiguration(String name, String query) {
        return given()
        .when()
            .get(BASE_PATH + "/functions/" + name + "/configuration" + query)
        .then();
    }

    @Test
    void createDefaultsRetentionTimeoutAndJsonLogging() {
        createDurableFunction("durable-defaults-fn", "{\"ExecutionTimeout\": 3600}")
            .statusCode(201)
            .body("DurableConfig.ExecutionTimeout", equalTo(3600))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(14))
            .body("DurableConfig", not(hasKey("KMSKeyArn")))
            .body("Timeout", equalTo(900))
            .body("LoggingConfig.LogFormat", equalTo("JSON"))
            .body("LoggingConfig.ApplicationLogLevel", equalTo("INFO"))
            .body("LoggingConfig.SystemLogLevel", equalTo("INFO"));

        createDurableFunction("durable-short-timeout-fn", "{\"ExecutionTimeout\": 60}")
            .statusCode(201)
            .body("Timeout", equalTo(60));
    }

    @Test
    void createKeepsExplicitMembers() {
        createFunction("durable-explicit-fn", """
                "Timeout": 3,
                "DurableConfig": {
                    "ExecutionTimeout": 60,
                    "RetentionPeriodInDays": 7,
                    "KMSKeyArn": "%s"
                }""".formatted(KMS_KEY_ARN))
            .statusCode(201);

        getConfiguration("durable-explicit-fn")
            .statusCode(200)
            .body("Timeout", equalTo(3))
            .body("DurableConfig.ExecutionTimeout", equalTo(60))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(7))
            .body("DurableConfig.KMSKeyArn", equalTo(KMS_KEY_ARN));
    }

    @Test
    void emptyKmsKeyArnMeansNoKey() {
        createDurableFunction("durable-no-key-fn", "{\"ExecutionTimeout\": 60, \"KMSKeyArn\": \"\"}")
            .statusCode(201)
            .body("DurableConfig", not(hasKey("KMSKeyArn")));
    }

    @Test
    void durableFunctionLogsInJsonFormatOnly() {
        createFunction("durable-text-logs-fn", """
                "LoggingConfig": {"LogFormat": "Text"},
                "DurableConfig": {"ExecutionTimeout": 60}""")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo(TEXT_LOGS_MESSAGE));

        createFunction("durable-partial-logs-fn", """
                "LoggingConfig": {"LogGroup": "/custom/durable"},
                "DurableConfig": {"ExecutionTimeout": 60}""")
            .statusCode(201)
            .body("LoggingConfig.LogFormat", equalTo("JSON"))
            .body("LoggingConfig.LogGroup", equalTo("/custom/durable"));

        createFunction("durable-json-levels-fn", """
                "LoggingConfig": {"LogFormat": "JSON", "ApplicationLogLevel": "WARN"},
                "DurableConfig": {"ExecutionTimeout": 60}""")
            .statusCode(201)
            .body("LoggingConfig.ApplicationLogLevel", equalTo("WARN"))
            .body("LoggingConfig.SystemLogLevel", equalTo("INFO"));

        updateConfiguration("durable-partial-logs-fn", "{\"LoggingConfig\": {\"LogFormat\": \"Text\"}}")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo(TEXT_LOGS_MESSAGE));

        updateConfiguration("durable-partial-logs-fn", "{\"LoggingConfig\": {\"LogGroup\": \"/custom/updated\"}}")
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("JSON"))
            .body("LoggingConfig.LogGroup", equalTo("/custom/updated"));
    }

    @Test
    void plainFunctionHasNoDurableConfig() {
        createFunction("plain-no-durable-fn", "")
            .statusCode(201)
            .body("$", not(hasKey("DurableConfig")))
            .body("Timeout", equalTo(3))
            .body("LoggingConfig.LogFormat", equalTo("Text"));

        getConfiguration("plain-no-durable-fn")
            .statusCode(200)
            .body("$", not(hasKey("DurableConfig")));
    }

    @Test
    void createWithoutExecutionTimeoutIsRejected() {
        createDurableFunction("durable-invalid-fn", "{\"RetentionPeriodInDays\": 7}")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("You cannot create a function with a durable configuration "
                    + "without an executionTimeout"));
    }

    @Test
    void membersOutOfRangeAreValidationErrors() {
        createDurableFunction("durable-invalid-fn", "{\"ExecutionTimeout\": 0}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value '0' at "
                    + "'durableConfig.executionTimeout' failed to satisfy constraint: "
                    + "Member must have value greater than or equal to 1"));

        createDurableFunction("durable-invalid-fn", "{\"ExecutionTimeout\": 60, \"RetentionPeriodInDays\": 91}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value '91' at "
                    + "'durableConfig.retentionPeriodInDays' failed to satisfy constraint: "
                    + "Member must have value less than or equal to 90"));
    }

    @Test
    void nonIntegerMembersAreSerializationErrors() {
        createDurableFunction("durable-invalid-fn", "{\"ExecutionTimeout\": \"60\"}")
            .statusCode(400)
            .body("__type", equalTo("SerializationException"))
            .body("message", equalTo("DurableConfig.ExecutionTimeout must be an integer"));
    }

    @Test
    void kmsKeyArnMustMatchThePattern() {
        createDurableFunction("durable-invalid-fn", "{\"ExecutionTimeout\": 60, \"KMSKeyArn\": \"not-an-arn\"}")
            .statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("1 validation error detected: Value 'not-an-arn' at "
                    + "'durableConfig.kMSKeyArn' failed to satisfy constraint: Member must satisfy regular "
                    + "expression pattern: (arn:(aws[a-zA-Z-]*)?:[a-z0-9-.]+:.*)|()"));
    }

    @Test
    void updateMergesMembersAndAPublishedVersionKeepsItsSnapshot() {
        createDurableFunction("durable-update-fn", "{\"ExecutionTimeout\": 3600, \"RetentionPeriodInDays\": 7}")
            .statusCode(201);

        given()
            .contentType("application/json")
            .body("{}")
        .when()
            .post(BASE_PATH + "/functions/durable-update-fn/versions")
        .then()
            .statusCode(201)
            .body("Version", equalTo("1"))
            .body("DurableConfig.ExecutionTimeout", equalTo(3600))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(7));

        updateDurableConfig("durable-update-fn", "{\"ExecutionTimeout\": 7200}")
            .statusCode(200)
            .body("DurableConfig.ExecutionTimeout", equalTo(7200))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(7));

        updateDurableConfig("durable-update-fn", "{\"RetentionPeriodInDays\": 3, \"KMSKeyArn\": \"" + KMS_KEY_ARN + "\"}")
            .statusCode(200)
            .body("DurableConfig.ExecutionTimeout", equalTo(7200))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(3))
            .body("DurableConfig.KMSKeyArn", equalTo(KMS_KEY_ARN));

        updateDurableConfig("durable-update-fn", "{}")
            .statusCode(200)
            .body("DurableConfig.ExecutionTimeout", equalTo(7200))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(3));

        getConfiguration("durable-update-fn", "?Qualifier=1")
            .statusCode(200)
            .body("DurableConfig.ExecutionTimeout", equalTo(3600))
            .body("DurableConfig.RetentionPeriodInDays", equalTo(7))
            .body("DurableConfig", not(hasKey("KMSKeyArn")));
    }

    @Test
    void updateCannotAddDurableConfigToAPlainFunction() {
        createFunction("plain-stays-plain-fn", "").statusCode(201);

        updateDurableConfig("plain-stays-plain-fn", "{\"ExecutionTimeout\": 60}")
            .statusCode(400)
            .body("__type", equalTo("InvalidParameterValueException"))
            .body("message", equalTo("You cannot add a durable configuration to a function that was "
                    + "originally created with no durable configuration"));

        getConfiguration("plain-stays-plain-fn")
            .statusCode(200)
            .body("$", not(hasKey("DurableConfig")));
    }

    private static ValidatableResponse updateDurableConfig(String name, String durableConfig) {
        return updateConfiguration(name, "{\"DurableConfig\": " + durableConfig + "}");
    }
}
