package io.github.hectorvent.floci.services.elbv2;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * A load balancer or target group that has never been modified used to answer with an empty
 * attribute list: only keys somebody had explicitly Modify-d ever appeared. AWS always returns
 * the full set with its defaults, and the Terraform provider reads those on every refresh into
 * aws_lb's idle_timeout, enable_http2, enable_deletion_protection and access_logs, and
 * aws_lb_target_group's stickiness, deregistration_delay and load_balancing_algorithm_type, so an
 * empty list leaves every one of them a permanent diff.
 */
@QuarkusTest
class ElbV2DefaultAttributesIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/elasticloadbalancing/aws4_request";

    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ec2/aws4_request";

    private String createVpc(String cidr) {
        return given()
            .formParam("Action", "CreateVpc")
            .formParam("CidrBlock", cidr)
            .header("Authorization", EC2_AUTH)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateVpcResponse.vpc.vpcId");
    }

    private String createSubnet(String vpcId, String cidr, String zone) {
        return given()
            .formParam("Action", "CreateSubnet")
            .formParam("VpcId", vpcId)
            .formParam("CidrBlock", cidr)
            .formParam("AvailabilityZone", zone)
            .header("Authorization", EC2_AUTH)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateSubnetResponse.subnet.subnetId");
    }

    /** An ALB needs subnets in two availability zones, so every load balancer here gets two. */
    private String createLoadBalancer(String name, String type, String base) {
        String vpcId = createVpc(base + "0.0/16");
        return given()
            .formParam("Action", "CreateLoadBalancer")
            .formParam("Version", "2015-12-01")
            .formParam("Name", name)
            .formParam("Type", type)
            .formParam("Subnets.member.1", createSubnet(vpcId, base + "1.0/24", "us-east-1a"))
            .formParam("Subnets.member.2", createSubnet(vpcId, base + "2.0/24", "us-east-1b"))
            .header("Authorization", AUTH)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    @Test
    void aFreshApplicationLoadBalancerReportsTheDocumentedDefaults() {
        String arn = createLoadBalancer("attrs-alb", "application", "10.95.");

        given()
            .formParam("Action", "DescribeLoadBalancerAttributes")
            .formParam("LoadBalancerArn", arn)
            .header("Authorization", AUTH)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.find { it.Key == 'idle_timeout.timeout_seconds' }.Value",
                    equalTo("60"))
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.find { it.Key == 'routing.http2.enabled' }.Value",
                    equalTo("true"))
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.find { it.Key == 'deletion_protection.enabled' }.Value",
                    equalTo("false"))
            // Cross-zone load balancing is on for an ALB, off for an NLB. Same key, different default.
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.find { it.Key == 'load_balancing.cross_zone.enabled' }.Value",
                    equalTo("true"));
    }

    @Test
    void aNetworkLoadBalancerGetsItsOwnKeysAndNotTheApplicationOnes() {
        String arn = createLoadBalancer("attrs-nlb", "network", "10.98.");

        given()
            .formParam("Action", "DescribeLoadBalancerAttributes")
            .formParam("LoadBalancerArn", arn)
            .header("Authorization", AUTH)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.Key",
                    hasItem("dns_record.client_routing_policy"))
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.find { it.Key == 'load_balancing.cross_zone.enabled' }.Value",
                    equalTo("false"))
            // An NLB has no idle timeout: the key is absent, not present and empty.
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.Key",
                    not(hasItem("idle_timeout.timeout_seconds")));
    }

    /**
     * Access logs are an Application and Network Load Balancer attribute. A Gateway Load Balancer
     * has only the two every type shares, and reporting more would hand a client a schema AWS
     * never returns for it.
     */
    @Test
    void aGatewayLoadBalancerGetsOnlyTheAttributesEveryTypeShares() {
        String arn = createLoadBalancer("attrs-gwlb", "gateway", "10.100.");

        given()
            .formParam("Action", "DescribeLoadBalancerAttributes")
            .formParam("LoadBalancerArn", arn)
            .header("Authorization", AUTH)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.Key",
                    hasItem("deletion_protection.enabled"))
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.find { it.Key == 'load_balancing.cross_zone.enabled' }.Value",
                    equalTo("false"))
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.Key",
                    not(hasItem("access_logs.s3.enabled")))
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.Key",
                    not(hasItem("idle_timeout.timeout_seconds")));
    }

    @Test
    void aModifiedAttributeWinsOverItsDefaultAndTheRestStillReport() {
        String arn = createLoadBalancer("attrs-modified", "application", "10.99.");

        given()
            .formParam("Action", "ModifyLoadBalancerAttributes")
            .formParam("LoadBalancerArn", arn)
            .formParam("Attributes.member.1.Key", "idle_timeout.timeout_seconds")
            .formParam("Attributes.member.1.Value", "120")
            .header("Authorization", AUTH)
        .when().post("/")
        .then().statusCode(200);

        given()
            .formParam("Action", "DescribeLoadBalancerAttributes")
            .formParam("LoadBalancerArn", arn)
            .header("Authorization", AUTH)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.find { it.Key == 'idle_timeout.timeout_seconds' }.Value",
                    equalTo("120"))
            .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.find { it.Key == 'routing.http2.enabled' }.Value",
                    equalTo("true"));
    }

    @Test
    void aFreshTargetGroupReportsTheDocumentedDefaults() {
        String vpcId = createVpc("10.96.0.0/16");

        String tgArn = given()
            .formParam("Action", "CreateTargetGroup")
            .formParam("Version", "2015-12-01")
            .formParam("Name", "attrs-tg")
            .formParam("Protocol", "HTTP")
            .formParam("Port", "80")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");

        given()
            .formParam("Action", "DescribeTargetGroupAttributes")
            .formParam("TargetGroupArn", tgArn)
            .header("Authorization", AUTH)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeTargetGroupAttributesResponse.DescribeTargetGroupAttributesResult.Attributes.member.find { it.Key == 'deregistration_delay.timeout_seconds' }.Value",
                    equalTo("300"))
            .body("DescribeTargetGroupAttributesResponse.DescribeTargetGroupAttributesResult.Attributes.member.find { it.Key == 'stickiness.enabled' }.Value",
                    equalTo("false"))
            // An HTTP target group belongs to an ALB, so it gets the cookie stickiness type and
            // an algorithm, not the NLB's source_ip and proxy protocol.
            .body("DescribeTargetGroupAttributesResponse.DescribeTargetGroupAttributesResult.Attributes.member.find { it.Key == 'stickiness.type' }.Value",
                    equalTo("lb_cookie"))
            .body("DescribeTargetGroupAttributesResponse.DescribeTargetGroupAttributesResult.Attributes.member.find { it.Key == 'load_balancing.algorithm.type' }.Value",
                    equalTo("round_robin"))
            .body("DescribeTargetGroupAttributesResponse.DescribeTargetGroupAttributesResult.Attributes.member.Key",
                    not(hasItem("proxy_protocol_v2.enabled")));
    }

    @Test
    void aTcpTargetGroupGetsTheNetworkDefaults() {
        String vpcId = createVpc("10.97.0.0/16");

        String tgArn = given()
            .formParam("Action", "CreateTargetGroup")
            .formParam("Version", "2015-12-01")
            .formParam("Name", "attrs-tcp-tg")
            .formParam("Protocol", "TCP")
            .formParam("Port", "80")
            .formParam("VpcId", vpcId)
            .header("Authorization", AUTH)
        .when().post("/")
        .then().statusCode(200)
            .extract().path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");

        given()
            .formParam("Action", "DescribeTargetGroupAttributes")
            .formParam("TargetGroupArn", tgArn)
            .header("Authorization", AUTH)
        .when().post("/")
        .then()
            .statusCode(200)
            .body("DescribeTargetGroupAttributesResponse.DescribeTargetGroupAttributesResult.Attributes.member.find { it.Key == 'stickiness.type' }.Value",
                    equalTo("source_ip"))
            .body("DescribeTargetGroupAttributesResponse.DescribeTargetGroupAttributesResult.Attributes.member.find { it.Key == 'proxy_protocol_v2.enabled' }.Value",
                    equalTo("false"))
            .body("DescribeTargetGroupAttributesResponse.DescribeTargetGroupAttributesResult.Attributes.member.Key",
                    not(hasItem("load_balancing.algorithm.type")));
    }
}
