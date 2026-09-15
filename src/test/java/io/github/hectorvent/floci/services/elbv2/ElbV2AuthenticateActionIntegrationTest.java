package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * DescribeListeners and DescribeRules have to echo back the authenticate-oidc and
 * authenticate-cognito configurations a listener or rule was created with. Without them a client
 * that reads its own configuration back sees every sub-field missing and treats the listener as
 * changed on every plan.
 *
 * <p>ClientSecret is the exception. Real AWS never returns it, so neither does floci.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ElbV2AuthenticateActionIntegrationTest {

    private static final String AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260427/us-east-1/elasticloadbalancing/aws4_request";

    private static String lbArn;
    private static String tgArn;
    private static String listenerArn;

    private static String xml(io.restassured.specification.RequestSpecification spec) {
        return spec.header("Authorization", AUTH).when().post("/").then().statusCode(200)
                .extract().asString();
    }

    @Test
    @Order(1)
    void seedLoadBalancerAndTargetGroup() {
        lbArn = XmlPath.from(xml(given()
                .formParam("Action", "CreateLoadBalancer")
                .formParam("Name", "auth-action-lb")
                .formParam("Type", "application")
                .formParam("Subnets.member.1", Ec2Service.defaultSubnetId("us-east-1", "a"))
                .formParam("Subnets.member.2", Ec2Service.defaultSubnetId("us-east-1", "b"))))
                .getString("CreateLoadBalancerResponse.CreateLoadBalancerResult"
                        + ".LoadBalancers.member.LoadBalancerArn");

        tgArn = XmlPath.from(xml(given()
                .formParam("Action", "CreateTargetGroup")
                .formParam("Name", "auth-action-tg")
                .formParam("Protocol", "HTTP")
                .formParam("Port", "80")
                .formParam("VpcId", Ec2Service.defaultVpcId("us-east-1"))))
                .getString("CreateTargetGroupResponse.CreateTargetGroupResult"
                        + ".TargetGroups.member.TargetGroupArn");
    }

    @Test
    @Order(2)
    void createListener_withAuthenticateOidc_roundTripsEverySubFieldExceptTheSecret() {
        String base = "DefaultActions.member.1.AuthenticateOidcConfig.";
        listenerArn = XmlPath.from(xml(given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTPS")
                .formParam("Port", "443")
                .formParam("DefaultActions.member.1.Type", "authenticate-oidc")
                .formParam("DefaultActions.member.1.Order", "1")
                .formParam(base + "Issuer", "https://idp.example.com")
                .formParam(base + "AuthorizationEndpoint", "https://idp.example.com/authorize")
                .formParam(base + "TokenEndpoint", "https://idp.example.com/token")
                .formParam(base + "UserInfoEndpoint", "https://idp.example.com/userinfo")
                .formParam(base + "ClientId", "client-abc")
                .formParam(base + "ClientSecret", "shhh")
                .formParam(base + "OnUnauthenticatedRequest", "authenticate")
                .formParam(base + "AuthenticationRequestExtraParams.entry.1.key", "prompt")
                .formParam(base + "AuthenticationRequestExtraParams.entry.1.value", "login")
                .formParam("DefaultActions.member.2.Type", "forward")
                .formParam("DefaultActions.member.2.Order", "2")
                .formParam("DefaultActions.member.2.TargetGroupArn", tgArn)))
                .getString("CreateListenerResponse.CreateListenerResult"
                        + ".Listeners.member.ListenerArn");

        String raw = xml(given()
                .formParam("Action", "DescribeListeners")
                .formParam("ListenerArns.member.1", listenerArn));
        XmlPath described = XmlPath.from(raw);

        String action = "DescribeListenersResponse.DescribeListenersResult.Listeners.member"
                + ".DefaultActions.member[0].AuthenticateOidcConfig.";
        assertEquals("https://idp.example.com", described.getString(action + "Issuer"));
        assertEquals("https://idp.example.com/authorize",
                described.getString(action + "AuthorizationEndpoint"));
        assertEquals("https://idp.example.com/token", described.getString(action + "TokenEndpoint"));
        assertEquals("https://idp.example.com/userinfo",
                described.getString(action + "UserInfoEndpoint"));
        assertEquals("client-abc", described.getString(action + "ClientId"));
        assertEquals("authenticate", described.getString(action + "OnUnauthenticatedRequest"));
        assertEquals("prompt",
                described.getString(action + "AuthenticationRequestExtraParams.entry.key"));
        assertEquals("login",
                described.getString(action + "AuthenticationRequestExtraParams.entry.value"));

        // The model documents all three defaults, and AWS reports the resolved value.
        assertEquals("AWSELBAuthSessionCookie", described.getString(action + "SessionCookieName"));
        assertEquals("openid", described.getString(action + "Scope"));
        assertEquals("604800", described.getString(action + "SessionTimeout"));

        assertFalse(raw.contains("ClientSecret"),
                "real AWS never returns ClientSecret from a Describe call");
    }

    @Test
    @Order(3)
    void createRule_withAuthenticateCognito_roundTripsItsConfig() {
        String base = "Actions.member.1.AuthenticateCognitoConfig.";
        given()
                .formParam("Action", "CreateRule")
                .formParam("ListenerArn", listenerArn)
                .formParam("Priority", "10")
                .formParam("Conditions.member.1.Field", "path-pattern")
                .formParam("Conditions.member.1.Values.member.1", "/private/*")
                .formParam("Actions.member.1.Type", "authenticate-cognito")
                .formParam("Actions.member.1.Order", "1")
                .formParam(base + "UserPoolArn",
                        "arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_abc")
                .formParam(base + "UserPoolClientId", "pool-client")
                .formParam(base + "UserPoolDomain", "auth.example.com")
                .formParam(base + "Scope", "openid profile")
                .formParam(base + "SessionTimeout", "3600")
                .formParam("Actions.member.2.Type", "forward")
                .formParam("Actions.member.2.Order", "2")
                .formParam("Actions.member.2.TargetGroupArn", tgArn)
                .header("Authorization", AUTH)
            .when().post("/").then().statusCode(200);

        XmlPath described = XmlPath.from(xml(given()
                .formParam("Action", "DescribeRules")
                .formParam("ListenerArn", listenerArn)));

        String action = "DescribeRulesResponse.DescribeRulesResult.Rules.member"
                + ".findAll { it.Priority.text() == '10' }.Actions.member[0]"
                + ".AuthenticateCognitoConfig.";
        assertEquals("arn:aws:cognito-idp:us-east-1:000000000000:userpool/us-east-1_abc",
                described.getString(action + "UserPoolArn"));
        assertEquals("pool-client", described.getString(action + "UserPoolClientId"));
        assertEquals("auth.example.com", described.getString(action + "UserPoolDomain"));
        assertEquals("openid profile", described.getString(action + "Scope"));
        assertEquals("3600", described.getString(action + "SessionTimeout"));
        assertEquals("AWSELBAuthSessionCookie", described.getString(action + "SessionCookieName"));
    }

    @Test
    @Order(4)
    void createListener_missingRequiredOidcMember_isRejected() {
        given()
                .formParam("Action", "CreateListener")
                .formParam("LoadBalancerArn", lbArn)
                .formParam("Protocol", "HTTPS")
                .formParam("Port", "8443")
                .formParam("DefaultActions.member.1.Type", "authenticate-oidc")
                .formParam("DefaultActions.member.1.AuthenticateOidcConfig.Issuer",
                        "https://idp.example.com")
                .header("Authorization", AUTH)
            .when().post("/")
            .then().statusCode(400)
                .body("ErrorResponse.Error.Code", equalTo("ValidationError"));
    }
}
