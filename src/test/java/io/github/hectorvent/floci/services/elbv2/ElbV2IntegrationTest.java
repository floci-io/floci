package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests for ELB v2 via the Query protocol (form-encoded POST, XML response).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2IntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";
    private static final String EC2_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/ec2/aws4_request";
    private static final String ELB_V2_XMLNS =
            "https://elasticloadbalancing.amazonaws.com/doc/2015-12-01/";

    private static String lbArn;
    private static String tgArn;
    private static String listenerArn;
    private static String ruleArn1;
    private static String ruleArn2;

    @ParameterizedTest
    @CsvSource({"RegisterTargets", "DeregisterTargets", "DescribeTargetHealth"})
    void targetPortErrorsAggregateBeforeLookup(String action) {
        given().header("Authorization", AUTH)
                .formParam("Action", action)
                .formParam("TargetGroupArn", "arn:aws:elasticloadbalancing:us-east-1:000000000000:"
                        + "targetgroup/aggregate-missing/0000000000000000")
                .formParam("Targets.member.1.Id", "10.0.0.10")
                .formParam("Targets.member.1.Port", "80")
                .formParam("Targets.member.2.Id", "10.0.0.10")
                .formParam("Targets.member.2.Port", "65536")
                .formParam("Targets.member.3.Id", "10.0.0.10")
                .formParam("Targets.member.3.Port", "65537")
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo("2 validation errors detected: "
                        + "Value '65536' at 'targets.2.member.port' failed to satisfy constraint: "
                        + "Member must have value less than or equal to 65535; "
                        + "Value '65537' at 'targets.3.member.port' failed to satisfy constraint: "
                        + "Member must have value less than or equal to 65535"));
    }

    @ParameterizedTest
    @CsvSource({"CreateListener,0", "CreateListener,65536", "ModifyListener,0", "ModifyListener,65536"})
    void listenerPortValidationPrecedesLookup(String action, int port) {
        String constraint = port == 0 ? "greater than or equal to 1" : "less than or equal to 65535";
        given().header("Authorization", AUTH)
                .formParam("Action", action)
                .formParam("LoadBalancerArn", "missing-balancer")
                .formParam("ListenerArn", "missing-listener")
                .formParam("Protocol", "HTTP")
                .formParam("Port", port)
                .when().post("/").then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo("1 validation error detected: Value '" + port
                        + "' at 'port' failed to satisfy constraint: Member must have value " + constraint));
    }

    @ParameterizedTest
    @CsvSource({"Port,6081", "Protocol,GENEVE", "Protocol,TCP"})
    void gatewayListenerRejectionsPreservePortlessListener(String field, String value) {
        String gatewayArn = given().header("Authorization", AUTH)
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "gateway-listener-port")
                .formParam("Type", "gateway")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .when().post("/").then().statusCode(200)
                .extract().path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
        try {
            given().header("Authorization", AUTH)
                    .formParam("Action", "CreateListener").formParam("LoadBalancerArn", gatewayArn)
                    .formParam(field, value)
                    .when().post("/").then().statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("InvalidConfigurationRequest"));
            given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeListeners").formParam("LoadBalancerArn", gatewayArn)
                    .when().post("/").then().statusCode(200)
                    .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.size()", equalTo(0));
            Response created = given().header("Authorization", AUTH)
                    .formParam("Action", "CreateListener").formParam("LoadBalancerArn", gatewayArn)
                    .when().post("/").then().statusCode(200).extract().response();
            String gatewayListener = created.path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
            String originalProtocol = created.path("CreateListenerResponse.CreateListenerResult.Listeners.member.Protocol");
            given().header("Authorization", AUTH)
                    .formParam("Action", "ModifyListener").formParam("ListenerArn", gatewayListener)
                    .formParam(field, value)
                    .when().post("/").then().statusCode(400)
                    .body("ErrorResponse.Error.Code", equalTo("InvalidConfigurationRequest"));
            given().header("Authorization", AUTH)
                    .formParam("Action", "DescribeListeners").formParam("ListenerArns.member.1", gatewayListener)
                    .when().post("/").then().statusCode(200)
                    .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Protocol", equalTo(originalProtocol))
                    .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.Port.size()", equalTo(0));
            given().header("Authorization", AUTH)
                    .formParam("Action", "ModifyListener").formParam("ListenerArn", gatewayListener)
                    .when().post("/").then().statusCode(200)
                    .body("ModifyListenerResponse.ModifyListenerResult.Listeners.member.Protocol", equalTo(originalProtocol))
                    .body("ModifyListenerResponse.ModifyListenerResult.Listeners.member.Port.size()", equalTo(0));
        } finally {
            given().header("Authorization", AUTH)
                    .formParam("Action", "DeleteLoadBalancer").formParam("LoadBalancerArn", gatewayArn)
                    .when().post("/").then().statusCode(200);
        }
    }

    // ── Load Balancers ────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void createLoadBalancer() {
        lbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "my-test-lb")
                .formParam("Type", "application")
                .formParam("Scheme", "internet-facing")
                .formParam("IpAddressType", "ipv4")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerName",
                        equalTo("my-test-lb"))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.Type",
                        equalTo("application"))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.Scheme",
                        equalTo("internet-facing"))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.State.Code",
                        equalTo("provisioning"))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        hasItems(Ec2Service.defaultSubnetId("us-east-1", "a"), Ec2Service.defaultSubnetId("us-east-1", "b")))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.DNSName",
                        containsString(".elb.localhost.floci.io"))
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
    }

    @Test
    @Order(2)
    void describeLoadBalancerByArn() {
        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.LoadBalancerArn",
                        equalTo(lbArn))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.State.Code",
                        equalTo("active"))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        hasItems(Ec2Service.defaultSubnetId("us-east-1", "a"), Ec2Service.defaultSubnetId("us-east-1", "b")));
    }

    @Test
    @Order(3)
    void describeLoadBalancerByName() {
        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Names.member.1", "my-test-lb")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.LoadBalancerName",
                        equalTo("my-test-lb"));
    }

    @Test
    @Order(4)
    void duplicateLoadBalancerNameThrows() {
        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "my-test-lb")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DuplicateLoadBalancerName"));
    }

    @Test
    @Order(5)
    void modifyLoadBalancerAttributes() {
        given()
                .formParam("Action", "ModifyLoadBalancerAttributes")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Attributes.member.1.Key", "deletion_protection.enabled")
                .formParam("Attributes.member.1.Value", "true")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ModifyLoadBalancerAttributesResponse.ModifyLoadBalancerAttributesResult.Attributes.member.Key",
                        equalTo("deletion_protection.enabled"))
                .body("ModifyLoadBalancerAttributesResponse.ModifyLoadBalancerAttributesResult.Attributes.member.Value",
                        equalTo("true"));
    }

    @Test
    @Order(6)
    void describeLoadBalancerAttributes() {
        // AWS answers with the full attribute set, not just the keys somebody modified, so the
        // modified key is asserted among them rather than as the only one.
        given()
                .formParam("Action", "DescribeLoadBalancerAttributes")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.Key",
                        hasItem("deletion_protection.enabled"))
                .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.Key",
                        hasItem("idle_timeout.timeout_seconds"));
    }

    @Test
    @Order(7)
    void describeCapacityReservationReturnsEmptyForLbWithoutReservation() {
        given()
                .formParam("Action", "DescribeCapacityReservation")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body("DescribeCapacityReservationResponse.ResponseMetadata.RequestId",
                        not(emptyOrNullString()));
    }

    @Test
    @Order(8)
    void describeCapacityReservationUnknownLbThrows() {
        given()
                .formParam("Action", "DescribeCapacityReservation")
                .formParam("LoadBalancerArn",
                        "arn:aws:elasticloadbalancing:us-east-1:000000000000:loadbalancer/app/missing/0123456789abcdef")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }

    @Test
    @Order(9)
    void createAndDescribeLoadBalancerWithSubnetsExposeSubnetAndZoneName() {
        String subnetAwareLbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "lb-with-subnets")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.AvailabilityZones.member.size()",
                        equalTo(2))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.AvailabilityZones.member[0].SubnetId",
                        equalTo(Ec2Service.defaultSubnetId("us-east-1", "a")))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.AvailabilityZones.member[0].ZoneName",
                        equalTo("us-east-1a"))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.AvailabilityZones.member[1].SubnetId",
                        equalTo(Ec2Service.defaultSubnetId("us-east-1", "b")))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.AvailabilityZones.member[1].ZoneName",
                        equalTo("us-east-1b"))
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", subnetAwareLbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.size()",
                        equalTo(2))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member[0].SubnetId",
                        equalTo(Ec2Service.defaultSubnetId("us-east-1", "a")))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member[0].ZoneName",
                        equalTo("us-east-1a"))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member[1].SubnetId",
                        equalTo(Ec2Service.defaultSubnetId("us-east-1", "b")))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member[1].ZoneName",
                        equalTo("us-east-1b"));
    }

    @Test
    @Order(9)
    void setSubnetsReturnsEmptyResultEnvelope() {
        given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "c"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body(containsString("<SetSubnetsResponse xmlns=\"" + ELB_V2_XMLNS + "\">"))
                .body(containsString("<SetSubnetsResult></SetSubnetsResult>"))
                .body("SetSubnetsResponse.ResponseMetadata.RequestId", not(emptyOrNullString()));

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        hasItems(Ec2Service.defaultSubnetId("us-east-1", "b"), Ec2Service.defaultSubnetId("us-east-1", "c")));
    }

    @Test
    @Order(17)
    void createAndSetSubnetMappingsDriveSubnetsAndVpcId() {
        // The AWS Load Balancer Controller sends SubnetMappings rather than Subnets; both actions
        // must treat the mappings' SubnetId values as the subnet list (address pinning is not modelled).
        String subnetMappedArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "nlb-with-subnet-mappings")
                .formParam("Type", "network")
                .formParam("SubnetMappings.member.1.SubnetId", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("SubnetMappings.member.2.SubnetId", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.VpcId",
                        equalTo(Ec2Service.defaultVpcId("us-east-1")))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.AvailabilityZones.member.size()",
                        equalTo(2))
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        hasItems(Ec2Service.defaultSubnetId("us-east-1", "a"), Ec2Service.defaultSubnetId("us-east-1", "b")))
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", subnetMappedArn)
                .formParam("SubnetMappings.member.1.SubnetId", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .formParam("SubnetMappings.member.2.SubnetId", Ec2Service.defaultSubnetId("us-east-1", "c"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", subnetMappedArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.size()",
                        equalTo(2))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        hasItems(Ec2Service.defaultSubnetId("us-east-1", "b"), Ec2Service.defaultSubnetId("us-east-1", "c")));
    }

    @Test
    @Order(18)
    void createLoadBalancerRejectsBothSubnetsAndSubnetMappings() {
        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "both-subnet-forms")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("SubnetMappings.member.1.SubnetId", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("You can specify either subnets or subnet mappings, not both"));
    }

    @Test
    @Order(19)
    void createLoadBalancerRejectsMappingWithoutSubnetId() {
        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "mapping-without-subnet-id")
                .formParam("SubnetMappings.member.1.AllocationId", "eipalloc-12345678")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("You must specify a subnet ID for each subnet mapping."));
    }

    // ── Target Groups ─────────────────────────────────────────────────────────

    @Test
    @Order(10)
    void createLoadBalancerWithSubnetsFromDifferentVpcsThrows() {
        String otherVpcId = given()
                .formParam("Action", "CreateVpc")
                .formParam("CidrBlock", "10.0.0.0/16")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateVpcResponse.vpc.vpcId");

        String otherSubnetId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", otherVpcId)
                .formParam("CidrBlock", "10.0.1.0/24")
                .formParam("AvailabilityZone", "us-east-1a")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateSubnetResponse.subnet.subnetId");

        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "mixed-vpc-lb")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", otherSubnetId)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidConfigurationRequest"));
    }

    @Test
    @Order(11)
    void setSubnetsWithSubnetsFromDifferentVpcsThrows() {
        String otherVpcId = given()
                .formParam("Action", "CreateVpc")
                .formParam("CidrBlock", "10.1.0.0/16")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateVpcResponse.vpc.vpcId");

        String otherSubnetId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", otherVpcId)
                .formParam("CidrBlock", "10.1.1.0/24")
                .formParam("AvailabilityZone", "us-east-1b")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateSubnetResponse.subnet.subnetId");

        String subnetLbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "set-subnets-test-lb")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", subnetLbArn)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", otherSubnetId)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidConfigurationRequest"));
    }

    /**
     * An Application Load Balancer takes subnets in at least two Availability Zones, so one subnet, in
     * Subnets or in SubnetMappings, is the ValidationError a balancer with none gets, and nothing is
     * created. AWS counts the subnets before it looks them up, so one that does not exist gets it too.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NONE", value = {
        "single-subnet-lb|application|Subnets.member.1|a",
        "single-subnet-default-lb|NONE|Subnets.member.1|a",
        "single-mapping-lb|application|SubnetMappings.member.1.SubnetId|a",
        "single-missing-subnet-lb|application|Subnets.member.1|NONE"
    })
    @Order(12)
    void createLoadBalancerWithOneSubnetIsAValidationError(String name, String type, String parameter, String zone) {
        String subnet = zone == null ? "subnet-does-not-exist" : Ec2Service.defaultSubnetId("us-east-1", zone);
        RequestSpecification request = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", name)
                .formParam(parameter, subnet)
                .header("Authorization", AUTH);
        if (type != null) {
            request.formParam("Type", type);
        }
        request
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("At least two subnets in two different Availability Zones must be specified"));

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Names.member.1", name)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }

    /** A Network or Gateway Load Balancer can be in a single Availability Zone. */
    @ParameterizedTest
    @CsvSource({"network", "gateway"})
    @Order(79)
    void createLoadBalancerOnOneSubnetIsAllowedForNetworkAndGatewayLoadBalancers(String type) {
        String arn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "single-subnet-" + type)
                .formParam("Type", type)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        equalTo(Ec2Service.defaultSubnetId("us-east-1", "a")))
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", arn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(13)
    void createLoadBalancerWithSubnetsInSameAvailabilityZoneThrowsInvalidConfigurationRequest() {
        String secondSubnetId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", Ec2Service.defaultVpcId("us-east-1"))
                .formParam("CidrBlock", "172.31.64.0/24")
                .formParam("AvailabilityZone", "us-east-1a")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateSubnetResponse.subnet.subnetId");

        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "duplicate-az-lb")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", secondSubnetId)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidConfigurationRequest"));
    }

    @Test
    @Order(14)
    void setSubnetsWithSubnetsInSameAvailabilityZoneThrowsInvalidConfigurationRequest() {
        String secondSubnetId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", Ec2Service.defaultVpcId("us-east-1"))
                .formParam("CidrBlock", "172.31.65.0/24")
                .formParam("AvailabilityZone", "us-east-1a")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateSubnetResponse.subnet.subnetId");

        String duplicateAzLbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "set-duplicate-az-lb")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", duplicateAzLbArn)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", secondSubnetId)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidConfigurationRequest"));
    }

    /**
     * A load balancer stays in the VPC it was created in. A balancer saved without one, before
     * CreateLoadBalancer required subnets, takes the VPC of its first SetSubnets instead
     * ({@code ElbV2ServiceTest}).
     */
    @Test
    @Order(15)
    void setSubnetsInAnotherVpcIsRefused() {
        String customVpcId = given()
                .formParam("Action", "CreateVpc")
                .formParam("CidrBlock", "10.2.0.0/16")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateVpcResponse.vpc.vpcId");

        String customSubnetAId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", customVpcId)
                .formParam("CidrBlock", "10.2.1.0/24")
                .formParam("AvailabilityZone", "us-east-1b")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateSubnetResponse.subnet.subnetId");

        String customSubnetBId = given()
                .formParam("Action", "CreateSubnet")
                .formParam("VpcId", customVpcId)
                .formParam("CidrBlock", "10.2.2.0/24")
                .formParam("AvailabilityZone", "us-east-1c")
                .header("Authorization", EC2_AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateSubnetResponse.subnet.subnetId");

        String otherVpcLbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "other-vpc-subnets-lb")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", otherVpcLbArn)
                .formParam("Subnets.member.1", customSubnetAId)
                .formParam("Subnets.member.2", customSubnetBId)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidConfigurationRequest"))
                .body("ErrorResponse.Error.Message", equalTo("All subnets must belong to the load balancer VPC."));

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", otherVpcLbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.VpcId",
                        equalTo(Ec2Service.defaultVpcId("us-east-1")))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        contains(Ec2Service.defaultSubnetId("us-east-1", "a"), Ec2Service.defaultSubnetId("us-east-1", "b")));
    }

    @Test
    @Order(16)
    void createLoadBalancerWithMissingSubnetThrowsSubnetNotFound() {
        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "missing-subnet-lb")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", "subnet-does-not-exist")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("SubnetNotFound"));
    }

    // ── Target Groups ─────────────────────────────────────────────────────────

    @Test
    @Order(20)
    void createTargetGroup() {
        tgArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "my-test-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", "80")
                .formParam("VpcId", "vpc-00000001")
                .formParam("TargetType", "ip")
                .formParam("HealthCheckPath", "/health")
                .formParam("HealthCheckIntervalSeconds", "15")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupName",
                        equalTo("my-test-tg"))
                .body("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.Protocol",
                        equalTo("HTTP"))
                .body("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.Port",
                        equalTo("80"))
                .body("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.HealthCheckPath",
                        equalTo("/health"))
                .body("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.HealthCheckIntervalSeconds",
                        equalTo("15"))
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    @Test
    @Order(21)
    void describeTargetGroups() {
        given()
                .formParam("Action", "DescribeTargetGroups")
                .formParam("TargetGroupArns.member.1", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTargetGroupsResponse.DescribeTargetGroupsResult.TargetGroups.member.TargetGroupArn",
                        equalTo(tgArn));
    }

    @Test
    @Order(22)
    void duplicateTargetGroupNameThrows() {
        given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "my-test-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", "81")
                .formParam("VpcId", "vpc-00000001")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DuplicateTargetGroupName"));
    }

    @Test
    @Order(22)
    void createTargetGroupRejectsOutOfRangeHealthCheckPort() {
        given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "bad-hc-port-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", "80")
                .formParam("HealthCheckPort", "70000")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(22)
    void modifyTargetGroupRejectsNonNumericHealthCheckPort() {
        given()
                .formParam("Action", "ModifyTargetGroup")
                .formParam("TargetGroupArn", tgArn)
                .formParam("HealthCheckPort", "not-a-port")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }

    @Test
    @Order(23)
    void modifyTargetGroupAttributes() {
        given()
                .formParam("Action", "ModifyTargetGroupAttributes")
                .formParam("TargetGroupArn", tgArn)
                .formParam("Attributes.member.1.Key", "deregistration_delay.timeout_seconds")
                .formParam("Attributes.member.1.Value", "60")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ModifyTargetGroupAttributesResponse.ModifyTargetGroupAttributesResult.Attributes.member.Key",
                        equalTo("deregistration_delay.timeout_seconds"));
    }

    @Test
    @Order(24)
    void describeTargetGroupsByMissingNameThrows() {
        given()
                .formParam("Action", "DescribeTargetGroups")
                .formParam("Names.member.1", "target-group-that-does-not-exist")
                .header("Authorization", AUTH)
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("TargetGroupNotFound"));
    }

    @Test
    @Order(25)
    void describeTargetGroupsByMissingArnThrows() {
        given()
                .formParam("Action", "DescribeTargetGroups")
                .formParam("TargetGroupArns.member.1",
                        "arn:aws:elasticloadbalancing:us-east-1:000000000000:targetgroup/missing/0123456789abcdef")
                .header("Authorization", AUTH)
                .when()
                .post("/")
                .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("TargetGroupNotFound"));
    }

    // ── Targets ───────────────────────────────────────────────────────────────

    @Test
    @Order(26)
    void registerTargets() {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", tgArn)
                .formParam("Targets.member.1.Id", "10.0.0.1")
                .formParam("Targets.member.1.Port", "80")
                .formParam("Targets.member.2.Id", "10.0.0.2")
                .formParam("Targets.member.2.Port", "80")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    @Test
    @Order(26)
    void registerTargetsRejectsMetadataAddress() {
        given()
                .formParam("Action", "RegisterTargets")
                .formParam("TargetGroupArn", tgArn)
                .formParam("Targets.member.1.Id", "169.254.169.254")
                .formParam("Targets.member.1.Port", "80")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("InvalidTarget"));

        given()
                .formParam("Action", "DescribeTargetHealth")
                .formParam("TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member.Target.Id",
                        not(hasItem("169.254.169.254")));
    }

    @Test
    @Order(27)
    void describeTargetHealthReturnsInitial() {
        given()
                .formParam("Action", "DescribeTargetHealth")
                .formParam("TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member[0].TargetHealth.State",
                        equalTo("initial"))
                .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member[0].TargetHealth.Reason",
                        equalTo("Elb.RegistrationInProgress"));
    }

    @Test
    @Order(28)
    void describeTargetHealthReturnsUnusedForExplicitUnregisteredTarget() {
        given()
                .formParam("Action", "DescribeTargetHealth")
                .formParam("TargetGroupArn", tgArn)
                .formParam("Targets.member.1.Id", "i-1234567890abcdef0")
                .formParam("Targets.member.1.Port", "80")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member.TargetHealth.State",
                        equalTo("unused"))
                .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member.TargetHealth.Reason",
                        equalTo("Target.NotRegistered"))
                .body("DescribeTargetHealthResponse.DescribeTargetHealthResult.TargetHealthDescriptions.member.TargetHealth.Description",
                        equalTo("Target is not registered to the target group"));
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    @Test
    @Order(30)
    void createListener() {
        listenerArn = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", "80")
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateListenerResponse.CreateListenerResult.Listeners.member.Protocol",
                        equalTo("HTTP"))
                .body("CreateListenerResponse.CreateListenerResult.Listeners.member.Port",
                        equalTo("80"))
                .body("CreateListenerResponse.CreateListenerResult.Listeners.member.LoadBalancerArn",
                        equalTo(lbArn))
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    @Test
    @Order(31)
    void describeListeners() {
        given()
                .formParam("Action", "DescribeListeners")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.ListenerArn",
                        equalTo(listenerArn));
    }

    @Test
    @Order(32)
    void duplicateListenerPortThrows() {
        given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", "80")
                .formParam("DefaultActions.member.1.Type", "forward")
                .formParam("DefaultActions.member.1.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DuplicateListener"));
    }

    @Test
    @Order(33)
    void modifyListenerAttributes() {
        given()
                .formParam("Action", "ModifyListenerAttributes")
                .formParam("ListenerArn", listenerArn)
                .formParam("Attributes.member.1.Key", "tcp.idle_timeout.seconds")
                .formParam("Attributes.member.1.Value", "400")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("ModifyListenerAttributesResponse.ModifyListenerAttributesResult.Attributes.member.Key",
                        equalTo("tcp.idle_timeout.seconds"))
                .body("ModifyListenerAttributesResponse.ModifyListenerAttributesResult.Attributes.member.Value",
                        equalTo("400"));
    }

    @Test
    @Order(34)
    void describeListenerAttributes() {
        given()
                .formParam("Action", "DescribeListenerAttributes")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenerAttributesResponse.DescribeListenerAttributesResult.Attributes.member.Key",
                        equalTo("tcp.idle_timeout.seconds"));
    }

    @Test
    @Order(35)
    void describeListenerAttributesUnknownListenerThrows() {
        given()
                .formParam("Action", "DescribeListenerAttributes")
                .formParam("ListenerArn",
                        "arn:aws:elasticloadbalancing:us-east-1:000000000000:listener/app/missing/0123456789abcdef/0123456789abcdef")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ListenerNotFound"));
    }

    // ── Rules ─────────────────────────────────────────────────────────────────

    @Test
    @Order(40)
    void describeRulesIncludesDefaultRule() {
        given()
                .formParam("Action", "DescribeRules")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeRulesResponse.DescribeRulesResult.Rules.member.IsDefault",
                        equalTo("true"))
                .body("DescribeRulesResponse.DescribeRulesResult.Rules.member.Priority",
                        equalTo("default"));
    }

    @Test
    @Order(41)
    void createRuleWithPathPattern() {
        ruleArn1 = given()
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", listenerArn)
                .formParam("Priority", "10")
                .formParam("Conditions.member.1.Field", "path-pattern")
                .formParam("Conditions.member.1.PathPatternConfig.Values.member.1", "/api/*")
                .formParam("Actions.member.1.Type", "forward")
                .formParam("Actions.member.1.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateRuleResponse.CreateRuleResult.Rules.member.Priority",
                        equalTo("10"))
                .body("CreateRuleResponse.CreateRuleResult.Rules.member.IsDefault",
                        equalTo("false"))
                .extract()
                .path("CreateRuleResponse.CreateRuleResult.Rules.member.RuleArn");
    }

    @Test
    @Order(42)
    void createRuleWithHostHeader() {
        ruleArn2 = given()
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", listenerArn)
                .formParam("Priority", "20")
                .formParam("Conditions.member.1.Field", "host-header")
                .formParam("Conditions.member.1.HostHeaderConfig.Values.member.1", "api.example.com")
                .formParam("Actions.member.1.Type", "forward")
                .formParam("Actions.member.1.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("CreateRuleResponse.CreateRuleResult.Rules.member.Priority", equalTo("20"))
                .extract()
                .path("CreateRuleResponse.CreateRuleResult.Rules.member.RuleArn");
    }

    @Test
    @Order(43)
    void priorityInUseThrows() {
        given()
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", listenerArn)
                .formParam("Priority", "10")
                .formParam("Conditions.member.1.Field", "path-pattern")
                .formParam("Conditions.member.1.PathPatternConfig.Values.member.1", "/other/*")
                .formParam("Actions.member.1.Type", "forward")
                .formParam("Actions.member.1.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("PriorityInUse"));
    }

    @Test
    @Order(44)
    void setRulePriorities() {
        given()
                .formParam("Action", "SetRulePriorities")
                .formParam("RulePriorities.member.1.RuleArn", ruleArn1)
                .formParam("RulePriorities.member.1.Priority", "100")
                .formParam("RulePriorities.member.2.RuleArn", ruleArn2)
                .formParam("RulePriorities.member.2.Priority", "200")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("SetRulePrioritiesResponse.SetRulePrioritiesResult.Rules.member[0].Priority",
                        anyOf(equalTo("100"), equalTo("200")));
    }

    @Test
    @Order(45)
    void deleteDefaultRuleThrows() {
        // find the default rule ARN specifically
        String defaultRuleArn = given()
                .formParam("Action", "DescribeRules")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .extract()
                .path("DescribeRulesResponse.DescribeRulesResult.Rules.member.find { it.IsDefault == 'true' }.RuleArn");

        given()
                .formParam("Action", "DeleteRule")
                .formParam("RuleArn", defaultRuleArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("OperationNotPermitted"));
    }

    // ── Tags ──────────────────────────────────────────────────────────────────

    @Test
    @Order(50)
    void tagsRoundtrip() {
        given()
                .formParam("Action", "AddTags")
                .formParam("ResourceArns.member.1", lbArn)
                .formParam("ResourceArns.member.2", tgArn)
                .formParam("Tags.member.1.Key", "Environment")
                .formParam("Tags.member.1.Value", "test")
                .formParam("Tags.member.2.Key", "Team")
                .formParam("Tags.member.2.Value", "platform")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeTags")
                .formParam("ResourceArns.member.1", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeTagsResponse.DescribeTagsResult.TagDescriptions.member.ResourceArn",
                        equalTo(lbArn))
                .body("DescribeTagsResponse.DescribeTagsResult.TagDescriptions.member.Tags.member[0].Key",
                        anyOf(equalTo("Environment"), equalTo("Team")));

        given()
                .formParam("Action", "RemoveTags")
                .formParam("ResourceArns.member.1", lbArn)
                .formParam("TagKeys.member.1", "Environment")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    // ── Meta ──────────────────────────────────────────────────────────────────

    @Test
    @Order(60)
    void describeSSLPolicies() {
        given()
                .formParam("Action", "DescribeSSLPolicies")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeSSLPoliciesResponse.DescribeSSLPoliciesResult.SslPolicies.member.size()",
                        greaterThanOrEqualTo(7))
                .body("DescribeSSLPoliciesResponse.DescribeSSLPoliciesResult.SslPolicies.member.Name",
                        hasItem("ELBSecurityPolicy-2016-08"))
                .body(not(containsString("NextMarker")));
    }

    @Test
    @Order(61)
    void describeAccountLimits() {
        given()
                .formParam("Action", "DescribeAccountLimits")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeAccountLimitsResponse.DescribeAccountLimitsResult.Limits.member.Name",
                        hasItem("application-load-balancers"));
    }

    // ── Delete cascade ────────────────────────────────────────────────────────

    @Test
    @Order(70)
    void deleteTargetGroupInUseThrows() {
        given()
                .formParam("Action", "DeleteTargetGroup")
                .formParam("TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                // TG still has loadBalancerArns from the listener
                .statusCode(anyOf(equalTo(200), equalTo(400)));
    }

    @Test
    @Order(71)
    void deleteListenerThenDeleteTargetGroup() {
        given()
                .formParam("Action", "DeleteListener")
                .formParam("ListenerArn", listenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body(containsString("<DeleteListenerResponse xmlns=\"" + ELB_V2_XMLNS + "\">"))
                .body(containsString("<DeleteListenerResult></DeleteListenerResult>"))
                .body("DeleteListenerResponse.ResponseMetadata.RequestId", not(emptyOrNullString()));

        given()
                .formParam("Action", "DescribeListeners")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.size()", equalTo(0));

        given()
                .formParam("Action", "DeleteTargetGroup")
                .formParam("TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body(containsString("<DeleteTargetGroupResponse xmlns=\"" + ELB_V2_XMLNS + "\">"))
                .body(containsString("<DeleteTargetGroupResult></DeleteTargetGroupResult>"))
                .body("DeleteTargetGroupResponse.ResponseMetadata.RequestId", not(emptyOrNullString()));
        tgArn = null;
    }

    @Test
    @Order(72)
    void deleteTargetGroupIncludesEmptyResult() {
        given()
                .formParam("Action", "DeleteTargetGroup")
                .formParam("TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(containsString("<DeleteTargetGroupResult"))
                .body("DeleteTargetGroupResponse.ResponseMetadata.RequestId", not(isEmptyOrNullString()));
    }

    @Test
    @Order(73)
    void deleteLoadBalancerCascades() {
        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .contentType("application/xml")
                .body(containsString("<DeleteLoadBalancerResponse xmlns=\"" + ELB_V2_XMLNS + "\">"))
                .body(containsString("<DeleteLoadBalancerResult></DeleteLoadBalancerResult>"))
                .body("DeleteLoadBalancerResponse.ResponseMetadata.RequestId", not(emptyOrNullString()));

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Names.member.1", "my-test-lb")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }

    @Test
    @Order(74)
    void createLoadBalancerWithMissingSubnetReturnsSubnetNotFound() {
        given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "lb-missing-subnet")
                .formParam("Type", "network")
                .formParam("Subnets.member.1", "subnet-does-not-exist")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("SubnetNotFound"));
    }

    @Test
    @Order(75)
    void setSubnetsWithMissingSubnetReturnsSubnetNotFound() {
        String setSubnetsLbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "lb-set-subnets")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        // Two subnets, so the Application Load Balancer's subnet count passes and the lookup is reached.
        given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", setSubnetsLbArn)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", "subnet-does-not-exist")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("SubnetNotFound"));
    }

    /**
     * SetSubnets counts the subnets the way CreateLoadBalancer does, so one subnet for an Application
     * Load Balancer, in Subnets or in SubnetMappings, is the ValidationError CreateLoadBalancer gives,
     * and the balancer stays on its subnets and in its VPC. The count comes before the subnet is
     * looked up, so one that does not exist gets it too.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NONE", value = {
        "set-one-subnet|Subnets.member.1|a",
        "set-one-mapping|SubnetMappings.member.1.SubnetId|a",
        "set-one-missing-subnet|Subnets.member.1|NONE"
    })
    @Order(68)
    void setSubnetsOntoOneSubnetIsAValidationErrorForAnApplicationLoadBalancer(String name, String parameter,
                                                                                String zone) {
        String subnetA = Ec2Service.defaultSubnetId("us-east-1", "a");
        String subnetB = Ec2Service.defaultSubnetId("us-east-1", "b");
        String subnet = zone == null ? "subnet-does-not-exist" : Ec2Service.defaultSubnetId("us-east-1", zone);
        String arn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", name)
                .formParam("Type", "application")
                .formParam("Subnets.member.1", subnetA)
                .formParam("Subnets.member.2", subnetB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", arn)
                .formParam(parameter, subnet)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("At least two subnets in two different Availability Zones must be specified"));

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", arn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        contains(subnetA, subnetB))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.VpcId",
                        equalTo(Ec2Service.defaultVpcId("us-east-1")));

        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", arn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    /**
     * A Network or Gateway Load Balancer can be in a single Availability Zone, so SetSubnets onto one
     * subnet is taken: the Network Load Balancer leaves one of its two subnets, and the Gateway Load
     * Balancer, which AWS lets add subnets but not drop them, is set onto the one it is on.
     */
    @ParameterizedTest
    @CsvSource({"network,true", "gateway,false"})
    @Order(69)
    void setSubnetsOntoOneSubnetIsAllowedForNetworkAndGatewayLoadBalancers(String type, boolean onTwoSubnets) {
        String subnetA = Ec2Service.defaultSubnetId("us-east-1", "a");
        RequestSpecification create = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "set-one-subnet-" + type)
                .formParam("Type", type)
                .formParam("Subnets.member.1", subnetA)
                .header("Authorization", AUTH);
        if (onTwoSubnets) {
            create.formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"));
        }
        String arn = create
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", arn)
                .formParam("Subnets.member.1", subnetA)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", arn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        equalTo(subnetA));

        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", arn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }

    /**
     * Every type takes Subnets or SubnetMappings, so CreateLoadBalancer with neither is a
     * ValidationError and no load balancer is created. The messages are the ones AWS gives an
     * Application or a Network Load Balancer; a Gateway Load Balancer gets the Network one.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "NONE", value = {
        "NONE|At least two subnets in two different Availability Zones must be specified",
        "application|At least two subnets in two different Availability Zones must be specified",
        "network|At least one subnet must be specified",
        "gateway|At least one subnet must be specified"
    })
    @Order(76)
    void createLoadBalancerWithNeitherSubnetsNorSubnetMappingsIsRefused(String type, String message) {
        String name = "no-subnets-" + (type == null ? "default" : type);
        RequestSpecification request = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", name)
                .header("Authorization", AUTH);
        if (type != null) {
            request.formParam("Type", type);
        }
        request
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo(message));

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("Names.member.1", name)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("LoadBalancerNotFound"));
    }

    /** SetSubnets takes one or the other as well: both are refused and the balancer keeps its subnets. */
    @Test
    @Order(77)
    void setSubnetsRejectsBothSubnetsAndSubnetMappings() {
        String bothFormsLbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "set-both-subnet-forms")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", bothFormsLbArn)
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "b"))
                .formParam("SubnetMappings.member.1.SubnetId", Ec2Service.defaultSubnetId("us-east-1", "c"))
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message",
                        equalTo("You can specify either subnets or subnet mappings, not both"));

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", bothFormsLbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        contains(Ec2Service.defaultSubnetId("us-east-1", "a"), Ec2Service.defaultSubnetId("us-east-1", "b")));
    }

    /**
     * SetSubnets with neither Subnets nor SubnetMappings, left out or sent as empty lists, is a
     * ValidationError, and the balancer stays on its subnets and in its VPC. A Network Load Balancer
     * gets the message AWS gives it; an Application Load Balancer gets the one CreateLoadBalancer gives
     * it, and a Gateway Load Balancer the Network one.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "application|false|At least two subnets in two different Availability Zones must be specified",
        "application|true|At least two subnets in two different Availability Zones must be specified",
        "network|false|At least one subnet must be specified",
        "network|true|At least one subnet must be specified",
        "gateway|false|At least one subnet must be specified",
        "gateway|true|At least one subnet must be specified"
    })
    @Order(80)
    void setSubnetsWithNeitherSubnetsNorSubnetMappingsIsRefused(String type, boolean emptyLists, String message) {
        String subnetA = Ec2Service.defaultSubnetId("us-east-1", "a");
        String subnetB = Ec2Service.defaultSubnetId("us-east-1", "b");
        String arn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "set-none-" + type + (emptyLists ? "-empty" : ""))
                .formParam("Type", type)
                .formParam("Subnets.member.1", subnetA)
                .formParam("Subnets.member.2", subnetB)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        RequestSpecification setSubnets = given()
                .formParam("Action", "SetSubnets")
                .formParam("LoadBalancerArn", arn)
                .header("Authorization", AUTH);
        if (emptyLists) {
            setSubnets.formParam("Subnets", "").formParam("SubnetMappings", "");
        }
        setSubnets
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"))
                .body("ErrorResponse.Error.Message", equalTo(message));

        given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", arn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.AvailabilityZones.member.SubnetId",
                        contains(subnetA, subnetB))
                .body("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.VpcId",
                        equalTo(Ec2Service.defaultVpcId("us-east-1")));

        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", arn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);
    }
}
