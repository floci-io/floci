package io.github.hectorvent.floci.services.iam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ExtractableResponse;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GetWebIdentityToken} over the wire: the signed JWT it answers with, the switch it reads,
 * and the constraints it is held to.
 *
 * <p>The token is checked here by decoding it rather than by verifying its signature, no endpoint
 * publishing the verification key yet, that being the other half of the feature. What is checked
 * instead is the shape a verifier depends on, including the one detail an ECDSA JWS is easy to get
 * wrong: the signature is the raw {@code R || S} pair, 96 bytes for P-384, rather than the
 * variable-length DER sequence the JDK's default ECDSA signer returns. That the signature verifies
 * at all, against the key the issuer stored and over the claims it was made for, is
 * {@link WebIdentityTokenIssuerTest}, which reaches the key through the store.
 *
 * <p>The feature switch is account-level and shared, so each test sets the state it needs first.
 */
@QuarkusTest
class WebIdentityTokenIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261025/us-east-1/iam/aws4_request";
    private static final String STS_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20261025/us-east-1/sts/aws4_request";

    private static final String TOKEN =
            "GetWebIdentityTokenResponse.GetWebIdentityTokenResult.WebIdentityToken";
    private static final String EXPIRATION =
            "GetWebIdentityTokenResponse.GetWebIdentityTokenResult.Expiration";
    /** A violation is read off the message rather than the body, which escapes its quotes. */
    private static final String CODE = "ErrorResponse.Error.Code";
    private static final String MESSAGE = "ErrorResponse.Error.Message";
    private static final String ISSUER = "GetOutboundWebIdentityFederationInfoResponse"
            + ".GetOutboundWebIdentityFederationInfoResult.IssuerIdentifier";
    /** The namespace the IAM User Guide nests the identity claims and the request's tags under. */
    private static final String CLAIM_NAMESPACE = "https://sts.amazonaws.com/";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static RequestSpecification sts(String action) {
        return given().header("Authorization", STS_AUTH).formParam("Action", action);
    }

    /** One audience and RS256, which is the shape the Reference's own sample request sends. */
    private static RequestSpecification token(String algorithm) {
        return sts("GetWebIdentityToken")
                .formParam("Audience.member.1", "app")
                .formParam("SigningAlgorithm", algorithm);
    }

    /** The same request, signed as the session {@code GetSessionToken} handed out. */
    private static RequestSpecification session(String accessKeyId) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                        + "/20261025/us-east-1/sts/aws4_request")
                .formParam("Action", "GetWebIdentityToken")
                .formParam("Audience.member.1", "app")
                .formParam("SigningAlgorithm", "RS256");
    }

    /** Enables the feature if it is off, and answers with the account's issuer either way. */
    private static String enabledIssuer() {
        iam("EnableOutboundWebIdentityFederation").when().post("/");
        return iam("GetOutboundWebIdentityFederationInfo")
            .when().post("/").then().statusCode(200).extract().path(ISSUER);
    }

    private static void disableFederation() {
        iam("DisableOutboundWebIdentityFederation").when().post("/");
    }

    private static JsonNode part(String jwt, int index) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.", -1)[index]));
    }

    private static byte[] signature(String jwt) {
        return Base64.getUrlDecoder().decode(jwt.split("\\.", -1)[2]);
    }

    /**
     * "The token contains standard JWT claims such as subject, audience, expiration time", the
     * audience being the one the request named, and the issuer the one IAM minted for the account.
     * The default duration is 300 seconds, and {@code Expiration} names the same instant as
     * {@code exp}. Beside them sit the identity claims the guide nests under its own namespace,
     * the account the token was asked for and the region it was asked from, and no
     * {@code request_tags} where the request named no tags.
     */
    @Test
    void aTokenCarriesTheStandardClaimsAndTheAccountsIssuer() throws Exception {
        String issuer = enabledIssuer();

        String jwt = token("RS256").when().post("/").then().statusCode(200)
            .body(EXPIRATION, matchesPattern("\\d{4}-\\d{2}-\\d{2}T.*Z"))
            .extract().path(TOKEN);

        JsonNode header = part(jwt, 0);
        assertEquals("RS256", header.path("alg").asText());
        assertEquals("JWT", header.path("typ").asText());
        assertTrue(header.path("kid").asText().matches("[0-9a-f]{32}"), header.toString());

        JsonNode claims = part(jwt, 1);
        assertEquals(issuer, claims.path("iss").asText());
        assertEquals(1, claims.path("aud").size(), claims.toString());
        assertEquals("app", claims.path("aud").get(0).asText());
        assertTrue(claims.path("sub").asText().matches("arn:aws[a-z-]*:iam::\\d{12}:.+"),
                claims.toString());
        assertEquals(300, claims.path("exp").asLong() - claims.path("iat").asLong());
        assertNotNull(claims.path("jti").asText());
        assertEquals(256, signature(jwt).length, "RS256 signs with the 2048-bit account key");

        JsonNode identity = claims.path(CLAIM_NAMESPACE);
        assertTrue(identity.path("aws_account").asText().matches("\\d{12}"), claims.toString());
        assertEquals("us-east-1", identity.path("source_region").asText(), claims.toString());
        assertTrue(identity.path("request_tags").isMissingNode(),
                "a request that named no tags carries no request_tags claim");
    }

    /**
     * {@code ES384} is "ECDSA using P-384 curve with SHA-384", and JWS defines that signature as
     * the raw {@code R || S} pair: two 48-byte halves for P-384. A DER sequence would be close to
     * this length and would verify nowhere.
     */
    @Test
    void anEs384TokenSignsWithTheRawPairJwsDefines() throws Exception {
        enabledIssuer();

        String jwt = token("ES384").when().post("/").then().statusCode(200).extract().path(TOKEN);

        assertEquals("ES384", part(jwt, 0).path("alg").asText());
        assertEquals(96, signature(jwt).length, "ES384 is R || S over P-384, not DER");
    }

    /**
     * Request tags "appear under the request_tags field in the token", which the guide nests under
     * the same namespace as the identity claims. That nesting is also what keeps a tag named after
     * a claim STS sets out of it: such a tag is a key under {@code request_tags}, so it cannot
     * lengthen the token's own life.
     */
    @Test
    void tagsBecomeRequestTagsUnderTheNamespaceRatherThanClaimsOfTheirOwn() throws Exception {
        enabledIssuer();

        String jwt = token("RS256")
            .formParam("Tags.member.1.Key", "Team")
            .formParam("Tags.member.1.Value", "platform")
            .formParam("Tags.member.2.Key", "exp")
            .formParam("Tags.member.2.Value", "99999999999")
        .when().post("/").then().statusCode(200).extract().path(TOKEN);

        JsonNode claims = part(jwt, 1);
        JsonNode requestTags = claims.path(CLAIM_NAMESPACE).path("request_tags");
        assertEquals("platform", requestTags.path("Team").asText(), claims.toString());
        assertEquals("99999999999", requestTags.path("exp").asText(),
                "a tag named after a claim is a request tag, not that claim");
        assertTrue(claims.path("Team").isMissingNode(), "a tag is not a claim of its own");
        assertEquals(300, claims.path("exp").asLong() - claims.path("iat").asLong(),
                "the request's own exp tag does not become the expiry");
    }

    /**
     * The duration is 60 to 3600, and {@code Expiration} is "determined by adding the
     * DurationSeconds value to the time the token was issued".
     */
    @Test
    void theDurationIsHeldToItsRangeAndSetsTheExpiry() throws Exception {
        enabledIssuer();

        ExtractableResponse<Response> issued = token("RS256").formParam("DurationSeconds", "3600")
            .when().post("/").then().statusCode(200).extract();
        JsonNode claims = part(issued.path(TOKEN), 1);
        assertEquals(3600, claims.path("exp").asLong() - claims.path("iat").asLong());
        assertEquals(claims.path("exp").asLong(),
                Instant.parse(issued.path(EXPIRATION)).getEpochSecond(),
                "Expiration names the instant the exp claim does");

        token("RS256").formParam("DurationSeconds", "59").when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"))
            .body(containsString("Member must have value greater than or equal to 60"));
        token("RS256").formParam("DurationSeconds", "3601").when().post("/").then().statusCode(400)
            .body(containsString("Member must have value less than or equal to 3600"));
    }

    /** The feature's own refusal, which is not IAM's {@code FeatureDisabled} 404. */
    @Test
    void theSwitchBeingOffIsTheOperationsOwnRefusal() {
        disableFederation();

        token("RS256").when().post("/").then().statusCode(403)
            .body(CODE, equalTo("OutboundWebIdentityFederationDisabledException"))
            .body(MESSAGE, containsString("The outbound web identity federation feature is not"
                    + " enabled for this account."))
            .body(not(containsString("FeatureDisabled")));

        enabledIssuer();
    }

    /**
     * The validation layer runs ahead of the operation, so a request that breaks a declared
     * constraint is the 400 even where the feature the operation reads is off.
     */
    @Test
    void theDeclaredConstraintsAreReadAheadOfTheSwitch() {
        disableFederation();

        sts("GetWebIdentityToken").formParam("SigningAlgorithm", "RS256")
        .when().post("/").then().statusCode(400)
            .body(CODE, equalTo("ValidationError"))
            .body(MESSAGE, equalTo("1 validation error detected: Value null at 'audience' failed"
                    + " to satisfy constraint: Member must not be null"))
            .body(not(containsString("OutboundWebIdentityFederationDisabled")));

        enabledIssuer();
    }

    /**
     * Both required members are reported together when both are absent, and a member sent empty is
     * a member that is present, so it breaks the minimum length of 1 rather than reading as absent.
     */
    @Test
    void theRequiredMembersAndTheirBoundsAreReportedTogether() {
        enabledIssuer();

        sts("GetWebIdentityToken").when().post("/").then().statusCode(400)
            .body(MESSAGE, equalTo("2 validation errors detected: Value null at 'audience' failed"
                    + " to satisfy constraint: Member must not be null; Value null at"
                    + " 'signingAlgorithm' failed to satisfy constraint: Member must not be"
                    + " null"));

        sts("GetWebIdentityToken")
            .formParam("Audience.member.1", "")
            .formParam("SigningAlgorithm", "RS256")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, containsString("Value '' at 'audience.1.member' failed to satisfy"
                    + " constraint: Member must have length greater than or equal to 1"));

        RequestSpecification eleven = sts("GetWebIdentityToken")
                .formParam("SigningAlgorithm", "RS256");
        for (int audience = 1; audience <= 11; audience++) {
            eleven.formParam("Audience.member." + audience, "app" + audience);
        }
        eleven.when().post("/").then().statusCode(400)
            .body(MESSAGE, containsString("Value at 'audience' failed to satisfy constraint:"
                    + " Member must have length less than or equal to 10"));
    }

    /**
     * {@code jwtAlgorithmType} publishes a fixed length of 5 and no enum, so the length is the
     * declared constraint and the two values the Reference names are read from it.
     */
    @Test
    void aSigningAlgorithmIsHeldToItsLengthAndThenToTheTwoValues() {
        enabledIssuer();

        token("RS25").when().post("/").then().statusCode(400)
            .body(MESSAGE, containsString("Value 'RS25' at 'signingAlgorithm' failed to satisfy"
                    + " constraint: Member must have length greater than or equal to 5"));

        token("HS256").when().post("/").then().statusCode(400)
            .body(MESSAGE, containsString("Value 'HS256' at 'signingAlgorithm' failed to satisfy"
                    + " constraint: Member must satisfy enum value set: [RS256, ES384]"));
    }

    /**
     * The session ceiling against a real session rather than a stubbed one: {@code GetSessionToken}
     * hands out credentials that expire, and a token asked for past that instant is refused.
     * {@code StsQueryHandlerTest} holds the boundary itself on a fixed clock; what this adds is
     * that the expiration is found through the session the caller is actually on.
     */
    @Test
    void aTokenIsRefusedWhereItWouldOutliveTheCallersOwnSession() throws Exception {
        enabledIssuer();
        String sessionKey = sts("GetSessionToken").formParam("DurationSeconds", "900")
            .when().post("/").then().statusCode(200)
            .extract().path("GetSessionTokenResponse.GetSessionTokenResult.Credentials.AccessKeyId");

        session(sessionKey).formParam("DurationSeconds", "3600")
        .when().post("/").then().statusCode(403)
            .body(CODE, equalTo("SessionDurationEscalationException"))
            .body(MESSAGE, containsString("You cannot use this operation to extend the lifetime"
                    + " of a session beyond what was granted when the session was originally"
                    + " created."));

        String jwt = session(sessionKey).formParam("DurationSeconds", "60")
            .when().post("/").then().statusCode(200).extract().path(TOKEN);
        JsonNode claims = part(jwt, 1);
        assertEquals(60, claims.path("exp").asLong() - claims.path("iat").asLong(),
                "a token inside the session is issued for what it asked for");
    }

    /**
     * The same refusal over the wire for a session key nothing issued, which reaches the operation
     * because enforcement is off here: no token is minted, and none names the account root.
     */
    @Test
    void aSessionKeyWithNoSessionBehindItIsRefusedAsExpired() {
        enabledIssuer();

        session("ASIANOSUCHSESSION001").when().post("/").then().statusCode(403)
            .body(CODE, equalTo("ExpiredTokenException"))
            .body(MESSAGE, equalTo("The security token included in the request has expired."))
            .body(not(containsString(":root")));
    }

    /** A {@code Tag} declares its key and its value required, and each carries its own shape. */
    @Test
    void aTagIsHeldToItsOwnMembers() {
        enabledIssuer();

        token("RS256").formParam("Tags.member.1.Key", "Team")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, containsString("Value null at 'tags.1.member.value' failed to satisfy"
                    + " constraint: Member must not be null"));

        token("RS256")
            .formParam("Tags.member.1.Key", "Team?")
            .formParam("Tags.member.1.Value", "platform")
        .when().post("/").then().statusCode(400)
            .body(MESSAGE, containsString("at 'tags.1.member.key' failed to satisfy constraint:"
                    + " Member must satisfy regular expression pattern"));

        RequestSpecification fiftyOne = token("RS256");
        for (int tag = 1; tag <= 51; tag++) {
            fiftyOne.formParam("Tags.member." + tag + ".Key", "Key" + tag)
                    .formParam("Tags.member." + tag + ".Value", "value" + tag);
        }
        fiftyOne.when().post("/").then().statusCode(400)
            .body(MESSAGE, containsString("Value at 'tags' failed to satisfy constraint: Member"
                    + " must have length less than or equal to 50"));
    }
}
