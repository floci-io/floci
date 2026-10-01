package io.github.hectorvent.floci.services.iam;

import io.github.hectorvent.floci.testing.IamEnforcementProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code iam.enforcement-enabled=true}, SES v2 calls are authorized against their {@code ses:}
 * action like any other request, instead of resolving to no action and passing unchecked.
 */
@QuarkusTest
@TestProfile(IamEnforcementProfile.class)
class SesV2IamEnforcementIntegrationTest {

    private static final String ACCOUNT = "111111111111";

    @Test
    void callerWithoutSesPermissionsIsRefusedEveryV2Operation() {
        String configurationSet = createConfigurationSet();
        String accessKeyId = userWithPolicy(null);

        assertAccessDenied(getAccount(accessKeyId));
        assertAccessDenied(createEventDestination(accessKeyId, configurationSet));
    }

    @Test
    void anAllowedActionGrantsThatOperationOnly() {
        String configurationSet = createConfigurationSet();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Action":"ses:GetAccount","Resource":"*"}]}""");

        getAccount(accessKeyId).then().statusCode(200);
        assertAccessDenied(createEventDestination(accessKeyId, configurationSet));
    }

    @Test
    void theEventDestinationActionAllowsCreatingOne() {
        String configurationSet = createConfigurationSet();
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow",
                  "Action":"ses:CreateConfigurationSetEventDestination","Resource":"*"}]}""");

        createEventDestination(accessKeyId, configurationSet).then().statusCode(200);
        assertAccessDenied(getAccount(accessKeyId));
    }

    @Test
    void aTargetHeaderDoesNotChangeTheActionARestCallIsCheckedAs() {
        String accessKeyId = userWithPolicy("""
                {"Version":"2012-10-17","Statement":[
                  {"Effect":"Allow","Action":"*","Resource":"*"},
                  {"Effect":"Deny","Action":"ses:SendEmail","Resource":"*"}]}""");

        assertAccessDenied(given()
            .contentType("application/json")
            .header("Authorization", auth(accessKeyId, "ses"))
            .header("X-Amz-Target", "SES.GetAccount")
            .body("""
                {"FromEmailAddress":"sender@example.com","Destination":{"ToAddresses":["to@example.com"]},
                  "Content":{"Simple":{"Subject":{"Data":"s"},"Body":{"Text":{"Data":"b"}}}}}""")
        .when().post("/v2/email/outbound-emails"));
    }

    @Test
    void anEncodedSlashInAPathParameterIsStillAuthorized() {
        String accessKeyId = userWithPolicy(null);

        assertAccessDenied(given()
            .urlEncodingEnabled(false)
            .header("Authorization", auth(accessKeyId, "ses"))
        .when().get("/v2/email/suppression/addresses/a%2Fb@example.com"));
        assertAccessDenied(given()
            .urlEncodingEnabled(false)
            .header("Authorization", auth(accessKeyId, "ses"))
        .when().delete("/v2/email/suppression/addresses/a%2Fb@example.com"));
    }

    /** SES v2 API Reference, common errors: {@code AccessDeniedException}, HTTP 403. */
    private static void assertAccessDenied(Response response) {
        response.then().statusCode(403).body("__type", equalTo("AccessDeniedException"));
    }

    private static Response getAccount(String accessKeyId) {
        return given()
            .header("Authorization", auth(accessKeyId, "ses"))
        .when().get("/v2/email/account");
    }

    private static Response createEventDestination(String accessKeyId, String configurationSet) {
        return given()
            .contentType("application/json")
            .header("Authorization", auth(accessKeyId, "ses"))
            .body("""
                {"EventDestinationName":"dest","EventDestination":{"Enabled":true,
                  "MatchingEventTypes":["SEND"],
                  "CloudWatchDestination":{"DimensionConfigurations":[{"DimensionName":"d",
                    "DimensionValueSource":"MESSAGE_TAG","DefaultDimensionValue":"v"}]}}}""")
        .when().post("/v2/email/configuration-sets/" + configurationSet + "/event-destinations");
    }

    private static String createConfigurationSet() {
        String name = "sesv2-iam-" + UUID.randomUUID().toString().substring(0, 8);
        given()
            .contentType("application/json")
            .header("Authorization", auth(ACCOUNT, "ses"))
            .body("{\"ConfigurationSetName\":\"" + name + "\"}")
        .when().post("/v2/email/configuration-sets")
        .then().statusCode(200);
        return name;
    }

    /** An IAM user in {@link #ACCOUNT} with {@code policy} inline, or no policy when it is null. */
    private static String userWithPolicy(String policy) {
        String user = "sesv2-iam-" + UUID.randomUUID().toString().substring(0, 8);
        given().formParam("Action", "CreateUser").formParam("UserName", user)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        if (policy != null) {
            given().formParam("Action", "PutUserPolicy").formParam("UserName", user).formParam("PolicyName", "p")
                    .formParam("PolicyDocument", policy)
                    .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200);
        }
        return given().formParam("Action", "CreateAccessKey").formParam("UserName", user)
                .header("Authorization", auth(ACCOUNT, "iam")).when().post("/").then().statusCode(200)
                .extract().path("CreateAccessKeyResponse.CreateAccessKeyResult.AccessKey.AccessKeyId");
    }

    private static String auth(String accessKeyId, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/20260930/us-east-1/" + service
                + "/aws4_request, SignedHeaders=host, Signature=abc";
    }
}
