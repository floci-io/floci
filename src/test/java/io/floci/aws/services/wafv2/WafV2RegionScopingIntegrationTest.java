package io.floci.aws.services.wafv2;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.UUID;

import static io.floci.aws.testing.WafV2Requests.waf;
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
        ValidatableResponse created = waf("CreateWebACL", HOME, body).statusCode(200);
        String id = created.extract().path("Summary.Id");
        String arn = created.extract().path("Summary.ARN");
        String lockToken = created.extract().path("Summary.LockToken");
        String byId = "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + id + "\"";
        try {
            waf("ListWebACLs", OTHER, "{\"Scope\":\"REGIONAL\"}").statusCode(200)
                    .body("WebACLs.Name", not(hasItem(name)));
            nonexistent(waf("GetWebACL", OTHER, byId + "}"));
            nonexistent(waf("DeleteWebACL", OTHER, byId + ",\"LockToken\":\"" + lockToken + "\"}"));
            nonexistent(waf("ListTagsForResource", OTHER, "{\"ResourceARN\":\"" + arn + "\"}"));
            nonexistent(waf("PutLoggingConfiguration", OTHER, "{\"LoggingConfiguration\":{\"ResourceArn\":\""
                    + arn + "\",\"LogDestinationConfigs\":[\"arn:aws:firehose:" + OTHER
                    + ":000000000000:deliverystream/aws-waf-logs-x\"]}}"));

            String loadBalancer = "arn:aws:elasticloadbalancing:" + HOME + ":000000000000:loadbalancer/app/" + name
                    + "/0123456789abcdef";
            waf("AssociateWebACL", HOME, "{\"WebACLArn\":\"" + arn + "\",\"ResourceArn\":\"" + loadBalancer + "\"}")
                    .statusCode(200);
            waf("DisassociateWebACL", OTHER, "{\"ResourceArn\":\"" + loadBalancer + "\"}").statusCode(200);
            waf("ListResourcesForWebACL", OTHER, "{\"WebACLArn\":\"" + arn + "\"}").statusCode(200)
                    .body("ResourceArns", not(hasItem(loadBalancer)));
            waf("GetWebACLForResource", HOME, "{\"ResourceArn\":\"" + loadBalancer + "\"}").statusCode(200)
                    .body("WebACL.ARN", equalTo(arn));
            waf("DisassociateWebACL", HOME, "{\"ResourceArn\":\"" + loadBalancer + "\"}").statusCode(200);

            ValidatableResponse other = waf("CreateWebACL", OTHER, body).statusCode(200)
                    .body("Summary.ARN", startsWith("arn:aws:wafv2:" + OTHER + ":"));
            waf("DeleteWebACL", OTHER, "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\""
                    + other.extract().path("Summary.Id") + "\",\"LockToken\":\""
                    + other.extract().path("Summary.LockToken") + "\"}").statusCode(200);

            waf("ListWebACLs", HOME, "{\"Scope\":\"REGIONAL\"}").statusCode(200).body("WebACLs.Name", hasItem(name));
            waf("GetWebACL", HOME, byId + "}").statusCode(200).body("WebACL.ARN", equalTo(arn));
        } finally {
            waf("DeleteWebACL", HOME, byId + ",\"LockToken\":\"" + lockToken + "\"}");
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
        ValidatableResponse created = waf(create, HOME, "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\","
                + members + "}").statusCode(200);
        String byId = "{\"Name\":\"" + name + "\",\"Scope\":\"REGIONAL\",\"Id\":\"" + created.extract().path("Summary.Id")
                + "\"";
        String lockToken = created.extract().path("Summary.LockToken");
        try {
            waf(list, OTHER, "{\"Scope\":\"REGIONAL\"}").statusCode(200).body(listed + ".Name", not(hasItem(name)));
            nonexistent(waf(get, OTHER, byId + "}"));
            nonexistent(waf(delete, OTHER, byId + ",\"LockToken\":\"" + lockToken + "\"}"));

            waf(list, HOME, "{\"Scope\":\"REGIONAL\"}").statusCode(200).body(listed + ".Name", hasItem(name));
        } finally {
            waf(delete, HOME, byId + ",\"LockToken\":\"" + lockToken + "\"}");
        }
    }

    @Test
    void aCloudFrontWebAclIsReachedFromEveryRegion() {
        String name = "edge-" + UUID.randomUUID().toString().substring(0, 8);
        ValidatableResponse created = waf("CreateWebACL", HOME, "{\"Name\":\"" + name + "\",\"Scope\":\"CLOUDFRONT\","
                + "\"DefaultAction\":{\"Allow\":{}}," + VISIBILITY + "}").statusCode(200)
                .body("Summary.ARN", startsWith("arn:aws:wafv2:us-east-1:"));
        String byId = "{\"Name\":\"" + name + "\",\"Scope\":\"CLOUDFRONT\",\"Id\":\"" + created.extract().path("Summary.Id")
                + "\"";
        try {
            waf("ListWebACLs", OTHER, "{\"Scope\":\"CLOUDFRONT\"}").statusCode(200)
                    .body("WebACLs.Name", hasItem(name));
            waf("GetWebACL", OTHER, byId + "}").statusCode(200).body("WebACL.Name", equalTo(name));
        } finally {
            waf("DeleteWebACL", OTHER, byId + ",\"LockToken\":\"" + created.extract().path("Summary.LockToken")
                    + "\"}");
        }
    }

    private static void nonexistent(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("WAFNonexistentItemException"));
    }
}
