package io.floci.aws.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

@QuarkusTest
class CognitoCorsIntegrationTest {

    private static final String ORIGIN = "http://localhost:3000";
    private static final String TARGET_PREFIX = "AWSCognitoIdentityProviderService.";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"content-type,x-amz-target,x-amz-user-agent",
            "Content-Type, X-Amz-Target, X-Amz-User-Agent, authorization, x-amz-security-token"})
    void browserPreflightAllowsCognitoHeaders(String requestedHeaders) {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", requestedHeaders)
                .when().options("/")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .header("Access-Control-Allow-Methods", equalTo("POST"))
                .header("Access-Control-Allow-Headers", equalTo(requestedHeaders))
                .header("Access-Control-Expose-Headers", containsString("x-amzn-RequestId"))
                .header("Access-Control-Max-Age", equalTo("172800"))
                .header("Access-Control-Allow-Credentials", nullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {ORIGIN, "https://browser.example.test", "null"})
    void browserCanReadCognitoAuthenticationErrors(String origin) {
        given()
                .header("Origin", origin)
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSCognitoIdentityProviderService.InitiateAuth")
                .body("""
                        {"AuthFlow":"USER_PASSWORD_AUTH","ClientId":"missing-client",
                         "AuthParameters":{"USERNAME":"alice","PASSWORD":"wrong"}}
                        """)
                .when().post("/")
                .then()
                .statusCode(400)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .header("Access-Control-Expose-Headers", containsString("x-amzn-RequestId"))
                .header("Access-Control-Expose-Headers", containsString("x-amzn-ErrorType"))
                .header("Access-Control-Allow-Credentials", nullValue())
                .body("__type", equalTo("ResourceNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null"})
    void browserCanReadSerializationErrors(String body) {
        given()
                .header("Origin", ORIGIN)
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", TARGET_PREFIX + "InitiateAuth")
                .body(body)
                .when().post("/")
                .then()
                .statusCode(400)
                .header("Access-Control-Allow-Origin", equalTo("*"))
                .body("__type", equalTo("SerializationException"));
    }

    @Test
    void browserCanCompleteNewPasswordChallenge() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"browser-cors-%s"}
                """.formatted(UUID.randomUUID())).path("UserPool").path("Id").asText();
        try {
            String clientId = cognitoJson("CreateUserPoolClient", """
                    {"UserPoolId":"%s","ClientName":"browser",
                     "ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH"]}
                    """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
            cognitoAction("AdminCreateUser", """
                    {"UserPoolId":"%s","Username":"browser-user",
                     "TemporaryPassword":"Temporary123!","MessageAction":"SUPPRESS"}
                    """.formatted(poolId)).then().statusCode(200);

            Response response = given()
                    .header("Origin", ORIGIN)
                    .contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", TARGET_PREFIX + "InitiateAuth")
                    .header("X-Amz-User-Agent", "aws-sdk-js/3 browser")
                    .body("""
                            {"AuthFlow":"USER_PASSWORD_AUTH","ClientId":"%s",
                             "AuthParameters":{"USERNAME":"browser-user","PASSWORD":"Temporary123!"}}
                            """.formatted(clientId))
                    .when().post("/")
                    .then()
                    .statusCode(200)
                    .header("Access-Control-Allow-Origin", equalTo("*"))
                    .body("ChallengeName", equalTo("NEW_PASSWORD_REQUIRED"))
                    .extract().response();
            JsonNode challenge = OBJECT_MAPPER.readTree(response.asByteArray());

            given()
                    .header("Origin", ORIGIN)
                    .contentType("application/x-amz-json-1.1")
                    .header("X-Amz-Target", TARGET_PREFIX + "RespondToAuthChallenge")
                    .body("""
                            {"ClientId":"%s","ChallengeName":"NEW_PASSWORD_REQUIRED","Session":"%s",
                             "ChallengeResponses":{"USERNAME":"browser-user","NEW_PASSWORD":"Permanent123!"}}
                            """.formatted(clientId, challenge.path("Session").asText()))
                    .when().post("/")
                    .then()
                    .statusCode(200)
                    .header("Access-Control-Allow-Origin", equalTo("*"))
                    .body("AuthenticationResult.AccessToken", notNullValue())
                    .body("AuthenticationResult.IdToken", notNullValue());
        } finally {
            cognitoAction("DeleteUserPool", """
                    {"UserPoolId":"%s"}
                    """.formatted(poolId)).then().statusCode(200);
        }
    }

    @Test
    void requestsWithoutOriginDoNotGetBrowserHeaders() {
        cognitoAction("InitiateAuth", "{}")
                .then()
                .statusCode(400)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void sharedPreflightDoesNotExposeOtherJsonServices() {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-amz-target")
                .when().options("/")
                .then().statusCode(200);

        given()
                .header("Origin", ORIGIN)
                .header("X-Amz-Target", "DynamoDB_20120810.ListTables")
                .contentType("application/x-amz-json-1.0")
                .body("{}")
                .when().post("/")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @ParameterizedTest
    @CsvSource({"/,PUT,content-type;x-amz-target", "/,POST,content-type",
            "/bucket/key,POST,content-type;x-amz-target", "/execute-api/missing/prod,POST,content-type;x-amz-target"})
    void unrelatedPreflightsDoNotGetCognitoCors(String path, String method, String headers) {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", method)
                .header("Access-Control-Request-Headers", headers.replace(';', ','))
                .when().options(path)
                .then()
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void aCognitoTargetDoesNotExposeAQueryResponse() {
        given()
                .header("Origin", ORIGIN)
                .header("X-Amz-Target", TARGET_PREFIX + "InitiateAuth")
                .contentType("application/x-www-form-urlencoded")
                .body("Action=GetCallerIdentity&Version=2011-06-15")
                .when().post("/")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Origin", nullValue());
    }

    @Test
    void preflightDoesNotGrantPrivateNetworkAccessByDefault() {
        given()
                .header("Origin", ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type,x-amz-target")
                .header("Access-Control-Request-Private-Network", "true")
                .when().options("/")
                .then()
                .statusCode(200)
                .header("Access-Control-Allow-Private-Network", nullValue());
    }
}
