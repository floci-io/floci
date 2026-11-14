package io.floci.aws.services.elbv2;

import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.testing.QueryRequests;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A rule moved to another target group with ModifyRule leaves the first one unused: DescribeTargetGroups lists the
 * load balancer for the group the rule forwards to and not for the other, and only the unused one can be deleted.
 */
@QuarkusTest
class ElbV2ModifyRuleTargetGroupIntegrationTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";

    @Test
    void aRuleMovedToAnotherTargetGroupLeavesTheFirstUnused() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String lb = null;
        String first = null;
        String second = null;
        String listener = null;
        String rule = null;
        try {
            lb = send("CreateLoadBalancer", "Name", "modify-rule-" + suffix, "Type", "application",
                    "Subnets.member.1", Ec2Service.defaultSubnetId(REGION, "a"),
                    "Subnets.member.2", Ec2Service.defaultSubnetId(REGION, "b"))
                    .then().statusCode(200).extract()
                    .path("CreateLoadBalancerResponse.CreateLoadBalancerResult.LoadBalancers.member.LoadBalancerArn");
            first = targetGroup("first-" + suffix);
            second = targetGroup("second-" + suffix);
            listener = send("CreateListener", "LoadBalancerArn", lb, "Protocol", "HTTP", "Port", "80",
                    "DefaultActions.member.1.Type", "fixed-response",
                    "DefaultActions.member.1.FixedResponseConfig.StatusCode", "404")
                    .then().statusCode(200).extract()
                    .path("CreateListenerResponse.CreateListenerResult.Listeners.member.ListenerArn");
            rule = send("CreateRule", "ListenerArn", listener, "Priority", "10",
                    "Conditions.member.1.Field", "path-pattern", "Conditions.member.1.Values.member.1", "/app/*",
                    "Actions.member.1.Type", "forward", "Actions.member.1.TargetGroupArn", first)
                    .then().statusCode(200).extract()
                    .path("CreateRuleResponse.CreateRuleResult.Rules.member.RuleArn");

            send("ModifyRule", "RuleArn", rule,
                    "Actions.member.1.Type", "forward", "Actions.member.1.TargetGroupArn", second)
                    .then().statusCode(200);

            assertEquals(List.of(), loadBalancersOf(first));
            assertEquals(List.of(lb), loadBalancersOf(second));
            send("DeleteTargetGroup", "TargetGroupArn", second).then().statusCode(400);
            send("DeleteTargetGroup", "TargetGroupArn", first).then().statusCode(200);
            first = null;
        } finally {
            if (rule != null) {
                send("DeleteRule", "RuleArn", rule);
            }
            if (listener != null) {
                send("DeleteListener", "ListenerArn", listener);
            }
            if (lb != null) {
                send("DeleteLoadBalancer", "LoadBalancerArn", lb);
            }
            for (String group : new String[] {first, second}) {
                if (group != null) {
                    send("DeleteTargetGroup", "TargetGroupArn", group);
                }
            }
        }
    }

    private static String targetGroup(String name) {
        return send("CreateTargetGroup", "Name", name, "Protocol", "HTTP", "Port", "80",
                "VpcId", Ec2Service.defaultVpcId(REGION), "TargetType", "ip")
                .then().statusCode(200).extract()
                .path("CreateTargetGroupResponse.CreateTargetGroupResult.TargetGroups.member.TargetGroupArn");
    }

    private static List<String> loadBalancersOf(String targetGroup) {
        return send("DescribeTargetGroups", "TargetGroupArns.member.1", targetGroup)
                .then().statusCode(200).extract().xmlPath()
                .getList("DescribeTargetGroupsResponse.DescribeTargetGroupsResult.TargetGroups.member"
                        + ".LoadBalancerArns.member", String.class);
    }

    private static Response send(String action, String... params) {
        return QueryRequests.request(ACCOUNT, REGION, "elasticloadbalancing", action, params).when().post("/");
    }
}
