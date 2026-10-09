package com.floci.test;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AssociateSoftwareTokenResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeNameType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.CreateUserPoolResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExplicitAuthFlowsType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.RespondToAuthChallengeResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolMfaType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.VerifySoftwareTokenResponse;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CognitoTotpMfaTest {
    private static final String USERNAME = "totp-sdk-user";
    private static final String PASSWORD = "SdkTotp123!";

    @Test
    void sdkCompletesSetupAndLaterSoftwareTokenChallenges() throws Exception {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            CreateUserPoolResponse created = cognito.createUserPool(b -> b
                    .poolName("totp-sdk-" + UUID.randomUUID()));
            String poolId = created.userPool().id();
            try {
                cognito.setUserPoolMfaConfig(b -> b.userPoolId(poolId)
                        .mfaConfiguration(UserPoolMfaType.ON)
                        .softwareTokenMfaConfiguration(m -> m.enabled(true)));
                String clientId = cognito.createUserPoolClient(b -> b.userPoolId(poolId)
                        .clientName("totp-sdk-client")
                        .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH))
                        .userPoolClient().clientId();
                cognito.adminCreateUser(b -> b.userPoolId(poolId).username(USERNAME)
                        .messageAction(MessageActionType.SUPPRESS));
                cognito.adminSetUserPassword(b -> b.userPoolId(poolId).username(USERNAME)
                        .password(PASSWORD).permanent(true));

                InitiateAuthResponse first = passwordLogin(cognito, clientId);
                assertThat(first.challengeName()).isEqualTo(ChallengeNameType.MFA_SETUP);
                assertThat(first.authenticationResult()).isNull();
                assertThat(first.challengeParameters().get("MFAS_CAN_SETUP"))
                        .contains("SOFTWARE_TOKEN_MFA");

                AssociateSoftwareTokenResponse associated = cognito.associateSoftwareToken(
                        b -> b.session(first.session()));
                assertThat(associated.secretCode()).matches("[A-Z2-7]{32}");
                String setupCode = totp(associated.secretCode(), Instant.now());
                VerifySoftwareTokenResponse verified = cognito.verifySoftwareToken(b -> b
                        .session(associated.session())
                        .userCode(setupCode));
                assertThat(verified.statusAsString()).isEqualTo("SUCCESS");

                RespondToAuthChallengeResponse completed = cognito.respondToAuthChallenge(b -> b
                        .clientId(clientId).challengeName(ChallengeNameType.MFA_SETUP)
                        .session(verified.session())
                        .challengeResponses(Map.of("USERNAME", USERNAME)));
                assertThat(completed.authenticationResult().accessToken()).isNotBlank();

                InitiateAuthResponse later = passwordLogin(cognito, clientId);
                assertThat(later.challengeName()).isEqualTo(ChallengeNameType.SOFTWARE_TOKEN_MFA);
                assertThat(later.authenticationResult()).isNull();
                String signInCode = totp(associated.secretCode(), Instant.now());
                RespondToAuthChallengeResponse signedIn = cognito.respondToAuthChallenge(b -> b
                        .clientId(clientId).challengeName(ChallengeNameType.SOFTWARE_TOKEN_MFA)
                        .session(later.session())
                        .challengeResponses(Map.of("USERNAME", USERNAME,
                                "SOFTWARE_TOKEN_MFA_CODE", signInCode)));
                assertThat(signedIn.authenticationResult().accessToken()).isNotBlank();
            } finally {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        }
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
