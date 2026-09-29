package io.github.hectorvent.floci.services.ssm;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * Parameter Store answers {@code /aws/reference/secretsmanager/<secret-id>} from Secrets Manager.
 * The expected responses and error texts were measured against AWS in eu-west-1 on 2026-09-28.
 */
@QuarkusTest
class SsmSecretsManagerReferenceIntegrationTest {

    private static final String JSON = "application/x-amz-json-1.1";
    private static final String PREFIX = "/aws/reference/secretsmanager/";
    private static final String NEEDS_DECRYPTION =
            "WithDecryption flag must be True for retrieving a Secret Manager secret.";
    private static final String BAD_SELECTOR =
            "Invalid parameter name. Please use correct syntax for referencing a version/label  <name>:<version/label>";

    @Inject
    SsmService ssmService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void resolvesTheCurrentValueOfASecretCreatedThroughSecretsManager() {
        String name = unique("ref-sm") + "/nested/path";
        String arn = createSecret(name, "first-value");
        putSecretValue(name, "second-value");
        float created = given()
            .header("X-Amz-Target", "secretsmanager.GetSecretValue")
            .contentType(JSON)
            .body("{\"SecretId\": \"" + name + "\"}")
        .when().post("/").then().statusCode(200).extract().path("CreatedDate");

        getParameter(PREFIX + name, true)
            .statusCode(200)
            .body("Parameter.Name", equalTo(PREFIX + name))
            .body("Parameter.Type", equalTo("SecureString"))
            .body("Parameter.Value", equalTo("second-value"))
            .body("Parameter.Version", equalTo(0))
            .body("Parameter.ARN", equalTo(arn))
            .body("Parameter.LastModifiedDate", equalTo(created))
            .body("Parameter", not(hasKey("DataType")))
            .body("Parameter", not(hasKey("Selector")))
            .body("Parameter.SourceResult", startsWith("{\"ARN\":\"" + arn + "\",\"name\":\"" + name + "\",\"versionId\":\""))
            .body("Parameter.SourceResult", containsString(
                    "\"secretString\":\"second-value\",\"versionStages\":[\"AWSCURRENT\"],\"createdDate\":\""));
    }

    @Test
    void ecsAndCodeBuildReadAReferenceWithDecryption() {
        String name = unique("ref-internal");
        createSecret(name, "internal-value");

        assertEquals("internal-value", ssmService.getParameter(PREFIX + name, "us-east-1").getValue());
    }

    @Test
    void refusesAReferenceWithoutDecryptionBeforeLookingItUp() {
        String name = unique("ref-plain");
        createSecret(name, "value");

        getParameter(PREFIX + name, false).statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo(NEEDS_DECRYPTION));
        given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(JSON)
            .body("{\"Name\": \"" + PREFIX + name + "\"}")
        .when().post("/").then().statusCode(400)
            .body("message", equalTo(NEEDS_DECRYPTION));
        getParameter(PREFIX + unique("ref-absent"), false).statusCode(400)
            .body("message", equalTo(NEEDS_DECRYPTION));
    }

    @Test
    void missingSecretIsParameterNotFoundWithTheTextAwsSends() {
        String name = unique("ref-missing");

        getParameter(PREFIX + name, true).statusCode(400)
            .body("__type", equalTo("ParameterNotFound"))
            .body("message", equalTo("An error occurred (ParameterNotFound) when referencing Secrets Manager: "
                    + "Secret aws/reference/secretsmanager/" + name + "null not found."));
    }

    @Test
    void selectsAVersionByStagingLabelOrVersionId() {
        String name = unique("ref-select");
        createSecret(name, "first-value");
        String firstVersion = given()
            .header("X-Amz-Target", "secretsmanager.DescribeSecret")
            .contentType(JSON)
            .body("{\"SecretId\": \"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getMap("VersionIdsToStages").keySet().iterator().next().toString();
        putSecretValue(name, "second-value");

        getParameter(PREFIX + name + ":AWSPREVIOUS", true).statusCode(200)
            .body("Parameter.Name", equalTo(PREFIX + name))
            .body("Parameter.Selector", equalTo(":AWSPREVIOUS"))
            .body("Parameter.Value", equalTo("first-value"))
            .body("Parameter.SourceResult", containsString("\"versionStages\":[\"AWSPREVIOUS\"]"));
        getParameter(PREFIX + name + ":" + firstVersion, true).statusCode(200)
            .body("Parameter.Selector", equalTo(":" + firstVersion))
            .body("Parameter.Value", equalTo("first-value"));
        getParameter(PREFIX + name + ":AWSCURRENT", true).statusCode(200)
            .body("Parameter.Value", equalTo("second-value"));
        getParameter(PREFIX + name + ":NOSUCHSTAGE", true).statusCode(400)
            .body("__type", equalTo("ParameterNotFound"))
            .body("message", equalTo("An error occurred (ParameterNotFound) when referencing Secrets Manager: "
                    + "Secret aws/reference/secretsmanager/" + name + ":NOSUCHSTAGE not found."));
    }

    @Test
    void malformedSelectorIsRefusedBeforeTheDecryptionCheck() {
        String name = unique("ref-malformed");
        createSecret(name, "value");

        for (String reference : new String[] {name + ":AWSCURRENT:extra", name + ":",
                "arn:aws:secretsmanager:us-east-1:000000000000:secret:" + name}) {
            getParameter(PREFIX + reference, false).statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo(BAD_SELECTOR));
        }
    }

    @Test
    void secretScheduledForDeletionIsAnInvalidRequest() {
        String name = unique("ref-deleted");
        createSecret(name, "value");
        given()
            .header("X-Amz-Target", "secretsmanager.DeleteSecret")
            .contentType(JSON)
            .body("{\"SecretId\": \"" + name + "\", \"RecoveryWindowInDays\": 7}")
        .when().post("/").then().statusCode(200);

        getParameter(PREFIX + name, true).statusCode(400)
            .body("__type", equalTo("ValidationException"))
            .body("message", equalTo("Invalid Request to Secrets Manager"));
    }

    @Test
    void binarySecretHasNoValue() {
        String name = unique("ref-binary");
        given()
            .header("X-Amz-Target", "secretsmanager.CreateSecret")
            .contentType(JSON)
            .body("{\"Name\": \"" + name + "\", \"SecretBinary\": \"YWJj\"}")
        .when().post("/").then().statusCode(200);

        getParameter(PREFIX + name, true).statusCode(200)
            .body("Parameter.Type", equalTo("SecureString"))
            .body("Parameter", not(hasKey("Value")));
    }

    @Test
    void getParametersListsEveryReferenceItCannotAnswerAsInvalid() {
        String name = unique("ref-batch");
        String plain = "/" + unique("ref-batch-plain");
        createSecret(name, "first-value");
        putSecretValue(name, "second-value");
        given()
            .header("X-Amz-Target", "AmazonSSM.PutParameter")
            .contentType(JSON)
            .body("{\"Name\": \"" + plain + "\", \"Value\": \"plain\", \"Type\": \"String\"}")
        .when().post("/").then().statusCode(200);
        String missing = PREFIX + unique("ref-batch-missing");
        String unknownStage = PREFIX + name + ":NOSUCH";
        String malformed = PREFIX + name + ":a:b";

        getParameters(true, PREFIX + name, PREFIX + name + ":AWSPREVIOUS", missing, unknownStage, malformed, plain)
            .statusCode(200)
            .body("Parameters.Value", containsInAnyOrder("second-value", "first-value", "plain"))
            .body("InvalidParameters", containsInAnyOrder(missing, unknownStage, malformed));

        getParameters(false, PREFIX + name, plain)
            .statusCode(200)
            .body("Parameters.Value", containsInAnyOrder("plain"))
            .body("InvalidParameters", containsInAnyOrder(PREFIX + name));
    }

    private static ValidatableResponse getParameter(String name, boolean withDecryption) {
        return given()
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .contentType(JSON)
            .body("{\"Name\": \"" + name + "\", \"WithDecryption\": " + withDecryption + "}")
        .when().post("/").then();
    }

    private static ValidatableResponse getParameters(boolean withDecryption, String... names) {
        StringBuilder list = new StringBuilder();
        for (String name : names) {
            list.append(list.isEmpty() ? "" : ",").append('"').append(name).append('"');
        }
        return given()
            .header("X-Amz-Target", "AmazonSSM.GetParameters")
            .contentType(JSON)
            .body("{\"Names\": [" + list + "], \"WithDecryption\": " + withDecryption + "}")
        .when().post("/").then();
    }

    private static String createSecret(String name, String value) {
        return given()
            .header("X-Amz-Target", "secretsmanager.CreateSecret")
            .contentType(JSON)
            .body("{\"Name\": \"" + name + "\", \"SecretString\": \"" + value + "\"}")
        .when().post("/").then().statusCode(200).extract().path("ARN");
    }

    private static void putSecretValue(String name, String value) {
        given()
            .header("X-Amz-Target", "secretsmanager.PutSecretValue")
            .contentType(JSON)
            .body("{\"SecretId\": \"" + name + "\", \"SecretString\": \"" + value + "\"}")
        .when().post("/").then().statusCode(200);
    }

    private static String unique(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }
}
