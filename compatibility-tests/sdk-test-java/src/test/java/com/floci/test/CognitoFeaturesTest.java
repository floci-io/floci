package com.floci.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/**
 * Compatibility tests for Cognito fixes (Java SDK-based):
 *   #218 — RS256 JWT signing + JWKS signature verification
 *   #220 — AdminGetUser accepts sub UUID and email alias as Username
 *   #228 — AccessToken contains client_id claim
 *   #229 — InitiateAuth rejects auth when no password hash is set
 *   #233 — ListUsers respects Filter parameter
 *   #235 — AdminSetUserPassword(Permanent=false) changes the password
 *
 * Note: Issue #234 (GetTokensFromRefreshToken) is tested in sdk-test-node/tests/cognito-features.test.ts
 * because GetTokensFromRefreshTokenCommand is not present in Java SDK 2.31.8.
 */
@DisplayName("Cognito IDP — bug fixes #218 #220 #228 #229 #233 #235")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoFeaturesTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static CognitoIdentityProviderClient cognito;

    private static String poolId;
    private static String poolArn;
    private static String clientId;
    private static final String USERNAME = "compat-user-" + UUID.randomUUID() + "@example.com";
    private static final String PASSWORD = "CompatPass1!";
    private static String userSub;

    @BeforeAll
    static void setup() {
        cognito = TestFixtures.cognitoClient();
    }

    @AfterAll
    static void cleanup() {
        if (cognito == null) return;
        try {
            if (poolId != null) {
                cognito.deleteUserPool(b -> b.userPoolId(poolId));
            }
        } catch (Exception ignored) {}
        cognito.close();
    }

    // ── Setup ─────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createPool() {
        CreateUserPoolResponse resp = cognito.createUserPool(b -> b.poolName("compat-test-pool"));
        poolId = resp.userPool().id();
        poolArn = resp.userPool().arn();
        assertThat(poolId).isNotBlank();
        assertThat(poolArn).isNotBlank();
    }

    @Test
    @Order(2)
    void tagListAndUntagResourceRoundTrip() {
        cognito.tagResource(b -> b.resourceArn(poolArn).tags(Map.of("env", "test", "team", "platform")));

        ListTagsForResourceResponse tagged = cognito.listTagsForResource(b -> b.resourceArn(poolArn));
        assertThat(tagged.tags()).containsEntry("env", "test").containsEntry("team", "platform");

        cognito.untagResource(b -> b.resourceArn(poolArn).tagKeys("team"));

        ListTagsForResourceResponse untagged = cognito.listTagsForResource(b -> b.resourceArn(poolArn));
        assertThat(untagged.tags()).containsEntry("env", "test").doesNotContainKey("team");
    }

    @Test
    @Order(3)
    void tagResourceRejectsReservedKey() {
        assertThatThrownBy(() -> cognito.tagResource(b -> b
                .resourceArn(poolArn)
                .tags(Map.of("floci:override-id", "late-id"))))
                .isInstanceOf(CognitoIdentityProviderException.class)
                .hasMessageContaining("Reserved tag keys with prefix floci:");
    }

    @Test
    @Order(4)
    void createClient() {
        CreateUserPoolClientResponse resp = cognito.createUserPoolClient(b -> b
                .userPoolId(poolId)
                .clientName("compat-test-client")
                .explicitAuthFlows(
                        ExplicitAuthFlowsType.ALLOW_USER_PASSWORD_AUTH,
                        ExplicitAuthFlowsType.ALLOW_REFRESH_TOKEN_AUTH));
        clientId = resp.userPoolClient().clientId();
        assertThat(clientId).isNotBlank();
    }

    @Test
    @Order(5)
    void createUserWithPermPassword() {
        cognito.adminCreateUser(b -> b
                .userPoolId(poolId)
                .username(USERNAME)
                .userAttributes(
                        AttributeType.builder().name("email").value(USERNAME).build(),
                        AttributeType.builder().name("email_verified").value("true").build())
                .messageAction(MessageActionType.SUPPRESS));
        cognito.adminSetUserPassword(b -> b
                .userPoolId(poolId)
                .username(USERNAME)
                .password(PASSWORD)
                .permanent(true));

        AdminGetUserResponse user = cognito.adminGetUser(b -> b.userPoolId(poolId).username(USERNAME));
        userSub = user.userAttributes().stream()
                .filter(a -> "sub".equals(a.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
        assertThat(userSub).isNotBlank();
    }

    // ── Issue #229 — InitiateAuth rejects when no password hash is set ────────

    @Test
    @Order(10)
    void initiateAuthRejectsAnyPasswordForUserWithNoHashSet() {
        String noHashUser = "no-hash-" + UUID.randomUUID() + "@example.com";
        cognito.adminCreateUser(b -> b
                .userPoolId(poolId)
                .username(noHashUser)
                .userAttributes(AttributeType.builder().name("email").value(noHashUser).build())
                .messageAction(MessageActionType.SUPPRESS));

        assertThatThrownBy(() -> cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", noHashUser, "PASSWORD", "anything"))))
                .isInstanceOf(CognitoIdentityProviderException.class)
                .matches(e -> e.getClass().getSimpleName().equals("NotAuthorizedException")
                        || e.getMessage().contains("NotAuthorizedException"));
    }

    @Test
    @Order(11)
    void initiateAuthRejectsWrongPassword() {
        assertThatThrownBy(() -> cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", "WrongPass1!"))))
                .isInstanceOf(CognitoIdentityProviderException.class)
                .matches(e -> e.getClass().getSimpleName().equals("NotAuthorizedException")
                        || e.getMessage().contains("NotAuthorizedException"));
    }

    // ── Issue #235 — AdminSetUserPassword(Permanent=false) changes the password ─

    @Test
    @Order(20)
    void adminSetUserPasswordPermanentFalseChangesPassword() {
        String tempPass = "TempPass1!";

        cognito.adminSetUserPassword(b -> b
                .userPoolId(poolId)
                .username(USERNAME)
                .password(tempPass)
                .permanent(false));

        // Old password now rejected
        assertThatThrownBy(() -> cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD))))
                .isInstanceOf(CognitoIdentityProviderException.class)
                .matches(e -> e.getClass().getSimpleName().equals("NotAuthorizedException")
                        || e.getMessage().contains("NotAuthorizedException"));

        // New temp password triggers NEW_PASSWORD_REQUIRED challenge, not tokens
        InitiateAuthResponse challengeResp = cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", tempPass)));
        assertThat(challengeResp.challengeName()).isEqualTo(ChallengeNameType.NEW_PASSWORD_REQUIRED);

        // Restore permanent password for subsequent tests
        cognito.adminSetUserPassword(b -> b
                .userPoolId(poolId)
                .username(USERNAME)
                .password(PASSWORD)
                .permanent(true));
    }

    // ── Issue #228 — AccessToken contains client_id claim ─────────────────────

    @Test
    @Order(30)
    void accessTokenContainsClientIdClaim() throws Exception {
        InitiateAuthResponse resp = cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)));

        JsonNode payload = decodeJwtPayload(resp.authenticationResult().accessToken());
        assertThat(payload.path("client_id").asText())
                .as("AccessToken must contain client_id matching the requesting ClientId")
                .isEqualTo(clientId);
    }

    @Test
    @Order(31)
    void idTokenDoesNotContainClientIdClaim() throws Exception {
        InitiateAuthResponse resp = cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)));

        JsonNode payload = decodeJwtPayload(resp.authenticationResult().idToken());
        assertThat(payload.has("client_id"))
                .as("IdToken should not contain client_id claim")
                .isFalse();
    }

    // ── Issue #218 — RS256 JWT signing and JWKS signature verification ─────────

    @Test
    @Order(32)
    void accessTokenIsSignedWithRs256() throws Exception {
        InitiateAuthResponse resp = cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)));

        JsonNode header = decodeJwtHeader(resp.authenticationResult().accessToken());
        assertThat(header.path("alg").asText()).isEqualTo("RS256");
        assertThat(header.path("kid").asText()).isNotBlank();
    }

    @Test
    @Order(33)
    void accessTokenSignatureVerifiesAgainstJwks() throws Exception {
        InitiateAuthResponse resp = cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)));

        String accessToken = resp.authenticationResult().accessToken();
        String kid = decodeJwtHeader(accessToken).path("kid").asText();

        URI jwksUri = TestFixtures.endpoint().resolve("/" + poolId + "/.well-known/jwks.json");
        HttpResponse<String> jwksResp = HTTP.send(
                HttpRequest.newBuilder().uri(jwksUri).GET().timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(jwksResp.statusCode()).isEqualTo(200);

        JsonNode jwk = null;
        for (JsonNode key : JSON.readTree(jwksResp.body()).path("keys")) {
            if (kid.equals(key.path("kid").asText())) {
                jwk = key;
                break;
            }
        }
        assertThat(jwk).as("JWK with kid=%s must be present in JWKS", kid).isNotNull();
        assertThat(verifyRs256Signature(accessToken, jwk))
                .as("AccessToken RS256 signature must verify against published JWKS public key")
                .isTrue();
    }

    // ── Issue #220 — AdminGetUser accepts sub UUID and email as Username ───────

    @Test
    @Order(40)
    void adminGetUserBySubUuid() {
        assertThat(userSub).isNotBlank();
        AdminGetUserResponse resp = cognito.adminGetUser(b -> b
                .userPoolId(poolId)
                .username(userSub));
        assertThat(resp.username())
                .as("AdminGetUser with sub UUID should resolve to the correct user")
                .isEqualTo(USERNAME);
    }

    @Test
    @Order(41)
    void adminGetUserByEmailAlias() {
        AdminGetUserResponse resp = cognito.adminGetUser(b -> b
                .userPoolId(poolId)
                .username(USERNAME));
        assertThat(resp.username()).isEqualTo(USERNAME);
    }

    @Test
    @Order(42)
    void adminSetUserPasswordBySubUuid() {
        cognito.adminSetUserPassword(b -> b
                .userPoolId(poolId)
                .username(userSub)
                .password(PASSWORD)
                .permanent(true));

        InitiateAuthResponse resp = cognito.initiateAuth(b -> b
                .clientId(clientId)
                .authFlow(AuthFlowType.USER_PASSWORD_AUTH)
                .authParameters(Map.of("USERNAME", USERNAME, "PASSWORD", PASSWORD)));
        assertThat(resp.authenticationResult().accessToken()).isNotBlank();
    }

    // ── Issue #233 — ListUsers respects Filter parameter ─────────────────────

    @Test
    @Order(50)
    void listUsersNoFilterReturnsUser() {
        ListUsersResponse resp = cognito.listUsers(b -> b.userPoolId(poolId));
        assertThat(resp.users()).extracting(UserType::username).contains(USERNAME);
    }

    @Test
    @Order(51)
    void listUsersFilterByEmailExactMatch() {
        ListUsersResponse resp = cognito.listUsers(b -> b
                .userPoolId(poolId)
                .filter("email = \"" + USERNAME + "\""));
        System.out.println("Filter: email = \"" + USERNAME + "\"");
        System.out.println("Users found: " + resp.users().size());
        for (UserType u : resp.users()) {
            String email = u.attributes().stream().filter(a -> "email".equals(a.name())).map(AttributeType::value).findFirst().orElse("null");
            System.out.println(" - User: " + u.username() + ", email: " + email);
        }
        assertThat(resp.users()).hasSize(1);
        assertThat(resp.users().get(0).username()).isEqualTo(USERNAME);
    }

    @Test
    @Order(52)
    void listUsersFilterByEmailPrefixStartsWith() {
        ListUsersResponse resp = cognito.listUsers(b -> b
                .userPoolId(poolId)
                .filter("email ^= \"compat-user-\""));
        assertThat(resp.users()).extracting(UserType::username).contains(USERNAME);
    }

    @Test
    @Order(53)
    void listUsersFilterBySubExactMatch() {
        assertThat(userSub).isNotBlank();
        ListUsersResponse resp = cognito.listUsers(b -> b
                .userPoolId(poolId)
                .filter("sub = \"" + userSub + "\""));
        assertThat(resp.users()).hasSize(1);
        assertThat(resp.users().get(0).username()).isEqualTo(USERNAME);
    }

    @Test
    @Order(54)
    void listUsersFilterNoMatchReturnsEmpty() {
        ListUsersResponse resp = cognito.listUsers(b -> b
                .userPoolId(poolId)
                .filter("email = \"nobody@nowhere.invalid\""));
        assertThat(resp.users()).isEmpty();
    }

    @Test
    @Order(55)
    void describeUserPoolReturnsAllTwentyStandardAttributes() {
        DescribeUserPoolResponse resp = cognito.describeUserPool(b -> b.userPoolId(poolId));
        List<SchemaAttributeType> schema = resp.userPool().schemaAttributes();
        assertThat(schema).hasSize(20);
        List<String> names = schema.stream().map(SchemaAttributeType::name).toList();
        assertThat(names).contains(
                "sub", "name", "given_name", "family_name", "middle_name", "nickname",
                "preferred_username", "profile", "picture", "website", "email",
                "email_verified", "gender", "birthdate", "zoneinfo", "locale",
                "phone_number", "phone_number_verified", "address", "updated_at");

        SchemaAttributeType sub = schema.stream().filter(a -> "sub".equals(a.name())).findFirst().orElseThrow();
        assertThat(sub.required()).isTrue();
        assertThat(sub.mutable()).isFalse();
    }

    // ── Issue #234 note ───────────────────────────────────────────────────────
    // GetTokensFromRefreshToken is tested in sdk-test-node/tests/cognito-features.test.ts
    // because GetTokensFromRefreshTokenCommand is not available in Java SDK 2.31.8.

    // ── JWT helpers ───────────────────────────────────────────────────────────

    private static JsonNode decodeJwtPayload(String jwt) throws Exception {
        return decodeJwtPart(jwt, 1);
    }

    private static JsonNode decodeJwtHeader(String jwt) throws Exception {
        return decodeJwtPart(jwt, 0);
    }

    private static JsonNode decodeJwtPart(String jwt, int index) throws Exception {
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("JWT must have 3 parts, got " + parts.length);
        }
        byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[index]));
        return JSON.readTree(new String(decoded, StandardCharsets.UTF_8));
    }

    private static boolean verifyRs256Signature(String jwt, JsonNode jwk) throws Exception {
        String[] parts = jwt.split("\\.");
        BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(padBase64(jwk.path("n").asText())));
        BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(padBase64(jwk.path("e").asText())));
        RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(modulus, exponent));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(publicKey);
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8));
        return verifier.verify(Base64.getUrlDecoder().decode(padBase64(parts[2])));
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        return remainder == 0 ? value : value + "=".repeat(4 - remainder);
    }
}
