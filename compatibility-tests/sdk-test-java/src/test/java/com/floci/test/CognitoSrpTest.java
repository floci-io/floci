package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Cognito SRP Authentication (Issue #310)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CognitoSrpTest {

    private static final BigInteger G = BigInteger.valueOf(2);
    private static final BigInteger N = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1" +
            "29024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
            "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
            "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D" +
            "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
            "83655D23DCA3AD961C62F356208552BB9ED529077096966D" +
            "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
            "DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
            "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64" +
            "ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
            "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6B" +
            "F12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
            "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
            "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16);

    private static CognitoIdentityProviderClient cognito;
    private static String poolId;
    private static String clientId;
    private static final String USERNAME = "srp-user-" + UUID.randomUUID();
    private static final String PASSWORD = "SrpPass1!";

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

    @Test
    @Order(1)
    void createPoolAndClient() {
        CreateUserPoolResponse poolResp = cognito.createUserPool(b -> b.poolName("srp-test-pool"));
        poolId = poolResp.userPool().id();

        CreateUserPoolClientResponse clientResp = cognito.createUserPoolClient(b -> b
                .userPoolId(poolId)
                .clientName("srp-test-client")
                .explicitAuthFlows(ExplicitAuthFlowsType.ALLOW_USER_SRP_AUTH));
        clientId = clientResp.userPoolClient().clientId();

        assertThat(poolId).isNotBlank();
        assertThat(clientId).isNotBlank();
    }

    @Test
    @Order(2)
    void createUser() {
        cognito.adminCreateUser(b -> b
                .userPoolId(poolId)
                .username(USERNAME)
                .messageAction(MessageActionType.SUPPRESS));
        
        cognito.adminSetUserPassword(b -> b
                .userPoolId(poolId)
                .username(USERNAME)
                .password(PASSWORD)
                .permanent(true));
    }

    @Test
    @Order(3)
    @DisplayName("Verify USER_SRP_AUTH returns PASSWORD_VERIFIER challenge")
    void srpAuthReturnsChallenge() {
        BigInteger a = new BigInteger(256, new SecureRandom());
        BigInteger A = G.modPow(a, N);

        InitiateAuthResponse authResp = cognito.initiateAuth(b -> b
                .authFlow(AuthFlowType.USER_SRP_AUTH)
                .clientId(clientId)
                .authParameters(Map.of(
                        "USERNAME", USERNAME,
                        "SRP_A", A.toString(16)
                )));

        assertThat(authResp.challengeName()).isEqualTo(ChallengeNameType.PASSWORD_VERIFIER);
        Map<String, String> params = authResp.challengeParameters();
        assertThat(params).containsKey("SRP_B");
        assertThat(params).containsKey("SALT");
        assertThat(params).containsKey("SECRET_BLOCK");
    }

    @Test
    @Order(4)
    @DisplayName("Full USER_SRP_AUTH round-trip yields valid tokens")
    void srpAuthCompletesAndReturnsTokens() throws Exception {
        // 1. Generate client ephemeral a, public A = g^a mod N.
        BigInteger a = new BigInteger(256, new SecureRandom());
        BigInteger A = G.modPow(a, N);

        // 2. Kick off SRP — server returns SRP_B, SALT, SECRET_BLOCK, USER_ID_FOR_SRP.
        InitiateAuthResponse init = cognito.initiateAuth(b -> b
                .authFlow(AuthFlowType.USER_SRP_AUTH)
                .clientId(clientId)
                .authParameters(Map.of(
                        "USERNAME", USERNAME,
                        "SRP_A", A.toString(16)
                )));
        assertThat(init.challengeName()).isEqualTo(ChallengeNameType.PASSWORD_VERIFIER);

        Map<String, String> challenge = init.challengeParameters();
        BigInteger B = new BigInteger(challenge.get("SRP_B"), 16);
        BigInteger salt = new BigInteger(challenge.get("SALT"), 16);
        String secretBlockB64 = challenge.get("SECRET_BLOCK");
        String userIdForSrp = challenge.get("USER_ID_FOR_SRP");
        String poolName = poolId.split("_", 2)[1];

        // 3. Compute the PASSWORD_CLAIM_SIGNATURE per the AWS "Caldera" SRP variant.
        BigInteger x = computeX(poolName, userIdForSrp, PASSWORD, salt);
        BigInteger u = computeU(A, B);
        BigInteger k = computeK();
        // S = (B - k * g^x)^(a + u*x) mod N
        BigInteger S = B.subtract(k.multiply(G.modPow(x, N))).mod(N)
                .modPow(a.add(u.multiply(x)), N);
        byte[] hkdfKey = calderaHkdf(S, u);
        String timestamp = formatCognitoTimestamp(new Date());
        byte[] secretBlock = Base64.getDecoder().decode(secretBlockB64);
        byte[] signature = hmacSha256(hkdfKey,
                concat(poolName.getBytes(StandardCharsets.UTF_8),
                       userIdForSrp.getBytes(StandardCharsets.UTF_8),
                       secretBlock,
                       timestamp.getBytes(StandardCharsets.UTF_8)));

        // 4. Respond to the challenge — Floci must return real tokens if its
        //    server-side k, U, S, HKDF and HMAC layouts all match the spec.
        Map<String, String> responses = new HashMap<>();
        responses.put("USERNAME", userIdForSrp);
        responses.put("PASSWORD_CLAIM_SECRET_BLOCK", secretBlockB64);
        responses.put("TIMESTAMP", timestamp);
        responses.put("PASSWORD_CLAIM_SIGNATURE", Base64.getEncoder().encodeToString(signature));

        RespondToAuthChallengeResponse resp = cognito.respondToAuthChallenge(b -> b
                .clientId(clientId)
                .challengeName(ChallengeNameType.PASSWORD_VERIFIER)
                .session(init.session())
                .challengeResponses(responses));

        AuthenticationResultType auth = resp.authenticationResult();
        assertThat(auth).as("AuthenticationResult populated").isNotNull();
        // JWTs have three base64 segments separated by dots.
        assertThat(auth.idToken()).matches("[^.]+\\.[^.]+\\.[^.]+");
        assertThat(auth.accessToken()).matches("[^.]+\\.[^.]+\\.[^.]+");
        assertThat(auth.refreshToken()).isNotBlank();
    }

    // ────────────────────────── Caldera SRP helpers ──────────────────────────
    // The AWS Java SDK v2 doesn't expose a public SRP helper — clients writing
    // server-side SRP normally pull amazon-cognito-identity-provider's mobile
    // SDK or AWSCognitoAuthHelper from aws-android-sdk-cognitoidentityprovider.
    // For test isolation we inline the math here, matching the spec floci's
    // CognitoSrpHelper implements (the canonical AWS "Caldera" variant).

    private static BigInteger computeK() throws Exception {
        // k = SHA-256(N || g) over 2's-complement byte representations
        // (minimal length with sign byte where needed).
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(N.toByteArray());
        sha.update(G.toByteArray());
        return new BigInteger(1, sha.digest());
    }

    private static BigInteger computeU(BigInteger A, BigInteger B) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(A.toByteArray());
        sha.update(B.toByteArray());
        return new BigInteger(1, sha.digest());
    }

    private static BigInteger computeX(String poolName, String username, String password, BigInteger salt) throws Exception {
        // inner = SHA-256(poolName + username + ":" + password)
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(poolName.getBytes(StandardCharsets.UTF_8));
        sha.update(username.getBytes(StandardCharsets.UTF_8));
        sha.update(":".getBytes(StandardCharsets.UTF_8));
        sha.update(password.getBytes(StandardCharsets.UTF_8));
        byte[] inner = sha.digest();
        // x = SHA-256(saltBytes || inner) — salt as 2's-complement bytes.
        sha.reset();
        sha.update(salt.toByteArray());
        sha.update(inner);
        return new BigInteger(1, sha.digest());
    }

    private static byte[] calderaHkdf(BigInteger S, BigInteger U) throws Exception {
        // HKDF-Expand over (S, U as salt, "Caldera Derived Key" || 0x01) → 16 bytes
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(U.toByteArray(), "HmacSHA256"));
        byte[] prk = mac.doFinal(S.toByteArray());

        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update("Caldera Derived Key".getBytes(StandardCharsets.UTF_8));
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), 16);
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] p : parts) { System.arraycopy(p, 0, out, off, p.length); off += p.length; }
        return out;
    }

    private static String formatCognitoTimestamp(Date date) {
        // Cognito expects "EEE MMM d HH:mm:ss z yyyy" in US locale, UTC, day NOT
        // zero-padded — see amazon-cognito-identity-js/getNowString.ts.
        SimpleDateFormat fmt = new SimpleDateFormat("EEE MMM d HH:mm:ss z yyyy", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(date);
    }
}
