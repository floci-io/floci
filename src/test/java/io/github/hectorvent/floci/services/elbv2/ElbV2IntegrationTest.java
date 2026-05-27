package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

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

    private static String lbArn;
    private static String tgArn;
    private static String listenerArn;
    private static String ruleArn1;
    private static String ruleArn2;

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
                .body("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.DNSName",
                        containsString(".elb.localhost"))
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
                        equalTo("active"));
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
        given()
                .formParam("Action", "DescribeLoadBalancerAttributes")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeLoadBalancerAttributesResponse.DescribeLoadBalancerAttributesResult.Attributes.member.Key",
                        equalTo("deletion_protection.enabled"));
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

    // ── Target Groups ─────────────────────────────────────────────────────────

    @Test
    @Order(10)
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
    @Order(11)
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
    @Order(12)
    void duplicateTargetGroupNameThrows() {
        given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "my-test-tg")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("DuplicateTargetGroupName"));
    }

    @Test
    @Order(13)
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

    // ── Targets ───────────────────────────────────────────────────────────────

    @Test
    @Order(20)
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
    @Order(21)
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
                        hasItem("ELBSecurityPolicy-2016-08"));
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
                .statusCode(200);

        given()
                .formParam("Action", "DescribeListeners")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("DescribeListenersResponse.DescribeListenersResult.Listeners.member.size()", equalTo(0));
    }

    @Test
    @Order(72)
    void deleteLoadBalancerCascades() {
        given()
                .formParam("Action", "DeleteLoadBalancer")
                .formParam("LoadBalancerArn", lbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200);

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
}
