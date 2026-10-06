package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExplicitAuthFlowsType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserPoolMfaConfigResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListWebAuthnCredentialsResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolMfaType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserVerificationType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.WebAuthnFactorConfigurationType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.WebAuthnCredentialDescription;
import software.amazon.awssdk.services.cognitoidentityprovider.model.WebAuthnNotEnabledException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CognitoWebAuthnTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String USERNAME = "passkey-sdk-user";
    private static final String PASSWORD = "SdkPasskey123!";

    @Test
    @DisplayName("passkey registration and WEB_AUTHN sign-in through the SDK's document members")
    void sdkRegistersAPasskeyAndSignsInWithIt() throws Exception {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            String poolId = cognito.createUserPool(b -> b.poolName("passkey-sdk-" + UUID.randomUUID())
                    .policies(p -> p.signInPolicy(s -> s.allowedFirstAuthFactorsWithStrings("PASSWORD", "WEB_AUTHN"))))
                    .userPool().id();
            try {
                cognito.setUserPoolMfaConfig(b -> b.userPoolId(poolId).mfaConfiguration(UserPoolMfaType.OFF)
                        .webAuthnConfiguration(w -> w.relyingPartyId("localhost")
                                .userVerification(UserVerificationType.PREFERRED)));
                GetUserPoolMfaConfigResponse mfaConfig = cognito.getUserPoolMfaConfig(b -> b.userPoolId(poolId));
                assertThat(mfaConfig.webAuthnConfiguration().relyingPartyId()).isEqualTo("localhost");
                String clientId = cognito.createUserPoolClient(b -> b.userPoolId(poolId)
                        .clientName("passkey-sdk-client")
                        .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH,
                                ExplicitAuthFlowsType.ALLOW_USER_AUTH))
                        .userPoolClient().clientId();
                cognito.adminCreateUser(b -> b.userPoolId(poolId).username(USERNAME)
                        .messageAction(MessageActionType.SUPPRESS));
                cognito.adminSetUserPassword(b -> b.userPoolId(poolId).username(USERNAME)
                        .password(PASSWORD).permanent(true));
                String accessToken = cognito.initiateAuth(b -> b.clientId(clientId)
                        .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                        .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)))
                        .authenticationResult().accessToken();

                Document options = cognito.startWebAuthnRegistration(b -> b.accessToken(accessToken))
                        .credentialCreationOptions();
                JsonNode creationOptions = toJson(options);
                assertThat(creationOptions.path("rp").path("id").asText()).isEqualTo("localhost");

                WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256("http://localhost:5173");
                Document credential = toDocument(authenticator.register(creationOptions, "packed"));
                cognito.completeWebAuthnRegistration(b -> b.accessToken(accessToken).credential(credential));

                ListWebAuthnCredentialsResponse listed = cognito.listWebAuthnCredentials(b -> b.accessToken(accessToken));
                assertThat(listed.credentials()).hasSize(1);
                WebAuthnCredentialDescription description = listed.credentials().get(0);
                assertThat(description.credentialId()).isEqualTo(authenticator.credentialId());
                assertThat(description.relyingPartyId()).isEqualTo("localhost");
                assertThat(description.createdAt()).isNotNull();

                InitiateAuthResponse challenge = cognito.initiateAuth(b -> b.clientId(clientId)
                        .authFlow(AuthFlowType.USER_AUTH)
                        .authParameters(Map.of("USERNAME", USERNAME, "PREFERRED_CHALLENGE", "WEB_AUTHN")));
                assertThat(challenge.challengeName()).isEqualTo(ChallengeNameType.WEB_AUTHN);
                String assertion = authenticator.authenticate(
                        challenge.challengeParameters().get("CREDENTIAL_REQUEST_OPTIONS"));
                RespondToAuthChallengeResponse signedIn = cognito.respondToAuthChallenge(b -> b.clientId(clientId)
                        .challengeName(ChallengeNameType.WEB_AUTHN)
                        .session(challenge.session())
                        .challengeResponses(Map.of("USERNAME", USERNAME, "CREDENTIAL", assertion)));
                assertThat(signedIn.authenticationResult().accessToken()).isNotBlank();

                WebAuthnTestAuthenticator rsa = WebAuthnTestAuthenticator.rs256("http://localhost:5173");
                JsonNode rsaOptions = toJson(cognito.startWebAuthnRegistration(b -> b.accessToken(accessToken))
                        .credentialCreationOptions());
                Document rsaCredential = toDocument(rsa.register(rsaOptions, "none"));
                cognito.completeWebAuthnRegistration(b -> b.accessToken(accessToken).credential(rsaCredential));
                InitiateAuthResponse rsaChallenge = cognito.initiateAuth(b -> b.clientId(clientId)
                        .authFlow(AuthFlowType.USER_AUTH)
                        .authParameters(Map.of("USERNAME", USERNAME, "PREFERRED_CHALLENGE", "WEB_AUTHN")));
                String rsaAssertion = rsa.authenticate(
                        rsaChallenge.challengeParameters().get("CREDENTIAL_REQUEST_OPTIONS"));
                assertThat(cognito.respondToAuthChallenge(b -> b.clientId(clientId)
                        .challengeName(ChallengeNameType.WEB_AUTHN)
                        .session(rsaChallenge.session())
                        .challengeResponses(Map.of("USERNAME", USERNAME, "CREDENTIAL", rsaAssertion)))
                        .authenticationResult().accessToken()).isNotBlank();

                cognito.deleteWebAuthnCredential(b -> b.accessToken(accessToken)
                        .credentialId(authenticator.credentialId()));
                cognito.deleteWebAuthnCredential(b -> b.accessToken(accessToken)
                        .credentialId(rsa.credentialId()));
                assertThat(cognito.listWebAuthnCredentials(b -> b.accessToken(accessToken)).credentials()).isEmpty();
            } finally {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        }
    }

    @Test
    @DisplayName("a passkey stands in for required MFA only for a user who turned WebAuthnMfaSettings on")
    void sdkTurnsPasskeyMfaOnPerUser() throws Exception {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            String poolId = cognito.createUserPool(b -> b.poolName("passkey-mfa-sdk-" + UUID.randomUUID())
                    .policies(p -> p.signInPolicy(s -> s.allowedFirstAuthFactorsWithStrings("PASSWORD", "WEB_AUTHN"))))
                    .userPool().id();
            try {
                cognito.setUserPoolMfaConfig(b -> b.userPoolId(poolId).mfaConfiguration(UserPoolMfaType.OFF)
                        .webAuthnConfiguration(w -> w.relyingPartyId("localhost")));
                String clientId = cognito.createUserPoolClient(b -> b.userPoolId(poolId)
                        .clientName("passkey-mfa-sdk-client")
                        .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH,
                                ExplicitAuthFlowsType.ALLOW_USER_AUTH))
                        .userPoolClient().clientId();
                cognito.adminCreateUser(b -> b.userPoolId(poolId).username(USERNAME)
                        .messageAction(MessageActionType.SUPPRESS));
                cognito.adminSetUserPassword(b -> b.userPoolId(poolId).username(USERNAME)
                        .password(PASSWORD).permanent(true));
                String accessToken = cognito.initiateAuth(b -> b.clientId(clientId)
                        .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                        .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)))
                        .authenticationResult().accessToken();
                WebAuthnTestAuthenticator authenticator = WebAuthnTestAuthenticator.es256("http://localhost:5173");
                JsonNode creationOptions = toJson(cognito.startWebAuthnRegistration(b -> b.accessToken(accessToken))
                        .credentialCreationOptions());
                Document credential = toDocument(authenticator.register(creationOptions, "none"));
                cognito.completeWebAuthnRegistration(b -> b.accessToken(accessToken).credential(credential));

                assertThatThrownBy(() -> cognito.setUserMFAPreference(b -> b.accessToken(accessToken)
                        .webAuthnMfaSettings(w -> w.enabled(true))))
                        .isInstanceOf(InvalidParameterException.class);

                cognito.setUserPoolMfaConfig(b -> b.userPoolId(poolId).mfaConfiguration(UserPoolMfaType.ON)
                        .softwareTokenMfaConfiguration(t -> t.enabled(true))
                        .webAuthnConfiguration(w -> w.relyingPartyId("localhost")
                                .factorConfiguration(WebAuthnFactorConfigurationType.MULTI_FACTOR_WITH_USER_VERIFICATION)));
                InitiateAuthResponse withoutOptIn = cognito.initiateAuth(b -> b.clientId(clientId)
                        .authFlow(AuthFlowType.USER_AUTH)
                        .authParameters(Map.of("USERNAME", USERNAME)));
                assertThat(withoutOptIn.challengeName()).isEqualTo(ChallengeNameType.SELECT_CHALLENGE);
                assertThat(withoutOptIn.availableChallengesAsStrings()).containsExactly("PASSWORD", "PASSWORD_SRP");

                cognito.setUserMFAPreference(b -> b.accessToken(accessToken)
                        .webAuthnMfaSettings(w -> w.enabled(true)));
                InitiateAuthResponse challenge = cognito.initiateAuth(b -> b.clientId(clientId)
                        .authFlow(AuthFlowType.USER_AUTH)
                        .authParameters(Map.of("USERNAME", USERNAME, "PREFERRED_CHALLENGE", "WEB_AUTHN")));
                assertThat(challenge.challengeName()).isEqualTo(ChallengeNameType.WEB_AUTHN);
                String requestOptions = challenge.challengeParameters().get("CREDENTIAL_REQUEST_OPTIONS");
                assertThat(JSON.readTree(requestOptions).path("userVerification").asText()).isEqualTo("required");
                String assertion = authenticator.authenticate(requestOptions);
                RespondToAuthChallengeResponse signedIn = cognito.respondToAuthChallenge(b -> b.clientId(clientId)
                        .challengeName(ChallengeNameType.WEB_AUTHN)
                        .session(challenge.session())
                        .challengeResponses(Map.of("USERNAME", USERNAME, "CREDENTIAL", assertion)));
                assertThat(signedIn.authenticationResult().accessToken()).isNotBlank();

                cognito.adminSetUserMFAPreference(b -> b.userPoolId(poolId).username(USERNAME)
                        .webAuthnMfaSettings(w -> w.enabled(false)));
                assertThat(cognito.initiateAuth(b -> b.clientId(clientId)
                        .authFlow(AuthFlowType.USER_AUTH)
                        .authParameters(Map.of("USERNAME", USERNAME))).availableChallengesAsStrings())
                        .containsExactly("PASSWORD", "PASSWORD_SRP");
            } finally {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        }
    }

    @Test
    @DisplayName("StartWebAuthnRegistration is refused when the pool does not allow WEB_AUTHN")
    void sdkSeesWebAuthnNotEnabled() {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            String poolId = cognito.createUserPool(b -> b.poolName("no-passkey-sdk-" + UUID.randomUUID()))
                    .userPool().id();
            try {
                String clientId = cognito.createUserPoolClient(b -> b.userPoolId(poolId)
                        .clientName("no-passkey-sdk-client")
                        .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH))
                        .userPoolClient().clientId();
                cognito.adminCreateUser(b -> b.userPoolId(poolId).username(USERNAME)
                        .messageAction(MessageActionType.SUPPRESS));
                cognito.adminSetUserPassword(b -> b.userPoolId(poolId).username(USERNAME)
                        .password(PASSWORD).permanent(true));
                String accessToken = cognito.initiateAuth(b -> b.clientId(clientId)
                        .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                        .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)))
                        .authenticationResult().accessToken();

                assertThatThrownBy(() -> cognito.startWebAuthnRegistration(b -> b.accessToken(accessToken)))
                        .isInstanceOf(WebAuthnNotEnabledException.class);
            } finally {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        }
    }

    private static JsonNode toJson(Document document) {
        if (document.isMap()) {
            ObjectNode node = JSON.createObjectNode();
            document.asMap().forEach((key, value) -> node.set(key, toJson(value)));
            return node;
        }
        if (document.isList()) {
            ArrayNode node = JSON.createArrayNode();
            document.asList().forEach(value -> node.add(toJson(value)));
            return node;
        }
        if (document.isString()) {
            return JSON.getNodeFactory().textNode(document.asString());
        }
        if (document.isNumber()) {
            return JSON.getNodeFactory().numberNode(document.asNumber().bigDecimalValue());
        }
        if (document.isBoolean()) {
            return JSON.getNodeFactory().booleanNode(document.asBoolean());
        }
        return JSON.getNodeFactory().nullNode();
    }

    private static Document toDocument(JsonNode node) {
        if (node.isObject()) {
            Map<String, Document> members = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> members.put(entry.getKey(), toDocument(entry.getValue())));
            return Document.fromMap(members);
        }
        if (node.isArray()) {
            List<Document> items = new ArrayList<>();
            node.forEach(item -> items.add(toDocument(item)));
            return Document.fromList(items);
        }
        if (node.isBoolean()) {
            return Document.fromBoolean(node.asBoolean());
        }
        if (node.isNumber()) {
            return Document.fromNumber(node.decimalValue());
        }
        if (node.isNull()) {
            return Document.fromNull();
        }
        return Document.fromString(node.asText());
    }
}
