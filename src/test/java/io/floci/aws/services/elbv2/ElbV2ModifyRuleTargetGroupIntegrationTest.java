package io.floci.aws.services.elbv2;

import io.floci.aws.testing.AlbFixtures;
import io.floci.aws.testing.QueryRequests;
import io.floci.aws.testutil.FreePorts;
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
    void aRuleMovedToAnotherTargetGroupLeavesTheFirstUnused() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String lb = null;
        String fallback = null;
        String first = null;
        String second = null;
        String listener = null;
        try {
            lb = AlbFixtures.createLoadBalancer(ACCOUNT, "modify-rule-" + suffix);
            fallback = AlbFixtures.createTargetGroup(ACCOUNT, "default-" + suffix, "ip", 80);
            first = AlbFixtures.createTargetGroup(ACCOUNT, "first-" + suffix, "ip", 80);
            second = AlbFixtures.createTargetGroup(ACCOUNT, "second-" + suffix, "ip", 80);
            listener = AlbFixtures.createListener(ACCOUNT, lb, fallback, FreePorts.anyInterfacePort());
            String rule = send("CreateRule", "ListenerArn", listener, "Priority", "10",
                    "Conditions.member.1.Field", "path-pattern", "Conditions.member.1.Values.member.1", "/app/*",
                    "Actions.member.1.Type", "forward", "Actions.member.1.TargetGroupArn", first)
                    .then().statusCode(200).extract()
                    .path("CreateRuleResponse.CreateRuleResult.Rules.member.RuleArn");

            send("ModifyRule", "RuleArn", rule,
                    "Actions.member.1.Type", "forward", "Actions.member.1.TargetGroupArn", second)
                    .then().statusCode(200);

            assertEquals(List.of(lb), loadBalancersOf(fallback));
            assertEquals(List.of(), loadBalancersOf(first));
            assertEquals(List.of(lb), loadBalancersOf(second));
            send("DeleteTargetGroup", "TargetGroupArn", second).then().statusCode(400);
            send("DeleteTargetGroup", "TargetGroupArn", first).then().statusCode(200);
            first = null;
        } finally {
            // Deleting the listener deletes its rule too.
            AlbFixtures.deleteListener(ACCOUNT, listener);
            AlbFixtures.deleteLoadBalancer(ACCOUNT, lb);
            for (String group : new String[] {fallback, first, second}) {
                AlbFixtures.deleteTargetGroup(ACCOUNT, group);
            }
        }
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
