package io.floci.aws.services.cognito;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Consumer;

import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CognitoEmailCaseIntegrationTest {

    private static final String PASSWORD = "Valid-password123!";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void caseInsensitivePoolSignsInAMixedCaseSpellingOfTheEmail() throws Exception {
        withEmailUser("{ \"CaseSensitive\": false }", clientId -> {
            signIn(clientId, "fixture@example.com")
                    .then().statusCode(200)
                    .body("AuthenticationResult.AccessToken", not(emptyOrNullString()));
            signIn(clientId, "Fixture@Example.com")
                    .then().statusCode(200)
                    .body("AuthenticationResult.AccessToken", not(emptyOrNullString()));
        });
    }

    @Test
    void caseSensitivePoolRefusesAMixedCaseSpellingOfTheEmail() throws Exception {
        withEmailUser("{ \"CaseSensitive\": true }", clientId -> {
            signIn(clientId, "fixture@example.com").then().statusCode(200);
            signIn(clientId, "Fixture@Example.com")
                    .then().statusCode(400)
                    .body("__type", equalTo("UserNotFoundException"));
        });
    }

    private static void withEmailUser(String usernameConfiguration, Consumer<String> body) throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "email-case-%s",
                  "UsernameAttributes": ["email"],
                  "UsernameConfiguration": %s
                }
                """.formatted(UUID.randomUUID(), usernameConfiguration)).path("UserPool").path("Id").asText();
        try {
            String clientId = cognitoJson("CreateUserPoolClient", """
                    {
                      "UserPoolId": "%s",
                      "ClientName": "email-case-client",
                      "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH"]
                    }
                    """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
            String username = cognitoJson("AdminCreateUser", """
                    {
                      "UserPoolId": "%s",
                      "Username": "fixture@example.com",
                      "TemporaryPassword": "Temporary-password123!",
                      "MessageAction": "SUPPRESS",
                      "UserAttributes": [
                        { "Name": "email", "Value": "fixture@example.com" },
                        { "Name": "email_verified", "Value": "true" }
                      ]
                    }
                    """.formatted(poolId)).path("User").path("Username").asText();
            cognitoAction("AdminSetUserPassword", """
                    { "UserPoolId": "%s", "Username": "%s", "Password": "%s", "Permanent": true }
                    """.formatted(poolId, username, PASSWORD)).then().statusCode(200);
            body.accept(clientId);
        } finally {
            cognitoAction("DeleteUserPool", """
                    { "UserPoolId": "%s" }
                    """.formatted(poolId)).then().statusCode(200);
        }
    }

    private static Response signIn(String clientId, String email) {
        return cognitoAction("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_PASSWORD_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PASSWORD": "%s" }
                }
                """.formatted(clientId, email, PASSWORD));
    }
}
