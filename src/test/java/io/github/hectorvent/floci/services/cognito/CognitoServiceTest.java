package io.github.hectorvent.floci.services.cognito;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.ReservedTags;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.cognito.model.CognitoGroup;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.IdentityProvider;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.model.UserPoolClient;
import io.github.hectorvent.floci.services.cognito.model.UserPoolDomain;
import io.github.hectorvent.floci.services.cognito.verification.CognitoMessageDispatcher;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCode;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class CognitoServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CognitoService service;
    private InMemoryStorage<String, CognitoUser> userStore;
    private InMemoryStorage<String, CognitoGroup> groupStore;
    private RegionResolver regionResolver;

    @BeforeEach
    void setUp() {
        userStore = new InMemoryStorage<>();
        groupStore = new InMemoryStorage<>();
        regionResolver = new RegionResolver("us-east-1", "000000000000");
        service = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                userStore,
                groupStore,
                new InMemoryStorage<>(), // revokedTokenStore
                "http://localhost:4566",
                regionResolver,
                null
        );
    }

    private UserPool createPoolAndUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), "TempPass1!");
        service.adminSetUserPassword(pool.getId(), "alice", "Perm1234!", true);
        return pool;
    }

    private UserPool createPoolWithStrictPasswordPolicy() {
        return service.createUserPool(Map.of(
                "PoolName", "StrictPasswordPool",
                "Policies", Map.of(
                        "PasswordPolicy", Map.of(
                                "MinimumLength", 12,
                                "RequireUppercase", true,
                                "RequireLowercase", true,
                                "RequireNumbers", true,
                                "RequireSymbols", true,
                                "PasswordHistorySize", 10,
                                "TemporaryPasswordValidityDays", 2
                        )
                )
        ), "us-east-1");
    }

    @Test
    void createUserPoolWithFullConfig() {
        List<Map<String, Object>> schema = List.of(
                Map.of("Name", "my-attr", "AttributeDataType", "String")
        );
        Map<String, Object> policies = Map.of(
                "PasswordPolicy", Map.of("MinimumLength", 12)
        );

        Map<String, Object> request = new HashMap<>();
        request.put("PoolName", "FullConfigPool");
        request.put("Schema", schema);
        request.put("Policies", policies);
        request.put("UsernameAttributes", List.of("email"));

        UserPool pool = service.createUserPool(request, "us-east-1");

        assertNotNull(pool.getId());
        assertEquals("FullConfigPool", pool.getName());
        assertEquals("arn:aws:cognito-idp:us-east-1:000000000000:userpool/" + pool.getId(), pool.getArn());
        assertEquals(schema, pool.getSchemaAttributes());
        // A supplied PasswordPolicy is normalized with AWS's defaults for the fields left unset
        // (MinimumLength here was explicit; the rest were not), matching what DescribeUserPool
        // returns on real Cognito for a policy submitted this way.
        Map<String, Object> expectedPasswordPolicy = new HashMap<>();
        expectedPasswordPolicy.put("MinimumLength", 12);
        expectedPasswordPolicy.put("RequireUppercase", true);
        expectedPasswordPolicy.put("RequireLowercase", true);
        expectedPasswordPolicy.put("RequireNumbers", true);
        expectedPasswordPolicy.put("RequireSymbols", true);
        expectedPasswordPolicy.put("TemporaryPasswordValidityDays", 7);
        assertEquals(Map.of("PasswordPolicy", expectedPasswordPolicy), pool.getPolicies());
        assertEquals(List.of("email"), pool.getUsernameAttributes());
    }

    @ParameterizedTest
    @CsvSource({
            "Short1!a",
            "lowercase123!",
            "UPPERCASE123!",
            "NoNumbersHere!",
            "NoSymbols1234",
            "NoSymbols 123"
    })
    void signUpRejectsPasswordsThatDoNotMatchTheUserPoolPolicy(String password) {
        UserPool pool = createPoolWithStrictPasswordPolicy();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "strict-client", false, false, List.of(), List.of());

        AwsException exception = assertThrows(AwsException.class, () ->
                service.signUp(client.getClientId(), "alice@example.com", password, Map.of(
                        "email", "alice@example.com",
                        "phone_number", "+4915112345678"
                )));

        assertEquals("InvalidPasswordException", exception.getErrorCode());
    }

    @Test
    void signUpAcceptsAPasswordThatMatchesTheUserPoolPolicy() {
        UserPool pool = createPoolWithStrictPasswordPolicy();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "strict-client", false, false, List.of(), List.of());

        CognitoUser user = service.signUp(
                client.getClientId(),
                "alice@example.com",
                "ValidPassword1!",
                Map.of("email", "alice@example.com", "phone_number", "+4915112345678")
        );

        assertEquals("alice@example.com", user.getUsername());
    }

    @Test
    void passwordHistoryRejectsARecentlyUsedPassword() {
        UserPool pool = createPoolWithStrictPasswordPolicy();
        service.adminCreateUser(
                pool.getId(),
                "alice",
                Map.of("email", "alice@example.com"),
                "InitialPass1!"
        );
        service.adminSetUserPassword(pool.getId(), "alice", "Replacement2!", true);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.adminSetUserPassword(pool.getId(), "alice", "InitialPass1!", true));

        // AWS declares PasswordHistoryPolicyViolationException specifically for password reuse,
        // distinct from InvalidPasswordException for a password that fails the complexity rules.
        assertEquals("PasswordHistoryPolicyViolationException", exception.getErrorCode());
        assertEquals(400, exception.getHttpStatus());
    }

    @Test
    void passwordHistoryCountsTheCurrentPasswordAsOneOfN() {
        // PasswordHistorySize: 1 blocks the current password and nothing else — AWS counts the
        // current password as one of the n, not an extra entry on top of n stored ones.
        UserPool pool = service.createUserPool(Map.of(
                "PoolName", "HistorySizeOnePool",
                "Policies", Map.of("PasswordPolicy", Map.of("PasswordHistorySize", 1))
        ), "us-east-1");
        service.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), "PasswordA1!");
        service.adminSetUserPassword(pool.getId(), "alice", "PasswordB1!", true);

        AwsException reuseOfCurrent = assertThrows(AwsException.class, () ->
                service.adminSetUserPassword(pool.getId(), "alice", "PasswordB1!", true));
        assertEquals("PasswordHistoryPolicyViolationException", reuseOfCurrent.getErrorCode());

        // Two changes back, real Cognito allows this at PasswordHistorySize: 1.
        assertDoesNotThrow(() -> service.adminSetUserPassword(pool.getId(), "alice", "PasswordA1!", true));
    }

    @Test
    void adminResetUserPasswordDoesNotBypassPasswordHistory() {
        UserPool pool = createPoolWithStrictPasswordPolicy();
        service.adminCreateUser(
                pool.getId(), "alice", Map.of("email", "alice@example.com"), "InitialPass1!");
        service.adminSetUserPassword(pool.getId(), "alice", "Replacement2!", true);

        // Resetting must archive the outgoing password into history rather than discarding it,
        // or an admin reset becomes a way around PasswordHistorySize.
        service.adminResetUserPassword(pool.getId(), "alice");

        AwsException exception = assertThrows(AwsException.class, () ->
                service.adminSetUserPassword(pool.getId(), "alice", "Replacement2!", true));
        assertEquals("PasswordHistoryPolicyViolationException", exception.getErrorCode());
    }

    @Test
    void adminResetUserPasswordDoesNotShortenTheHistoryWindow() {
        // A reset clears the current password without replacing it, so the freed slot must go
        // to history, not be dropped: with PasswordHistorySize 2, Pass1 -> Pass2 -> Pass3 already
        // ages Pass1 out (only Pass3 + Pass2 are within the window) -- a reset right after must
        // not additionally age Pass2 out just because the current slot is temporarily empty.
        UserPool pool = service.createUserPool(Map.of(
                "PoolName", "ResetHistoryWindowPool",
                "Policies", Map.of("PasswordPolicy", Map.of("PasswordHistorySize", 2))
        ), "us-east-1");
        service.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), "Pass1word!");
        service.adminSetUserPassword(pool.getId(), "alice", "Pass2word!", true);
        service.adminSetUserPassword(pool.getId(), "alice", "Pass3word!", true);

        service.adminResetUserPassword(pool.getId(), "alice");

        // Both passwords still within the window (Pass3 was current, Pass2 was the one prior)
        // must still be blocked immediately after the reset, before any new password is set.
        assertEquals("PasswordHistoryPolicyViolationException", assertThrows(AwsException.class, () ->
                service.adminSetUserPassword(pool.getId(), "alice", "Pass3word!", true)).getErrorCode());
        assertEquals("PasswordHistoryPolicyViolationException", assertThrows(AwsException.class, () ->
                service.adminSetUserPassword(pool.getId(), "alice", "Pass2word!", true)).getErrorCode());

        // Setting a new password re-occupies the current slot, so the window shrinks back to
        // n-1 in history and Pass2 (now two changes back) is free to reuse again.
        service.adminSetUserPassword(pool.getId(), "alice", "Pass4word!", true);
        assertDoesNotThrow(() -> service.adminSetUserPassword(pool.getId(), "alice", "Pass2word!", true));
    }

    @Test
    void createUserPoolDefaultsAnUnsetMinimumLengthToEight() {
        // A policy present but silent on MinimumLength gets AWS's default (8), not policyInt's
        // fallback of 0 for an absent key — and the unset RequireUppercase/Lowercase/Numbers
        // default to enabled too, the same "Cognito defaults" a console-created pool gets.
        UserPool pool = service.createUserPool(Map.of(
                "PoolName", "SymbolsOnlyPool",
                "Policies", Map.of("PasswordPolicy", Map.of("RequireSymbols", true))
        ), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "symbols-only-client", false, false, List.of(), List.of());

        AwsException exception = assertThrows(AwsException.class, () ->
                service.signUp(client.getClientId(), "alice@example.com", "a!", Map.of(
                        "email", "alice@example.com", "phone_number", "+4915112345678")));
        assertEquals("InvalidPasswordException", exception.getErrorCode());

        assertDoesNotThrow(() -> service.signUp(
                client.getClientId(), "bob@example.com", "Eightplus1!", Map.of(
                        "email", "bob@example.com", "phone_number", "+4915112345679")));
    }

    @Test
    void createUserPoolWithOverrideIdUsesProvidedId() {
        UserPool pool = service.createUserPool(
                Map.of(
                        "PoolName", "PinnedPool",
                        "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "us-east-1_testpool1")
                ),
                "us-east-1"
        );

        assertEquals("us-east-1_testpool1", pool.getId());
        assertEquals("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_testpool1", pool.getArn());
    }

    @Test
    void createUserPoolWithOverrideIdStripsReservedTagOnCreate() {
        UserPool pool = service.createUserPool(
                Map.of(
                        "PoolName", "PinnedPool",
                        "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "us-east-1_testpool1", "env", "test")
                ),
                "us-east-1"
        );

        assertEquals(Map.of("env", "test"), pool.getUserPoolTags());
        assertFalse(pool.getUserPoolTags().containsKey(ReservedTags.OVERRIDE_ID_KEY));
    }

    @Test
    void createUserPoolWithDuplicateOverrideIdThrowsResourceConflict() {
        service.createUserPool(
                Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "us-east-1_testpool1")),
                "us-east-1"
        );

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool2", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "us-east-1_testpool1")),
                        "us-east-1"
                )
        );

        assertEquals("ResourceConflictException", exception.getErrorCode());
    }

    // =========================================================================
    // Issue #1306 — CreateUserPoolClient extended configuration
    // =========================================================================

    @Test
    void createUserPoolClientPersistsExtendedConfiguration() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        Map<String, Object> analyticsConfiguration = Map.of(
                "ApplicationId", "d70b2ba36a8c4dc5a04a0451a31a1e12",
                "ExternalId", "my-external-id",
                "RoleArn", "arn:aws:iam::123456789012:role/test-cognitouserpool-role",
                "UserDataShared", true
        );
        Map<String, String> tokenValidityUnits = Map.of(
                "AccessToken", "hours",
                "IdToken", "minutes",
                "RefreshToken", "days"
        );
        Map<String, Object> refreshTokenRotation = Map.of(
                "Feature", "ENABLED",
                "RetryGracePeriodSeconds", 30
        );

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "my-test-app-client",
                true,
                true,
                List.of(" code ", "code"),
                List.of("aws.cognito.signin.user.admin", "openid"),
                analyticsConfiguration,
                List.of("https://example.com", "http://localhost", "myapp://example"),
                "https://example.com",
                List.of("ALLOW_USER_AUTH", "ALLOW_ADMIN_USER_PASSWORD_AUTH", "ALLOW_USER_PASSWORD_AUTH",
                        "ALLOW_REFRESH_TOKEN_AUTH"),
                6,
                6,
                List.of("https://example.com/logout"),
                "ENABLED",
                List.of("email", "address", "preferred_username"),
                6,
                List.of("SignInWithApple", "MySSO"),
                tokenValidityUnits,
                List.of("family_name", "email"),
                refreshTokenRotation,
                true
        );

        assertNotNull(client.getClientId());
        assertEquals(pool.getId(), client.getUserPoolId());
        assertEquals("my-test-app-client", client.getClientName());
        assertTrue(client.isGenerateSecret());
        assertNotNull(client.getClientSecret());
        assertEquals(1, client.getUserPoolClientSecrets().size());
        assertTrue(client.isAllowedOAuthFlowsUserPoolClient());
        assertEquals(List.of("code"), client.getAllowedOAuthFlows());
        assertEquals(List.of("aws.cognito.signin.user.admin", "openid"), client.getAllowedOAuthScopes());
        assertEquals(analyticsConfiguration, client.getAnalyticsConfiguration());
        assertEquals(List.of("https://example.com", "http://localhost", "myapp://example"), client.getCallbackURLs());
        assertEquals("https://example.com", client.getDefaultRedirectURI());
        assertEquals(List.of("ALLOW_USER_AUTH", "ALLOW_ADMIN_USER_PASSWORD_AUTH", "ALLOW_USER_PASSWORD_AUTH",
                "ALLOW_REFRESH_TOKEN_AUTH"), client.getExplicitAuthFlows());
        assertEquals(6, client.getAccessTokenValidity());
        assertEquals(6, client.getIdTokenValidity());
        assertEquals(List.of("https://example.com/logout"), client.getLogoutURLs());
        assertEquals("ENABLED", client.getPreventUserExistenceErrors());
        assertEquals(List.of("email", "address", "preferred_username"), client.getReadAttributes());
        assertEquals(6, client.getRefreshTokenValidity());
        assertEquals(List.of("SignInWithApple", "MySSO"), client.getSupportedIdentityProviders());
        assertEquals(tokenValidityUnits, client.getTokenValidityUnits());
        assertEquals(List.of("family_name", "email"), client.getWriteAttributes());
        assertEquals(refreshTokenRotation, client.getRefreshTokenRotation());
        assertEquals(Boolean.TRUE, client.getEnableTokenRevocation());
    }

    @Test
    void createUserPoolClientGeneratesIdSecretTimestampsAndNormalizesLists() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "basic-client",
                true,
                true,
                List.of(" code ", "code", "implicit", "", "implicit"),
                new ArrayList<>(java.util.Arrays.asList(" openid ", "openid", "email", null, "email")),
                null,
                List.of("https://example.com/callback"),
                "https://example.com/callback",
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null
        );

        assertNotNull(client.getClientId());
        assertEquals(26, client.getClientId().length());
        assertTrue(client.getClientId().chars().allMatch(Character::isLetterOrDigit));

        assertTrue(client.isGenerateSecret());
        assertNotNull(client.getClientSecret());
        assertFalse(client.getClientSecret().isBlank());
        assertEquals(1, client.getUserPoolClientSecrets().size());
        assertEquals(client.getClientSecret(), client.getUserPoolClientSecrets().get(0).getClientSecretValue());

        assertTrue(client.getCreationDate() > 0);
        assertTrue(client.getLastModifiedDate() > 0);
        assertEquals(client.getCreationDate(), client.getLastModifiedDate());

        assertEquals(List.of("code", "implicit"), client.getAllowedOAuthFlows());
        assertEquals(List.of("openid", "email"), client.getAllowedOAuthScopes());
    }

    @Test
    void createUserPoolClientAppliesAwsLikeDefaultsForSupportedIdentityProvidersAndTokenRevocation() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "defaulted-client",
                false,
                false,
                List.of(),
                List.of()
        );

        assertEquals(List.of("COGNITO"), client.getSupportedIdentityProviders());
        assertEquals(Boolean.TRUE, client.getEnableTokenRevocation());
    }

    @Test
    void createUserPoolClientRejectsInvalidTokenValidityConfiguration() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-token-validity-client",
                        false,
                        false,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        -1,
                        0,
                        List.of(),
                        null,
                        List.of(),
                        -7,
                        List.of(),
                        Map.of(
                                "AccessToken", "weeks",
                                "IdToken", "minutes",
                                "RefreshToken", "days"
                        ),
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolClientAcceptsRefreshTokenValidityZeroAndCoercesToDefault() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "refresh-default-client",
                false,
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                0,
                List.of(),
                null,
                List.of(),
                null,
                null
        );

        assertEquals(30, client.getRefreshTokenValidity());
    }

    @Test
    void createUserPoolClientRejectsLogoutUrlsWhenOAuthFlowsUserPoolClientIsFalse() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-logout-client",
                        false,
                        false,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null,
                        List.of("https://example.com/logout"),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolClientRejectsMixedCaseTokenValidityUnits() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-token-unit-client",
                        false,
                        false,
                        List.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        1,
                        1,
                        List.of(),
                        null,
                        List.of(),
                        7,
                        List.of(),
                        Map.of(
                                "AccessToken", "Hours",
                                "IdToken", "minutes",
                                "RefreshToken", "days"
                        ),
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolClientRejectsInconsistentOAuthFlowConfiguration() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-oauth-client",
                        false,
                        false,
                        List.of("code"),
                        List.of("openid"),
                        null,
                        List.of("https://example.com/callback"),
                        "https://example.com/callback",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolClientRejectsDefaultRedirectUriNotInCallbackUrls() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPoolClient(
                        pool.getId(),
                        "invalid-redirect-client",
                        false,
                        true,
                        List.of("code"),
                        List.of("openid"),
                        null,
                        List.of("https://example.com/callback"),
                        "https://different.example.com/callback",
                        List.of(),
                        null,
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(),
                        null,
                        null
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    // Issue #1505: CreateUserPoolClient must not set optional block fields when they were not provided
    @Test
    void createUserPoolClientWithNoOptionalBlocksLeavesThemNull() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "MinimalPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "minimal-client",
                false,
                false,
                List.of(),
                List.of()
        );

        assertNull(client.getAnalyticsConfiguration(), "analyticsConfiguration must be null when not provided");
        assertNull(client.getTokenValidityUnits(), "tokenValidityUnits must be null when not provided");
        assertNull(client.getRefreshTokenRotation(), "refreshTokenRotation must be null when not provided");
    }

    @Test
    void updateUserPoolClientAllowsClearingListFieldsWithEmptyArrays() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "client",
                false,
                true,
                List.of("code"),
                List.of("openid"),
                null,
                List.of("https://example.com"),
                "https://example.com",
                List.of("ALLOW_USER_AUTH"),
                null,
                null,
                List.of("https://example.com/logout"),
                null,
                List.of("email"),
                null,
                List.of("COGNITO", "Google"),
                null,
                List.of("family_name"),
                null,
                null
        );

        service.updateUserPoolClient(
                pool.getId(),
                client.getClientId(),
                null,
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                "",
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null
        );

        UserPoolClient updated = service.describeUserPoolClient(pool.getId(), client.getClientId());
        assertEquals(List.of(), updated.getCallbackURLs());
        assertEquals(List.of(), updated.getExplicitAuthFlows());
        assertEquals(List.of(), updated.getLogoutURLs());
        assertEquals(List.of(), updated.getReadAttributes());
        assertEquals(List.of(), updated.getSupportedIdentityProviders());
        assertEquals(List.of(), updated.getWriteAttributes());
    }

    @Test
    void updateUserPoolClientAcceptsRefreshTokenValidityZeroAndCoercesToDefault() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClientPool"), "us-east-1");

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "client",
                false,
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                7,
                List.of(),
                null,
                List.of(),
                null,
                null
        );

        UserPoolClient updated = service.updateUserPoolClient(
                pool.getId(),
                client.getClientId(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null,
                null
        );

        assertEquals(30, updated.getRefreshTokenValidity());
    }

    @Test
    void createUserPoolWithBlankOverrideIdThrowsValidation() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "   ")),
                        "us-east-1"
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolWithSlashInOverrideThrowsValidation() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "bad/pool")),
                        "us-east-1"
                )
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void createUserPoolWithQuestionMarkOrHashInOverrideThrowsValidation() {
        AwsException questionMarkException = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "bad?pool")),
                        "us-east-1"
                )
        );
        assertEquals("InvalidParameterException", questionMarkException.getErrorCode());

        AwsException hashException = assertThrows(
                AwsException.class,
                () -> service.createUserPool(
                        Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "bad#pool")),
                        "us-east-1"
                )
        );
        assertEquals("InvalidParameterException", hashException.getErrorCode());
    }

    @Test
    void updateUserPoolWithReservedTagStripsIt() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "PinnedPool"), "us-east-1");

        service.updateUserPool(
                Map.of(
                        "UserPoolId", pool.getId(),
                        "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "late-id", "env", "test")
                ),
                "us-east-1"
        );

        UserPool updated = service.describeUserPool(pool.getId());
        assertEquals(Map.of("env", "test"), updated.getUserPoolTags());
    }

    @Test
    void tagResourceAddsAndOverwritesTags() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TaggedPool", "UserPoolTags", Map.of("env", "dev")),
                "us-east-1"
        );

        service.tagResource(pool.getArn(), Map.of("team", "platform", "env", "test"));

        assertEquals(Map.of("env", "test", "team", "platform"), service.listTagsForResource(pool.getArn()));
    }

    @Test
    void tagResourceRejectsReservedKey() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TaggedPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.tagResource(pool.getArn(), Map.of(ReservedTags.OVERRIDE_ID_KEY, "late-id"))
        );

        assertEquals("ValidationException", exception.getErrorCode());
    }

    @Test
    void tagResourceRejectsEmptyTags() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TaggedPool"), "us-east-1");

        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.tagResource(pool.getArn(), Map.of())
        );

        assertEquals("InvalidParameterException", exception.getErrorCode());
    }

    @Test
    void tagResourceWithUnknownArnThrowsNotFound() {
        AwsException exception = assertThrows(
                AwsException.class,
                () -> service.tagResource("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_missing", Map.of("env", "test"))
        );

        assertEquals("ResourceNotFoundException", exception.getErrorCode());
    }

    @Test
    void untagResourceRemovesRequestedKeysAndAllowsReservedRemoval() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TaggedPool", "UserPoolTags", Map.of("env", "test", "team", "platform")),
                "us-east-1"
        );

        service.untagResource(pool.getArn(), List.of("team", ReservedTags.OVERRIDE_ID_KEY));

        assertEquals(Map.of("env", "test"), service.listTagsForResource(pool.getArn()));
    }

    @Test
    void listTagsForResourceReturnsCurrentTags() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TaggedPool", "UserPoolTags", Map.of("env", "test")),
                "us-east-1"
        );

        assertEquals(Map.of("env", "test"), service.listTagsForResource(pool.getArn()));
    }

    @Test
    void updateUserPoolAndTagResourceShareConsistentVisibleTagBehavior() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TaggedPool"), "us-east-1");

        service.updateUserPool(
                Map.of(
                        "UserPoolId", pool.getId(),
                        "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "late-id", "env", "test")
                ),
                "us-east-1"
        );
        service.tagResource(pool.getArn(), Map.of("team", "platform"));

        assertEquals(Map.of("env", "test", "team", "platform"), service.listTagsForResource(pool.getArn()));
    }

    @Test
    void issuerUrlForPinnedPoolResolvesAsBaseUrlSlashPoolId() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "PinnedPool", "UserPoolTags", Map.of(ReservedTags.OVERRIDE_ID_KEY, "custompool")),
                "us-east-1"
        );

        assertEquals("http://localhost:4566/custompool", service.getIssuer(pool.getId()));
    }

    // =========================================================================
    // Groups
    // =========================================================================

    @Test
    void createGroup() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        CognitoGroup group = service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        assertEquals("admins", group.getGroupName());
        assertEquals(pool.getId(), group.getUserPoolId());
        assertEquals("Admin group", group.getDescription());
        assertEquals(1, group.getPrecedence());
        assertNull(group.getRoleArn());
        assertTrue(group.getCreationDate() > 0);
        assertTrue(group.getLastModifiedDate() > 0);
    }

    @Test
    void createGroupDuplicateThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        assertThrows(AwsException.class, () ->
                service.createGroup(pool.getId(), "admins", "Another desc", 2, null));
    }

    @Test
    void getGroup() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        CognitoGroup fetched = service.getGroup(pool.getId(), "admins");
        assertEquals("admins", fetched.getGroupName());
        assertEquals(pool.getId(), fetched.getUserPoolId());
        assertEquals("Admin group", fetched.getDescription());
        assertEquals(1, fetched.getPrecedence());
    }

    @Test
    void getGroupNotFoundThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");

        assertThrows(AwsException.class, () ->
                service.getGroup(pool.getId(), "nonexistent"));
    }

    @Test
    void listGroups() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.createGroup(pool.getId(), "editors", "Editor group", 2, null);

        List<CognitoGroup> groups = service.listGroups(pool.getId());
        assertEquals(2, groups.size());
    }

    @Test
    void deleteGroup() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        service.deleteGroup(pool.getId(), "admins");

        assertThrows(AwsException.class, () ->
                service.getGroup(pool.getId(), "admins"));
    }

    @Test
    void deleteGroupCleansUpUserMembership() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        service.deleteGroup(pool.getId(), "admins");

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        assertTrue(user.getGroupNames().isEmpty());
    }

    @Test
    void adminDeleteUserCleansUpGroupMembership() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        service.adminDeleteUser(pool.getId(), "alice");

        CognitoGroup group = service.getGroup(pool.getId(), "admins");
        assertFalse(group.getUserNames().contains("alice"));
    }

    // =========================================================================
    // Group membership
    // =========================================================================

    @Test
    void adminAddUserToGroup() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        CognitoGroup group = service.getGroup(pool.getId(), "admins");
        assertTrue(group.getUserNames().contains("alice"));

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        assertTrue(user.getGroupNames().contains("admins"));
    }

    @Test
    void adminAddUserToGroupIdempotent() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        service.adminAddUserToGroup(pool.getId(), "admins", "alice");
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        CognitoGroup group = service.getGroup(pool.getId(), "admins");
        assertEquals(1, group.getUserNames().size());
    }

    @Test
    void adminRemoveUserFromGroup() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        service.adminRemoveUserFromGroup(pool.getId(), "admins", "alice");

        CognitoGroup group = service.getGroup(pool.getId(), "admins");
        assertFalse(group.getUserNames().contains("alice"));

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        assertFalse(user.getGroupNames().contains("admins"));
    }

    @Test
    void adminListGroupsForUser() {
        UserPool pool = createPoolAndUser();
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.createGroup(pool.getId(), "editors", "Editor group", 2, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");
        service.adminAddUserToGroup(pool.getId(), "editors", "alice");

        List<CognitoGroup> groups = service.adminListGroupsForUser(pool.getId(), "alice");
        assertEquals(2, groups.size());
    }

    @Test
    void adminAddUserToGroupNonexistentGroupThrows() {
        UserPool pool = createPoolAndUser();

        assertThrows(AwsException.class, () ->
                service.adminAddUserToGroup(pool.getId(), "nonexistent", "alice"));
    }

    @Test
    void adminAddUserToGroupNonexistentUserThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);

        assertThrows(AwsException.class, () ->
                service.adminAddUserToGroup(pool.getId(), "admins", "nonexistent"));
    }

    // =========================================================================
    // Issue #1563 — AdminLinkProviderForUser
    // =========================================================================

    @Test
    void adminLinkProviderForUserAppendsIdentity() {
        UserPool pool = createPoolAndUser();

        service.adminLinkProviderForUser(pool.getId(), "alice", "Google", "google-sub-123");

        JsonNode identities = identitiesOf(pool, "alice");
        assertEquals(1, identities.size());
        JsonNode identity = identities.get(0);
        assertEquals("google-sub-123", identity.get("userId").asText());
        assertEquals("Google", identity.get("providerName").asText());
        assertEquals("Google", identity.get("providerType").asText());
        assertTrue(identity.get("issuer").isNull());
        assertFalse(identity.get("primary").asBoolean());
        assertTrue(identity.get("dateCreated").isNumber(),
                "dateCreated must be a JSON number, not a string");
        assertTrue(identity.get("dateCreated").asLong() > 1_600_000_000_000L,
                "dateCreated must be epoch milliseconds, not seconds");
    }

    @Test
    void adminLinkProviderForUserRejectsMissingSourceFields() {
        UserPool pool = createPoolAndUser();

        AwsException noProvider = assertThrows(AwsException.class,
                () -> service.adminLinkProviderForUser(pool.getId(), "alice", "", "google-sub-123"));
        assertEquals("InvalidParameterException", noProvider.getErrorCode());

        AwsException noUserId = assertThrows(AwsException.class,
                () -> service.adminLinkProviderForUser(pool.getId(), "alice", "Google", ""));
        assertEquals("InvalidParameterException", noUserId.getErrorCode());

        AwsException noDestination = assertThrows(AwsException.class,
                () -> service.adminLinkProviderForUser(pool.getId(), "", "Google", "google-sub-123"));
        assertEquals("InvalidParameterException", noDestination.getErrorCode());

        assertNull(service.adminGetUser(pool.getId(), "alice").getAttributes().get("identities"));
    }

    @Test
    void adminLinkProviderForUserReplacesMalformedIdentities() {
        UserPool pool = createPoolAndUser();
        service.adminUpdateUserAttributes(pool.getId(), "alice", Map.of("identities", "not-json-at-all"));

        service.adminLinkProviderForUser(pool.getId(), "alice", "Google", "google-sub-123");

        JsonNode identities = identitiesOf(pool, "alice");
        assertEquals(1, identities.size());
        assertEquals("google-sub-123", identities.get(0).get("userId").asText());
    }

    @Test
    void adminLinkProviderForUserSecondProviderAppends() {
        UserPool pool = createPoolAndUser();

        service.adminLinkProviderForUser(pool.getId(), "alice", "Google", "google-sub-123");
        service.adminLinkProviderForUser(pool.getId(), "alice", "MyOIDCProvider", "oidc-sub-456");

        JsonNode identities = identitiesOf(pool, "alice");
        assertEquals(2, identities.size());
        assertEquals("Google", identities.get(0).get("providerName").asText());
        assertEquals("MyOIDCProvider", identities.get(1).get("providerName").asText());
        assertTrue(identities.get(1).get("providerType").isNull(),
                "providerType is unknowable without a registered IdP");
    }

    @Test
    void adminLinkProviderForUserDuplicateIdentityThrows() {
        UserPool pool = createPoolAndUser();
        service.adminLinkProviderForUser(pool.getId(), "alice", "Google", "google-sub-123");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminLinkProviderForUser(pool.getId(), "alice", "Google", "google-sub-123"));
        assertEquals("AliasExistsException", ex.getErrorCode());
    }

    @Test
    void adminLinkProviderForUserRelinkToDifferentUserThrows() {
        UserPool pool = createPoolAndUser();
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), "TempPass1!");
        service.adminLinkProviderForUser(pool.getId(), "alice", "Google", "google-sub-123");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminLinkProviderForUser(pool.getId(), "bob", "Google", "google-sub-123"));
        assertEquals("AliasExistsException", ex.getErrorCode());
        assertEquals(1, identitiesOf(pool, "alice").size(), "the existing link must survive");
    }

    @Test
    void adminLinkProviderForUserUnknownUserThrows() {
        UserPool pool = createPoolAndUser();

        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminLinkProviderForUser(pool.getId(), "ghost", "Google", "google-sub-123"));
        assertEquals("UserNotFoundException", ex.getErrorCode());
    }

    @Test
    void adminLinkProviderForUserUnknownPoolThrows() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminLinkProviderForUser("us-east-1_missing", "alice", "Google", "google-sub-123"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    private JsonNode identitiesOf(UserPool pool, String username) {
        String raw = service.adminGetUser(pool.getId(), username).getAttributes().get("identities");
        assertNotNull(raw, "identities attribute should be set");
        try {
            return MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new AssertionError("identities is not valid JSON: " + raw, e);
        }
    }

    // =========================================================================
    // JWT groups claim
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void jwtContainsGroupsClaim() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client", false, false, List.of(), List.of());
        String clientId = client.getClientId();

        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.adminAddUserToGroup(pool.getId(), "admins", "alice");

        Map<String, Object> authResult = service.initiateAuth(
                clientId, "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        Map<String, Object> authenticationResult = (Map<String, Object>) authResult.get("AuthenticationResult");
        String accessToken = (String) authenticationResult.get("AccessToken");

        // Decode the JWT payload (second segment)
        String[] parts = accessToken.split("\\.");
        String payloadJson = new String(
                Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);

        assertTrue(payloadJson.contains("\"cognito:groups\":[\"admins\"]"),
                "JWT payload should contain cognito:groups claim with the group name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwtEscapesSpecialCharsInGroupName() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client", false, false, List.of(), List.of());

        String specialGroup = "group\"with\\special\nchars";
        service.createGroup(pool.getId(), specialGroup, null, null, null);
        service.adminAddUserToGroup(pool.getId(), specialGroup, "alice");

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String token = (String) auth.get("AccessToken");
        String payloadJson = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertTrue(payloadJson.contains("cognito:groups"),
                "JWT should contain cognito:groups claim");
        assertTrue(payloadJson.contains("group\\\"with\\\\special\\nchars"),
                "Group name should be properly JSON-escaped in JWT payload");
    }

    // =========================================================================
    // Issue #68 — sub attribute and AdminUserGlobalSignOut
    // =========================================================================

    @Test
    void adminCreateUserAutoGeneratesSub() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        CognitoUser user = service.adminCreateUser(pool.getId(), "bob",
                Map.of("email", "bob@example.com"), null);

        assertTrue(user.getAttributes().containsKey("sub"),
                "adminCreateUser should auto-generate a sub attribute");
        assertFalse(user.getAttributes().get("sub").isBlank());
    }

    @Test
    void adminCreateUserPreservesExplicitSub() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        String explicitSub = "aaaaaaaa-1111-2222-3333-444444444444";
        CognitoUser user = service.adminCreateUser(pool.getId(), "bob",
                Map.of("email", "bob@example.com", "sub", explicitSub), null);

        assertEquals(explicitSub, user.getAttributes().get("sub"),
                "adminCreateUser should not overwrite an explicitly provided sub");
    }

    @Test
    void adminCreateUserResendRefreshesExistingForceChangePasswordUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        CognitoUser created = service.adminCreateUser(pool.getId(),
                "alice",
                Map.of("email", "alice@example.com"),
                "TempPass1!");

        // Backdate lastModifiedDate so RESEND's refresh is unambiguously observable
        // without relying on wall-clock sleep (lastModifiedDate has 1s precision).
        long backdated = (System.currentTimeMillis() / 1000L) - 60;
        created.setLastModifiedDate(backdated);

        CognitoUser resent = service.adminCreateUser(pool.getId(), "alice",
                Map.of("email", "alice@example.com"), null, "RESEND");

        assertEquals(created.getAttributes().get("sub"), resent.getAttributes().get("sub"),
                "RESEND must not recreate the user");
        assertEquals("FORCE_CHANGE_PASSWORD", resent.getUserStatus());
        assertTrue(resent.getLastModifiedDate() > backdated,
                "RESEND should refresh lastModifiedDate");
    }

    @Test
    void adminCreateUserResendThrowsUserNotFoundForMissingUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminCreateUser(pool.getId(), "ghost",
                        Map.of("email", "g@example.com"), null, "RESEND"));
        assertEquals("UserNotFoundException", ex.getErrorCode());
    }

    @Test
    void adminCreateUserResendThrowsUnsupportedStateForConfirmedUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), "TempPass1!");
        service.adminSetUserPassword(pool.getId(), "bob", "Permanent1!", true);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminCreateUser(pool.getId(), "bob",
                        Map.of("email", "bob@example.com"), null, "RESEND"));
        assertEquals("UnsupportedUserStateException", ex.getErrorCode());
    }

    @Test
    void signUpAutoGeneratesSub() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        CognitoUser user = service.signUp(client.getClientId(),
                "carol", "Pass1234!", Map.of("email", "carol@example.com"));

        assertTrue(user.getAttributes().containsKey("sub"),
                "signUp should auto-generate a sub attribute");
        assertFalse(user.getAttributes().get("sub").isBlank());
    }

    @Test
    void signUpWithoutDeliveryTargetFailsBeforePersistingUser() {
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        CognitoMessageDispatcher messageDispatcher = mock(CognitoMessageDispatcher.class);
        CognitoService serviceWithVerification = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "http://localhost:4566",
                regionResolver,
                null,
                verificationCodeService,
                messageDispatcher
        );

        UserPool pool = serviceWithVerification.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        pool.setAutoVerifiedAttributes(List.of("email"));
        UserPoolClient client = serviceWithVerification.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                serviceWithVerification.signUp(client.getClientId(), "carol", "Pass1234!", Map.of()));
        assertEquals("InvalidParameterException", ex.getErrorCode());

        AwsException lookupEx = assertThrows(AwsException.class, () ->
                serviceWithVerification.adminGetUser(pool.getId(), "carol"));
        assertEquals("UserNotFoundException", lookupEx.getErrorCode());
    }

    @Test
    void signUpRollsBackUserWhenDispatchFails() {
        VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
        CognitoMessageDispatcher messageDispatcher = mock(CognitoMessageDispatcher.class);
        when(verificationCodeService.issue(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), any()))
                .thenReturn("123456");
        doThrow(new RuntimeException("SES unavailable")).when(messageDispatcher)
                .dispatch(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), eq("123456"), any());

        CognitoService serviceWithVerification = new CognitoService(
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                new InMemoryStorage<>(),
                "http://localhost:4566",
                regionResolver,
                null,
                verificationCodeService,
                messageDispatcher
        );

        UserPool pool = serviceWithVerification.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        pool.setAutoVerifiedAttributes(List.of("email"));
        UserPoolClient client = serviceWithVerification.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                serviceWithVerification.signUp(client.getClientId(), "carol", "Pass1234!",
                        Map.of("email", "carol@example.com")));
        assertEquals("CodeDeliveryFailureException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
        assertEquals("Failed to deliver the message.", ex.getMessage());

        AwsException lookupEx = assertThrows(AwsException.class, () ->
                serviceWithVerification.adminGetUser(pool.getId(), "carol"));
        assertEquals("UserNotFoundException", lookupEx.getErrorCode());
        verify(verificationCodeService).invalidatePrevious(pool.getId(), "carol",
                VerificationCode.Purpose.SIGNUP_CONFIRMATION);
    }

    @Test
    void signUpReturnsResourceNotFoundAsBadRequestWhenClientMissing() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.signUp("missing-client", "carol", "Pass1234!", Map.of("email", "carol@example.com")));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void confirmSignUpReturnsResourceNotFoundAsBadRequestWhenClientMissing() {
        AwsException ex = assertThrows(AwsException.class, () ->
                service.confirmSignUp("missing-client", "carol", "123456"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void confirmSignUpNamesDeletedUserPoolInResourceNotFoundMessage() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "test-client", false, false, List.of(), List.of());
        service.deleteUserPool(pool.getId());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.confirmSignUp(client.getClientId(), "carol", "123456"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals("User pool " + pool.getId() + " does not exist.", ex.getMessage());
        assertEquals(400, ex.getHttpStatus());
    }

    @ParameterizedTest
    @CsvSource({"email_verified", "phone_number_verified"})
    @SuppressWarnings("unchecked")
    void updateUserAttributesRejectsSelfManagedVerificationStatusWithoutPartialPersistence(
            String verificationStatusAttribute) {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "verification-status-client", false, false, List.of(), List.of());
        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        String accessToken = (String) ((Map<String, Object>) authResult.get("AuthenticationResult"))
                .get("AccessToken");

        AwsException error = assertThrows(AwsException.class,
                () -> service.updateUserAttributes(accessToken, Map.of(
                        verificationStatusAttribute, "true",
                        "name", "must-not-be-persisted")));

        assertEquals("InvalidParameterException", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        CognitoUser unchanged = service.adminGetUser(pool.getId(), "alice");
        assertFalse(unchanged.getAttributes().containsKey(verificationStatusAttribute));
        assertFalse(unchanged.getAttributes().containsKey("name"));

        service.adminUpdateUserAttributes(
                pool.getId(), "alice", Map.of(verificationStatusAttribute, "true"));
        assertEquals("true", service.adminGetUser(pool.getId(), "alice")
                .getAttributes().get(verificationStatusAttribute));
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwtSubMatchesStoredSubAttribute() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        String storedSub = service.adminGetUser(pool.getId(), "alice")
                .getAttributes().get("sub");
        assertNotNull(storedSub, "user should have a sub attribute after creation");

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));

        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String token = (String) auth.get("AccessToken");
        String payloadJson = new String(
                Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);

        assertTrue(payloadJson.contains("\"sub\":\"" + storedSub + "\""),
                "JWT sub claim must match the stored sub attribute, not be randomly generated");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jwtSubIsConsistentAcrossMultipleLogins() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "test-client",
                false, false, List.of(), List.of());

        Function<String, String> extractSub = token -> {
            String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
            int start = payload.indexOf("\"sub\":\"") + 7;
            int end = payload.indexOf("\"", start);
            return payload.substring(start, end);
        };

        Map<String, Object> auth1 = (Map<String, Object>)
                ((Map<String, Object>) service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"))).get("AuthenticationResult");
        Map<String, Object> auth2 = (Map<String, Object>)
                ((Map<String, Object>) service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"))).get("AuthenticationResult");

        String sub1 = extractSub.apply((String) auth1.get("AccessToken"));
        String sub2 = extractSub.apply((String) auth2.get("AccessToken"));

        assertEquals(sub1, sub2, "JWT sub claim must be identical across multiple logins");
    }

    @Test
    void adminUserGlobalSignOutSucceedsForExistingUser() {
        UserPool pool = createPoolAndUser();
        assertDoesNotThrow(() -> service.adminUserGlobalSignOut(pool.getId(), "alice"));
    }

    @Test
    void adminUserGlobalSignOutThrowsForNonexistentUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        assertThrows(AwsException.class,
                () -> service.adminUserGlobalSignOut(pool.getId(), "ghost"));
    }

    // =========================================================================
    // Issue #229 — password verification
    // =========================================================================

    @Test
    void initiateAuthRejectsAnyPasswordWhenNoHashSet() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "bob", "PASSWORD", "anything")));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void initiateAuthWorksAfterPasswordIsSet() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);
        service.adminSetUserPassword(pool.getId(), "bob", "Perm1!", true);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "bob", "PASSWORD", "Perm1!"));
        assertNotNull(((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken"));
    }

    // =========================================================================
    // Issue #235 — AdminSetUserPassword(Permanent=false) changes the password
    // =========================================================================

    @Test
    void adminSetUserPasswordPermanentFalseChangesPassword() {
        UserPool pool = createPoolAndUser(); // alice has permanent "Perm1234!"
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        service.adminSetUserPassword(pool.getId(), "alice", "NewTemp1!", false);

        // Old password now rejected
        assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));

        // New temp password triggers NEW_PASSWORD_REQUIRED challenge
        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "NewTemp1!"));
        assertEquals("NEW_PASSWORD_REQUIRED", result.get("ChallengeName"));
    }

    // =========================================================================
    // USER_SRP_AUTH flow
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void initiateAuthWithUserSrpAuthFlow() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        String password = "Password123!";
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);
        service.adminSetUserPassword(pool.getId(), "bob", password, true);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> initResult = service.initiateAuth(client.getClientId(), "USER_SRP_AUTH",
                Map.of("USERNAME", "bob", "SRP_A", "ABCDEF1234567890"));

        assertEquals("PASSWORD_VERIFIER", initResult.get("ChallengeName"));
        assertNotNull(initResult.get("Session"));
        Map<String, String> params = (Map<String, String>) initResult.get("ChallengeParameters");
        assertNotNull(params.get("SALT"));
        assertNotNull(params.get("SRP_B"));
        assertNotNull(params.get("SECRET_BLOCK"));
        assertEquals("bob", params.get("USER_ID_FOR_SRP"));
        // Real AWS Cognito returns USERNAME alongside USER_ID_FOR_SRP; the .NET
        // Amazon.Extensions.CognitoAuthentication SRP client requires it (issue #1305).
        assertEquals("bob", params.get("USERNAME"));
    }

    @Test
    void initiateAuthWithUserSrpAuthRejectsUnconfirmedUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());
        service.signUp(client.getClientId(), "bob", "Password123!", Map.of("email", "bob@example.com"));

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_SRP_AUTH",
                        Map.of("USERNAME", "bob", "SRP_A", "ABCDEF1234567890")));

        assertEquals("UserNotConfirmedException", ex.getErrorCode());
    }

    @Test
    void respondToAuthChallengeWithUserSrpAuthRejectsNewlyUnconfirmedUser() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        String password = "Password123!";
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);
        service.adminSetUserPassword(pool.getId(), "bob", password, true);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> initResult = service.initiateAuth(client.getClientId(), "USER_SRP_AUTH",
                Map.of("USERNAME", "bob", "SRP_A", "ABCDEF1234567890"));
        String session = (String) initResult.get("Session");
        // Simulate the user becoming unconfirmed after the SRP session is created.
        CognitoUser user = service.adminGetUser(pool.getId(), "bob");
        user.setUserStatus("UNCONFIRMED");
        userStore.put(pool.getId() + "::" + user.getUsername(), user);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.respondToAuthChallenge(client.getClientId(), "PASSWORD_VERIFIER", session,
                        Map.of(
                                "USERNAME", "bob",
                                "PASSWORD_CLAIM_SIGNATURE", "invalid-sig",
                                "TIMESTAMP", "Wed Apr 8 12:00:00 UTC 2026"
                        )));

        assertEquals("UserNotConfirmedException", ex.getErrorCode());
    }

    @Test
    void respondToAuthChallengeWithInvalidSrpSignatureRejects() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        String password = "Password123!";
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);
        service.adminSetUserPassword(pool.getId(), "bob", password, true);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> initResult = service.initiateAuth(client.getClientId(), "USER_SRP_AUTH",
                Map.of("USERNAME", "bob", "SRP_A", "ABCDEF1234567890"));
        String session = (String) initResult.get("Session");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.respondToAuthChallenge(client.getClientId(), "PASSWORD_VERIFIER", session,
                        Map.of(
                                "USERNAME", "bob",
                                "PASSWORD_CLAIM_SIGNATURE", "invalid-sig",
                                "TIMESTAMP", "Wed Apr 8 12:00:00 UTC 2026"
                        )));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
    }

    // =========================================================================
    // Issue #228 — AccessToken contains client_id claim
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void accessTokenContainsClientId() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String accessToken = (String) auth.get("AccessToken");

        String payloadJson = new String(Base64.getUrlDecoder().decode(accessToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertTrue(payloadJson.contains("\"client_id\":\"" + client.getClientId() + "\""),
                "AccessToken should contain client_id claim matching the requesting client");
    }

    @Test
    @SuppressWarnings("unchecked")
    void idTokenDoesNotContainClientId() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String idToken = (String) auth.get("IdToken");

        String payloadJson = new String(Base64.getUrlDecoder().decode(idToken.split("\\.")[1]),
                StandardCharsets.UTF_8);
        assertFalse(payloadJson.contains("\"client_id\""),
                "IdToken should not contain client_id claim");
    }

    // =========================================================================
    // AdminGetUser resolves configured identifiers
    // =========================================================================

    @Test
    void adminGetUserBySubUuid() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);

        String sub = service.adminGetUser(pool.getId(), "bob").getAttributes().get("sub");
        assertNotNull(sub);

        CognitoUser found = service.adminGetUser(pool.getId(), sub);
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserByEmailAlias() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("email")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob",
                Map.of("email", "bob@example.com", "email_verified", "true"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "bob@example.com");
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserByPhoneNumberAlias() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("phone_number")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob",
                Map.of("phone_number", "+15551234567", "phone_number_verified", "true"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "+15551234567");
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserByPreferredUsernameAlias() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("preferred_username")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob", Map.of("preferred_username", "bobby"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "bobby");
        assertEquals("bob", found.getUsername());
    }

    @Test
    void adminGetUserByEmailUsernameAttribute() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "UsernameAttributes", List.of("email")),
                "us-east-1"
        );
        // UsernameAttributes=email pools mint a UUID username; the supplied email is the alias.
        CognitoUser created = service.adminCreateUser(pool.getId(), "bob@example.com",
                Map.of("email", "bob@example.com"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "bob@example.com");
        assertEquals(created.getUsername(), found.getUsername());
        assertEquals(found.getAttributes().get("sub"), found.getUsername());
    }

    @Test
    void adminGetUserByPhoneNumberUsernameAttribute() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "UsernameAttributes", List.of("phone_number")),
                "us-east-1"
        );
        CognitoUser created = service.adminCreateUser(pool.getId(), "+15551234567",
                Map.of("phone_number", "+15551234567"), null);

        CognitoUser found = service.adminGetUser(pool.getId(), "+15551234567");
        assertEquals(created.getUsername(), found.getUsername());
        assertEquals(found.getAttributes().get("sub"), found.getUsername());
    }

    @Test
    void adminGetUserRejectsEmailWithoutConfiguredAlias() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "bob@example.com"));
        assertEquals("UserNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void adminGetUserRejectsUnverifiedEmailAlias() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("email")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "bob@example.com"));
        assertEquals("UserNotFoundException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void adminGetUserRejectsUnknownPoolWithResourceNotFound() {
        String missingPoolId = "us-east-1_missing";

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(missingPoolId, "bob"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
        assertEquals("User pool " + missingPoolId + " does not exist.", ex.getMessage());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void adminGetUserRejectsAmbiguousLookupValueCreatedViaAdminCreateUser() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("email")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "shared-lookup", Map.of("email", "owner@example.com"), null);
        service.adminCreateUser(pool.getId(), "alice",
                Map.of("email", "shared-lookup", "email_verified", "true"), null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "shared-lookup"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void adminGetUserRejectsAmbiguousLookupValueCreatedViaAdminUpdateUserAttributes() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "TestPool", "AliasAttributes", List.of("email")),
                "us-east-1"
        );
        service.adminCreateUser(pool.getId(), "shared-lookup", Map.of("email", "owner@example.com"), null);
        service.adminCreateUser(pool.getId(), "alice", Map.of("email", "alice@example.com"), null);
        service.adminUpdateUserAttributes(pool.getId(), "alice",
                Map.of("email", "shared-lookup", "email_verified", "true"));

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "shared-lookup"));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    // =========================================================================
    // Issue #233 — listUsers Filter
    // =========================================================================

    @Test
    void listUsersNoFilterReturnsAll() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "user1@example.com"), null);
        service.adminCreateUser(pool.getId(), "user2", Map.of("email", "user2@example.com"), null);

        assertEquals(2, service.listUsers(pool.getId(), null).size());
    }

    @Test
    void listUsersFilterBySubExactMatch() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "user1@example.com"), null);
        service.adminCreateUser(pool.getId(), "user2", Map.of("email", "user2@example.com"), null);

        String sub2 = service.adminGetUser(pool.getId(), "user2").getAttributes().get("sub");
        List<CognitoUser> result = service.listUsers(pool.getId(), "sub = \"" + sub2 + "\"");

        assertEquals(1, result.size());
        assertEquals("user2", result.get(0).getUsername());
    }

    @Test
    void listUsersFilterByEmailExactMatch() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "user1@example.com"), null);
        service.adminCreateUser(pool.getId(), "user2", Map.of("email", "user2@example.com"), null);

        List<CognitoUser> result = service.listUsers(pool.getId(), "email = \"user1@example.com\"");
        assertEquals(1, result.size());
        assertEquals("user1", result.get(0).getUsername());
    }

    @Test
    void listUsersFilterByEmailPrefix() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "alice@example.com"), null);
        service.adminCreateUser(pool.getId(), "user2", Map.of("email", "bob@example.com"), null);
        service.adminCreateUser(pool.getId(), "user3", Map.of("email", "alice2@example.com"), null);

        List<CognitoUser> result = service.listUsers(pool.getId(), "email ^= \"alice\"");
        assertEquals(2, result.size());
    }

    @Test
    void listUsersFilterNoMatchReturnsEmpty() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "user1", Map.of("email", "user1@example.com"), null);

        List<CognitoUser> result = service.listUsers(pool.getId(), "email = \"nobody@example.com\"");
        assertTrue(result.isEmpty());
    }

    // =========================================================================
    // Issue #2952 - listUsers/listUserPoolClients against an absent user pool
    // =========================================================================

    @Test
    void listUsersAgainstAnAbsentUserPoolFails() {
        // listUsers scanned by a "{poolId}::" prefix without ever touching poolStore, so an
        // absent pool was indistinguishable from an empty one. list-groups and
        // list-resource-servers already resolve the pool and are correct; this brought
        // list-users in line with them.
        AwsException ex = assertThrows(AwsException.class,
                () -> service.listUsers("us-east-1_nonexistent", null));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void listUserPoolClientsAgainstAnAbsentUserPoolFails() {
        AwsException ex = assertThrows(AwsException.class,
                () -> service.listUserPoolClients("us-east-1_nonexistent"));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    void listUsersAgainstAnExistingEmptyPoolStillReturnsEmpty() {
        // The existence check must not turn a real, merely-empty pool into an error.
        UserPool pool = service.createUserPool(Map.of("PoolName", "EmptyPool"), "us-east-1");

        assertTrue(service.listUsers(pool.getId(), null).isEmpty());
    }

    @Test
    void listUserPoolClientsAgainstAnExistingEmptyPoolStillReturnsEmpty() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "EmptyPool"), "us-east-1");

        assertTrue(service.listUserPoolClients(pool.getId()).isEmpty());
    }

    /** Signs a hand-crafted {@code poolId|username|clientId|issuedAt|nonce} payload the same way buildRefreshToken does. */
    private static String signRawRefreshToken(UserPool pool, String raw) {
        String signature = CognitoService.hmacSha256(CognitoService.refreshTokenSecretBytes(pool), raw);
        return Base64.getEncoder().withoutPadding()
                .encodeToString((raw + "|" + signature).getBytes(StandardCharsets.UTF_8));
    }

    // =========================================================================
    // Issue #234 — GetTokensFromRefreshToken
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void refreshTokenIsStructuredAndDecodable() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        Map<String, Object> auth = (Map<String, Object>) authResult.get("AuthenticationResult");
        String refreshToken = (String) auth.get("RefreshToken");

        assertNotNull(refreshToken);
        // Should be parseable as base64 structured token: 5 payload fields + trailing HMAC signature
        String decoded = new String(Base64.getDecoder().decode(refreshToken), StandardCharsets.UTF_8);
        String[] parts = decoded.split("\\|", 6);
        assertEquals(6, parts.length, "Refresh token should encode 5 pipe-separated fields plus a signature");
        assertEquals(pool.getId(), parts[0]);
        assertEquals("alice", parts[1]);
        assertEquals(client.getClientId(), parts[2]);
        assertFalse(parts[3].isBlank(), "Refresh token should encode its issued-at timestamp");
        assertFalse(parts[5].isBlank(), "Refresh token should encode a signature");
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTokensFromRefreshTokenReturnsNewAccessAndIdTokens() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        String refreshToken = (String) ((Map<String, Object>) authResult.get("AuthenticationResult")).get("RefreshToken");

        Map<String, Object> refreshResult = service.getTokensFromRefreshToken(client.getClientId(), refreshToken);
        Map<String, Object> refreshAuth = (Map<String, Object>) refreshResult.get("AuthenticationResult");

        assertNotNull(refreshAuth.get("AccessToken"), "Should return a new AccessToken");
        assertNotNull(refreshAuth.get("IdToken"), "Should return a new IdToken");
        assertNull(refreshAuth.get("RefreshToken"), "GetTokensFromRefreshToken should not return a new RefreshToken");
    }

    @Test
    void getTokensFromRefreshTokenInvalidTokenThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        assertThrows(AwsException.class, () ->
                service.getTokensFromRefreshToken(client.getClientId(), "not-a-valid-refresh-token"));
    }

    // =========================================================================
    // Issue #1306 — Refresh token expiry respects client token validity
    // =========================================================================

    @Test
    void getTokensFromRefreshTokenExpiredTokenThrows() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "c",
                false,
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                1,
                List.of(),
                Map.of("RefreshToken", "seconds"),
                List.of(),
                null,
                null
        );

        long issuedAt = (System.currentTimeMillis() / 1000L) - 5;
        String raw = pool.getId() + "|alice|" + client.getClientId() + "|" + issuedAt + "|" + java.util.UUID.randomUUID();
        String expiredRefreshToken = signRawRefreshToken(pool, raw);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getTokensFromRefreshToken(client.getClientId(), expiredRefreshToken));
        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    @Test
    void refreshTokenAuthFlowReturnsNewTokens() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        @SuppressWarnings("unchecked")
        Map<String, Object> firstAuth = (Map<String, Object>) service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")).get("AuthenticationResult");
        String refreshToken = (String) firstAuth.get("RefreshToken");

        @SuppressWarnings("unchecked")
        Map<String, Object> refreshed = (Map<String, Object>) service.initiateAuth(
                client.getClientId(), "REFRESH_TOKEN_AUTH",
                Map.of("REFRESH_TOKEN", refreshToken)).get("AuthenticationResult");

        assertNotNull(refreshed.get("AccessToken"));
        assertNotNull(refreshed.get("IdToken"));
    }

    @Test
    void refreshTokenAuthFlowExpiredTokenThrows() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "c",
                false,
                false,
                List.of(),
                List.of(),
                null,
                List.of(),
                null,
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
                1,
                List.of(),
                Map.of("RefreshToken", "seconds"),
                List.of(),
                null,
                null
        );

        // issued-at is epoch MILLISECONDS, exactly as buildRefreshToken writes it. A token
        // issued 10s ago against a 1s refresh lifetime is expired only if isRefreshTokenExpired
        // converts millis to seconds before comparing — before the fix the InitiateAuth
        // REFRESH_TOKEN_AUTH path never called the check at all, so this minted fresh tokens.
        long issuedAtMillis = System.currentTimeMillis() - 10_000L;
        String raw = pool.getId() + "|alice|" + client.getClientId() + "|" + issuedAtMillis + "|"
                + java.util.UUID.randomUUID();
        String expiredRefreshToken = signRawRefreshToken(pool, raw);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "REFRESH_TOKEN_AUTH",
                        Map.of("REFRESH_TOKEN", expiredRefreshToken)));
        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void refreshTokenAuthFlowRejectsTokenFromAnotherPool() {
        // Two independent pools, each with an "alice". A refresh token minted for poolB is
        // validly HMAC-signed (by poolB) and its username resolves in poolA too, so the only
        // thing that can reject it against poolA's client is the embedded pool-id check.
        UserPool poolA = createPoolAndUser();
        UserPool poolB = createPoolAndUser();
        UserPoolClient clientA = service.createUserPoolClient(poolA.getId(), "ca", false, false, List.of(), List.of());
        UserPoolClient clientB = service.createUserPoolClient(poolB.getId(), "cb", false, false, List.of(), List.of());

        Map<String, Object> authB = (Map<String, Object>) service.initiateAuth(
                clientB.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")).get("AuthenticationResult");
        String foreignRefreshToken = (String) authB.get("RefreshToken");

        AwsException exception = assertThrows(AwsException.class, () ->
                service.initiateAuth(clientA.getClientId(), "REFRESH_TOKEN_AUTH",
                        Map.of("REFRESH_TOKEN", foreignRefreshToken)));
        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    // =========================================================================
    // Issue #2137 — Refresh tokens must be unforgeable (HMAC-signed)
    // =========================================================================

    @Test
    void getTokensFromRefreshTokenRejectsUnsignedForgedToken() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        // Attacker who knows the pool id, client id, and a valid username, but not the pool's secret.
        String raw = pool.getId() + "|alice|" + client.getClientId() + "|"
                + System.currentTimeMillis() + "|" + java.util.UUID.randomUUID();
        String forgedToken = Base64.getEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getTokensFromRefreshToken(client.getClientId(), forgedToken));
        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTokensFromRefreshTokenRejectsTamperedUsername() {
        UserPool pool = createPoolAndUser();
        service.adminCreateUser(pool.getId(), "mallory", Map.of("email", "mallory@example.com"), "TempPass1!");
        service.adminSetUserPassword(pool.getId(), "mallory", "Perm1234!", true);
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "mallory", "PASSWORD", "Perm1234!"));
        String refreshToken = (String) ((Map<String, Object>) authResult.get("AuthenticationResult")).get("RefreshToken");

        // Swap the username in the decoded payload post-signing; the HMAC no longer matches.
        String decoded = new String(Base64.getDecoder().decode(refreshToken), StandardCharsets.UTF_8);
        String tampered = decoded.replaceFirst("\\|mallory\\|", "|alice|");
        String tamperedToken = Base64.getEncoder().withoutPadding().encodeToString(tampered.getBytes(StandardCharsets.UTF_8));

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getTokensFromRefreshToken(client.getClientId(), tamperedToken));
        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getTokensFromRefreshTokenRejectsSwappedPoolId() {
        UserPool poolA = createPoolAndUser();
        UserPool poolB = service.createUserPool(Map.of("PoolName", "OtherPool"), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(poolA.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> authResult = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        String refreshToken = (String) ((Map<String, Object>) authResult.get("AuthenticationResult")).get("RefreshToken");

        // Swap the embedded pool id for a different real pool; the signature was computed over poolA's id.
        String decoded = new String(Base64.getDecoder().decode(refreshToken), StandardCharsets.UTF_8);
        String tampered = decoded.replaceFirst("^" + poolA.getId() + "\\|", poolB.getId() + "|");
        String tamperedToken = Base64.getEncoder().withoutPadding().encodeToString(tampered.getBytes(StandardCharsets.UTF_8));

        AwsException exception = assertThrows(AwsException.class, () ->
                service.getTokensFromRefreshToken(client.getClientId(), tamperedToken));
        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    @Test
    void refreshTokenAuthRejectsForgedTokenInsteadOfIssuingUnknownUserTokens() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        // A garbage/forged REFRESH_TOKEN must not fall through to minting real, signed
        // tokens for a hardcoded "unknown" user via InitiateAuth REFRESH_TOKEN_AUTH.
        AwsException exception = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "REFRESH_TOKEN_AUTH",
                        Map.of("REFRESH_TOKEN", "not-a-valid-refresh-token")));
        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    @Test
    void refreshTokenAuthRejectsNonNumericIssuedAt() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(pool.getId(), "c", false, false, List.of(), List.of());

        // Validly signed, but with a non-numeric issued-at field, must not throw an
        // uncaught NumberFormatException — it should be rejected as an invalid token.
        String raw = pool.getId() + "|alice|" + client.getClientId() + "|not-a-number|" + java.util.UUID.randomUUID();
        String malformedToken = signRawRefreshToken(pool, raw);

        AwsException exception = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "REFRESH_TOKEN_AUTH",
                        Map.of("REFRESH_TOKEN", malformedToken)));
        assertEquals("NotAuthorizedException", exception.getErrorCode());
    }

    // =========================================================================
    // deleteUserPool cascades groups
    // =========================================================================

    @Test
    void deleteUserPoolCascadesGroups() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.createGroup(pool.getId(), "admins", "Admin group", 1, null);
        service.createGroup(pool.getId(), "editors", "Editor group", 2, null);

        String prefix = pool.getId() + "::";
        assertEquals(2, groupStore.scan(k -> k.startsWith(prefix)).size());

        service.deleteUserPool(pool.getId());

        assertEquals(0, groupStore.scan(k -> k.startsWith(prefix)).size());
    }

    // =========================================================================
    // Issue #433 — AdminEnableUser / AdminDisableUser
    // =========================================================================

    @Test
    void adminDisableUserSetsEnabledFalse() {
        UserPool pool = createPoolAndUser();

        CognitoUser before = service.adminGetUser(pool.getId(), "alice");
        assertTrue(before.isEnabled(), "User should be enabled by default");

        service.adminDisableUser(pool.getId(), "alice");

        CognitoUser after = service.adminGetUser(pool.getId(), "alice");
        assertFalse(after.isEnabled(), "User should be disabled after adminDisableUser");
    }

    @Test
    void adminEnableUserSetsEnabledTrue() {
        UserPool pool = createPoolAndUser();
        service.adminDisableUser(pool.getId(), "alice");

        service.adminEnableUser(pool.getId(), "alice");

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");
        assertTrue(user.isEnabled(), "User should be enabled after adminEnableUser");
    }

    @Test
    void disabledUserCannotAuthenticate() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        service.adminDisableUser(pool.getId(), "alice");

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("UserNotConfirmedException", ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reEnabledUserCanAuthenticate() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        service.adminDisableUser(pool.getId(), "alice");
        service.adminEnableUser(pool.getId(), "alice");

        Map<String, Object> result = service.initiateAuth(
                client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!"));
        assertNotNull(((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken"));
    }

    @Test
    void adminDisableUserNonexistentThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");

        assertThrows(AwsException.class, () ->
                service.adminDisableUser(pool.getId(), "ghost"));
    }

    @Test
    void adminEnableUserNonexistentThrows() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");

        assertThrows(AwsException.class, () ->
                service.adminEnableUser(pool.getId(), "ghost"));
    }

    // =========================================================================
    // CUSTOM_AUTH flow (requires Lambda triggers)
    // =========================================================================

    @Test
    void customAuthInitiateFailsWhenDefineTriggerIsMissing() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                        Map.of("USERNAME", "alice", "CHALLENGE_NAME", "SRP_A")));
        assertEquals("InvalidUserPoolConfigurationException", ex.getErrorCode());
    }

    @Test
    void customAuthRejectsWhenNoLambdaTriggersAreConfigured() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "CUSTOM_AUTH",
                        Map.of("USERNAME", "alice")));
        assertEquals("InvalidUserPoolConfigurationException", ex.getErrorCode());
    }

    @Test
    void customChallengeWithUnknownSessionThrows() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.respondToAuthChallenge(client.getClientId(), "CUSTOM_CHALLENGE",
                        "not-a-real-session", Map.of("USERNAME", "alice", "ANSWER", "x")));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
    }

    // =========================================================================
    // NEW_PASSWORD_REQUIRED — challenge response shape + userAttributes updates
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void newPasswordRequiredChallengeReturnsUserAttributesJson() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "carol",
                Map.of("email", "carol@example.com", "given_name", "Carol"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "carol", "PASSWORD", "TempPass1!"));

        assertEquals("NEW_PASSWORD_REQUIRED", result.get("ChallengeName"));
        Map<String, String> params = (Map<String, String>) result.get("ChallengeParameters");
        String userAttrsJson = params.get("userAttributes");
        assertNotNull(userAttrsJson);
        assertTrue(userAttrsJson.contains("\"email\":\"carol@example.com\""),
                "userAttributes JSON should include user's email; was: " + userAttrsJson);
        assertTrue(userAttrsJson.contains("\"given_name\":\"Carol\""),
                "userAttributes JSON should include given_name; was: " + userAttrsJson);
    }

    @Test
    @SuppressWarnings("unchecked")
    void newPasswordRequiredAppliesUserAttributeUpdates() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "carol", Map.of("email", "carol@example.com"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> challengeResp = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "carol", "PASSWORD", "TempPass1!"));
        String session = (String) challengeResp.get("Session");

        Map<String, String> responses = new HashMap<>();
        responses.put("USERNAME", "carol");
        responses.put("NEW_PASSWORD", "Permanent99!");
        responses.put("userAttributes.given_name", "Carolyn");
        responses.put("userAttributes.family_name", "Smith");

        Map<String, Object> tokens = service.respondToAuthChallenge(
                client.getClientId(), "NEW_PASSWORD_REQUIRED", session, responses);
        assertNotNull(((Map<String, Object>) tokens.get("AuthenticationResult")).get("AccessToken"));

        CognitoUser user = service.adminGetUser(pool.getId(), "carol");
        assertEquals("Carolyn", user.getAttributes().get("given_name"));
        assertEquals("Smith", user.getAttributes().get("family_name"));
        assertEquals("CONFIRMED", user.getUserStatus());
    }

    // =========================================================================
    // SECRET_HASH validation
    // =========================================================================

    @Test
    void initiateAuthRejectsMissingSecretHashWhenClientHasSecret() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", true, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice", "PASSWORD", "Perm1234!")));
        assertEquals("InvalidParameterException", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("SECRET_HASH"));
    }

    @Test
    void initiateAuthRejectsWrongSecretHash() {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", true, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                        Map.of("USERNAME", "alice",
                                "PASSWORD", "Perm1234!",
                                "SECRET_HASH", "wrong-hash")));
        assertEquals("NotAuthorizedException", ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void initiateAuthAcceptsCorrectSecretHash() throws Exception {
        UserPool pool = createPoolAndUser();
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", true, false, List.of(), List.of());

        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                client.getClientSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String secretHash = Base64.getEncoder().encodeToString(
                mac.doFinal(("alice" + client.getClientId()).getBytes(StandardCharsets.UTF_8)));

        Map<String, Object> result = service.initiateAuth(client.getClientId(), "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "alice",
                        "PASSWORD", "Perm1234!",
                        "SECRET_HASH", secretHash));
        Map<String, Object> auth = (Map<String, Object>) result.get("AuthenticationResult");
        assertNotNull(auth);
        assertNotNull(auth.get("AccessToken"));
    }

    // =========================================================================
    // AdminRespondToAuthChallenge
    // =========================================================================

    @Test
    @SuppressWarnings("unchecked")
    void adminRespondToAuthChallengeNewPasswordRequired() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "bob", Map.of("email", "bob@example.com"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> challengeResp = service.adminInitiateAuth(
                pool.getId(), client.getClientId(), "ADMIN_USER_PASSWORD_AUTH",
                Map.of("USERNAME", "bob", "PASSWORD", "TempPass1!"), Map.of());
        assertEquals("NEW_PASSWORD_REQUIRED", challengeResp.get("ChallengeName"));
        String session = (String) challengeResp.get("Session");

        Map<String, Object> result = service.adminRespondToAuthChallenge(
                pool.getId(), client.getClientId(), "NEW_PASSWORD_REQUIRED", session,
                Map.of("USERNAME", "bob", "NEW_PASSWORD", "Permanent99!"));
        Map<String, Object> auth = (Map<String, Object>) result.get("AuthenticationResult");
        assertNotNull(auth, "AuthenticationResult should be present");
        assertNotNull(auth.get("AccessToken"));
        assertNotNull(auth.get("IdToken"));
        assertNotNull(auth.get("RefreshToken"));

        CognitoUser user = service.adminGetUser(pool.getId(), "bob");
        assertEquals("CONFIRMED", user.getUserStatus());
    }

    @Test
    void adminRespondToAuthChallengeInvalidPool() {
        UserPool pool1 = service.createUserPool(Map.of("PoolName", "Pool1"), "us-east-1");
        UserPool pool2 = service.createUserPool(Map.of("PoolName", "Pool2"), "us-east-1");
        service.adminCreateUser(pool1.getId(), "alice", Map.of("email", "a@example.com"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool1.getId(), "c", false, false, List.of(), List.of());

        AwsException ex = assertThrows(AwsException.class, () ->
                service.adminRespondToAuthChallenge(
                        pool2.getId(), client.getClientId(), "NEW_PASSWORD_REQUIRED", null,
                        Map.of("USERNAME", "alice", "NEW_PASSWORD", "NewPass1!")));
        assertEquals("ResourceNotFoundException", ex.getErrorCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminRespondToAuthChallengeWithUserAttributes() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "TestPool"), "us-east-1");
        service.adminCreateUser(pool.getId(), "carol", Map.of("email", "carol@example.com"), "TempPass1!");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());

        Map<String, Object> challengeResp = service.adminInitiateAuth(
                pool.getId(), client.getClientId(), "ADMIN_USER_PASSWORD_AUTH",
                Map.of("USERNAME", "carol", "PASSWORD", "TempPass1!"), Map.of());
        String session = (String) challengeResp.get("Session");

        Map<String, String> responses = new HashMap<>();
        responses.put("USERNAME", "carol");
        responses.put("NEW_PASSWORD", "Permanent99!");
        responses.put("userAttributes.given_name", "Carolyn");

        Map<String, Object> result = service.adminRespondToAuthChallenge(
                pool.getId(), client.getClientId(), "NEW_PASSWORD_REQUIRED", session, responses);
        assertNotNull(((Map<String, Object>) result.get("AuthenticationResult")).get("AccessToken"));

        CognitoUser user = service.adminGetUser(pool.getId(), "carol");
        assertEquals("Carolyn", user.getAttributes().get("given_name"));
    }

    // =========================================================================
    // Cognito ClientId And Secret overrides
    // =========================================================================

    @ParameterizedTest
    @CsvSource({
            "use-name,basic-client",
            "prepend-to-name:prepended-,prepended-basic-client",
            "append-to-name:-appended,basic-client-appended",
    })
    void createUserPoolWithOverrideForClientIdAndClientSecret(String overrideClientId, String expectedClientId) {
        UserPool pool = service.createUserPool(
                Map.of(
                        "PoolName", "ClientOverridesPool",
                        "UserPoolTags", Map.of(
                                "env", "test",
                                ReservedTags.OVERRIDE_COGNITO_CLIENT_ID_KEY, overrideClientId,
                                ReservedTags.OVERRIDE_COGNITO_CLIENT_SECRET_KEY, "secret")
                ),
                "us-east-1"
        );

        assertEquals("test", pool.getUserPoolTags().get("env"));
        assertFalse(pool.getUserPoolTags().containsKey(ReservedTags.OVERRIDE_COGNITO_CLIENT_ID_KEY));
        assertFalse(pool.getUserPoolTags().containsKey(ReservedTags.OVERRIDE_COGNITO_CLIENT_SECRET_KEY));

        UserPoolClient client = service.createUserPoolClient(
                pool.getId(),
                "basic-client",
                true,
                true,
                List.of(),
                List.of()
        );

        assertEquals("basic-client", client.getClientName());
        assertEquals(expectedClientId, client.getClientId());
        assertEquals("secret", client.getClientSecret());
    }

    @ParameterizedTest
    @CsvSource({
            "prepend-to-name: prepended- ,secret",
            "append-to-name: -appended ,secret",
            "append-to-name:-appended,",
            "something-else,secret"
    })
    void createUserPoolWithInvalidOverrideForClientIdAndClientSecret(String overrideClientId, String secret) {
        Map<String, Object> createUserPool = new HashMap<>();
        Map<String, String> userPoolTags = new HashMap<>();
        userPoolTags.put(ReservedTags.OVERRIDE_COGNITO_CLIENT_ID_KEY, overrideClientId);
        userPoolTags.put(ReservedTags.OVERRIDE_COGNITO_CLIENT_SECRET_KEY, secret);
        createUserPool.put("PoolName", "InvalidOverridesPool");
        createUserPool.put("UserPoolTags", userPoolTags);

        AwsException ex = assertThrows(AwsException.class, () ->
                service.createUserPool(
                        createUserPool,
                        "us-east-1"
                ));
        assertEquals("InvalidParameterException", ex.getErrorCode());

    }

    @Test
    void updateIdentityProviderDoesNotMutateAlreadyReturnedInstances() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "IdpCopyPool"), "us-east-1");
        Map<String, String> details = Map.of(
                "client_id", "before",
                "client_secret", "secret",
                "attributes_request_method", "GET",
                "oidc_issuer", "https://issuer.example.com",
                "authorize_scopes", "openid");
        service.createIdentityProvider(pool.getId(), "CopyOidc", "OIDC", details, null, null);

        IdentityProvider held = service.describeIdentityProvider(pool.getId(), "CopyOidc");

        Map<String, String> updated = new java.util.LinkedHashMap<>(details);
        updated.put("client_id", "after");
        service.updateIdentityProvider(pool.getId(), "CopyOidc", updated, null, null);

        assertEquals("before", held.getProviderDetails().get("client_id"),
                "update must write a copy, not mutate the instance the store already handed out");
        assertEquals("after",
                service.describeIdentityProvider(pool.getId(), "CopyOidc").getProviderDetails().get("client_id"));
    }

    // Issue #1654: ConfirmSignUp updates verified attribute
    @Nested
    class ConfirmSignUpVerifiedAttributes {

        private CognitoService svc;

        @BeforeEach
        void setUpVerificationService() {
            svc = createVerificationEnabledService();
        }

        @Test
        void confirmSignUpSetsEmailVerifiedAndDoesNotWritePhoneVerifiedForEmailOnlyUser() {
            String poolName = "EmailVerifyPool";
            String username = "alice@example.com";
            String password = "Passw0rd!";

            UserPool pool = svc.createUserPool(Map.of(
                    "PoolName", poolName,
                    "AutoVerifiedAttributes", List.of("email")
            ), "us-east-1");
            UserPoolClient client = svc.createUserPoolClient(
                    pool.getId(), "c", false, false, List.of(), List.of());

            svc.signUp(client.getClientId(), username, password,
                    Map.of("email", username));
            svc.confirmSignUp(client.getClientId(), username, "123456");

            CognitoUser user = svc.adminGetUser(pool.getId(), username);

            assertEquals("CONFIRMED", user.getUserStatus());
            assertEquals("true", user.getAttributes().get("email_verified"),
                    "ConfirmSignUp should set email_verified=true when email is the auto-verified attribute");
            assertFalse(user.getAttributes().containsKey("phone_number_verified"),
                    "ConfirmSignUp should not proactively write phone_number_verified when the user has no phone");
        }

        @Test
        void confirmSignUpSetsPhoneNumberVerifiedAndDoesNotWriteEmailVerifiedForPhoneOnlyUser() {
            String poolName = "PhoneVerifyPool";
            String username = "phone-user";
            String password = "Passw0rd!";
            String phoneNumber = "+491701234567";

            UserPool pool = svc.createUserPool(Map.of(
                    "PoolName", poolName,
                    "AutoVerifiedAttributes", List.of("phone_number")
            ), "us-east-1");
            UserPoolClient client = svc.createUserPoolClient(
                    pool.getId(), "c", false, false, List.of(), List.of());

            svc.signUp(client.getClientId(), username, password,
                    Map.of("phone_number", phoneNumber));
            svc.confirmSignUp(client.getClientId(), username, "123456");

            CognitoUser user = svc.adminGetUser(pool.getId(), username);

            assertEquals("CONFIRMED", user.getUserStatus());
            assertEquals("true", user.getAttributes().get("phone_number_verified"),
                    "ConfirmSignUp should set phone_number_verified=true when phone_number is the auto-verified attribute");
            assertFalse(user.getAttributes().containsKey("email_verified"),
                    "ConfirmSignUp should not proactively write email_verified when the user has no email");
        }

        @Test
        void confirmSignUpMarksOnlyPhoneNumberVerifiedWhenBothAutoVerifiedEvenWhenEmailListedFirst() {
            String poolName = "BothVerifyEmailFirstPool";
            String email = "dual-user@example.com";
            String password = "Passw0rd!";
            String phoneNumber = "+491701234567";

            UserPool pool = svc.createUserPool(Map.of(
                    "PoolName", poolName,
                    "AutoVerifiedAttributes", List.of("email", "phone_number")
            ), "us-east-1");
            UserPoolClient client = svc.createUserPoolClient(
                    pool.getId(), "c", false, false, List.of(), List.of());

            svc.signUp(client.getClientId(), email, password,
                    Map.of("email", email, "phone_number", phoneNumber));
            svc.confirmSignUp(client.getClientId(), email, "123456");

            CognitoUser user = svc.adminGetUser(pool.getId(), email);

            assertEquals("CONFIRMED", user.getUserStatus());
            assertEquals("true", user.getAttributes().get("phone_number_verified"),
                    "ConfirmSignUp should set phone_number_verified=true when both contacts are auto-verified, regardless of list order");
            assertFalse(user.getAttributes().containsKey("email_verified"),
                    "ConfirmSignUp should not set email_verified when the code was delivered to phone, not email");
        }

        @Test
        void confirmSignUpMarksOnlyPhoneNumberVerifiedWhenBothAutoVerifiedAndPhoneIsFirstDeliveryTarget() {
            String poolName = "BothVerifyPhoneFirstPool";
            String email = "dual-user2@example.com";
            String password = "Passw0rd!";
            String phoneNumber = "+491701234567";

            UserPool pool = svc.createUserPool(Map.of(
                    "PoolName", poolName,
                    "AutoVerifiedAttributes", List.of("phone_number", "email")
            ), "us-east-1");
            UserPoolClient client = svc.createUserPoolClient(
                    pool.getId(), "c", false, false, List.of(), List.of());

            svc.signUp(client.getClientId(), email, password,
                    Map.of("email", email, "phone_number", phoneNumber));
            svc.confirmSignUp(client.getClientId(), email, "123456");

            CognitoUser user = svc.adminGetUser(pool.getId(), email);

            assertEquals("CONFIRMED", user.getUserStatus());
            assertEquals("true", user.getAttributes().get("phone_number_verified"),
                    "ConfirmSignUp should set phone_number_verified=true when phone_number is the first auto-verified delivery target");
            assertFalse(user.getAttributes().containsKey("email_verified"),
                    "ConfirmSignUp should not set email_verified when the code was delivered to phone, not email");
        }

        @Test
        void confirmSignUpDoesNotSetVerifiedFlagsWhenPoolHasNoAutoVerifiedAttributes() {
            String poolName = "NoAutoVerifyPool";
            String username = "no-auto-verify@example.com";
            String password = "Passw0rd!";

            UserPool pool = svc.createUserPool(Map.of("PoolName", poolName), "us-east-1");
            UserPoolClient client = svc.createUserPoolClient(
                    pool.getId(), "c", false, false, List.of(), List.of());

            svc.signUp(client.getClientId(), username, password,
                    Map.of("email", username));
            svc.confirmSignUp(client.getClientId(), username, "123456");

            CognitoUser user = svc.adminGetUser(pool.getId(), username);

            assertEquals("CONFIRMED", user.getUserStatus());
            assertFalse(user.getAttributes().containsKey("email_verified"),
                    "ConfirmSignUp should not set email_verified when the pool has no auto-verified attributes");
            assertFalse(user.getAttributes().containsKey("phone_number_verified"),
                    "ConfirmSignUp should not set phone_number_verified when the pool has no auto-verified attributes");
        }

        @Test
        void confirmSignUpDoesNotSetVerifiedFlagsWhenVerificationServiceIsAbsent() {
            String poolName = "NoVerificationServicePool";
            String username = "no-verification@example.com";
            String password = "Passw0rd!";

            // The default service from setUp() has verificationCodeService == null.
            UserPool pool = service.createUserPool(Map.of(
                    "PoolName", poolName,
                    "AutoVerifiedAttributes", List.of("email")
            ), "us-east-1");
            UserPoolClient client = service.createUserPoolClient(
                    pool.getId(), "c", false, false, List.of(), List.of());

            service.signUp(client.getClientId(), username, password,
                    Map.of("email", username));

            service.confirmSignUp(client.getClientId(), username);

            CognitoUser user = service.adminGetUser(pool.getId(), username);

            assertEquals("CONFIRMED", user.getUserStatus());
            assertFalse(user.getAttributes().containsKey("email_verified"),
                    "ConfirmSignUp should not set email_verified when no verification service is configured");
            assertFalse(user.getAttributes().containsKey("phone_number_verified"),
                    "ConfirmSignUp should not set phone_number_verified when no verification service is configured");
        }

        @Test
        void confirmSignUpDoesNotSetVerifiedFlagsWhenDeliveryTargetIsNull() {
            String poolName = "NullDeliveryTargetPool";
            String username = "email-only@example.com";
            String password = "Passw0rd!";

            UserPool pool = svc.createUserPool(Map.of(
                    "PoolName", poolName,
                    "AutoVerifiedAttributes", List.of("email")
            ), "us-east-1");
            UserPoolClient client = svc.createUserPoolClient(
                    pool.getId(), "c", false, false, List.of(), List.of());

            svc.signUp(client.getClientId(), username, password,
                    Map.of("email", username));
            svc.updateUserPool(Map.of(
                    "UserPoolId", pool.getId(),
                    "AutoVerifiedAttributes", List.of("phone_number")
            ), "us-east-1");

            svc.confirmSignUp(client.getClientId(), username, "123456");

            CognitoUser user = svc.adminGetUser(pool.getId(), username);

            assertEquals("CONFIRMED", user.getUserStatus());
            assertFalse(user.getAttributes().containsKey("email_verified"),
                    "ConfirmSignUp should not set email_verified when the user has no matching attribute for the updated auto verified attribute");
            assertFalse(user.getAttributes().containsKey("phone_number_verified"),
                    "ConfirmSignUp should not set phone_number_verified when the delivery target is null");
        }

        private CognitoService createVerificationEnabledService() {
            VerificationCodeService verificationCodeService = mock(VerificationCodeService.class);
            CognitoMessageDispatcher messageDispatcher = mock(CognitoMessageDispatcher.class);
            when(verificationCodeService.issue(any(), any(), eq(VerificationCode.Purpose.SIGNUP_CONFIRMATION), any()))
                    .thenReturn("123456");
            return new CognitoService(
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    new InMemoryStorage<>(),
                    "http://localhost:4566",
                    regionResolver,
                    null,
                    verificationCodeService,
                    messageDispatcher
            );
        }
    }

    // ──────── UsernameAttributes=email: immutable UUID username + mutable email alias ────────

    private UserPool createEmailAliasPool() {
        Map<String, Object> req = new HashMap<>();
        req.put("PoolName", "EmailAliasPool");
        req.put("UsernameAttributes", List.of("email"));
        return service.createUserPool(req, "us-east-1");
    }

    private static boolean isUuid(String value) {
        try {
            java.util.UUID.fromString(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void emailAliasPoolMintsUuidUsernameEqualToSub() {
        UserPool pool = createEmailAliasPool();
        CognitoUser user = service.adminCreateUser(pool.getId(), "ew@gmail.com",
                new HashMap<>(Map.of("email", "ew@gmail.com")), null);

        assertTrue(isUuid(user.getUsername()), "canonical username must be a generated UUID");
        assertEquals(user.getAttributes().get("sub"), user.getUsername(), "username must equal sub");
        assertNotEquals("ew@gmail.com", user.getUsername());
        assertEquals("ew@gmail.com", user.getAttributes().get("email"));
    }

    @Test
    void emailAliasPoolResolvesByUuidAndByEmail() {
        UserPool pool = createEmailAliasPool();
        CognitoUser created = service.adminCreateUser(pool.getId(), "a@b.com",
                new HashMap<>(Map.of("email", "a@b.com")), null);
        String uuid = created.getUsername();

        assertEquals(uuid, service.adminGetUser(pool.getId(), uuid).getUsername());
        assertEquals(uuid, service.adminGetUser(pool.getId(), "a@b.com").getUsername());
    }

    @Test
    void emailAliasPoolListUsersReturnsUuidUsername() {
        UserPool pool = createEmailAliasPool();
        CognitoUser created = service.adminCreateUser(pool.getId(), "list@b.com",
                new HashMap<>(Map.of("email", "list@b.com")), null);

        List<CognitoUser> users = service.listUsers(pool.getId(), null);
        assertEquals(1, users.size());
        assertEquals(created.getUsername(), users.get(0).getUsername());
        assertTrue(isUuid(users.get(0).getUsername()));
    }

    @Test
    void emailAliasPoolRejectsDuplicateEmail() {
        UserPool pool = createEmailAliasPool();
        service.adminCreateUser(pool.getId(), "dup@b.com",
                new HashMap<>(Map.of("email", "dup@b.com")), null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminCreateUser(pool.getId(), "dup@b.com",
                        new HashMap<>(Map.of("email", "dup@b.com")), null));
        assertEquals("UsernameExistsException", ex.getErrorCode());
    }

    @Test
    void emailAliasPoolRejectsNonEmailUsername() {
        UserPool pool = createEmailAliasPool();
        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminCreateUser(pool.getId(), "not-an-email", new HashMap<>(), null));
        assertEquals("InvalidParameterException", ex.getErrorCode());
    }

    @Test
    void emailAliasPoolEmailChangeRebindsSignInAndKeepsSub() {
        UserPool pool = createEmailAliasPool();
        CognitoUser created = service.adminCreateUser(pool.getId(), "old@b.com",
                new HashMap<>(Map.of("email", "old@b.com")), null);
        String uuid = created.getUsername();
        String sub = created.getAttributes().get("sub");

        service.adminUpdateUserAttributes(pool.getId(), uuid, Map.of("email", "new@b.com"));

        assertEquals(uuid, service.adminGetUser(pool.getId(), "new@b.com").getUsername());
        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminGetUser(pool.getId(), "old@b.com"));
        assertEquals("UserNotFoundException", ex.getErrorCode());

        CognitoUser after = service.adminGetUser(pool.getId(), uuid);
        assertEquals(uuid, after.getUsername());
        assertEquals(sub, after.getAttributes().get("sub"));
        assertEquals("new@b.com", after.getAttributes().get("email"));
    }

    @Test
    void emailAliasPoolRejectsEmailChangeToTakenEmail() {
        UserPool pool = createEmailAliasPool();
        service.adminCreateUser(pool.getId(), "taken@b.com",
                new HashMap<>(Map.of("email", "taken@b.com")), null);
        CognitoUser second = service.adminCreateUser(pool.getId(), "mover@b.com",
                new HashMap<>(Map.of("email", "mover@b.com")), null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminUpdateUserAttributes(pool.getId(), second.getUsername(),
                        Map.of("email", "taken@b.com")));
        assertEquals("AliasExistsException", ex.getErrorCode());
    }

    @Test
    void classicPoolKeepsLiteralUsername() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClassicPool"), "us-east-1");
        CognitoUser user = service.adminCreateUser(pool.getId(), "bob",
                new HashMap<>(Map.of("email", "bob@example.com")), null);

        assertEquals("bob", user.getUsername());
        assertNotEquals(user.getAttributes().get("sub"), user.getUsername());
        assertEquals("bob", service.adminGetUser(pool.getId(), "bob").getUsername());
    }

    @Test
    void emailAliasPoolIgnoresCallerSuppliedSub() {
        UserPool pool = createEmailAliasPool();
        CognitoUser user = service.adminCreateUser(pool.getId(), "hijack@b.com",
                new HashMap<>(Map.of("email", "hijack@b.com", "sub", "attacker-controlled")), null);

        assertNotEquals("attacker-controlled", user.getUsername());
        assertNotEquals("attacker-controlled", user.getAttributes().get("sub"));
        assertTrue(isUuid(user.getUsername()));
        assertEquals(user.getUsername(), user.getAttributes().get("sub"));
    }

    @Test
    void emailAliasPoolResendByUuidRefreshesUser() {
        UserPool pool = createEmailAliasPool();
        CognitoUser created = service.adminCreateUser(pool.getId(), "resend@example.com",
                new HashMap<>(Map.of("email", "resend@example.com")), "Temp123!", null);
        String uuid = created.getUsername();
        assertEquals("FORCE_CHANGE_PASSWORD", created.getUserStatus());

        // RESEND addressed by the minted canonical UUID (not a valid email alias) must
        // refresh the invitation, not throw InvalidParameterException.
        CognitoUser resentByUuid = service.adminCreateUser(pool.getId(), uuid,
                new HashMap<>(), null, "RESEND");
        assertEquals(uuid, resentByUuid.getUsername(), "RESEND by UUID must return the same user");

        // RESEND addressed by the email alias must keep working too.
        CognitoUser resentByAlias = service.adminCreateUser(pool.getId(), "resend@example.com",
                new HashMap<>(), null, "RESEND");
        assertEquals(uuid, resentByAlias.getUsername(), "RESEND by alias must return the same user");
    }

    @Test
    void emailAliasPoolRejectsMalformedEmail() {
        UserPool pool = createEmailAliasPool();
        for (String bad : List.of("@", "a@", "notvalid@", "@domain.com")) {
            AwsException ex = assertThrows(AwsException.class,
                    () -> service.adminCreateUser(pool.getId(), bad, new HashMap<>(), null),
                    "malformed email must be rejected: " + bad);
            assertEquals("InvalidParameterException", ex.getErrorCode(),
                    "malformed email must be rejected: " + bad);
        }
    }

    @Test
    void emailAliasPoolMigrationIgnoresLambdaSuppliedSub() {
        UserPool pool = createEmailAliasPool();
        Map<String, String> lambdaAttributes = new HashMap<>(Map.of(
                "email", "migrated@example.com", "sub", "attacker-controlled"));
        service.adminCreateMigratedUser(pool.getId(), "migrated@example.com", "Passw0rd!",
                lambdaAttributes, "CONFIRMED");

        CognitoUser user = service.adminGetUser(pool.getId(), "migrated@example.com");
        assertNotEquals("attacker-controlled", user.getUsername());
        assertNotEquals("attacker-controlled", user.getAttributes().get("sub"));
        assertTrue(isUuid(user.getUsername()), "migrated canonical username must be a generated UUID");
        assertEquals(user.getUsername(), user.getAttributes().get("sub"), "username must equal sub");
        assertEquals("migrated@example.com", user.getAttributes().get("email"));
    }

    @Test
    void phoneAliasPoolTokenDoesNotLeakUuidAsEmailClaim() {
        UserPool pool = service.createUserPool(
                Map.of("PoolName", "PhonePool", "UsernameAttributes", List.of("phone_number")),
                "us-east-1");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());
        CognitoUser user = service.adminCreateUser(pool.getId(), "+15551234567",
                new HashMap<>(Map.of("phone_number", "+15551234567")), null);

        String idToken = service.generateSignedJwt(user, pool, "id", client, null, null);
        String segment = idToken.split("\\.")[1];
        int pad = (4 - segment.length() % 4) % 4;
        segment += "=".repeat(pad);
        String payload = new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);

        assertTrue(payload.contains("\"sub\":\"" + user.getUsername() + "\""));
        assertFalse(payload.contains("\"email\""),
                "no email claim must be emitted for a user without an email attribute: " + payload);
    }

    @Test
    void accessAndIdTokensSplitClaimsLikeAws() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ClaimsPool"), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());
        CognitoUser user = service.adminCreateUser(pool.getId(), "claims@example.com",
                new HashMap<>(Map.of("email", "claims@example.com", "email_verified", "true")), null);

        String access = jwtPayload(service.generateSignedJwt(user, pool, "access", client, null, null));
        String id = jwtPayload(service.generateSignedJwt(user, pool, "id", client, null, null));

        // Access token: `username` + reserved scope; no user attributes, no cognito:username.
        assertTrue(access.contains("\"username\":\"" + user.getUsername() + "\""));
        assertTrue(access.contains("\"scope\":\"aws.cognito.signin.user.admin\""));
        assertFalse(access.contains("\"email\""), "access token must not carry email: " + access);
        assertFalse(access.contains("\"cognito:username\""),
                "access token must not carry cognito:username: " + access);

        // ID token: `cognito:username` + email; no bare `username`.
        assertTrue(id.contains("\"cognito:username\":\"" + user.getUsername() + "\""));
        assertTrue(id.contains("\"email\":\"claims@example.com\""));
        assertFalse(id.contains("\"username\""), "id token must not carry a bare username claim: " + id);
    }

    @Test
    void emailAliasPoolVerifiedDuplicateThrowsAliasExists() {
        UserPool pool = createEmailAliasPool();
        service.adminCreateUser(pool.getId(), "dupe@b.com",
                new HashMap<>(Map.of("email", "dupe@b.com", "email_verified", "true")), null);

        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminCreateUser(pool.getId(), "dupe@b.com",
                        new HashMap<>(Map.of("email", "dupe@b.com", "email_verified", "true")), null));
        assertEquals("AliasExistsException", ex.getErrorCode());
    }

    @Test
    void emailAliasPoolAliasExistsUsesExistingVerificationNotIncoming() {
        UserPool pool = createEmailAliasPool();
        // Existing owner holds a *verified* alias.
        service.adminCreateUser(pool.getId(), "owner@b.com",
                new HashMap<>(Map.of("email", "owner@b.com", "email_verified", "true")), null);

        // The second create omits email_verified from its own payload. The exception
        // type must be driven by the existing owner's verified alias -> AliasExistsException.
        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminCreateUser(pool.getId(), "owner@b.com",
                        new HashMap<>(Map.of("email", "owner@b.com")), null));
        assertEquals("AliasExistsException", ex.getErrorCode());
    }

    @Test
    void emailAliasPoolIncomingVerifiedFlagCannotForceAliasExists() {
        UserPool pool = createEmailAliasPool();
        // Existing owner's alias is *unverified*, so the alias is not reserved.
        service.adminCreateUser(pool.getId(), "unv@b.com",
                new HashMap<>(Map.of("email", "unv@b.com")), null);

        // A caller-supplied email_verified=true must not upgrade the decision:
        // the existing owner is unverified -> UsernameExistsException, not AliasExistsException.
        AwsException ex = assertThrows(AwsException.class,
                () -> service.adminCreateUser(pool.getId(), "unv@b.com",
                        new HashMap<>(Map.of("email", "unv@b.com", "email_verified", "true")), null));
        assertEquals("UsernameExistsException", ex.getErrorCode());
    }

    @Test
    void emailAliasPoolForceAliasCreationReclaimsWithoutIncomingVerifiedFlag() {
        UserPool pool = createEmailAliasPool();
        CognitoUser first = service.adminCreateUser(pool.getId(), "reclaim@b.com",
                new HashMap<>(Map.of("email", "reclaim@b.com", "email_verified", "true")), null);

        // ForceAliasCreation reclaim keys off the existing owner's verified alias and
        // must not require the caller to re-send email_verified in the new payload.
        CognitoUser second = service.adminCreateUser(pool.getId(), "reclaim@b.com",
                new HashMap<>(Map.of("email", "reclaim@b.com")), null, null, true);

        assertNotEquals(first.getUsername(), second.getUsername());
        assertEquals(second.getUsername(), service.adminGetUser(pool.getId(), "reclaim@b.com").getUsername());
        assertNull(service.adminGetUser(pool.getId(), first.getUsername()).getAttributes().get("email"));
    }

    @Test
    void emailAliasPoolForceAliasCreationMigratesVerifiedAlias() {
        UserPool pool = createEmailAliasPool();
        CognitoUser first = service.adminCreateUser(pool.getId(), "move@b.com",
                new HashMap<>(Map.of("email", "move@b.com", "email_verified", "true")), null);

        CognitoUser second = service.adminCreateUser(pool.getId(), "move@b.com",
                new HashMap<>(Map.of("email", "move@b.com", "email_verified", "true")), null, null, true);

        assertNotEquals(first.getUsername(), second.getUsername());
        // The alias now resolves to the new user; the previous user lost it.
        assertEquals(second.getUsername(), service.adminGetUser(pool.getId(), "move@b.com").getUsername());
        assertNull(service.adminGetUser(pool.getId(), first.getUsername()).getAttributes().get("email"));
    }

    @Test
    void idTokenFiltersAttributesByReadAttributes() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "ReadAttrPool"), "us-east-1");
        UserPoolClient client = service.createUserPoolClient(
                pool.getId(), "c", false, false, List.of(), List.of());
        client.setReadAttributes(List.of("email")); // readable: email only, not name
        CognitoUser user = service.adminCreateUser(pool.getId(), "reader@example.com",
                new HashMap<>(Map.of("email", "reader@example.com", "name", "Ada Lovelace")), null);

        String id = jwtPayload(service.generateSignedJwt(user, pool, "id", client, null, null));
        assertTrue(id.contains("\"email\":\"reader@example.com\""), "readable attribute present: " + id);
        assertFalse(id.contains("\"name\""), "non-readable attribute must be filtered out: " + id);
    }
    @Test
    void adminSetUserMFAPreferenceUpdatesEmailMfaSettings() {
        UserPool pool = createPoolAndUser();

        service.adminSetUserMFAPreference(pool.getId(), "alice", true, true);

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");

        assertNotNull(user.getEmailMfaSettings());
        assertTrue(user.getEmailMfaSettings().isEnabled());
        assertTrue(user.getEmailMfaSettings().isPreferredMfa());
    }

    @Test
    void adminSetUserMFAPreferenceDoesNotMutateOnInvalidUpdate() {
        UserPool pool = createPoolAndUser();

        service.adminSetUserMFAPreference(pool.getId(), "alice", true, true);

        assertThrows(AwsException.class, () ->
                service.adminSetUserMFAPreference(pool.getId(), "alice", false, true));

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");

        assertTrue(user.getEmailMfaSettings().isEnabled());
        assertTrue(user.getEmailMfaSettings().isPreferredMfa());
    }
    @Test
    void adminSetUserMFAPreferenceDisablingEmailMfaClearsPreferredMfa() {
        UserPool pool = createPoolAndUser();

        service.adminSetUserMFAPreference(pool.getId(), "alice", true, true);

        service.adminSetUserMFAPreference(pool.getId(), "alice", false, null);

        CognitoUser user = service.adminGetUser(pool.getId(), "alice");

        assertFalse(user.getEmailMfaSettings().isEnabled());
        assertFalse(user.getEmailMfaSettings().isPreferredMfa());
   }
    // ──────────────────────────── User Pool Domains ────────────────────────────

    private static final String CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/11111111-2222-3333-4444-555555555555";
    private static final String RENEWED_CERTIFICATE_ARN =
            "arn:aws:acm:us-east-1:000000000000:certificate/99999999-2222-3333-4444-555555555555";

    private UserPool createPoolWithCustomDomain(String domain) {
        UserPool pool = service.createUserPool(Map.of("PoolName", "DomainPool"), "us-east-1");
        service.createUserPoolDomain(domain, pool.getId(), Map.of("CertificateArn", CERTIFICATE_ARN), 1);
        return pool;
    }

    @Test
    void updateUserPoolDomainReplacesTheCertificateAndKeepsTheCloudFrontDistribution() {
        UserPool pool = createPoolWithCustomDomain("auth.example.com");
        String cloudFront = service.describeUserPoolDomain("auth.example.com").getCloudFrontDistribution();

        UserPoolDomain updated = service.updateUserPoolDomain("auth.example.com", pool.getId(),
                Map.of("CertificateArn", RENEWED_CERTIFICATE_ARN), 2);

        assertEquals(RENEWED_CERTIFICATE_ARN, updated.getCertificateArn());
        assertEquals(cloudFront, updated.getCloudFrontDistribution());
        assertEquals(2, updated.getManagedLoginVersion());
        UserPoolDomain stored = service.describeUserPoolDomain("auth.example.com");
        assertEquals(RENEWED_CERTIFICATE_ARN, stored.getCertificateArn());
        assertEquals(2, stored.getManagedLoginVersion());
    }

    @Test
    void updateUserPoolDomainLeavesOmittedSettingsUnchanged() {
        UserPool pool = createPoolWithCustomDomain("auth.example.com");

        service.updateUserPoolDomain("auth.example.com", pool.getId(), null, null);

        UserPoolDomain stored = service.describeUserPoolDomain("auth.example.com");
        assertEquals(CERTIFICATE_ARN, stored.getCertificateArn());
        assertEquals(1, stored.getManagedLoginVersion());
    }

    @Test
    void updateUserPoolDomainSetsTheManagedLoginVersionOfAPrefixDomain() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "DomainPool"), "us-east-1");
        service.createUserPoolDomain("my-prefix", pool.getId(), null, null);

        UserPoolDomain updated = service.updateUserPoolDomain("my-prefix", pool.getId(), null, 2);

        assertEquals(2, updated.getManagedLoginVersion());
        assertFalse(updated.isCustomDomain());
        assertNull(updated.getCloudFrontDistribution());
    }

    @Test
    void updateUserPoolDomainRejectsACustomDomainConfigOnAPrefixDomain() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "DomainPool"), "us-east-1");
        service.createUserPoolDomain("my-prefix", pool.getId(), null, null);

        AwsException failure = assertThrows(AwsException.class, () -> service.updateUserPoolDomain(
                "my-prefix", pool.getId(), Map.of("CertificateArn", CERTIFICATE_ARN), null));

        assertEquals("InvalidParameterException", failure.getErrorCode());
        assertFalse(service.describeUserPoolDomain("my-prefix").isCustomDomain());
    }

    @Test
    void updateUserPoolDomainRequiresACertificateArnInTheCustomDomainConfig() {
        UserPool pool = createPoolWithCustomDomain("auth.example.com");

        AwsException failure = assertThrows(AwsException.class, () -> service.updateUserPoolDomain(
                "auth.example.com", pool.getId(), Map.of(), 2));

        assertEquals("InvalidParameterException", failure.getErrorCode());
        assertEquals(CERTIFICATE_ARN, service.describeUserPoolDomain("auth.example.com").getCertificateArn());
    }

    @Test
    void updateUserPoolDomainOfAnotherPoolsDomainIsNotFound() {
        createPoolWithCustomDomain("auth.example.com");
        UserPool otherPool = service.createUserPool(Map.of("PoolName", "OtherPool"), "us-east-1");

        AwsException failure = assertThrows(AwsException.class, () -> service.updateUserPoolDomain(
                "auth.example.com", otherPool.getId(), Map.of("CertificateArn", RENEWED_CERTIFICATE_ARN), null));

        assertEquals("ResourceNotFoundException", failure.getErrorCode());
        assertEquals(CERTIFICATE_ARN, service.describeUserPoolDomain("auth.example.com").getCertificateArn());
    }

    @Test
    void updateUserPoolDomainOfAnUnknownDomainIsNotFound() {
        UserPool pool = service.createUserPool(Map.of("PoolName", "DomainPool"), "us-east-1");

        AwsException failure = assertThrows(AwsException.class, () -> service.updateUserPoolDomain(
                "nobody.example.com", pool.getId(), Map.of("CertificateArn", CERTIFICATE_ARN), null));

        assertEquals("ResourceNotFoundException", failure.getErrorCode());
    }

    @Test
    void updateUserPoolDomainReplacesTheSecurityPolicyAndKeepsItWhenOmitted() {
        UserPool pool = createPoolWithCustomDomain("auth.example.com");
        assertEquals("TLS_V1_2_2021", service.describeUserPoolDomain("auth.example.com").getSecurityPolicy());

        service.updateUserPoolDomain("auth.example.com", pool.getId(),
                Map.of("CertificateArn", RENEWED_CERTIFICATE_ARN, "SecurityPolicy", "TLS_V1_2_2019"), null);
        assertEquals("TLS_V1_2_2019", service.describeUserPoolDomain("auth.example.com").getSecurityPolicy());

        service.updateUserPoolDomain("auth.example.com", pool.getId(), Map.of("CertificateArn", CERTIFICATE_ARN), null);

        UserPoolDomain stored = service.describeUserPoolDomain("auth.example.com");
        assertEquals("TLS_V1_2_2019", stored.getSecurityPolicy());
        assertEquals(CERTIFICATE_ARN, stored.getCertificateArn());
    }

    @Test
    void updateUserPoolDomainOfAnUnknownPoolIsNotFound() {
        createPoolWithCustomDomain("auth.example.com");

        AwsException failure = assertThrows(AwsException.class, () -> service.updateUserPoolDomain(
                "auth.example.com", "us-east-1_missing", Map.of("CertificateArn", CERTIFICATE_ARN), null));

        assertEquals("ResourceNotFoundException", failure.getErrorCode());
    }

    private static String jwtPayload(String token) {
        String segment = token.split("\\.")[1];
        int pad = (4 - segment.length() % 4) % 4;
        segment += "=".repeat(pad);
        return new String(Base64.getUrlDecoder().decode(segment), StandardCharsets.UTF_8);
    }
}
