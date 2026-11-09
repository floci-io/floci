package com.floci.test;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExplicitAuthFlowsType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.InitiateAuthResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserNotFoundException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UsernameAttributeType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Cognito email sign-in case sensitivity (issue #7828)")
class CognitoEmailCaseTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("Email sign-in respects the pool's CaseSensitive setting")
    void emailSignInRespectsCaseSensitivity(boolean caseSensitive) {
        try (CognitoIdentityProviderClient cognito = TestFixtures.cognitoClient()) {
            String poolId = cognito.createUserPool(b -> b
                    .poolName(TestFixtures.uniqueName("email-case"))
                    .usernameAttributes(UsernameAttributeType.EMAIL)
                    .usernameConfiguration(c -> c.caseSensitive(caseSensitive)))
                    .userPool().id();
            try {
                String clientId = cognito.createUserPoolClient(b -> b
                        .userPoolId(poolId)
                        .clientName("email-case-client")
                        .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH))
                        .userPoolClient().clientId();
                String username = cognito.adminCreateUser(b -> b
                        .userPoolId(poolId)
                        .username("fixture@example.com")
                        .messageAction(MessageActionType.SUPPRESS)
                        .userAttributes(
                                AttributeType.builder().name("email").value("fixture@example.com").build(),
                                AttributeType.builder().name("email_verified").value("true").build()))
                        .user().username();
                cognito.adminSetUserPassword(b -> b.userPoolId(poolId).username(username)
                        .password("Valid-password123!").permanent(true));

                InitiateAuthResponse lowercase = cognito.initiateAuth(b -> b.clientId(clientId)
                        .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                        .authParameters(Map.of("USERNAME", "fixture@example.com", "PASSWORD", "Valid-password123!")));
                assertThat(lowercase.authenticationResult().accessToken()).isNotBlank();

                if (caseSensitive) {
                    assertThatThrownBy(() -> cognito.initiateAuth(b -> b.clientId(clientId)
                            .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                            .authParameters(Map.of("USERNAME", "Fixture@Example.com", "PASSWORD", "Valid-password123!"))))
                            .isInstanceOf(UserNotFoundException.class);
                } else {
                    InitiateAuthResponse mixedCase = cognito.initiateAuth(b -> b.clientId(clientId)
                            .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                            .authParameters(Map.of("USERNAME", "Fixture@Example.com", "PASSWORD", "Valid-password123!")));
                    assertThat(mixedCase.authenticationResult().accessToken()).isNotBlank();
                    assertThat(mixedCase.authenticationResult().idToken()).isNotBlank();
                    assertThat(cognito.adminGetUser(b -> b.userPoolId(poolId).username("Fixture@Example.com"))
                            .username()).isEqualTo(username);
                }
            } finally {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        }
    }
}
