package io.github.hectorvent.floci.services.elb;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Classic (2012-06-01) Elastic Load Balancing over the Query protocol.
 *
 * <p>Classic ELB and ELBv2 share one endpoint host and one credential scope, so these tests also
 * pin the routing: a request declaring {@code Version=2012-06-01} must be answered by the Classic
 * implementation, in the 2012-06-01 namespace, and one declaring {@code Version=2015-12-01} must
 * still reach ELBv2.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbClassicIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";
    private static final String V1 = "2012-06-01";
    private static final String V2 = "2015-12-01";
    private static final String CLASSIC_XMLNS =
            "http://elasticloadbalancing.amazonaws.com/doc/2012-06-01/";
    private static final String V2_XMLNS =
            "https://elasticloadbalancing.amazonaws.com/doc/2015-12-01/";

    private static final String LB = "classic-elb-1";

    private static String subnetA() { return Ec2Service.defaultSubnetId("us-east-1", "a"); }

    @Test
    @Order(1)
    void createLoadBalancerAnswersTheClassicApiNotTheV2One() {
        String body = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("Scheme", "internet-facing")
                .formParam("Subnets.member.1", subnetA())
                .formParam("SecurityGroups.member.1", "sg-classic")
                .formParam("Listeners.member.1.Protocol", "HTTP")
                .formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstanceProtocol", "HTTP")
                .formParam("Listeners.member.1.InstancePort", "8080")
                .formParam("Tags.member.1.Key", "gw:example")
                .formParam("Tags.member.1.Value", "with-elb")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.DNSName",
                        containsString(LB + "-"))
                .extract().asString();

        // The two defects this fixes: wrong namespace, and an ELBv2 object in a Classic reply.
        assertTrue(body.contains("xmlns=\"" + CLASSIC_XMLNS + "\""),
                "Classic response must declare the 2012-06-01 namespace: " + body);
        assertFalse(body.contains(V2_XMLNS),
                "Classic response must not be in the 2015-12-01 namespace: " + body);
        assertFalse(body.contains("LoadBalancerArn"),
                "Classic load balancers have no ARN: " + body);
        assertFalse(body.contains("<LoadBalancers>"),
                "CreateLoadBalancerResult carries DNSName only, never a LoadBalancers list: " + body);
    }

    @Test
    @Order(2)
    void loadBalancerNameIsAcceptedRatherThanRejectedAsMissing() {
        // The reported symptom: 'Name is required for load balancer.' for a well-formed v1 request.
        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", "classic-elb-name-ok")
                .formParam("Listeners.member.1.Protocol", "TCP")
                .formParam("Listeners.member.1.LoadBalancerPort", "443")
                .formParam("Listeners.member.1.InstancePort", "443")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.DNSName",
                        containsString("classic-elb-name-ok-"));

        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", "classic-elb-name-ok")
                .header("Authorization", AUTH)
            .when().post("/").then().statusCode(200);
    }

    @Test
    @Order(3)
    void createRejectsARequestWithNoListeners() {
        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", "classic-no-listeners")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                // Not "Name is required for load balancer." — that is the v2 handler's message,
                // and seeing it here means a v1 request reached the wrong API again.
                .body("ErrorResponse.Error.Message", containsString("listener"));
    }

    @Test
    @Order(4)
    void duplicateNameIsRejected() {
        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("Listeners.member.1.Protocol", "HTTP")
                .formParam("Listeners.member.1.LoadBalancerPort", "80")
                .formParam("Listeners.member.1.InstancePort", "8080")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DuplicateLoadBalancerName"));
    }

    @Test
    @Order(5)
    void describeLoadBalancersReturnsTheClassicDescriptionShape() {
        String body = given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Version", V1)
                .formParam("LoadBalancerNames.member.1", LB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.LoadBalancerName", equalTo(LB))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.Scheme", equalTo("internet-facing"))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.Subnets.member", equalTo(subnetA()))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.SecurityGroups.member",
                        equalTo("sg-classic"))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.ListenerDescriptions.member"
                        + ".Listener.InstancePort", equalTo("8080"))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.HealthCheck.Target", equalTo("TCP:80"))
                .extract().asString();
        assertTrue(body.contains("xmlns=\"" + CLASSIC_XMLNS + "\""), body);
        assertFalse(body.contains("LoadBalancerArn"), body);
    }

    @Test
    @Order(6)
    void describeUnknownLoadBalancerIsNotFound() {
        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Version", V1)
                .formParam("LoadBalancerNames.member.1", "no-such-elb")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }

    @Test
    @Order(7)
    void configureHealthCheckStoresAndEchoesTheCheck() {
        given()
                .formParam("Action", "ConfigureHealthCheck")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("HealthCheck.Target", "HTTP:8080/")
                .formParam("HealthCheck.Interval", "10")
                .formParam("HealthCheck.Timeout", "3")
                .formParam("HealthCheck.HealthyThreshold", "2")
                .formParam("HealthCheck.UnhealthyThreshold", "2")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ConfigureHealthCheckResponse.ConfigureHealthCheckResult.HealthCheck.Target",
                        equalTo("HTTP:8080/"))
                .body("ConfigureHealthCheckResponse.ConfigureHealthCheckResult.HealthCheck.Interval",
                        equalTo("10"));
    }

    @Test
    @Order(8)
    void configureHealthCheckRejectsAnIntervalOutsideTheModelRange() {
        given()
                .formParam("Action", "ConfigureHealthCheck")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("HealthCheck.Target", "HTTP:8080/")
                .formParam("HealthCheck.Interval", "1")
                .formParam("HealthCheck.Timeout", "3")
                .formParam("HealthCheck.HealthyThreshold", "2")
                .formParam("HealthCheck.UnhealthyThreshold", "2")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(9)
    void registerDescribeAndDeregisterInstances() {
        given()
                .formParam("Action", "RegisterInstancesWithLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("Instances.member.1.InstanceId", "i-1111111111111111a")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("RegisterInstancesWithLoadBalancerResponse"
                        + ".RegisterInstancesWithLoadBalancerResult.Instances.member.InstanceId",
                        equalTo("i-1111111111111111a"));

        given()
                .formParam("Action", "DescribeInstanceHealth")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeInstanceHealthResponse.DescribeInstanceHealthResult"
                        + ".InstanceStates.member.InstanceId", equalTo("i-1111111111111111a"))
                .body("DescribeInstanceHealthResponse.DescribeInstanceHealthResult"
                        + ".InstanceStates.member.State", equalTo("InService"));

        given()
                .formParam("Action", "DeregisterInstancesFromLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("Instances.member.1.InstanceId", "i-1111111111111111a")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DeregisterInstancesFromLoadBalancerResponse"
                        + ".DeregisterInstancesFromLoadBalancerResult.Instances", equalTo(""));
    }

    @Test
    @Order(10)
    void describeInstanceHealthRejectsAnUnregisteredInstance() {
        given()
                .formParam("Action", "DescribeInstanceHealth")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("Instances.member.1.InstanceId", "i-not-registered")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidInstance"));
    }

    @Test
    @Order(11)
    void modifyAndDescribeAttributesRoundTrip() {
        given()
                .formParam("Action", "ModifyLoadBalancerAttributes")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("LoadBalancerAttributes.CrossZoneLoadBalancing.Enabled", "true")
                .formParam("LoadBalancerAttributes.ConnectionDraining.Enabled", "true")
                .formParam("LoadBalancerAttributes.ConnectionDraining.Timeout", "300")
                .formParam("LoadBalancerAttributes.ConnectionSettings.IdleTimeout", "90")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ModifyLoadBalancerAttributesResponse.ModifyLoadBalancerAttributesResult"
                        + ".LoadBalancerName", equalTo(LB));

        given()
                .formParam("Action", "DescribeLoadBalancerAttributes")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancerAttributesResponse"
                        + ".DescribeLoadBalancerAttributesResult.LoadBalancerAttributes"
                        + ".CrossZoneLoadBalancing.Enabled", equalTo("true"))
                .body("DescribeLoadBalancerAttributesResponse"
                        + ".DescribeLoadBalancerAttributesResult.LoadBalancerAttributes"
                        + ".ConnectionDraining.Timeout", equalTo("300"))
                .body("DescribeLoadBalancerAttributesResponse"
                        + ".DescribeLoadBalancerAttributesResult.LoadBalancerAttributes"
                        + ".ConnectionSettings.IdleTimeout", equalTo("90"))
                // Untouched members keep their AWS defaults rather than disappearing.
                .body("DescribeLoadBalancerAttributesResponse"
                        + ".DescribeLoadBalancerAttributesResult.LoadBalancerAttributes"
                        + ".AccessLog.Enabled", equalTo("false"));
    }

    @Test
    @Order(12)
    void listenersCanBeAddedAndRemoved() {
        given()
                .formParam("Action", "CreateLoadBalancerListeners")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("Listeners.member.1.Protocol", "TCP")
                .formParam("Listeners.member.1.LoadBalancerPort", "8443")
                .formParam("Listeners.member.1.InstancePort", "8443")
                .header("Authorization", AUTH)
            .when().post("/").then().statusCode(200);

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Version", V1)
                .formParam("LoadBalancerNames.member.1", LB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.ListenerDescriptions.member"
                        + ".Listener.LoadBalancerPort", hasItems("80", "8443"));

        given()
                .formParam("Action", "DeleteLoadBalancerListeners")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("LoadBalancerPorts.member.1", "8443")
                .header("Authorization", AUTH)
            .when().post("/").then().statusCode(200);

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Version", V1)
                .formParam("LoadBalancerNames.member.1", LB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.ListenerDescriptions.member"
                        + ".Listener.LoadBalancerPort", equalTo("80"));
    }

    @Test
    @Order(13)
    void tagsRoundTripByLoadBalancerName() {
        given()
                .formParam("Action", "DescribeTags")
                .formParam("Version", V1)
                .formParam("LoadBalancerNames.member.1", LB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTagsResponse.DescribeTagsResult.TagDescriptions.member"
                        + ".LoadBalancerName", equalTo(LB))
                .body("DescribeTagsResponse.DescribeTagsResult.TagDescriptions.member"
                        + ".Tags.member.Key", equalTo("gw:example"));

        given()
                .formParam("Action", "AddTags")
                .formParam("Version", V1)
                .formParam("LoadBalancerNames.member.1", LB)
                .formParam("Tags.member.1.Key", "owner")
                .formParam("Tags.member.1.Value", "harness")
                .header("Authorization", AUTH)
            .when().post("/").then().statusCode(200);

        given()
                .formParam("Action", "RemoveTags")
                .formParam("Version", V1)
                .formParam("LoadBalancerNames.member.1", LB)
                .formParam("Tags.member.1.Key", "gw:example")
                .header("Authorization", AUTH)
            .when().post("/").then().statusCode(200);

        given()
                .formParam("Action", "DescribeTags")
                .formParam("Version", V1)
                .formParam("LoadBalancerNames.member.1", LB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTagsResponse.DescribeTagsResult.TagDescriptions.member"
                        + ".Tags.member.Key", equalTo("owner"));
    }

    @Test
    @Order(14)
    void subnetAndSecurityGroupChangesRoundTrip() {
        given()
                .formParam("Action", "AttachLoadBalancerToSubnets")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("AttachLoadBalancerToSubnetsResponse.AttachLoadBalancerToSubnetsResult"
                        + ".Subnets.member", hasItems(subnetA(),
                        Ec2Service.defaultSubnetId("us-east-1", "b")));

        given()
                .formParam("Action", "DetachLoadBalancerFromSubnets")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DetachLoadBalancerFromSubnetsResponse.DetachLoadBalancerFromSubnetsResult"
                        + ".Subnets.member", equalTo(subnetA()));

        given()
                .formParam("Action", "ApplySecurityGroupsToLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("SecurityGroups.member.1", "sg-replaced")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ApplySecurityGroupsToLoadBalancerResponse"
                        + ".ApplySecurityGroupsToLoadBalancerResult.SecurityGroups.member",
                        equalTo("sg-replaced"));
    }

    @Test
    @Order(15)
    void unimplementedClassicPolicyOperationsSaySoInTheClassicNamespace() {
        String body = given()
                .formParam("Action", "CreateLBCookieStickinessPolicy")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .formParam("PolicyName", "stickiness")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("UnsupportedOperation"))
                .extract().asString();
        assertTrue(body.contains(CLASSIC_XMLNS), body);
    }

    @Test
    @Order(16)
    void aRequestWithoutAVersionRoutesOnItsParameterShape() {
        // A hand-rolled client that omitted Version but named a load balancer is Classic.
        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerNames.member.1", LB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult"
                        + ".LoadBalancerDescriptions.member.LoadBalancerName", equalTo(LB));
    }

    @Test
    @Order(17)
    void elbV2RequestsStillReachTheV2Handler() {
        String body = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Version", V2)
                .formParam("Name", "v2-still-works")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member"
                        + ".LoadBalancerName", equalTo("v2-still-works"))
                .extract().asString();
        assertTrue(body.contains(V2_XMLNS), body);

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Version", V2)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers"
                        + ".member.LoadBalancerName", not(hasItems(LB)));
    }

    @Test
    @Order(18)
    void deleteRemovesTheClassicLoadBalancerAndIsIdempotent() {
        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .header("Authorization", AUTH)
            .when().post("/").then().statusCode(200);

        // AWS answers a delete of something already gone with success.
        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("Version", V1)
                .formParam("LoadBalancerName", LB)
                .header("Authorization", AUTH)
            .when().post("/").then().statusCode(200);

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Version", V1)
                .formParam("LoadBalancerNames.member.1", LB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }
}
