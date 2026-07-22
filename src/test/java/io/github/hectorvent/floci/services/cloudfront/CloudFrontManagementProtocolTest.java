package io.github.hectorvent.floci.services.cloudfront;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
}
