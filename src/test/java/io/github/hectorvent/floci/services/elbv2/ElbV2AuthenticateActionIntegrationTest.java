package io.github.hectorvent.floci.services.elbv2;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * Regression coverage for lex00/floci#65: DescribeListeners/DescribeRules must round-trip
 * AuthenticateCognitoConfig and AuthenticateOidcConfig, not silently drop them.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2AuthenticateActionIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";

    private static String lbArn;
    private static String tgArn;
    private static String cognitoListenerArn;
    private static String oidcListenerArn;
    private static String oidcRuleArn;

    @Test
    @Order(1)
    void setUp() {
        lbArn = given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "auth-action-test-lb")
                .formParam("Type", "application")
                .formParam("Scheme", "internet-facing")
                .formParam("Subnets.member.1", "subnet-default-a")
                .formParam("Subnets.member.2", "subnet-default-b")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");

        tgArn = given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "auth-action-test-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", "80")
                .formParam("VpcId", "vpc-00000001")
                .formParam("TargetType", "ip")
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    // ── authenticate-cognito ─────────────────────────────────────────────────

    @Test
    @Order(2)
    void createListenerWithAuthenticateCognitoDefaultActionRoundTrips() {
        cognitoListenerArn = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", "8081")
                .formParam("DefaultActions.member.1.Type", "authenticate-cognito")
                .formParam("DefaultActions.member.1.Order", "1")
                .formParam("DefaultActions.member.1.AuthenticateCognitoConfig.UserPoolArn",
                        "arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_TestPool")
                .formParam("DefaultActions.member.1.AuthenticateCognitoConfig.UserPoolClientId", "test-client-id")
                .formParam("DefaultActions.member.1.AuthenticateCognitoConfig.UserPoolDomain", "test-domain")
                .formParam("DefaultActions.member.1.AuthenticateCognitoConfig.SessionCookieName", "MyAuthCookie")
                .formParam("DefaultActions.member.1.AuthenticateCognitoConfig.SessionTimeout", "3600")
                .formParam("DefaultActions.member.1.AuthenticateCognitoConfig.Scope", "openid email")
                .formParam("DefaultActions.member.1.AuthenticateCognitoConfig.OnUnauthenticatedRequest", "deny")
                .formParam("DefaultActions.member.1.AuthenticateCognitoConfig.AuthenticationRequestExtraParams.entry.1.key", "prompt")
                .formParam("DefaultActions.member.1.AuthenticateCognitoConfig.AuthenticationRequestExtraParams.entry.1.value", "login")
                .formParam("DefaultActions.member.2.Type", "forward")
                .formParam("DefaultActions.member.2.Order", "2")
                .formParam("DefaultActions.member.2.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");

        given()
                .formParam("Action", "DescribeListeners")
                .formParam("ListenerArns.member.1", cognitoListenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .rootPath("DescribeListenersResponse.DescribeListenersResult.Listeners.member.DefaultActions.member.find { it.Type == 'authenticate-cognito' }")
                .body("Type", equalTo("authenticate-cognito"))
                .body("AuthenticateCognitoConfig.UserPoolArn",
                        equalTo("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_TestPool"))
                .body("AuthenticateCognitoConfig.UserPoolClientId", equalTo("test-client-id"))
                .body("AuthenticateCognitoConfig.UserPoolDomain", equalTo("test-domain"))
                .body("AuthenticateCognitoConfig.SessionCookieName", equalTo("MyAuthCookie"))
                .body("AuthenticateCognitoConfig.SessionTimeout", equalTo("3600"))
                .body("AuthenticateCognitoConfig.Scope", equalTo("openid email"))
                .body("AuthenticateCognitoConfig.OnUnauthenticatedRequest", equalTo("deny"))
                .body("AuthenticateCognitoConfig.AuthenticationRequestExtraParams.entry.key", equalTo("prompt"))
                .body("AuthenticateCognitoConfig.AuthenticationRequestExtraParams.entry.value", equalTo("login"));
    }

    // ── authenticate-oidc ────────────────────────────────────────────────────

    @Test
    @Order(3)
    void createListenerWithAuthenticateOidcDefaultActionRoundTrips() {
        oidcListenerArn = given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTP")
                .formParam("Port", "8082")
                .formParam("DefaultActions.member.1.Type", "authenticate-oidc")
                .formParam("DefaultActions.member.1.Order", "1")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.Issuer", "https://idp.example.com")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.AuthorizationEndpoint", "https://idp.example.com/authorize")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.TokenEndpoint", "https://idp.example.com/token")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.UserInfoEndpoint", "https://idp.example.com/userinfo")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.ClientId", "test-oidc-client-id")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.ClientSecret", "super-secret-value")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.SessionCookieName", "MyOidcCookie")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.SessionTimeout", "7200")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.Scope", "openid")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.OnUnauthenticatedRequest", "authenticate")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.AuthenticationRequestExtraParams.entry.1.key", "display")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.AuthenticationRequestExtraParams.entry.1.value", "page")
                .formParam("DefaultActions.member.2.Type", "forward")
                .formParam("DefaultActions.member.2.Order", "2")
                .formParam("DefaultActions.member.2.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");

        given()
                .formParam("Action", "DescribeListeners")
                .formParam("ListenerArns.member.1", oidcListenerArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                // Real AWS never returns ClientSecret from Describe* calls; confirm the
                // emulator omits the element entirely rather than echoing it back.
                .body(not(containsString("ClientSecret")))
                .rootPath("DescribeListenersResponse.DescribeListenersResult.Listeners.member.DefaultActions.member.find { it.Type == 'authenticate-oidc' }")
                .body("Type", equalTo("authenticate-oidc"))
                .body("AuthenticateOidcConfig.Issuer", equalTo("https://idp.example.com"))
                .body("AuthenticateOidcConfig.AuthorizationEndpoint", equalTo("https://idp.example.com/authorize"))
                .body("AuthenticateOidcConfig.TokenEndpoint", equalTo("https://idp.example.com/token"))
                .body("AuthenticateOidcConfig.UserInfoEndpoint", equalTo("https://idp.example.com/userinfo"))
                .body("AuthenticateOidcConfig.ClientId", equalTo("test-oidc-client-id"))
                .body("AuthenticateOidcConfig.SessionCookieName", equalTo("MyOidcCookie"))
                .body("AuthenticateOidcConfig.SessionTimeout", equalTo("7200"))
                .body("AuthenticateOidcConfig.Scope", equalTo("openid"))
                .body("AuthenticateOidcConfig.OnUnauthenticatedRequest", equalTo("authenticate"))
                .body("AuthenticateOidcConfig.AuthenticationRequestExtraParams.entry.key", equalTo("display"))
                .body("AuthenticateOidcConfig.AuthenticationRequestExtraParams.entry.value", equalTo("page"));
    }

    @Test
    @Order(4)
    void createRuleWithAuthenticateOidcActionRoundTrips() {
        oidcRuleArn = given()
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", oidcListenerArn)
                .formParam("Priority", "10")
                .formParam("Conditions.member.1.Field", "path-pattern")
                .formParam("Conditions.member.1.PathPatternConfig.Values.member.1", "/secure/*")
                .formParam("Actions.member.1.Type", "authenticate-oidc")
                .formParam("Actions.member.1.Order", "1")
                .formParam("Actions.member.1.AuthenticateOidcConfig.Issuer", "https://idp.example.com")
                .formParam("Actions.member.1.AuthenticateOidcConfig.AuthorizationEndpoint", "https://idp.example.com/authorize")
                .formParam("Actions.member.1.AuthenticateOidcConfig.TokenEndpoint", "https://idp.example.com/token")
                .formParam("Actions.member.1.AuthenticateOidcConfig.UserInfoEndpoint", "https://idp.example.com/userinfo")
                .formParam("Actions.member.1.AuthenticateOidcConfig.ClientId", "rule-oidc-client-id")
                .formParam("Actions.member.1.AuthenticateOidcConfig.ClientSecret", "rule-super-secret")
                .formParam("Actions.member.1.AuthenticateOidcConfig.OnUnauthenticatedRequest", "deny")
                .formParam("Actions.member.2.Type", "forward")
                .formParam("Actions.member.2.Order", "2")
                .formParam("Actions.member.2.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("CreateRuleResponse.CreateRuleResult.Rules.member.RuleArn");

        given()
                .formParam("Action", "DescribeRules")
                .formParam("RuleArns.member.1", oidcRuleArn)
                .header("Authorization", AUTH)
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body(not(containsString("ClientSecret")))
                .rootPath("DescribeRulesResponse.DescribeRulesResult.Rules.member.Actions.member.find { it.Type == 'authenticate-oidc' }")
                .body("Type", equalTo("authenticate-oidc"))
                .body("AuthenticateOidcConfig.Issuer", equalTo("https://idp.example.com"))
                .body("AuthenticateOidcConfig.AuthorizationEndpoint", equalTo("https://idp.example.com/authorize"))
                .body("AuthenticateOidcConfig.TokenEndpoint", equalTo("https://idp.example.com/token"))
                .body("AuthenticateOidcConfig.UserInfoEndpoint", equalTo("https://idp.example.com/userinfo"))
                .body("AuthenticateOidcConfig.ClientId", equalTo("rule-oidc-client-id"))
                .body("AuthenticateOidcConfig.OnUnauthenticatedRequest", equalTo("deny"));
    }
}
