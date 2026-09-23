package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.testing.RealElbV2DataPlaneProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(RealElbV2DataPlaneProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2SamePortHostDispatchIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260629/us-east-1/elasticloadbalancing/aws4_request";
    private static final int LISTENER_PORT = 7793;

    private static String firstLbArn;
    private static String firstDnsName;
    private static String firstListenerArn;
    private static String secondLbArn;
    private static String secondDnsName;
    private static String secondListenerArn;
    private static String firstRuleArn;
    private static String secondRuleArn;

    @Test
    @Order(1)
    void createFirstLoadBalancerAndListener() {
        firstLbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "same-port-first")
                .formParam("Type", "application")
                .formParam("Scheme", "internet-facing")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        firstDnsName = given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", firstLbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.DNSName");

        firstListenerArn = createFixedResponseListener(firstLbArn, "first");
    }

    @Test
    @Order(2)
    void singleListenerFallsBackForUnknownHostHeader() {
        assertHostResponse("unknown.example.test", "first");
    }

    @Test
    @Order(3)
    void createSecondLoadBalancerAndListenerOnSamePort() {
        secondLbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "same-port-second")
                .formParam("Type", "application")
                .formParam("Scheme", "internet-facing")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        secondDnsName = given()
                .formParam("Action", "DescribeLoadBalancers")
                .formParam("LoadBalancerArns.member.1", secondLbArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("DescribeLoadBalancersResponse.DescribeLoadBalancersResult.LoadBalancers.member.DNSName");

        secondListenerArn = createFixedResponseListener(secondLbArn, "second");
    }

    @Test
    @Order(4)
    void samePortListenersDispatchByHostHeader() {
        assertHostResponse(firstDnsName, "first");
        assertHostResponse(secondDnsName, "second");
    }

    @Test
    @Order(5)
    void samePortListenersRejectUnknownHostHeader() {
        assertNoListenerForHost("unknown.example.test");
    }

    @Test
    @Order(6)
    void samePortListenerClaimsAHostItsRulesDeclare() {
        secondRuleArn = createHostHeaderRule(secondListenerArn, 10,
                List.of("app.example.test", "*.wild.example.test"), "second-rule");

        assertHostResponse("app.example.test", "second-rule");
        assertHostResponse("api.wild.example.test", "second-rule");
        assertHostResponse("APP.EXAMPLE.TEST", "second-rule");
    }

    /**
     * AWS states that the rule {@code *.example.com} matches {@code test.example.com} but not
     * {@code example.com}, so a wildcard must not let a listener claim the bare domain either.
     */
    @Test
    @Order(7)
    void aWildcardRuleDoesNotClaimTheBareDomain() {
        assertNoListenerForHost("wild.example.test");
    }

    @Test
    @Order(8)
    void aDeclaredHostDoesNotDisplaceTheLoadBalancerDnsName() {
        assertHostResponse(firstDnsName, "first");
        assertHostResponse(secondDnsName, "second");
    }

    @Test
    @Order(9)
    void aHostBothListenersDeclareStaysUnclaimed() {
        firstRuleArn = createHostHeaderRule(firstListenerArn, 10,
                List.of("app.example.test"), "first-rule");

        assertNoListenerForHost("app.example.test");
        assertHostResponse("api.wild.example.test", "second-rule");
    }

    @Test
    @Order(Integer.MAX_VALUE)
    void cleanup() {
        deleteRule(firstRuleArn);
        deleteRule(secondRuleArn);
        deleteListener(firstListenerArn);
        deleteListener(secondListenerArn);
        deleteLoadBalancer(firstLbArn);
        deleteLoadBalancer(secondLbArn);
    }

    private static String createHostHeaderRule(String listenerArn, int priority, List<String> hosts, String body) {
        RequestSpecification request = given()
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", listenerArn)
                .formParam("Priority", String.valueOf(priority))
                .formParam("Conditions.member.1.Field", "host-header")
                .formParam("Actions.member.1.Type", "fixed-response")
                .formParam("Actions.member.1.FixedResponseConfig.StatusCode", "200")
                .formParam("Actions.member.1.FixedResponseConfig.ContentType", "text/plain")
                .formParam("Actions.member.1.FixedResponseConfig.MessageBody", body)
                .header("Authorization", AUTH);
        for (int i = 0; i < hosts.size(); i++) {
            request = request.formParam(
                    "Conditions.member.1.HostHeaderConfig.Values.member." + (i + 1), hosts.get(i));
        }
        return request
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateRuleResponse.CreateRuleResult.Rules.member.RuleArn");
    }

    private static void deleteRule(String ruleArn) {
        if (ruleArn != null) {
            given()
                    .formParam("Action", "DeleteRule")
                    .formParam("RuleArn", ruleArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static String createFixedResponseListener(String lbArn, String body) {
        return given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", String.valueOf(LISTENER_PORT))
                .formParam("DefaultActions.member.1.Type", "fixed-response")
                .formParam("DefaultActions.member.1.FixedResponseConfig.StatusCode", "200")
                .formParam("DefaultActions.member.1.FixedResponseConfig.ContentType", "text/plain")
                .formParam("DefaultActions.member.1.FixedResponseConfig.MessageBody", body)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
    }

    private static void assertHostResponse(String host, String body) {
        given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
                .header("Host", host)
            .when()
                .get("/")
            .then()
                .statusCode(200)
                .body(equalTo(body));
    }

    private static void assertNoListenerForHost(String host) {
        given()
                .baseUri("http://127.0.0.1")
                .port(LISTENER_PORT)
                .header("Host", host)
            .when()
                .get("/")
            .then()
                .statusCode(502)
                .body(equalTo("No listener for host"));
    }

    private static void deleteListener(String listenerArn) {
        if (listenerArn != null) {
            given()
                    .formParam("Action", "DeleteListener")
                    .formParam("ListenerArn", listenerArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }

    private static void deleteLoadBalancer(String lbArn) {
        if (lbArn != null) {
            given()
                    .formParam("Action", "DeleteLoadBalancer")
                    .formParam("LoadBalancerArn", lbArn)
                    .header("Authorization", AUTH)
                .when()
                    .post("/")
                .then()
                    .statusCode(anyOf(equalTo(200), equalTo(204)));
        }
    }
}
