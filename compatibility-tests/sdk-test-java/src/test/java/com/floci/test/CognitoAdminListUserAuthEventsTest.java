package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdminListUserAuthEventsResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AdvancedSecurityModeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthEventType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AuthFlowType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeName;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ChallengeResponse;
import software.amazon.awssdk.services.cognitoidentityprovider.model.EventResponseType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.EventType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ExplicitAuthFlowsType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.MessageActionType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.NotAuthorizedException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolAddOnNotEnabledException;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserPoolTierType;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AdminListUserAuthEvents must deserialize through the typed SDK model: strict SDKs reject a
 * wrong timestamp or enum shape, which the AWS CLI would coerce silently.
 */
@DisplayName("Cognito IDP: AdminListUserAuthEvents")
class CognitoAdminListUserAuthEventsTest {

    private static final String PASSWORD = "CompatPass1!";

    private static CognitoIdentityProviderClient cognito;
    private static String plusPoolId;
    private static String essentialsPoolId;
    private static String username;

    @BeforeAll
    static void setup() {
        cognito = TestFixtures.cognitoClient();

        plusPoolId = cognito.createUserPool(b -> b
                .poolName(TestFixtures.uniqueName("compat-auth-events-plus"))
                .userPoolTier(UserPoolTierType.PLUS)
                .userPoolAddOns(a -> a.advancedSecurityMode(AdvancedSecurityModeType.AUDIT)))
                .userPool().id();
        String clientId = cognito.createUserPoolClient(b -> b
                .userPoolId(plusPoolId)
                .clientName("compat-auth-events-client")
                .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH,
                        ExplicitAuthFlowsType.ALLOW_REFRESH_TOKEN_AUTH))
                .userPoolClient().clientId();

        username = TestFixtures.uniqueName("auth-events-user");
        cognito.adminCreateUser(b -> b
                .userPoolId(plusPoolId)
                .username(username)
                .messageAction(MessageActionType.SUPPRESS));
        cognito.adminSetUserPassword(b -> b
                .userPoolId(plusPoolId)
                .username(username)
                .password(PASSWORD)
                .permanent(true));

        assertThatThrownBy(() -> cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", username, "PASSWORD", "WrongPass1!"))))
                .isInstanceOf(NotAuthorizedException.class);
        cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", username, "PASSWORD", PASSWORD)));

        essentialsPoolId = cognito.createUserPool(b -> b
                .poolName(TestFixtures.uniqueName("compat-auth-events-essentials")))
                .userPool().id();
        cognito.adminCreateUser(b -> b
                .userPoolId(essentialsPoolId)
                .username(username)
                .messageAction(MessageActionType.SUPPRESS));
    }

    @AfterAll
    static void cleanup() {
        if (cognito != null) {
            deletePoolQuietly(plusPoolId);
            deletePoolQuietly(essentialsPoolId);
            cognito.close();
        }
    }

    @Test
    @DisplayName("lists a failed and a successful sign-in, newest first, through the typed model")
    void listsSignInEventsNewestFirst() {
        AdminListUserAuthEventsResponse response = cognito.adminListUserAuthEvents(b -> b
                .userPoolId(plusPoolId)
                .username(username));

        List<AuthEventType> events = response.authEvents();
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.eventType()).isEqualTo(EventType.SIGN_IN);
            assertThat(event.eventId()).isNotBlank();
            assertThat(event.creationDate()).isInstanceOf(Instant.class).isNotNull();
            assertThat(event.eventRisk()).isNull();
        });

        AuthEventType newest = events.get(0);
        AuthEventType oldest = events.get(1);
        assertThat(newest.eventResponse()).isEqualTo(EventResponseType.PASS);
        assertThat(oldest.eventResponse()).isEqualTo(EventResponseType.FAIL);
        assertThat(newest.creationDate()).isAfterOrEqualTo(oldest.creationDate());

        assertThat(newest.challengeResponses()).anySatisfy(c -> {
            assertThat(c.challengeName()).isEqualTo(ChallengeName.PASSWORD);
            assertThat(c.challengeResponse()).isEqualTo(ChallengeResponse.SUCCESS);
        });
        assertThat(oldest.challengeResponses()).anySatisfy(c -> {
            assertThat(c.challengeName()).isEqualTo(ChallengeName.PASSWORD);
            assertThat(c.challengeResponse()).isEqualTo(ChallengeResponse.FAILURE);
        });
    }

    @Test
    @DisplayName("maxResults pages with a nextToken that fetches the next event")
    void paginatesWithNextToken() {
        AdminListUserAuthEventsResponse first = cognito.adminListUserAuthEvents(b -> b
                .userPoolId(plusPoolId)
                .username(username)
                .maxResults(1));
        assertThat(first.authEvents()).hasSize(1);
        assertThat(first.authEvents().get(0).eventResponse()).isEqualTo(EventResponseType.PASS);
        assertThat(first.nextToken()).isNotBlank();

        AdminListUserAuthEventsResponse second = cognito.adminListUserAuthEvents(b -> b
                .userPoolId(plusPoolId)
                .username(username)
                .maxResults(1)
                .nextToken(first.nextToken()));
        assertThat(second.authEvents()).hasSize(1);
        assertThat(second.authEvents().get(0).eventResponse()).isEqualTo(EventResponseType.FAIL);
        assertThat(second.authEvents().get(0).eventId())
                .isNotEqualTo(first.authEvents().get(0).eventId());
    }

    @Test
    @DisplayName("a default (Essentials) pool is UserPoolAddOnNotEnabledException")
    void essentialsPoolIsNotEnabled() {
        assertThatThrownBy(() -> cognito.adminListUserAuthEvents(b -> b
                .userPoolId(essentialsPoolId)
                .username(username)))
                .isInstanceOf(UserPoolAddOnNotEnabledException.class);
    }

    private static void deletePoolQuietly(String userPoolId) {
        if (userPoolId == null) {
            return;
        }
        try {
            cognito.deleteUserPool(b -> b.userPoolId(userPoolId));
        } catch (Exception ignored) {
            // Cleanup only. A failed delete must not hide the test result.
        }
    }
}
