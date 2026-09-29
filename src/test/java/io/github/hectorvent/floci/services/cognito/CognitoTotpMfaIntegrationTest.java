package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CognitoTotpMfaIntegrationTest {
    @Inject
    Clock clock;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void jsonProtocolRequiresTotpBeforeIssuingTokens() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"TotpProtocolPool"}
                """).path("UserPool").path("Id").asText();
        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"ON",
                 "SoftwareTokenMfaConfiguration":{"Enabled":true}}
                """.formatted(poolId));
        String clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"totp-protocol-client",
                 "ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH","ALLOW_USER_SRP_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"totp-user","MessageAction":"SUPPRESS"}
                """.formatted(poolId));
        cognitoJson("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"totp-user",
                 "Password":"Perm1234!","Permanent":true}
                """.formatted(poolId));

        String loginRequest = """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"totp-user","PASSWORD":"Perm1234!"}}
                """.formatted(clientId);
        JsonNode login = cognitoJson("InitiateAuth", loginRequest);
        assertEquals("MFA_SETUP", login.path("ChallengeName").asText());
        assertTrue(login.path("AuthenticationResult").isMissingNode());
        String firstSession = login.path("Session").asText();

        JsonNode associated = cognitoJson("AssociateSoftwareToken", """
                {"Session":"%s"}
                """.formatted(firstSession));
        assertTrue(associated.path("SecretCode").asText().matches("[A-Z2-7]{32}"));
        String code = CognitoTotp.code(associated.path("SecretCode").asText(), clock.instant());
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        cognitoAction("VerifySoftwareToken", """
                {"Session":"%s","UserCode":"%s"}
                """.formatted(associated.path("Session").asText(), wrongCode))
                .then().statusCode(400).body("__type", equalTo("CodeMismatchException"));
        JsonNode verified = cognitoJson("VerifySoftwareToken", """
                {"Session":"%s","UserCode":"%s"}
                """.formatted(associated.path("Session").asText(), code));
        assertEquals("SUCCESS", verified.path("Status").asText());

        JsonNode completed = cognitoJson("RespondToAuthChallenge", """
                {"ClientId":"%s","ChallengeName":"MFA_SETUP","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"totp-user"}}
                """.formatted(clientId, verified.path("Session").asText()));
        assertFalse(completed.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        JsonNode later = cognitoJson("InitiateAuth", loginRequest);
        assertEquals("SOFTWARE_TOKEN_MFA", later.path("ChallengeName").asText());
        assertTrue(later.path("AuthenticationResult").isMissingNode());
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", """
                {"ClientId":"%s","ChallengeName":"SOFTWARE_TOKEN_MFA","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"totp-user","SOFTWARE_TOKEN_MFA_CODE":"%s"}}
                """.formatted(clientId, later.path("Session").asText(), code));
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        String replay = """
                {"ClientId":"%s","ChallengeName":"SOFTWARE_TOKEN_MFA","Session":"%s",
                 "ChallengeResponses":{"USERNAME":"totp-user","SOFTWARE_TOKEN_MFA_CODE":"%s"}}
                """.formatted(clientId, later.path("Session").asText(), code);
        cognitoAction("RespondToAuthChallenge", replay).then().statusCode(400);
    }
}
