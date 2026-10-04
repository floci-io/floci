package io.github.hectorvent.floci.services.iam;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The account property and outbound federation operations over the wire.
 *
 * <p>These are account-level singletons, so the tests share one account and are written not to
 * depend on each other's properties: each uses its own namespace.
 */
@QuarkusTest
class AccountPropertiesIntegrationTest {

    private static final String IAM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260227/us-east-1/iam/aws4_request";

    private static RequestSpecification iam(String action) {
        return given().header("Authorization", IAM_AUTH).formParam("Action", action);
    }

    private static String namespace() {
        return "Ns" + Long.toString(System.nanoTime(), 36).replace("-", "");
    }

    private static final String PROPERTIES =
            "GetAccountPropertiesResponse.GetAccountPropertiesResult.Properties.";

    @Test
    void propertiesRoundTripThroughPutAndGet() {
        String ns = namespace();

        iam("PutAccountProperties")
            .formParam("Properties.entry.1.key", ns + "/RoleManagerEnabled")
            .formParam("Properties.entry.1.value", "true")
            .formParam("Properties.entry.2.key", ns + "/Mode")
            .formParam("Properties.entry.2.value", "strict")
        .when().post("/").then().statusCode(200);

        iam("GetAccountProperties").when().post("/").then().statusCode(200)
            .body(containsString(ns + "/RoleManagerEnabled"))
            .body(containsString(ns + "/Mode"))
            .body(containsString("strict"));
    }

    /** "All keys must belong to the same namespace." */
    @Test
    void mixingNamespacesInOneRequestIsRejected() {
        String first = namespace();
        String second = namespace();

        iam("PutAccountProperties")
            .formParam("Properties.entry.1.key", first + "/Alpha")
            .formParam("Properties.entry.1.value", "1")
            .formParam("Properties.entry.2.key", second + "/Beta")
            .formParam("Properties.entry.2.value", "2")
        .when().post("/").then().statusCode(400)
            .body(containsString("InvalidInput"))
            .body(containsString("same namespace"));

        // Rejected whole, not partly applied: neither key was written.
        iam("GetAccountProperties").when().post("/").then().statusCode(200)
            .body(not(containsString(first + "/Alpha")))
            .body(not(containsString(second + "/Beta")));
    }

    /** The key is Namespace/PropertyName: exactly one slash, not at either end. */
    @Test
    void aMalformedKeyIsRejected() {
        for (String key : List.of("NoSlash", "/LeadingSlash", "Trailing/", "Two/Slashes/Here",
                "1StartsWithADigit", "Has Space/Prop", "Ns/Prop!")) {
            iam("PutAccountProperties")
                .formParam("Properties.entry.1.key", key)
                .formParam("Properties.entry.1.value", "x")
            .when().post("/").then().statusCode(400)
                .body(containsString("InvalidInput"));
        }
    }

    @Test
    void aKeyOrValueOutsideItsLengthBoundsIsRejected() {
        String ns = namespace();
        // keyLength is 1 to 50.
        iam("PutAccountProperties")
            .formParam("Properties.entry.1.key", ns + "/" + "P".repeat(60))
            .formParam("Properties.entry.1.value", "x")
        .when().post("/").then().statusCode(400).body(containsString("InvalidInput"));

        // valueLength is 1 to 1024.
        iam("PutAccountProperties")
            .formParam("Properties.entry.1.key", ns + "/TooLongValue")
            .formParam("Properties.entry.1.value", "v".repeat(1025))
        .when().post("/").then().statusCode(400).body(containsString("InvalidInput"));

        // And the top of the range is accepted, so the bound is not off by one.
        iam("PutAccountProperties")
            .formParam("Properties.entry.1.key", ns + "/AtTheLimit")
            .formParam("Properties.entry.1.value", "v".repeat(1024))
        .when().post("/").then().statusCode(200);
    }

    @Test
    void anAbsentPropertiesMapIsRejected() {
        iam("PutAccountProperties").when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"));
    }

    @Test
    void theTokenVersionTakesOnlyTheTwoDocumentedValues() {
        for (String version : List.of("v1Token", "v2Token")) {
            iam("SetSecurityTokenServicePreferences")
                .formParam("GlobalEndpointTokenVersion", version)
            .when().post("/").then().statusCode(200);
        }

        for (String version : List.of("v3Token", "V1Token", "")) {
            iam("SetSecurityTokenServicePreferences")
                .formParam("GlobalEndpointTokenVersion", version)
            .when().post("/").then().statusCode(400)
                .body(containsString("ValidationError"));
        }

        iam("SetSecurityTokenServicePreferences").when().post("/").then().statusCode(400)
            .body(containsString("ValidationError"))
            .body(containsString("globalEndpointTokenVersion"));
    }

    /**
     * The version set here is what GetAccountSummary reports, which the operation's own
     * description requires: the version "is reported in the GlobalEndpointTokenVersion entry of
     * the response of the GetAccountSummary operation". Reporting a constant leaves a caller that
     * set v2Token reading back 1.
     */
    @Test
    void theTokenVersionIsWhatTheAccountSummaryReports() {
        String key = "GetAccountSummaryResponse.GetAccountSummaryResult.SummaryMap.entry"
                + ".find { it.key == 'GlobalEndpointTokenVersion' }.value";

        iam("SetSecurityTokenServicePreferences")
            .formParam("GlobalEndpointTokenVersion", "v2Token")
        .when().post("/").then().statusCode(200);
        iam("GetAccountSummary").when().post("/").then().statusCode(200)
            .body(key, equalTo("2"));

        iam("SetSecurityTokenServicePreferences")
            .formParam("GlobalEndpointTokenVersion", "v1Token")
        .when().post("/").then().statusCode(200);
        iam("GetAccountSummary").when().post("/").then().statusCode(200)
            .body(key, equalTo("1"));
    }

    /**
     * The federation lifecycle, in one test because it is one account-level switch: the order of
     * enable and disable is the behaviour under test and separate tests would race each other.
     *
     * <p>The asymmetry is the interesting part: enabling twice is 409, disabling twice is 404.
     */
    @Test
    void theFederationSwitchRefusesBothRedundantDirections() {
        String enabled = "EnableOutboundWebIdentityFederationResponse"
                + ".EnableOutboundWebIdentityFederationResult.IssuerIdentifier";
        String info = "GetOutboundWebIdentityFederationInfoResponse"
                + ".GetOutboundWebIdentityFederationInfoResult.";

        // Start from a known state: disabled, however earlier runs left it.
        iam("DisableOutboundWebIdentityFederation").when().post("/");

        iam("GetOutboundWebIdentityFederationInfo").when().post("/").then().statusCode(404)
            .body(containsString("FeatureDisabled"));

        String issuer = iam("EnableOutboundWebIdentityFederation")
        .when().post("/").then().statusCode(200)
            // https://<uuid>.tokens.sts.global.<dual-stack suffix>, api.aws in this partition.
            .body(enabled, matchesPattern(
                    "https://[0-9a-f-]{36}\\.tokens\\.sts\\.global\\.api\\.aws"))
            .extract().path(enabled);
        assertNotNull(issuer);

        iam("EnableOutboundWebIdentityFederation").when().post("/").then().statusCode(409)
            .body(containsString("FeatureEnabled"))
            .body(containsString("cannot enable the feature multiple times"));

        iam("GetOutboundWebIdentityFederationInfo").when().post("/").then().statusCode(200)
            .body(info + "IssuerIdentifier", equalTo(issuer))
            .body(info + "JwtVendingEnabled", equalTo("true"));

        iam("DisableOutboundWebIdentityFederation").when().post("/").then().statusCode(200);
        iam("DisableOutboundWebIdentityFederation").when().post("/").then().statusCode(404)
            .body(containsString("FeatureDisabled"))
            .body(containsString("cannot disable the feature multiple times"));

        // Re-enabling returns the same issuer: a relying party has pinned it.
        String reissued = iam("EnableOutboundWebIdentityFederation")
        .when().post("/").then().statusCode(200).extract().path(enabled);
        assertEquals(issuer, reissued, "the issuer is minted once per account");

        iam("DisableOutboundWebIdentityFederation").when().post("/").then().statusCode(200);
    }

    /**
     * Without an Authorization header the Query controller infers the service from the action name
     * alone, so these have to be in its IAM set or they fall through to SQS.
     */
    @Test
    void theActionsRouteToIamWithNoAuthHeader() {
        for (String action : List.of("GetAccountProperties", "PutAccountProperties",
                "SetSecurityTokenServicePreferences", "EnableOutboundWebIdentityFederation",
                "DisableOutboundWebIdentityFederation",
                "GetOutboundWebIdentityFederationInfo")) {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", action)
                    .formParam("Version", "2010-05-08")
            .when().post("/").then()
                .body(containsString("iam.amazonaws.com/doc/2010-05-08"));
        }
    }
}
