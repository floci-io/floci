package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminGetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminInitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminRespondToAuthChallengeResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExplicitAuthFlowsType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.GetUserResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InvalidParameterException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolClientType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolMfaType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameAttributeType;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CognitoOptionalTotpMfaTest {
    private static final String USERNAME = "optional-totp-sdk-user";
    private static final String PASSWORD = "SdkTotp123!";

    @Test
    @DisplayName("optional MFA challenges a user only after SetUserMFAPreference turns TOTP on")
    void sdkTurnsOnSoftwareTokenMfaInAnOptionalPool() throws Exception {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            String poolId = cognito.createUserPool(b -> b.poolName("optional-totp-sdk-" + UUID.randomUUID()))
                    .userPool().id();
            try {
                cognito.setUserPoolMfaConfig(b -> b.userPoolId(poolId)
                        .mfaConfiguration(UserPoolMfaType.OPTIONAL)
                        .softwareTokenMfaConfiguration(m -> m.enabled(true)));
                String clientId = cognito.createUserPoolClient(b -> b.userPoolId(poolId)
                        .clientName("optional-totp-sdk-client")
                        .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH))
                        .userPoolClient().clientId();
                cognito.adminCreateUser(b -> b.userPoolId(poolId).username(USERNAME)
                        .messageAction(MessageActionType.SUPPRESS));
                cognito.adminSetUserPassword(b -> b.userPoolId(poolId).username(USERNAME)
                        .password(PASSWORD).permanent(true));

                String accessToken = passwordLogin(cognito, clientId).authenticationResult().accessToken();
                assertThatThrownBy(() -> cognito.setUserMFAPreference(b -> b.accessToken(accessToken)
                        .softwareTokenMfaSettings(s -> s.enabled(true).preferredMfa(true))))
                        .isInstanceOf(InvalidParameterException.class);

                String secret = cognito.associateSoftwareToken(b -> b.accessToken(accessToken)).secretCode();
                String setupCode = totp(secret, Instant.now());
                cognito.verifySoftwareToken(b -> b.accessToken(accessToken).userCode(setupCode));
                assertThat(passwordLogin(cognito, clientId).authenticationResult()).isNotNull();

                cognito.setUserMFAPreference(b -> b.accessToken(accessToken)
                        .softwareTokenMfaSettings(s -> s.enabled(true).preferredMfa(true)));
                GetUserResponse user = cognito.getUser(b -> b.accessToken(accessToken));
                assertThat(user.userMFASettingList()).containsExactly("SOFTWARE_TOKEN_MFA");
                assertThat(user.preferredMfaSetting()).isEqualTo("SOFTWARE_TOKEN_MFA");
                AdminGetUserResponse adminUser = cognito.adminGetUser(b -> b.userPoolId(poolId).username(USERNAME));
                assertThat(adminUser.userMFASettingList()).containsExactly("SOFTWARE_TOKEN_MFA");
                assertThat(adminUser.preferredMfaSetting()).isEqualTo("SOFTWARE_TOKEN_MFA");

                InitiateAuthResponse challenged = passwordLogin(cognito, clientId);
                assertThat(challenged.challengeName()).isEqualTo(ChallengeNameType.SOFTWARE_TOKEN_MFA);
                assertThat(challenged.authenticationResult()).isNull();
                String signInCode = totp(secret, Instant.now());
                RespondToAuthChallengeResponse signedIn = cognito.respondToAuthChallenge(b -> b
                        .clientId(clientId).challengeName(ChallengeNameType.SOFTWARE_TOKEN_MFA)
                        .session(challenged.session())
                        .challengeResponses(Map.of("USERNAME", USERNAME,
                                "SOFTWARE_TOKEN_MFA_CODE", signInCode)));
                assertThat(signedIn.authenticationResult().accessToken()).isNotBlank();

                cognito.adminSetUserMFAPreference(b -> b.userPoolId(poolId).username(USERNAME)
                        .softwareTokenMfaSettings(s -> s.enabled(false)));
                assertThat(passwordLogin(cognito, clientId).authenticationResult()).isNotNull();
                assertThat(cognito.adminGetUser(b -> b.userPoolId(poolId).username(USERNAME))
                        .hasUserMFASettingList()).isFalse();
            } finally {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        }
    }

    @Test
    @DisplayName("SOFTWARE_TOKEN_MFA names the sub and the device, and accepts the sub with its SECRET_HASH")
    void sdkAnswersTheSoftwareTokenChallengeWithUserIdForSrp() throws Exception {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            String poolId = cognito.createUserPool(b -> b.poolName("email-totp-sdk-" + UUID.randomUUID())
                    .usernameAttributes(UsernameAttributeType.EMAIL)).userPool().id();
            try {
                cognito.setUserPoolMfaConfig(b -> b.userPoolId(poolId)
                        .mfaConfiguration(UserPoolMfaType.OPTIONAL)
                        .softwareTokenMfaConfiguration(m -> m.enabled(true)));
                UserPoolClientType client = cognito.createUserPoolClient(b -> b.userPoolId(poolId)
                        .clientName("email-totp-sdk-client").generateSecret(true)
                        .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_ADMIN_USER_PASSWORD_AUTH,
                                ExplicitAuthFlowsType.ALLOW_REFRESH_TOKEN_AUTH))
                        .userPoolClient();
                String email = "totp-" + UUID.randomUUID() + "@example.com";
                String sub = cognito.adminCreateUser(b -> b.userPoolId(poolId).username(email)
                        .userAttributes(a -> a.name("email").value(email),
                                a -> a.name("email_verified").value("true"))
                        .messageAction(MessageActionType.SUPPRESS)).user().username();
                cognito.adminSetUserPassword(b -> b.userPoolId(poolId).username(email)
                        .password(PASSWORD).permanent(true));

                String accessToken = adminPasswordLogin(cognito, poolId, client, email)
                        .authenticationResult().accessToken();
                String secret = cognito.associateSoftwareToken(b -> b.accessToken(accessToken)).secretCode();
                String setupCode = totp(secret, Instant.now());
                cognito.verifySoftwareToken(b -> b.accessToken(accessToken).userCode(setupCode)
                        .friendlyDeviceName("sdk-device"));
                cognito.adminSetUserMFAPreference(b -> b.userPoolId(poolId).username(email)
                        .softwareTokenMfaSettings(s -> s.enabled(true).preferredMfa(true)));

                AdminInitiateAuthResponse challenged = adminPasswordLogin(cognito, poolId, client, email);
                assertThat(challenged.challengeName()).isEqualTo(ChallengeNameType.SOFTWARE_TOKEN_MFA);
                assertThat(challenged.challengeParameters()).containsOnly(
                        Map.entry("USER_ID_FOR_SRP", sub), Map.entry("FRIENDLY_DEVICE_NAME", "sdk-device"));

                String userIdForSrp = challenged.challengeParameters().get("USER_ID_FOR_SRP");
                String signInCode = totp(secret, Instant.now());
                String challengeSecretHash = secretHash(client, userIdForSrp);
                AdminRespondToAuthChallengeResponse signedIn = cognito.adminRespondToAuthChallenge(b -> b
                        .userPoolId(poolId).clientId(client.clientId())
                        .challengeName(ChallengeNameType.SOFTWARE_TOKEN_MFA)
                        .session(challenged.session())
                        .challengeResponses(Map.of("USERNAME", userIdForSrp,
                                "SECRET_HASH", challengeSecretHash,
                                "SOFTWARE_TOKEN_MFA_CODE", signInCode)));
                assertThat(signedIn.authenticationResult().accessToken()).isNotBlank();
            } finally {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        }
    }

    private static AdminInitiateAuthResponse adminPasswordLogin(CognitoIdentityProviderClient cognito,
                                                                String poolId, UserPoolClientType client,
                                                                String username) throws Exception {
        String secretHash = secretHash(client, username);
        return cognito.adminInitiateAuth(b -> b.userPoolId(poolId).clientId(client.clientId())
                .authFlow(AuthFlowType.ADMIN_USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", username, "PASSWORD", PASSWORD, "SECRET_HASH", secretHash)));
    }

    private static String secretHash(UserPoolClientType client, String username) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(client.clientSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(
                mac.doFinal((username + client.clientId()).getBytes(StandardCharsets.UTF_8)));
    }

    private static InitiateAuthResponse passwordLogin(CognitoIdentityProviderClient cognito, String clientId) {
        return cognito.initiateAuth(b -> b.clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)));
    }

    private static String totp(String secret, Instant now) throws Exception {
        byte[] key = new byte[secret.length() * 5 / 8];
        int buffer = 0;
        int bits = 0;
        int index = 0;
        for (int i = 0; i < secret.length(); i++) {
            buffer = (buffer << 5) | "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(secret.charAt(i));
            bits += 5;
            if (bits >= 8) {
                key[index++] = (byte) (buffer >>> (bits - 8));
                bits -= 8;
            }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] digest = mac.doFinal(ByteBuffer.allocate(Long.BYTES)
                .putLong(now.getEpochSecond() / 30).array());
        int offset = digest[digest.length - 1] & 15;
        int binary = ((digest[offset] & 127) << 24)
                | ((digest[offset + 1] & 255) << 16)
                | ((digest[offset + 2] & 255) << 8)
                | (digest[offset + 3] & 255);
        return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
    }
}
