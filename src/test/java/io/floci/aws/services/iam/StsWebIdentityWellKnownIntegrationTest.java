package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The discovery document and key set for the issuer {@code GetWebIdentityToken} names, fetched as
 * a relying party fetches them: with no AWS credential, by the issuer's UUID.
 *
 * <p>The point of the routes is that a token Floci signs can be verified by someone who only holds
 * the token, so the signature checks here rebuild each key from its published JWK alone and never
 * reach into the store, unlike {@link WebIdentityTokenIssuerTest}.
 *
 * <p>Each test works in an account of its own, since the feature switch is account-level and
 * {@link WebIdentityTokenIntegrationTest} turns the default account's off and on.
 */
@QuarkusTest
class StsWebIdentityWellKnownIntegrationTest {

    private static final String ISSUER = "GetOutboundWebIdentityFederationInfoResponse"
            + ".GetOutboundWebIdentityFederationInfoResult.IssuerIdentifier";
    private static final String TOKEN =
            "GetWebIdentityTokenResponse.GetWebIdentityTokenResult.WebIdentityToken";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A request signed as {@code account} for {@code service}. */
    private static RequestSpecification signed(String account, String service) {
        return given().header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                + "/20261116/us-east-1/" + service + "/aws4_request");
    }

    private static RequestSpecification iam(String account, String action) {
        return signed(account, "iam").formParam("Action", action);
    }

    /** Enables the feature in {@code account} if it is off, and answers with its issuer. */
    private static String enabledIssuer(String account) {
        iam(account, "EnableOutboundWebIdentityFederation").when().post("/");
        return iam(account, "GetOutboundWebIdentityFederationInfo")
                .when().post("/").then().statusCode(200).extract().path(ISSUER);
    }

    private static String token(String account, String algorithm) {
        return signed(account, "sts")
                .formParam("Action", "GetWebIdentityToken")
                .formParam("Audience.member.1", "https://relying-party.example")
                .formParam("SigningAlgorithm", algorithm)
                .when().post("/").then().statusCode(200).extract().path(TOKEN);
    }

    /** The UUID AWS's issuer URL carries as its first host label. */
    private static String issuerId(String issuer) {
        String host = URI.create(issuer).getHost();
        return host.substring(0, host.indexOf('.'));
    }

    private static String base(String issuer) {
        return "/_floci/sts/web-identity/" + issuerId(issuer) + "/.well-known/";
    }

    private static JsonNode keySet(String issuer) throws Exception {
        String body = given().when().get(base(issuer) + "jwks.json")
                .then().statusCode(200).extract().asString();
        return MAPPER.readTree(body);
    }

    private static JsonNode jwt(String token, int index) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(token.split("\\.", -1)[index]));
    }

    private static JsonNode keyById(JsonNode keySet, String kid) {
        for (JsonNode key : keySet.path("keys")) {
            if (kid.equals(key.path("kid").asText())) {
                return key;
            }
        }
        throw new AssertionError("no published key has kid " + kid + ": " + keySet);
    }

    private static BigInteger unsigned(String base64Url) {
        return new BigInteger(1, Base64.getUrlDecoder().decode(base64Url));
    }

    /** The verification key rebuilt from nothing but its JWK. */
    private static PublicKey fromJwk(JsonNode jwk) throws Exception {
        if ("RSA".equals(jwk.path("kty").asText())) {
            return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(
                    unsigned(jwk.path("n").asText()), unsigned(jwk.path("e").asText())));
        }
        assertEquals("P-384", jwk.path("crv").asText());
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp384r1"));
        ECPoint point = new ECPoint(unsigned(jwk.path("x").asText()), unsigned(jwk.path("y").asText()));
        return KeyFactory.getInstance("EC").generatePublic(
                new ECPublicKeySpec(point, parameters.getParameterSpec(ECParameterSpec.class)));
    }

    /** Verifies {@code token} the way a relying party does: by its {@code kid}, from the key set. */
    private static void assertVerifies(String token, JsonNode keySet) throws Exception {
        JsonNode header = jwt(token, 0);
        JsonNode jwk = keyById(keySet, header.path("kid").asText());
        String signatureAlgorithm = switch (header.path("alg").asText()) {
            case "RS256" -> "SHA256withRSA";
            case "ES384" -> "SHA384withECDSAinP1363Format";
            default -> throw new AssertionError("unexpected alg " + header);
        };
        String[] parts = token.split("\\.", -1);
        Signature verifier = Signature.getInstance(signatureAlgorithm);
        verifier.initVerify(fromJwk(jwk));
        verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertTrue(verifier.verify(Base64.getUrlDecoder().decode(parts[2])),
                header.path("alg").asText() + " token does not verify against its published key");
    }

    /**
     * The document names the token's {@code iss} as its issuer, which is what a verifier compares,
     * and its {@code jwks_uri} is a Floci route that serves the key set.
     */
    @Test
    void theDiscoveryDocumentNamesTheTokensIssuerAndAKeySetFlociServes() throws Exception {
        String issuer = enabledIssuer("555555555551");
        String token = token("555555555551", "RS256");
        assertEquals(issuer, jwt(token, 1).path("iss").asText());

        String jwksUri = given().when().get(base(issuer) + "openid-configuration")
                .then().statusCode(200)
                .body("issuer", equalTo(issuer))
                .body("response_types_supported", contains("id_token"))
                .body("subject_types_supported", contains("public"))
                .body("id_token_signing_alg_values_supported", contains("RS256", "ES384"))
                .extract().path("jwks_uri");

        String jwksPath = URI.create(jwksUri).getPath();
        assertEquals(base(issuer) + "jwks.json", jwksPath);
        given().when().get(jwksPath).then().statusCode(200);
    }

    /** Both algorithms' tokens verify against the published key their {@code kid} names. */
    @Test
    void aTokenOfEitherAlgorithmVerifiesAgainstThePublishedKeyItNames() throws Exception {
        String issuer = enabledIssuer("555555555552");
        String rs256 = token("555555555552", "RS256");
        String es384 = token("555555555552", "ES384");

        JsonNode keySet = keySet(issuer);
        assertVerifies(rs256, keySet);
        assertVerifies(es384, keySet);

        JsonNode ec = keyById(keySet, jwt(es384, 0).path("kid").asText());
        assertEquals("EC", ec.path("kty").asText());
        assertEquals("ES384", ec.path("alg").asText());
        assertEquals("sig", ec.path("use").asText());
        assertEquals(48, Base64.getUrlDecoder().decode(ec.path("x").asText()).length);
        assertEquals(48, Base64.getUrlDecoder().decode(ec.path("y").asText()).length);
        JsonNode rsa = keyById(keySet, jwt(rs256, 0).path("kid").asText());
        assertEquals("RSA", rsa.path("kty").asText());
        assertEquals("sig", rsa.path("use").asText());
    }

    /**
     * A verifier that caches the key set before the first token is asked for already holds that
     * token's key: the set names both algorithms from the first read, and minting reuses them.
     */
    @Test
    void theKeySetReadBeforeAnyTokenAlreadyHoldsTheKeyTheTokenIsSignedWith() throws Exception {
        String issuer = enabledIssuer("555555555553");
        JsonNode cached = keySet(issuer);
        assertEquals(2, cached.path("keys").size(), cached.toString());

        assertVerifies(token("555555555553", "RS256"), cached);
        assertVerifies(token("555555555553", "ES384"), cached);
        assertEquals(cached, keySet(issuer), "a later read publishes the same keys");
    }

    /** Each account's issuer publishes that account's keys, and no other account's token verifies. */
    @Test
    void anIssuerPublishesOnlyItsOwnAccountsKeys() throws Exception {
        String first = enabledIssuer("555555555554");
        String second = enabledIssuer("555555555555");
        assertNotEquals(first, second);

        String token = token("555555555554", "RS256");
        String kid = jwt(token, 0).path("kid").asText();
        assertVerifies(token, keySet(first));
        for (JsonNode key : keySet(second).path("keys")) {
            assertNotEquals(kid, key.path("kid").asText(), "the other account publishes this key");
        }
    }

    /**
     * Turning the feature off stops new tokens, but the issuer outlives it and so do the tokens
     * already minted, which a relying party still verifies until they expire.
     */
    @Test
    void theIssuerStaysPublishedWhileTheFeatureIsOff() throws Exception {
        String issuer = enabledIssuer("555555555556");
        String token = token("555555555556", "ES384");
        iam("555555555556", "DisableOutboundWebIdentityFederation").when().post("/")
                .then().statusCode(200);

        given().when().get(base(issuer) + "openid-configuration")
                .then().statusCode(200).body("issuer", equalTo(issuer));
        assertVerifies(token, keySet(issuer));
    }

    /**
     * An issuer no account was given is not found, on either route, whether the id has the
     * issuer's UUID form or not.
     */
    @Test
    void anUnknownIssuerIsNotFound() {
        for (String id : new String[] {"00000000-0000-0000-0000-000000000000", "not-an-issuer"}) {
            String unknown = "/_floci/sts/web-identity/" + id + "/.well-known/";
            given().when().get(unknown + "openid-configuration").then().statusCode(404);
            given().when().get(unknown + "jwks.json").then().statusCode(404);
        }
    }
}
