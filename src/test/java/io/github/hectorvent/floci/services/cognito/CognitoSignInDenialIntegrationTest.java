package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The error a sign-in denial puts on the wire, which the AWS SDKs map to the exception class
 * named by {@code __type}: a disabled user is {@code NotAuthorizedException}, and a
 * PreAuthentication function that raised is {@code UserLambdaValidationException}, as on AWS.
 */
@QuarkusTest
class CognitoSignInDenialIntegrationTest {

    private static final String PASSWORD = "Perm1234!";
    private static final String PRE_AUTHENTICATION_ARN = "arn:aws:lambda:::pre-authentication";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void disabledUserIsNotAuthorized(boolean adminAuth) throws Exception {
        Pool pool = newPool(Map.of());
        cognitoAction("AdminDisableUser", """
                {"UserPoolId":"%s","Username":"%s"}
                """.formatted(pool.poolId(), pool.username())).then().statusCode(200);

        signIn(pool, adminAuth)
                .then()
                .statusCode(400)
                .body("__type", equalTo("NotAuthorizedException"))
                .body("message", equalTo("User is disabled."));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preAuthenticationFunctionErrorIsAUserLambdaValidationException(boolean adminAuth) throws Exception {
        Pool pool = newPool(Map.of("PreAuthentication", PRE_AUTHENTICATION_ARN));
        when(lambdaService.invoke(anyString(), eq(PRE_AUTHENTICATION_ARN), any(), any()))
                .thenReturn(new InvokeResult(200, "Unhandled", MAPPER.writeValueAsBytes(Map.of(
                        "errorMessage", "Org requires SSO login through IdP",
                        "errorType", "ForbiddenError")), null, "req-id"));

        signIn(pool, adminAuth)
                .then()
                .statusCode(400)
                .body("__type", equalTo("UserLambdaValidationException"))
                .body("message", equalTo(
                        "PreAuthentication failed with error Org requires SSO login through IdP."));
    }

    private record Pool(String poolId, String clientId, String username) {
    }

    private static Pool newPool(Map<String, String> lambdaConfig) throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"SignInDenialPool","LambdaConfig":%s}
                """.formatted(MAPPER.writeValueAsString(lambdaConfig))).path("UserPool").path("Id").asText();
        String clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"app",
                 "ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH","ALLOW_ADMIN_USER_PASSWORD_AUTH","ALLOW_REFRESH_TOKEN_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        String username = "user-" + System.nanoTime();
        cognitoAction("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","UserAttributes":[{"Name":"email","Value":"%s@example.com"}]}
                """.formatted(poolId, username, username)).then().statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, username, PASSWORD)).then().statusCode(200);
        return new Pool(poolId, clientId, username);
    }

    private static Response signIn(Pool pool, boolean adminAuth) {
        if (adminAuth) {
            return cognitoAction("AdminInitiateAuth", """
                    {"UserPoolId":"%s","ClientId":"%s","AuthFlow":"ADMIN_USER_PASSWORD_AUTH",
                     "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                    """.formatted(pool.poolId(), pool.clientId(), pool.username(), PASSWORD));
        }
        return cognitoAction("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(pool.clientId(), pool.username(), PASSWORD));
    }
}
