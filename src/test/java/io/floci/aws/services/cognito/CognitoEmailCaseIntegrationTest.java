package io.floci.aws.services.cognito;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.floci.aws.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

@QuarkusTest
class CognitoEmailCaseIntegrationTest {

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void initiateAuth_caseInsensitiveEmailPool_acceptsMixedCaseEmail() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "email-case-%s",
                  "UsernameAttributes": ["email"],
                  "UsernameConfiguration": { "CaseSensitive": false }
                }
                """.formatted(UUID.randomUUID())).path("UserPool").path("Id").asText();
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
                    {
                      "UserPoolId": "%s",
                      "Username": "%s",
                      "Password": "Valid-password123!",
                      "Permanent": true
                    }
                    """.formatted(poolId, username)).then().statusCode(200);

            for (String email : List.of("fixture@example.com", "Fixture@Example.com")) {
                cognitoAction("InitiateAuth", """
                        {
                          "ClientId": "%s",
                          "AuthFlow": "USER_PASSWORD_AUTH",
                          "AuthParameters": {
                            "USERNAME": "%s",
                            "PASSWORD": "Valid-password123!"
                          }
                        }
                        """.formatted(clientId, email))
                        .then().statusCode(200)
                        .body("AuthenticationResult.ExpiresIn", equalTo(3600))
                        .body("AuthenticationResult.AccessToken", not(emptyOrNullString()))
                        .body("AuthenticationResult.IdToken", not(emptyOrNullString()));
            }
        } finally {
            cognitoAction("DeleteUserPool", """
                    { "UserPoolId": "%s" }
                    """.formatted(poolId)).then().statusCode(200);
        }
    }
}
