package io.github.hectorvent.floci.services.wafv2;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

/**
 * WAF v2 management-plane flow over the AWS JSON 1.1 wire protocol: IP set + Web ACL
 * lifecycle, LockToken optimistic concurrency, association guards, and CLOUDFRONT vs
 * REGIONAL scope isolation.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WafV2IntegrationTest {

    private static final String CT = "application/x-amz-json-1.1";
    private static final String PREFIX = "AWSWAF_20190729.";
    private static final String API_RESOURCE_ARN =
            "arn:aws:apigateway:us-east-1::/restapis/floci-waf-test/stages/prod";

    private static String ipSetId;
    private static String ipSetArn;
    private static String webAclId;
    private static String webAclArn;
    private static String webAclLockToken;

    @BeforeAll
    static void configure() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given().contentType(CT).header("X-Amz-Target", PREFIX + action)
                .body(body).when().post("/");
    }

    @Test
    @Order(1)
    void createIpSet() {
        Response resp = call("CreateIPSet",
                "{\"Name\":\"floci-waf-ips\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[\"10.0.0.0/24\",\"192.168.0.0/16\"]}");
        resp.then().statusCode(200)
                .body("Summary.Id", notNullValue())
                .body("Summary.LockToken", notNullValue());
        ipSetId = resp.jsonPath().getString("Summary.Id");
        ipSetArn = resp.jsonPath().getString("Summary.ARN");
    }

    @Test
    @Order(2)
    void duplicateIpSetNameFails() {
        call("CreateIPSet",
                "{\"Name\":\"floci-waf-ips\",\"Scope\":\"REGIONAL\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[\"10.0.0.0/24\"]}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFDuplicateItemException"));
    }

    @Test
    @Order(3)
    void createWebAcl() {
        Response resp = call("CreateWebACL",
                "{\"Name\":\"floci-waf-acl\",\"Scope\":\"REGIONAL\","
                        + "\"DefaultAction\":{\"Allow\":{}},"
                        + "\"VisibilityConfig\":{\"SampledRequestsEnabled\":true,"
                        + "\"CloudWatchMetricsEnabled\":true,\"MetricName\":\"acl\"},"
                        + "\"Rules\":[{\"Name\":\"iprule\",\"Priority\":1,"
                        + "\"Statement\":{\"IPSetReferenceStatement\":{\"ARN\":\"" + ipSetArn + "\"}},"
                        + "\"Action\":{\"Block\":{}},"
                        + "\"VisibilityConfig\":{\"SampledRequestsEnabled\":true,"
                        + "\"CloudWatchMetricsEnabled\":true,\"MetricName\":\"iprule\"}}]}");
        resp.then().statusCode(200)
                .body("Summary.Id", notNullValue())
                .body("Summary.ARN", notNullValue())
                .body("Summary.LockToken", notNullValue());
        webAclId = resp.jsonPath().getString("Summary.Id");
        webAclArn = resp.jsonPath().getString("Summary.ARN");
    }

    @Test
    @Order(4)
    void getWebAcl() {
        Response resp = call("GetWebACL",
                "{\"Name\":\"floci-waf-acl\",\"Scope\":\"REGIONAL\",\"Id\":\"" + webAclId + "\"}");
        resp.then().statusCode(200)
                .body("WebACL.Name", equalTo("floci-waf-acl"))
                .body("WebACL.Rules[0].Statement.IPSetReferenceStatement.ARN", equalTo(ipSetArn))
                .body("WebACL.DefaultAction.Allow", notNullValue())
                .body("LockToken", notNullValue());
        webAclLockToken = resp.jsonPath().getString("LockToken");
    }

    @Test
    @Order(5)
    void updateWebAclRotatesLockToken() {
        Response resp = call("UpdateWebACL",
                "{\"Name\":\"floci-waf-acl\",\"Scope\":\"REGIONAL\",\"Id\":\"" + webAclId + "\","
                        + "\"LockToken\":\"" + webAclLockToken + "\","
                        + "\"Description\":\"updated\","
                        + "\"DefaultAction\":{\"Block\":{}},"
                        + "\"VisibilityConfig\":{\"SampledRequestsEnabled\":true,"
                        + "\"CloudWatchMetricsEnabled\":true,\"MetricName\":\"acl\"}}");
        resp.then().statusCode(200).body("NextLockToken", notNullValue());
        String next = resp.jsonPath().getString("NextLockToken");
        org.junit.jupiter.api.Assertions.assertNotEquals(webAclLockToken, next);
    }

    @Test
    @Order(6)
    void staleLockTokenUpdateFails() {
        // webAclLockToken is now stale after the previous update.
        call("UpdateWebACL",
                "{\"Name\":\"floci-waf-acl\",\"Scope\":\"REGIONAL\",\"Id\":\"" + webAclId + "\","
                        + "\"LockToken\":\"" + webAclLockToken + "\","
                        + "\"DefaultAction\":{\"Allow\":{}},"
                        + "\"VisibilityConfig\":{\"SampledRequestsEnabled\":true,"
                        + "\"CloudWatchMetricsEnabled\":true,\"MetricName\":\"acl\"}}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFOptimisticLockException"));
    }

    @Test
    @Order(7)
    void associateAndQuery() {
        call("AssociateWebACL",
                "{\"WebACLArn\":\"" + webAclArn + "\",\"ResourceArn\":\"" + API_RESOURCE_ARN + "\"}")
                .then().statusCode(200);

        call("GetWebACLForResource", "{\"ResourceArn\":\"" + API_RESOURCE_ARN + "\"}")
                .then().statusCode(200)
                .body("WebACL.Id", equalTo(webAclId));

        call("ListResourcesForWebACL", "{\"WebACLArn\":\"" + webAclArn + "\"}")
                .then().statusCode(200)
                .body("ResourceArns", hasSize(1))
                .body("ResourceArns[0]", equalTo(API_RESOURCE_ARN));
    }

    @Test
    @Order(8)
    void deleteWebAclWhileAssociatedFails() {
        String lockToken = call("GetWebACL",
                "{\"Name\":\"floci-waf-acl\",\"Scope\":\"REGIONAL\",\"Id\":\"" + webAclId + "\"}")
                .jsonPath().getString("LockToken");
        call("DeleteWebACL",
                "{\"Name\":\"floci-waf-acl\",\"Scope\":\"REGIONAL\",\"Id\":\"" + webAclId + "\","
                        + "\"LockToken\":\"" + lockToken + "\"}")
                .then().statusCode(400)
                .body("__type", equalTo("WAFAssociatedItemException"));
    }

    @Test
    @Order(9)
    void cloudfrontScopeIsolatedFromRegional() {
        // Same Name in CLOUDFRONT scope must not collide with the REGIONAL IP set.
        call("CreateIPSet",
                "{\"Name\":\"floci-waf-ips\",\"Scope\":\"CLOUDFRONT\",\"IPAddressVersion\":\"IPV4\","
                        + "\"Addresses\":[\"172.16.0.0/12\"]}")
                .then().statusCode(200);

        call("ListIPSets", "{\"Scope\":\"CLOUDFRONT\"}")
                .then().statusCode(200)
                .body("IPSets.find { it.Name == 'floci-waf-ips' }.ARN", not(equalTo(ipSetArn)));
    }

    @Test
    @Order(10)
    void teardown() {
        call("DisassociateWebACL", "{\"ResourceArn\":\"" + API_RESOURCE_ARN + "\"}")
                .then().statusCode(200);
        String lockToken = call("GetWebACL",
                "{\"Name\":\"floci-waf-acl\",\"Scope\":\"REGIONAL\",\"Id\":\"" + webAclId + "\"}")
                .jsonPath().getString("LockToken");
        call("DeleteWebACL",
                "{\"Name\":\"floci-waf-acl\",\"Scope\":\"REGIONAL\",\"Id\":\"" + webAclId + "\","
                        + "\"LockToken\":\"" + lockToken + "\"}")
                .then().statusCode(200);

        String ipLock = call("GetIPSet",
                "{\"Name\":\"floci-waf-ips\",\"Scope\":\"REGIONAL\",\"Id\":\"" + ipSetId + "\"}")
                .jsonPath().getString("LockToken");
        call("DeleteIPSet",
                "{\"Name\":\"floci-waf-ips\",\"Scope\":\"REGIONAL\",\"Id\":\"" + ipSetId + "\","
                        + "\"LockToken\":\"" + ipLock + "\"}")
                .then().statusCode(200);
    }

    @Test
    @Order(11)
    void getMissingWebAclReturnsNotFound() {
        call("GetWebACL",
                "{\"Name\":\"nope\",\"Scope\":\"REGIONAL\",\"Id\":\"00000000-0000-0000-0000-000000000000\"}")
                .then().statusCode(404)
                .body("__type", equalTo("WAFNonexistentItemException"));
    }
}
