package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoAction;
import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Passkey registration and sign-in over the JSON 1.1 wire protocol. */
@QuarkusTest
class CognitoWebAuthnIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PASSWORD = "Perm1234!";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void passkeyRegistrationAndSignInRoundTrip() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"PasskeyProtocolPool","UserPoolTier":"ESSENTIALS",
                 "Policies":{"SignInPolicy":{"AllowedFirstAuthFactors":["PASSWORD","WEB_AUTHN"]}}}
                """).path("UserPool").path("Id").asText();
        JsonNode mfaConfig = cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"OFF",
                 "WebAuthnConfiguration":{"RelyingPartyId":"localhost"}}
                """.formatted(poolId));
        assertEquals("localhost", mfaConfig.path("WebAuthnConfiguration").path("RelyingPartyId").asText());
        assertEquals("preferred", mfaConfig.path("WebAuthnConfiguration").path("UserVerification").asText());
        cognitoAction("GetUserPoolMfaConfig", """
                {"UserPoolId":"%s"}
                """.formatted(poolId))
                .then().statusCode(200)
                .body("WebAuthnConfiguration.RelyingPartyId", equalTo("localhost"))
                .body("WebAuthnConfiguration", not(hasKey("FactorConfiguration")));

        String clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"passkey-client",
                 "ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH","ALLOW_USER_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        String username = "passkey-" + UUID.randomUUID();
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS"}
                """.formatted(poolId, username));
        cognitoJson("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, username, PASSWORD));
        String accessToken = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, username, PASSWORD)).path("AuthenticationResult").path("AccessToken").asText();

        JsonNode options = cognitoJson("StartWebAuthnRegistration", """
                {"AccessToken":"%s"}
                """.formatted(accessToken)).path("CredentialCreationOptions");
        assertTrue(options.isObject(), "CredentialCreationOptions is a document");
        assertEquals("localhost", options.path("rp").path("id").asText());
        assertEquals(60000, options.path("timeout").asInt());

        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256("http://localhost:5173");
        ObjectNode complete = JSON.createObjectNode();
        complete.put("AccessToken", accessToken);
        complete.set("Credential", authenticator.register(options, "packed"));
        cognitoAction("CompleteWebAuthnRegistration", complete.toString()).then().statusCode(200);
        cognitoAction("CompleteWebAuthnRegistration", complete.toString())
                .then().statusCode(400).body("__type", equalTo("WebAuthnChallengeNotFoundException"));

        cognitoAction("ListWebAuthnCredentials", """
                {"AccessToken":"%s"}
                """.formatted(accessToken))
                .then().statusCode(200)
                .body("Credentials", hasSize(1))
                .body("Credentials[0].CredentialId", equalTo(authenticator.credentialId()))
                .body("Credentials[0].RelyingPartyId", equalTo("localhost"))
                .body("Credentials[0].AuthenticatorTransports", contains("internal", "hybrid"))
                .body("$", not(hasKey("NextToken")));
        cognitoAction("GetUserAuthFactors", """
                {"AccessToken":"%s"}
                """.formatted(accessToken))
                .then().statusCode(200)
                .body("ConfiguredUserAuthFactors", contains("PASSWORD", "WEB_AUTHN"));

        JsonNode challenge = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PREFERRED_CHALLENGE":"WEB_AUTHN"}}
                """.formatted(clientId, username));
        assertEquals("WEB_AUTHN", challenge.path("ChallengeName").asText());
        String requestOptions = challenge.path("ChallengeParameters").path("CREDENTIAL_REQUEST_OPTIONS").asText();
        ObjectNode respond = JSON.createObjectNode();
        respond.put("ClientId", clientId);
        respond.put("ChallengeName", "WEB_AUTHN");
        respond.put("Session", challenge.path("Session").asText());
        respond.putObject("ChallengeResponses")
                .put("USERNAME", username)
                .put("CREDENTIAL", authenticator.authenticate(requestOptions));
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", respond.toString());
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        cognitoAction("DeleteWebAuthnCredential", """
                {"AccessToken":"%s","CredentialId":"%s"}
                """.formatted(accessToken, authenticator.credentialId()))
                .then().statusCode(200);
        cognitoAction("ListWebAuthnCredentials", """
                {"AccessToken":"%s"}
                """.formatted(accessToken))
                .then().statusCode(200)
                .body("Credentials", empty());
        cognitoAction("DeleteWebAuthnCredential", """
                {"AccessToken":"%s","CredentialId":"%s"}
                """.formatted(accessToken, authenticator.credentialId()))
                .then().statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void passkeyMfaIsTurnedOnPerUserWithWebAuthnMfaSettings() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"PasskeyMfaPool","UserPoolTier":"ESSENTIALS",
                 "Policies":{"SignInPolicy":{"AllowedFirstAuthFactors":["PASSWORD","WEB_AUTHN"]}}}
                """).path("UserPool").path("Id").asText();
        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"OFF",
                 "WebAuthnConfiguration":{"RelyingPartyId":"localhost"}}
                """.formatted(poolId));
        String clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"passkey-mfa-client",
                 "ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH","ALLOW_USER_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        String username = "passkey-mfa-" + UUID.randomUUID();
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"%s","MessageAction":"SUPPRESS"}
                """.formatted(poolId, username));
        cognitoJson("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"%s","Password":"%s","Permanent":true}
                """.formatted(poolId, username, PASSWORD));
        String accessToken = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PASSWORD":"%s"}}
                """.formatted(clientId, username, PASSWORD)).path("AuthenticationResult").path("AccessToken").asText();
        WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256("http://localhost:5173");
        ObjectNode complete = JSON.createObjectNode();
        complete.put("AccessToken", accessToken);
        complete.set("Credential", authenticator.register(cognitoJson("StartWebAuthnRegistration", """
                {"AccessToken":"%s"}
                """.formatted(accessToken)).path("CredentialCreationOptions"), "none"));
        cognitoAction("CompleteWebAuthnRegistration", complete.toString()).then().statusCode(200);

        String turnOn = """
                {"AccessToken":"%s","WebAuthnMfaSettings":{"Enabled":true}}
                """.formatted(accessToken);
        cognitoAction("SetUserMFAPreference", turnOn)
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));

        cognitoJson("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"ON",
                 "SoftwareTokenMfaConfiguration":{"Enabled":true},
                 "WebAuthnConfiguration":{"RelyingPartyId":"localhost",
                   "FactorConfiguration":"MULTI_FACTOR_WITH_USER_VERIFICATION"}}
                """.formatted(poolId));
        String userAuth = """
                {"ClientId":"%s","AuthFlow":"USER_AUTH","AuthParameters":{"USERNAME":"%s"}}
                """.formatted(clientId, username);
        cognitoAction("InitiateAuth", userAuth)
                .then().statusCode(200)
                .body("ChallengeName", equalTo("SELECT_CHALLENGE"))
                .body("AvailableChallenges", contains("PASSWORD", "PASSWORD_SRP"));

        cognitoAction("SetUserMFAPreference", turnOn).then().statusCode(200);
        JsonNode challenge = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_AUTH",
                 "AuthParameters":{"USERNAME":"%s","PREFERRED_CHALLENGE":"WEB_AUTHN"}}
                """.formatted(clientId, username));
        assertEquals("WEB_AUTHN", challenge.path("ChallengeName").asText());
        String requestOptions = challenge.path("ChallengeParameters").path("CREDENTIAL_REQUEST_OPTIONS").asText();
        assertEquals("required", JSON.readTree(requestOptions).path("userVerification").asText());
        ObjectNode respond = JSON.createObjectNode();
        respond.put("ClientId", clientId);
        respond.put("ChallengeName", "WEB_AUTHN");
        respond.put("Session", challenge.path("Session").asText());
        respond.putObject("ChallengeResponses")
                .put("USERNAME", username)
                .put("CREDENTIAL", authenticator.authenticate(requestOptions));
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", respond.toString());
        assertFalse(signedIn.path("AuthenticationResult").path("AccessToken").asText().isEmpty());

        cognitoAction("AdminSetUserMFAPreference", """
                {"UserPoolId":"%s","Username":"%s","WebAuthnMfaSettings":{"Enabled":false}}
                """.formatted(poolId, username))
                .then().statusCode(200);
        cognitoAction("InitiateAuth", userAuth)
                .then().statusCode(200)
                .body("AvailableChallenges", contains("PASSWORD", "PASSWORD_SRP"));

        cognitoAction("ListWebAuthnCredentials", """
                {"AccessToken":"%s","NextToken":"bm90LWEtY3Vyc29y"}
                """.formatted(accessToken))
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }

    @Test
    void passkeyActionsNeedPasskeysEnabledOnThePool() throws Exception {
        String poolId = cognitoJson("CreateUserPool", """
                {"PoolName":"PasswordOnlyPool"}
                """).path("UserPool").path("Id").asText();
        String clientId = cognitoJson("CreateUserPoolClient", """
                {"UserPoolId":"%s","ClientName":"password-client","ExplicitAuthFlows":["ALLOW_USER_PASSWORD_AUTH"]}
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        cognitoJson("AdminCreateUser", """
                {"UserPoolId":"%s","Username":"no-passkeys","MessageAction":"SUPPRESS"}
                """.formatted(poolId));
        cognitoJson("AdminSetUserPassword", """
                {"UserPoolId":"%s","Username":"no-passkeys","Password":"%s","Permanent":true}
                """.formatted(poolId, PASSWORD));
        String accessToken = cognitoJson("InitiateAuth", """
                {"ClientId":"%s","AuthFlow":"USER_PASSWORD_AUTH",
                 "AuthParameters":{"USERNAME":"no-passkeys","PASSWORD":"%s"}}
                """.formatted(clientId, PASSWORD)).path("AuthenticationResult").path("AccessToken").asText();

        cognitoAction("StartWebAuthnRegistration", """
                {"AccessToken":"%s"}
                """.formatted(accessToken))
                .then().statusCode(400).body("__type", equalTo("WebAuthnNotEnabledException"));
        cognitoAction("SetUserPoolMfaConfig", """
                {"UserPoolId":"%s","MfaConfiguration":"OFF",
                 "WebAuthnConfiguration":{"RelyingPartyId":"localhost","UserVerification":"sometimes"}}
                """.formatted(poolId))
                .then().statusCode(400).body("__type", equalTo("InvalidParameterException"));
    }
}
