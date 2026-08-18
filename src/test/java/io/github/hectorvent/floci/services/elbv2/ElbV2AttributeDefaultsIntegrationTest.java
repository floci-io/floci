package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItems;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * DescribeXAttributes on an object nobody has modified must answer AWS's defaults, not an empty
 * set. A client that reads {@code idle_timeout.timeout_seconds} off a fresh load balancer expects
 * 60; an empty set gives it nothing, and a configuration leaving that argument at its default
 * then reads as drifted on every plan.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2AttributeDefaultsIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";

    private static String albArn;
    private static String nlbArn;
    private static String tgArn;

    private static final String LB_ATTRS =
            "DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member";
    private static final String TG_ATTRS =
            "DescribeTargetGroupAttributesResponse.DescribeTargetGroupAttributesResult.Attributes.member";

    @Test
    @Order(1)
    void createObjects() {
        albArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "attr-alb")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", "subnet-default-a")
                .formParam("Subnets.member.2", "subnet-default-b")
                .header("Authorization", AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        nlbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "attr-nlb")
                .formParam("Type", "network")
                .formParam("Subnets.member.1", "subnet-default-a")
                .header("Authorization", AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        tgArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "attr-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", "80")
                .formParam("VpcId", "vpc-default")
                .header("Authorization", AUTH)
                .when().post("/")
                .then().statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    @Test
    @Order(2)
    void applicationLoadBalancerReportsItsDefaults() {
        given()
                .formParam("Action", "DescribeLoadBalancerAttributes")
                .formParam("LoadBalancerArn", albArn)
                .header("Authorization", AUTH)
                .when().post("/")
                .then().statusCode(200)
                .body(LB_ATTRS + ".Key", hasItems(
                        "idle_timeout.timeout_seconds",
                        "deletion_protection.enabled",
                        "routing.http2.enabled",
                        "access_logs.s3.enabled",
                        "load_balancing.cross_zone.enabled"))
                .body(containsString("<Key>idle_timeout.timeout_seconds</Key><Value>60</Value>"))
                .body(containsString("<Key>routing.http2.enabled</Key><Value>true</Value>"))
                .body(containsString("<Key>load_balancing.cross_zone.enabled</Key><Value>true</Value>"));
    }

    @Test
    @Order(3)
    void networkLoadBalancerHasCrossZoneOffAndNoHttpAttributes() {
        String body = given()
                .formParam("Action", "DescribeLoadBalancerAttributes")
                .formParam("LoadBalancerArn", nlbArn)
                .header("Authorization", AUTH)
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<Key>load_balancing.cross_zone.enabled</Key><Value>false</Value>"))
                .body(containsString("<Key>dns_record.client_routing_policy</Key>"))
                .extract().asString();

        assertEquals(-1, body.indexOf("idle_timeout.timeout_seconds"),
                "idle_timeout is an application load balancer attribute");
    }

    @Test
    @Order(4)
    void targetGroupReportsItsDefaults() {
        given()
                .formParam("Action", "DescribeTargetGroupAttributes")
                .formParam("TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
                .when().post("/")
                .then().statusCode(200)
                .body(TG_ATTRS + ".Key", hasItems(
                        "deregistration_delay.timeout_seconds",
                        "stickiness.enabled",
                        "stickiness.type"))
                .body(containsString("<Key>deregistration_delay.timeout_seconds</Key><Value>300</Value>"))
                .body(containsString("<Key>stickiness.type</Key><Value>lb_cookie</Value>"));
    }

    @Test
    @Order(5)
    void aModifiedAttributeWinsOverItsDefaultAndModifyEchoesTheWholeSet() {
        given()
                .formParam("Action", "ModifyLoadBalancerAttributes")
                .formParam("LoadBalancerArn", albArn)
                .formParam("Attributes.member.1.Key", "idle_timeout.timeout_seconds")
                .formParam("Attributes.member.1.Value", "120")
                .header("Authorization", AUTH)
                .when().post("/")
                .then().statusCode(200)
                // AWS echoes the whole set, so the untouched defaults come back too.
                .body(containsString("<Key>idle_timeout.timeout_seconds</Key><Value>120</Value>"))
                .body(containsString("<Key>routing.http2.enabled</Key><Value>true</Value>"));

        given()
                .formParam("Action", "DescribeLoadBalancerAttributes")
                .formParam("LoadBalancerArn", albArn)
                .header("Authorization", AUTH)
                .when().post("/")
                .then().statusCode(200)
                .body(containsString("<Key>idle_timeout.timeout_seconds</Key><Value>120</Value>"));
    }
}
