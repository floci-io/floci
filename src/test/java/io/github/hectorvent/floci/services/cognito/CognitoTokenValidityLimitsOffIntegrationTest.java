package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.github.hectorvent.floci.services.cognito.CognitoCustomDomainFixtures.jwtPayload;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With {@code floci.services.cognito.enforce-token-validity-limits=false}, an app client keeps tokens
 * shorter than AWS allows, as tests of what happens when a token expires want.
 */
@QuarkusTest
@TestProfile(CognitoTokenValidityLimitsOffIntegrationTest.TokenValidityLimitsOffProfile.class)
class CognitoTokenValidityLimitsOffIntegrationTest {

    private static final String PASSWORD = "Perm1234!";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void oneMinuteTokensAreAcceptedAndIssued() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName": "ShortTokensPool"}
                """).path("UserPool").path("Id").asText();
        JsonNode client = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId": "%s", "ClientName": "short-tokens",
                 "ExplicitAuthFlows": ["ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"],
                 "AccessTokenValidity": 1, "IdTokenValidity": 1, "RefreshTokenValidity": 20,
                 "TokenValidityUnits": {"AccessToken": "minutes", "IdToken": "minutes", "RefreshToken": "minutes"}}
                """.formatted(poolId)).path("UserPoolClient");
        assertEquals(1, client.path("AccessTokenValidity").asInt());
        assertEquals(20, client.path("RefreshTokenValidity").asInt());
        cognitoAction("AdminCreateUser", """
                {"UserPoolId": "%s", "Username": "carol", "MessageAction": "SUPPRESS"}
                """.formatted(poolId)).then().statusCode(200);
        cognitoAction("AdminSetUserPassword", """
                {"UserPoolId": "%s", "Username": "carol", "Password": "%s", "Permanent": true}
                """.formatted(poolId, PASSWORD)).then().statusCode(200);

        JsonNode result = cognitoJson("InitiateAuth", """
                {"ClientId": "%s", "AuthFlow": "USER_PASSWORD_AUTH",
                 "AuthParameters": {"USERNAME": "carol", "PASSWORD": "%s"}}
                """.formatted(client.path("ClientId").asText(), PASSWORD)).path("AuthenticationResult");

        assertEquals(60, result.path("ExpiresIn").asInt());
        JsonNode idToken = jwtPayload(result.path("IdToken").asText());
        assertEquals(60, idToken.path("exp").asLong() - idToken.path("iat").asLong());
    }

    public static final class TokenValidityLimitsOffProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.cognito.enforce-token-validity-limits", "false");
        }
    }
}
