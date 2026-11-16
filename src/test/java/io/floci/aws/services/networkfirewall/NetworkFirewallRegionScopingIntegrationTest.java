package io.floci.aws.services.networkfirewall;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * Network Firewall rule groups, firewall policies and firewalls are Regional: each is listed, described, changed and
 * deleted only in the Region it was created in, by its name or its ARN, and the same name can be used in another
 * Region. Another Region's ARN finds nothing, as AWS answers it with {@code ResourceNotFoundException}, even beside a
 * name of the request's Region.
 */
@QuarkusTest
class NetworkFirewallRegionScopingIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String HOME = "eu-west-1";
    private static final String OTHER = "eu-central-1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aRuleGroupIsListedDescribedChangedAndDeletedOnlyInItsRegion() {
        String name = "rg-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\",\"Capacity\":10,"
                + "\"Rules\":\"pass tcp any any -> any any (sid:1;)\"}";
        String arn = call(HOME, "CreateRuleGroup", body).statusCode(200)
                .extract().path("RuleGroupResponse.RuleGroupArn");
        try {
            call(OTHER, "ListRuleGroups", "{}").statusCode(200).body("RuleGroups.Name", not(hasItem(name)));
            notFound(call(OTHER, "DescribeRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}"));
            notFound(call(OTHER, "DescribeRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\"}"));
            notFound(call(OTHER, "UpdateRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\",\"UpdateToken\":\"t\","
                    + "\"Rules\":\"pass tcp any any -> any any (sid:2;)\"}"));
            notFound(call(OTHER, "DeleteRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}"));

            String otherArn = call(OTHER, "CreateRuleGroup", body).statusCode(200)
                    .extract().path("RuleGroupResponse.RuleGroupArn");
            // The ARN names the rule group, so another one of its name in the request's Region isn't reached instead.
            notFound(call(OTHER, "DescribeRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\",\"RuleGroupName\":\""
                    + name + "\",\"Type\":\"STATEFUL\"}"));
            notFound(call(OTHER, "DeleteRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\",\"RuleGroupName\":\""
                    + name + "\",\"Type\":\"STATEFUL\"}"));
            call(OTHER, "DescribeRuleGroup", "{\"RuleGroupArn\":\"" + otherArn + "\"}").statusCode(200);
            call(OTHER, "DeleteRuleGroup", "{\"RuleGroupArn\":\"" + otherArn + "\"}").statusCode(200);

            call(HOME, "ListRuleGroups", "{}").statusCode(200).body("RuleGroups.Name", hasItem(name));
            call(HOME, "DescribeRuleGroup", "{\"RuleGroupName\":\"" + name + "\",\"Type\":\"STATEFUL\"}")
                    .statusCode(200).body("RuleGroupResponse.RuleGroupArn", equalTo(arn));
        } finally {
            call(HOME, "DeleteRuleGroup", "{\"RuleGroupArn\":\"" + arn + "\"}");
        }
    }

    @Test
    void aFirewallPolicyIsListedDescribedAndDeletedOnlyInItsRegion() {
        String name = "policy-" + UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"FirewallPolicyName\":\"" + name + "\",\"FirewallPolicy\":{"
                + "\"StatelessDefaultActions\":[\"aws:forward_to_sfe\"],"
                + "\"StatelessFragmentDefaultActions\":[\"aws:forward_to_sfe\"]}}";
        String arn = call(HOME, "CreateFirewallPolicy", body).statusCode(200)
                .extract().path("FirewallPolicyResponse.FirewallPolicyArn");
        try {
            call(OTHER, "ListFirewallPolicies", "{}").statusCode(200)
                    .body("FirewallPolicies.Name", not(hasItem(name)));
            notFound(call(OTHER, "DescribeFirewallPolicy", "{\"FirewallPolicyArn\":\"" + arn + "\"}"));
            notFound(call(OTHER, "DescribeFirewallPolicy", "{\"FirewallPolicyName\":\"" + name + "\"}"));
            notFound(call(OTHER, "DeleteFirewallPolicy", "{\"FirewallPolicyName\":\"" + name + "\"}"));

            call(OTHER, "CreateFirewallPolicy", body).statusCode(200);
            call(OTHER, "DeleteFirewallPolicy", "{\"FirewallPolicyName\":\"" + name + "\"}").statusCode(200);

            call(HOME, "DescribeFirewallPolicy", "{\"FirewallPolicyName\":\"" + name + "\"}").statusCode(200)
                    .body("FirewallPolicyResponse.FirewallPolicyArn", equalTo(arn));
        } finally {
            call(HOME, "DeleteFirewallPolicy", "{\"FirewallPolicyArn\":\"" + arn + "\"}");
        }
    }

    @Test
    void aFirewallAndItsLoggingAreReachedOnlyInItsRegion() {
        String name = "fw-" + UUID.randomUUID().toString().substring(0, 8);
        String policyArn = "arn:aws:network-firewall:" + HOME + ":" + ACCOUNT + ":firewall-policy/" + name;
        String body = "{\"FirewallName\":\"" + name + "\",\"FirewallPolicyArn\":\"" + policyArn + "\","
                + "\"VpcId\":\"vpc-0123456789abcdef0\",\"DeleteProtection\":false,"
                + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-0123456789abcdef0\"}]}";
        String arn = call(HOME, "CreateFirewall", body).statusCode(200).extract().path("Firewall.FirewallArn");
        try {
            call(OTHER, "ListFirewalls", "{}").statusCode(200).body("Firewalls.FirewallName", not(hasItem(name)));
            notFound(call(OTHER, "DescribeFirewall", "{\"FirewallArn\":\"" + arn + "\"}"));
            notFound(call(OTHER, "DescribeFirewall", "{\"FirewallName\":\"" + name + "\"}"));
            notFound(call(OTHER, "DescribeLoggingConfiguration", "{\"FirewallArn\":\"" + arn + "\"}"));
            notFound(call(OTHER, "AssociateSubnets", "{\"FirewallArn\":\"" + arn + "\","
                    + "\"SubnetMappings\":[{\"SubnetId\":\"subnet-0fedcba9876543210\"}]}"));
            notFound(call(OTHER, "DeleteFirewall", "{\"FirewallArn\":\"" + arn + "\"}"));

            String otherArn = call(OTHER, "CreateFirewall", body).statusCode(200)
                    .extract().path("Firewall.FirewallArn");
            call(OTHER, "DeleteFirewall", "{\"FirewallArn\":\"" + otherArn + "\"}").statusCode(200);

            call(HOME, "ListFirewalls", "{}").statusCode(200).body("Firewalls.FirewallArn", equalTo(List.of(arn)));
        } finally {
            call(HOME, "DeleteFirewall", "{\"FirewallArn\":\"" + arn + "\"}");
        }
    }

    private static ValidatableResponse call(String region, String action, String body) {
        return given()
                .contentType("application/x-amz-json-1.0")
                .header("X-Amz-Target", "NetworkFirewall_20201112." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + ACCOUNT + "/20260101/" + region
                        + "/network-firewall/aws4_request")
                .body(body)
                .when().post("/").then();
    }

    private static void notFound(ValidatableResponse response) {
        response.statusCode(400).body("__type", equalTo("ResourceNotFoundException"));
    }
}
