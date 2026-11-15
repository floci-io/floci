package io.floci.aws.services.wafv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

/**
 * WAF v2's {@code REGIONAL} web ACLs, IP sets, regex pattern sets and rule groups are Regional: each is listed and
 * reached only in the Region it was created in, by its Id or its ARN, and its name can be used again in another
 * Region. A {@code CLOUDFRONT} one lives in the partition's CloudFront Region, which a request reaches from any Region.
 */
@QuarkusTest
class WafV2RegionScopingIntegrationTest {

    private static final String HOME = "eu-west-1";
    private static final String OTHER = "eu-central-1";
    private static final String VISIBILITY = "\"VisibilityConfig\":{\"SampledRequestsEnabled\":false,"
            + "\"CloudWatchMetricsEnabled\":false,\"MetricName\":\"m\"}";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRegionalWebAclIsListedAndReachedOnlyInItsRegion() {
        String name = "acl-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"DefaultAction\":{\"Allow\":{}},"
                + VISIBILITY + "}";
        ValidatableResponse created = call(HOME, "CreateWebACL", body).statusCode(200);
        String id = created.extract().path("Summary.Id");
        String arn = created.extract().path("Summary.ARN");
        String lockToken = created.extract().path("Summary.LockToken");
        String byId = "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + id + "\"";
        try {
            call(OTHER, "ListWebACLs", "{\"Scope\":\"REGIONAL\"}").statusCode(200)
                    .body("WebACLs.Name", not(hasItem(name)));
            nonexistent(call(OTHER, "GetWebACL", byId + "}"));
            nonexistent(call(OTHER, "DeleteWebACL", byId + ",\"LockToken\":\"" + lockToken + "\"}"));
            nonexistent(call(OTHER, "ListTagsForResource", "{\"ResourceARN\":\"" + arn + "\"}"));
            nonexistent(call(OTHER, "PutLoggingConfiguration", "{\"LoggingConfiguration\":{\"ResourceArn\":\""
                    + arn + "\",\"LogDestinationConfigs\":[\"arn:aws:firehose:" + OTHER
                    + ":000000000000:deliverystream/aws-waf-logs-x\"]}}"));

            String loadBalancer = "arn:aws:elasticloadbalancing:" + HOME + ":000000000000:loadbalancer/app/" + name
                    + "/0123456789abcdef";
            call(HOME, "AssociateWebACL", "{\"WebACLArn\":\"" + arn + "\",\"ResourceArn\":\"" + loadBalancer + "\"}")
                    .statusCode(200);
            call(OTHER, "DisassociateWebACL", "{\"ResourceArn\":\"" + loadBalancer + "\"}").statusCode(200);
            call(OTHER, "ListResourcesForWebACL", "{\"WebACLArn\":\"" + arn + "\"}").statusCode(200)
                    .body("ResourceArns", not(hasItem(loadBalancer)));
            call(HOME, "GetWebACLForResource", "{\"ResourceArn\":\"" + loadBalancer + "\"}").statusCode(200)
                    .body("WebACL.ARN", equalTo(arn));
            call(HOME, "DisassociateWebACL", "{\"ResourceArn\":\"" + loadBalancer + "\"}").statusCode(200);

            ValidatableResponse other = call(OTHER, "CreateWebACL", body).statusCode(200)
                    .body("Summary.ARN", startsWith("arn:aws:wafv2:" + OTHER + ":"));
            call(OTHER, "DeleteWebACL", "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\""
                    + other.extract().path("Summary.Id") + "\",\"LockToken\":\""
                    + other.extract().path("Summary.LockToken") + "\"}").statusCode(200);

            call(HOME, "ListWebACLs", "{\"Scope\":\"REGIONAL\"}").statusCode(200).body("WebACLs.Name", hasItem(name));
            call(HOME, "GetWebACL", byId + "}").statusCode(200).body("WebACL.ARN", equalTo(arn));
        } finally {
            call(HOME, "DeleteWebACL", byId + ",\"LockToken\":\"" + lockToken + "\"}");
        }
    }

    @ParameterizedTest
    @CsvSource({
        "CreateIPSet, ListIPSets, IPSets, GetIPSet, DeleteIPSet, '\"IPAddressVersion\":\"IPV4\",\"Addresses\":[\"192.0.2.0/24\"]'",
        "CreateRegexPatternSet, ListRegexPatternSets, RegexPatternSets, GetRegexPatternSet, DeleteRegexPatternSet, "
                + "'\"RegularExpressionList\":[{\"RegexString\":\"^x\"}]'",
        "CreateRuleGroup, ListRuleGroups, RuleGroups, GetRuleGroup, DeleteRuleGroup, "
                + "'\"Capacity\":10," + VISIBILITY + "'"
    })
    void aRegionalSetOrRuleGroupIsListedAndReachedOnlyInItsRegion(String create, String list, String listed,
                                                                  String get, String delete, String members) {
        String name = "set-" + UUID.randomUUID().toString().substring(0, 8);
        ValidatableResponse created = call(HOME, create, "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\","
                + members + "}").statusCode(200);
        String byId = "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + created.extract().path("Summary.Id")
                + "\"";
        String lockToken = created.extract().path("Summary.LockToken");
        try {
            call(OTHER, list, "{\"Scope\":\"REGIONAL\"}").statusCode(200).body(listed + ".Name", not(hasItem(name)));
            nonexistent(call(OTHER, get, byId + "}"));
            nonexistent(call(OTHER, delete, byId + ",\"LockToken\":\"" + lockToken + "\"}"));

            call(HOME, list, "{\"Scope\":\"REGIONAL\"}").statusCode(200).body(listed + ".Name", hasItem(name));
        } finally {
            call(HOME, delete, byId + ",\"LockToken\":\"" + lockToken + "\"}");
        }
    }

    @Test
    void aCloudFrontWebAclIsReachedFromEveryRegion() {
        String name = "edge-" + UUID.randomUUID().toString().substring(0, 8);
        ValidatableResponse created = call(HOME, "CreateWebACL", "{\"Name\":\"" + name + "\",\"Scope\":\"CLOUDFRONT\","
                + "\"DefaultAction\":{\"Allow\":{}}," + VISIBILITY + "}").statusCode(200)
                .body("Summary.ARN", startsWith("arn:aws:wafv2:us-east-1:"));
        String byId = "{\"Name\":\"" + name + "\",\"Scope\":\"CLOUDFRONT\",\"Id\":\"" + created.extract().path("Summary.Id")
                + "\"";
        try {
            call(OTHER, "ListWebACLs", "{\"Scope\":\"CLOUDFRONT\"}").statusCode(200)
                    .body("WebACLs.Name", hasItem(name));
            call(OTHER, "GetWebACL", byId + "}").statusCode(200).body("WebACL.Name", equalTo(name));
        } finally {
            call(OTHER, "DeleteWebACL", byId + ",\"LockToken\":\"" + created.extract().path("Summary.LockToken")
                    + "\"}");
        }
    }

    private static ValidatableResponse call(String region, String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "AWSWAF_20190729." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=AKID/20260101/" + region + "/wafv2/aws4_request")
                .body(body)
                .when().post("/").then();
    }

    private static void nonexistent(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("WAFNonexistentItemException"));
    }
}
