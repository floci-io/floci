package io.github.hectorvent.floci.services.cloudfront;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.xml.HasXPath.hasXPath;

/** Verifies CloudFront REST XML payload roots against the AWS service model. */
@QuarkusTest
class CloudFrontManagementProtocolTest {

    private static final String API = "/2020-05-31/";

    @ParameterizedTest(name = "{0} returns {1}")
    @CsvSource({
            "distribution, DistributionList",
            "cache-policy, CachePolicyList",
            "origin-request-policy, OriginRequestPolicyList",
            "response-headers-policy, ResponseHeadersPolicyList",
            "origin-access-control, OriginAccessControlList",
            "origin-access-identity/cloudfront, CloudFrontOriginAccessIdentityList",
            "function, FunctionList",
            "tagging?Resource=arn%3Aaws%3Acloudfront%3A%3A000000000000%3Adistribution%2Fmissing, Tags",
            "continuous-deployment-policy, ContinuousDeploymentPolicyList",
            "public-key, PublicKeyList",
            "key-group, KeyGroupList",
            "realtime-log-config, RealtimeLogConfigs",
            "field-level-encryption, FieldLevelEncryptionList",
            "field-level-encryption-profile, FieldLevelEncryptionProfileList"
    })
    void listOperationsUseTheirModeledPayloadRoot(String endpoint, String expectedRoot) {
        given()
        .when()
            .get(API + endpoint)
        .then()
            .statusCode(200)
            .body(hasXPath("local-name(/*)", equalTo(expectedRoot)))
            .body(hasXPath("namespace-uri(/*)", equalTo(
                    "http://cloudfront.amazonaws.com/doc/2020-05-31/")));
    }

    @ParameterizedTest(name = "{0} omits unmodeled Marker and IsTruncated members")
    @ValueSource(strings = {
            "cache-policy",
            "origin-request-policy",
            "response-headers-policy",
            "function",
            "continuous-deployment-policy",
            "public-key",
            "key-group",
            "field-level-encryption",
            "field-level-encryption-profile"
    })
    void compactListPayloadsUseOnlyTheirModeledPaginationMembers(String endpoint) {
        given()
        .when()
            .get(API + endpoint)
        .then()
            .statusCode(200)
            .body(not(hasXPath("/*/*[local-name()='Marker']")))
            .body(not(hasXPath("/*/*[local-name()='IsTruncated']")));
    }

    @Test
    void realtimeLogListOmitsTheUnmodeledQuantityMember() {
        given()
        .when()
            .get(API + "realtime-log-config")
        .then()
            .statusCode(200)
            .body(not(hasXPath("/*/*[local-name()='Quantity']")))
            .body(hasXPath("/*/*[local-name()='Marker']"))
            .body(hasXPath("/*/*[local-name()='IsTruncated']"));
    }

    @Test
    void responseHeadersPolicyListRejectsNonModeledTypeCasing() {
        given()
            .queryParam("Type", "MANAGED")
        .when()
            .get(API + "response-headers-policy")
        .then()
            .statusCode(400)
            .body(hasXPath(
                    "//*[local-name()='Code']/text()",
                    equalTo("InvalidArgument")));
    }

    @Test
    void responseHeadersPolicyListRejectsUnknownMarker() {
        given()
            .queryParam("Marker", "missing-marker")
        .when()
            .get(API + "response-headers-policy")
        .then()
            .statusCode(400)
            .body(hasXPath(
                    "//*[local-name()='Code']/text()",
                    equalTo("InvalidArgument")));
    }

    @Test
    void describeAndPublishFunctionPreserveBothStages() {
        String name = "describe-test-" + UUID.randomUUID();
        String createBody = """
                <CreateFunctionRequest xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <Name>%s</Name>
                  <FunctionConfig>
                    <Comment>routing function</Comment>
                    <Runtime>cloudfront-js-2.0</Runtime>
                  </FunctionConfig>
                  <FunctionCode>function handler(event) { return event.request; }</FunctionCode>
                </CreateFunctionRequest>
                """.formatted(name);

        String developmentEtag = given()
            .contentType("application/xml")
            .body(createBody)
        .when()
            .post(API + "function")
        .then()
            .statusCode(201)
            .extract().header("ETag");

        given()
            .queryParam("Stage", "DEVELOPMENT")
        .when()
            .get(API + "function/" + name + "/describe")
        .then()
            .statusCode(200)
            .header("ETag", equalTo(developmentEtag))
            .body(hasXPath("//*[local-name()='Stage']/text()", equalTo("DEVELOPMENT")));

        given()
            .header("If-Match", developmentEtag)
        .when()
            .post(API + "function/" + name + "/publish")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='Stage']/text()", equalTo("LIVE")));

        given()
            .queryParam("Stage", "DEVELOPMENT")
        .when()
            .get(API + "function/" + name + "/describe")
        .then()
            .statusCode(200)
            .header("ETag", equalTo(developmentEtag));

        given()
            .queryParam("Stage", "LIVE")
        .when()
            .get(API + "function/" + name + "/describe")
        .then()
            .statusCode(200)
            .body(hasXPath("//*[local-name()='Stage']/text()", equalTo("LIVE")));

        given()
            .queryParam("Stage", "TESTING")
        .when()
            .get(API + "function/" + name + "/describe")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']/text()", equalTo("InvalidArgument")));

        given()
            .queryParam("Stage", "DEVELOPMENT")
        .when()
            .get(API + "function/" + name)
        .then()
            .statusCode(200)
            .contentType("application/octet-stream")
            .body(equalTo("function handler(event) { return event.request; }"));

        given()
            .header("If-Match", developmentEtag)
        .when()
            .delete(API + "function/" + name)
        .then()
            .statusCode(204);
    }

    @Test
    void createOriginAccessControlDoesNotDefaultRequiredConfiguration() {
        String body = """
                <OriginAccessControlConfig
                    xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <Name>missing-required-fields</Name>
                </OriginAccessControlConfig>
                """;

        given()
            .contentType("application/xml")
            .body(body)
        .when()
            .post(API + "origin-access-control")
        .then()
            .statusCode(400)
            .body(hasXPath(
                    "//*[local-name()='Code']/text()",
                    equalTo("InvalidArgument")));
    }

    @Test
    void policyCreationRejectsMissingConfigurationRoot() {
        given()
            .contentType("application/xml")
            .body("<UnexpectedConfig><Name>missing-cache-root</Name></UnexpectedConfig>")
        .when()
            .post(API + "cache-policy")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']/text()", equalTo("InvalidArgument")));

        given()
            .contentType("application/xml")
            .body("<UnexpectedConfig><Name>missing-origin-root</Name></UnexpectedConfig>")
        .when()
            .post(API + "origin-request-policy")
        .then()
            .statusCode(400)
            .body(hasXPath("//*[local-name()='Code']/text()", equalTo("InvalidArgument")));
    }

    @Test
    void cachePolicyReadFillsAwsTtlDefaults() {
        String name = "default-ttl-cache-policy-" + UUID.randomUUID();
        String id = null;
        String etag = null;
        try {
            Response created = given()
                    .contentType("application/xml")
                    .body("""
                            <CachePolicyConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                              <Name>%s</Name>
                              <MinTTL>10</MinTTL>
                              <ParametersInCacheKeyAndForwardedToOrigin>
                                <EnableAcceptEncodingGzip>false</EnableAcceptEncodingGzip>
                                <EnableAcceptEncodingBrotli>false</EnableAcceptEncodingBrotli>
                                <HeadersConfig><HeaderBehavior>none</HeaderBehavior></HeadersConfig>
                                <CookiesConfig><CookieBehavior>none</CookieBehavior></CookiesConfig>
                                <QueryStringsConfig>
                                  <QueryStringBehavior>none</QueryStringBehavior>
                                </QueryStringsConfig>
                              </ParametersInCacheKeyAndForwardedToOrigin>
                            </CachePolicyConfig>
                            """.formatted(name))
                    .when()
                    .post(API + "cache-policy");
            created.then()
                    .statusCode(201)
                    .body(hasXPath("//*[local-name()='DefaultTTL']/text()", equalTo("86400")))
                    .body(hasXPath("//*[local-name()='MaxTTL']/text()", equalTo("31536000")));
            id = XmlParser.extractFirst(created.asString(), "Id", null);
            etag = created.header("ETag");

            given()
                    .when()
                    .get(API + "cache-policy/" + id + "/config")
                    .then()
                    .statusCode(200)
                    .body(hasXPath("//*[local-name()='DefaultTTL']/text()", equalTo("86400")))
                    .body(hasXPath("//*[local-name()='MaxTTL']/text()", equalTo("31536000")));
        } finally {
            if (id != null && etag != null) {
                given().header("If-Match", etag)
                        .when().delete(API + "cache-policy/" + id)
                        .then().statusCode(204);
            }
        }
    }

    @Test
    void cachePolicyPreservesItsCompleteConfigAcrossManagementOperations() {
        String name = "complete-cache-policy-" + UUID.randomUUID();
        String id = null;
        String etag = null;
        try {
            Response created = given()
                    .contentType("application/xml")
                    .body(cachePolicyBody(name, "created", "3600", "session"))
                    .when()
                    .post(API + "cache-policy");
            created.then()
                    .statusCode(201)
                    .body(hasXPath("//*[local-name()='MinTTL']/text()", equalTo("10")))
                    .body(hasXPath("//*[local-name()='DefaultTTL']/text()", equalTo("3600")))
                    .body(hasXPath("//*[local-name()='MaxTTL']/text()", equalTo("86400")))
                    .body(hasXPath("//*[local-name()='EnableAcceptEncodingGzip']/text()", equalTo("true")))
                    .body(hasXPath("//*[local-name()='EnableAcceptEncodingBrotli']/text()", equalTo("true")))
                    .body(hasXPath("//*[local-name()='Cookies']/*[local-name()='Quantity']/text()",
                            equalTo("2")))
                    .body(hasXPath("//*[local-name()='Cookies']//*[local-name()='Name' and text()='session']"));
            id = XmlParser.extractFirst(created.asString(), "Id", null);
            etag = created.header("ETag");

            given()
                    .when()
                    .get(API + "cache-policy/" + id + "/config")
                    .then()
                    .statusCode(200)
                    .header("ETag", equalTo(etag))
                    .body(hasXPath("//*[local-name()='DefaultTTL']/text()", equalTo("3600")))
                    .body(hasXPath(
                            "//*[local-name()='Headers']//*[local-name()='Name' and text()='Accept-Language']"))
                    .body(hasXPath(
                            "//*[local-name()='QueryStrings']//*[local-name()='Name' and text()='page']"));

            given()
                    .when()
                    .get(API + "cache-policy")
                    .then()
                    .statusCode(200)
                    .body(hasXPath("//*[local-name()='CachePolicy'][*[local-name()='Id' and text()='" + id
                            + "']]//*[local-name()='DefaultTTL']/text()", equalTo("3600")));

            Response updated = given()
                    .contentType("application/xml")
                    .header("If-Match", etag)
                    .body(cachePolicyBody(name, "updated", "7200", "updated-session"))
                    .when()
                    .put(API + "cache-policy/" + id);
            updated.then()
                    .statusCode(200)
                    .header("ETag", not(equalTo(etag)))
                    .body(hasXPath("//*[local-name()='Comment']/text()", equalTo("updated")))
                    .body(hasXPath("//*[local-name()='DefaultTTL']/text()", equalTo("7200")))
                    .body(hasXPath(
                            "//*[local-name()='Cookies']//*[local-name()='Name' and text()='updated-session']"));
            etag = updated.header("ETag");
        } finally {
            if (id != null && etag != null) {
                given().header("If-Match", etag)
                        .when().delete(API + "cache-policy/" + id)
                        .then().statusCode(204);
            }
        }
    }

    @Test
    void originRequestPolicyPreservesItsCompleteConfigAcrossManagementOperations() {
        String name = "complete-origin-policy-" + UUID.randomUUID();
        String id = null;
        String etag = null;
        try {
            Response created = given()
                    .contentType("application/xml")
                    .body(originRequestPolicyBody(name, "created", "Origin"))
                    .when()
                    .post(API + "origin-request-policy");
            created.then()
                    .statusCode(201)
                    .body(hasXPath("//*[local-name()='HeaderBehavior']/text()", equalTo("whitelist")))
                    .body(hasXPath("//*[local-name()='Headers']//*[local-name()='Name' and text()='Origin']"))
                    .body(hasXPath("//*[local-name()='Cookies']//*[local-name()='Name' and text()='session']"))
                    .body(hasXPath(
                            "//*[local-name()='QueryStrings']//*[local-name()='Name' and text()='page']"));
            id = XmlParser.extractFirst(created.asString(), "Id", null);
            etag = created.header("ETag");

            given()
                    .when()
                    .get(API + "origin-request-policy/" + id + "/config")
                    .then()
                    .statusCode(200)
                    .header("ETag", equalTo(etag))
                    .body(hasXPath("//*[local-name()='HeaderBehavior']/text()", equalTo("whitelist")))
                    .body(hasXPath("//*[local-name()='Headers']/*[local-name()='Quantity']/text()",
                            equalTo("1")));

            given()
                    .when()
                    .get(API + "origin-request-policy")
                    .then()
                    .statusCode(200)
                    .body(hasXPath("//*[local-name()='OriginRequestPolicy'][*[local-name()='Id' and text()='" + id
                            + "']]//*[local-name()='QueryStringBehavior']/text()", equalTo("whitelist")));

            Response updated = given()
                    .contentType("application/xml")
                    .header("If-Match", etag)
                    .body(originRequestPolicyBody(name, "updated", "CloudFront-Viewer-Country"))
                    .when()
                    .put(API + "origin-request-policy/" + id);
            updated.then()
                    .statusCode(200)
                    .header("ETag", not(equalTo(etag)))
                    .body(hasXPath("//*[local-name()='Comment']/text()", equalTo("updated")))
                    .body(hasXPath(
                            "//*[local-name()='Headers']//*[local-name()='Name'"
                                    + " and text()='CloudFront-Viewer-Country']"));
            etag = updated.header("ETag");
        } finally {
            if (id != null && etag != null) {
                given().header("If-Match", etag)
                        .when().delete(API + "origin-request-policy/" + id)
                        .then().statusCode(204);
            }
        }
    }

    @Test
    void managedOriginRequestPoliciesAreReadableListableAndImmutable() {
        String exceptHost = "b689b0a8-53d0-40ab-baf2-68738e2966ac";
        given()
            .when()
                .get(API + "origin-request-policy/" + exceptHost)
            .then()
                .statusCode(200)
                .body(hasXPath("//*[local-name()='Name']/text()",
                        equalTo("Managed-AllViewerExceptHostHeader")))
                .body(hasXPath("//*[local-name()='HeaderBehavior']/text()", equalTo("allExcept")))
                .body(hasXPath("//*[local-name()='Headers']//*[local-name()='Name']/text()",
                        equalTo("Host")))
                .body(hasXPath("//*[local-name()='CookieBehavior']/text()", equalTo("all")))
                .body(hasXPath("//*[local-name()='QueryStringBehavior']/text()", equalTo("all")));

        given()
                .queryParam("Type", "managed")
            .when()
                .get(API + "origin-request-policy")
            .then()
                .statusCode(200)
                .body(hasXPath("/*/*[local-name()='Quantity']/text()", equalTo("8")))
                .body(hasXPath("//*[local-name()='OriginRequestPolicySummary']"
                        + "[.//*[local-name()='Id' and text()='" + exceptHost + "']]"
                        + "/*[local-name()='Type']/text()", equalTo("managed")));

        given()
                .header("If-Match", "E23ZP02F085DFQ")
            .when()
                .delete(API + "origin-request-policy/" + exceptHost)
            .then()
                .statusCode(400)
                .body(hasXPath("//*[local-name()='Code']/text()", equalTo("IllegalDelete")));
    }

    private static String cachePolicyBody(String name, String comment, String defaultTtl, String cookie) {
        return """
                <CachePolicyConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <Name>%s</Name><Comment>%s</Comment>
                  <MinTTL>10</MinTTL><DefaultTTL>%s</DefaultTTL><MaxTTL>86400</MaxTTL>
                  <ParametersInCacheKeyAndForwardedToOrigin>
                    <EnableAcceptEncodingGzip>true</EnableAcceptEncodingGzip>
                    <EnableAcceptEncodingBrotli>true</EnableAcceptEncodingBrotli>
                    <HeadersConfig><HeaderBehavior>whitelist</HeaderBehavior>
                      <Headers><Quantity>1</Quantity><Items><Name>Accept-Language</Name></Items></Headers>
                    </HeadersConfig>
                    <CookiesConfig><CookieBehavior>whitelist</CookieBehavior>
                      <Cookies><Quantity>2</Quantity><Items><Name>%s</Name><Name>locale</Name></Items></Cookies>
                    </CookiesConfig>
                    <QueryStringsConfig><QueryStringBehavior>whitelist</QueryStringBehavior>
                      <QueryStrings><Quantity>1</Quantity><Items><Name>page</Name></Items></QueryStrings>
                    </QueryStringsConfig>
                  </ParametersInCacheKeyAndForwardedToOrigin>
                </CachePolicyConfig>
                """.formatted(name, comment, defaultTtl, cookie);
    }

    private static String originRequestPolicyBody(String name, String comment, String header) {
        return """
                <OriginRequestPolicyConfig xmlns="http://cloudfront.amazonaws.com/doc/2020-05-31/">
                  <Name>%s</Name><Comment>%s</Comment>
                  <HeadersConfig><HeaderBehavior>whitelist</HeaderBehavior>
                    <Headers><Quantity>1</Quantity><Items><Name>%s</Name></Items></Headers>
                  </HeadersConfig>
                  <CookiesConfig><CookieBehavior>whitelist</CookieBehavior>
                    <Cookies><Quantity>1</Quantity><Items><Name>session</Name></Items></Cookies>
                  </CookiesConfig>
                  <QueryStringsConfig><QueryStringBehavior>whitelist</QueryStringBehavior>
                    <QueryStrings><Quantity>1</Quantity><Items><Name>page</Name></Items></QueryStrings>
                  </QueryStringsConfig>
                </OriginRequestPolicyConfig>
                """.formatted(name, comment, header);
    }
}
