package io.github.hectorvent.floci.services.cloudfront;

import io.quarkus.test.junit.QuarkusTest;
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
}
