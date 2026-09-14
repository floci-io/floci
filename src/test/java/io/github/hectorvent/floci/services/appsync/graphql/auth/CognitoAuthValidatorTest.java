package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncTransportException;
import io.github.hectorvent.floci.services.cognito.CognitoService;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CognitoAuthValidatorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String POOL_ID = "us-east-1_abc";
    private static final String KEY_ID = "signing-key-1";
    private static final String ISSUER = "http://127.0.0.1:12345/us-east-1_abc";

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock
    CognitoService cognitoService;

    private CognitoAuthValidator validator;
    private Map<String, Object> config;
    private RSAPrivateKey privateKey;
    private UserPool pool;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        privateKey = (RSAPrivateKey) pair.getPrivate();
        RSAPublicKey publicKey = (RSAPublicKey) pair.getPublic();

        pool = new UserPool();
        pool.setSigningKeyId(KEY_ID);
        pool.setSigningPublicKey(Base64.getEncoder().encodeToString(publicKey.getEncoded()));

        lenient().when(cognitoService.describeUserPool(POOL_ID)).thenReturn(pool);
        lenient().when(cognitoService.getIssuer(POOL_ID)).thenReturn(ISSUER);

        validator = new CognitoAuthValidator(new JwtClaimsDecoder(mapper), cognitoService,
                Clock.fixed(NOW, ZoneOffset.UTC));
        config = new HashMap<>();
        config.put("userPoolId", POOL_ID);
        config.put("awsRegion", "us-east-1");
        config.put("defaultAction", "ALLOW");
        config.put("appIdClientRegex", "client-1");
    }

    @Test
    void validSignedJwtBuildsUsernameAndAuthTypeIdentity() throws Exception {
        Map<String, Object> claims = baseClaims();
        claims.put("cognito:username", "alice");
        Map<String, Object> identity = validator.validate(
                "Bearer " + signedJwt(claims, KEY_ID, privateKey), config, List.of("127.0.0.1"));
        assertEquals("alice", identity.get("username"));
        assertEquals("abc", identity.get("sub"));
        assertNull(identity.get("cognitoUserPoolId"));
    }

    @Test
    void unsignedAlgNoneTokenIs401() {
        Map<String, Object> claims = baseClaims();
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + JwtClaimsDecoder.encode(claims, mapper), config, List.of()));
    }

    @Test
    void tokenSignedByAForeignKeyIs401() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        RSAPrivateKey forgedKey = (RSAPrivateKey) gen.generateKeyPair().getPrivate();
        Map<String, Object> claims = baseClaims();

        assertThrows(AppSyncTransportException.class,
                () -> validator.validate(
                        "Bearer " + signedJwt(claims, KEY_ID, forgedKey), config, List.of()));
    }

    @Test
    void tokenWithUnknownKidIs401() throws Exception {
        Map<String, Object> claims = baseClaims();
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate(
                        "Bearer " + signedJwt(claims, "some-other-key", privateKey), config, List.of()));
    }

    @Test
    void tokenForAnUnregisteredPoolIs401() throws Exception {
        when(cognitoService.describeUserPool(POOL_ID))
                .thenThrow(new AwsException("ResourceNotFoundException", "User pool not found", 400));
        Map<String, Object> claims = baseClaims();

        assertThrows(AppSyncTransportException.class,
                () -> validator.validate(
                        "Bearer " + signedJwt(claims, KEY_ID, privateKey), config, List.of()));
    }

    @Test
    void expiredJwtIs401() throws Exception {
        Map<String, Object> claims = baseClaims();
        claims.put("exp", NOW.getEpochSecond() - 10);
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + signedJwt(claims, KEY_ID, privateKey), config, List.of()));
    }

    @Test
    void malformedTokenIs401() {
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer not-a-jwt", config, List.of()));
    }

    @Test
    void missingSubIs401() throws Exception {
        Map<String, Object> claims = baseClaims();
        claims.remove("sub");
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + signedJwt(claims, KEY_ID, privateKey), config, List.of()));
    }

    @Test
    void wrongIssuerIs401() throws Exception {
        Map<String, Object> claims = baseClaims();
        claims.put("iss", "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_abc");
        assertThrows(AppSyncTransportException.class,
                () -> validator.validate("Bearer " + signedJwt(claims, KEY_ID, privateKey), config, List.of()));
    }

    @Test
    void groupsNullVsEmpty() throws Exception {
        Map<String, Object> missing = validator.validate(
                "Bearer " + signedJwt(baseClaims(), KEY_ID, privateKey), config, List.of());
        assertNull(missing.get("groups"));

        Map<String, Object> emptyClaims = baseClaims();
        emptyClaims.put("cognito:groups", List.of());
        Map<String, Object> empty = validator.validate(
                "Bearer " + signedJwt(emptyClaims, KEY_ID, privateKey), config, List.of());
        assertEquals(List.of(), empty.get("groups"));
    }

    private Map<String, Object> baseClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "abc");
        claims.put("iss", ISSUER);
        claims.put("aud", "client-1");
        claims.put("exp", NOW.getEpochSecond() + 3600);
        return claims;
    }

    private String signedJwt(Map<String, Object> claims, String kid, RSAPrivateKey signingKey)
            throws GeneralSecurityException {
        ObjectNode header = mapper.createObjectNode();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        header.put("kid", kid);

        ObjectNode payload = mapper.createObjectNode();
        claims.forEach((key, value) -> payload.putPOJO(key, value));

        String signingInput = base64Url(header.toString()) + "." + base64Url(payload.toString());
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signingKey);
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        return signingInput + "." + encodedSignature;
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
