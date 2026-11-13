package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetDescription;

import java.util.Set;
import java.util.stream.Collectors;

import static io.floci.aws.testing.CfnStackWaits.output;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Targets} on {@code AWS::ElasticLoadBalancingV2::TargetGroup}, read back through the AWS SDK's
 * DescribeTargetHealth: the stack registers the declared targets, an update registers the ones the
 * template gains and deregisters the ones it drops on the same group, a failed update puts them back,
 * whether it failed on the group or on a later resource, and a target registered outside the stack
 * stays registered.
 */
@QuarkusTest
class CloudFormationElbV2TargetGroupTargetsIntegrationTest {

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("cfn-tg-targets");

    @Test
    void ipTargetsFollowTheTemplateThroughChangeRollbackRefusalAndRemoval() {
        String arn = createGroup("ip", target("10.0.0.10"));
        assertEquals(Set.of("10.0.0.10:80"), registrations(arn));
        elb().registerTargets(request -> request.targetGroupArn(arn).targets(target -> target.id("10.0.0.99")));

        Stack changed = stack.update(template("ip", false, target("10.0.0.11"), target("10.0.0.12")),
                "UPDATE_COMPLETE");
        assertEquals(arn, output(changed, "TgArn"));
        Set<String> changedTargets = Set.of("10.0.0.11:80", "10.0.0.12:80", "10.0.0.99:80");
        assertEquals(changedTargets, registrations(arn));

        stack.update(template("ip", true, target("10.0.0.10")), "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(changedTargets, registrations(arn));

        // RegisterTargets refuses a port out of range, so this update fails on the group itself.
        stack.update(template("ip", false, target("10.0.0.11"), target("10.0.0.13", 70000)),
                "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(changedTargets, registrations(arn));

        stack.update(template("ip", false), "UPDATE_COMPLETE");
        assertEquals(Set.of("10.0.0.99:80"), registrations(arn));
    }

    /** An instance registered on two ports is two targets, and an update drops only the one it leaves out. */
    @Test
    void anInstanceTargetIsItsIdAndPort() {
        String first = "i-0aaaaaaaaaaaaaaa1";
        String second = "i-0bbbbbbbbbbbbbbb2";
        String arn = createGroup("instance", target(first), target(first, 8080), target(second));
        assertEquals(Set.of(first + ":80", first + ":8080", second + ":80"), registrations(arn));

        stack.update(template("instance", false, target(first, 8080)), "UPDATE_COMPLETE");
        assertEquals(Set.of(first + ":8080"), registrations(arn));
    }

    /** A Lambda target names no port, so the function is the whole target. */
    @Test
    void aLambdaTargetGroupSwitchesToTheDeclaredFunction() {
        String arn = createGroup("lambda", target(functionArn("first")));
        assertEquals(Set.of(functionArn("first")), registrations(arn));

        stack.update(template("lambda", false, target(functionArn("second"))), "UPDATE_COMPLETE");
        assertEquals(Set.of(functionArn("second")), registrations(arn));
    }

    private ElasticLoadBalancingV2Client elb() {
        return stack.client(ElasticLoadBalancingV2Client.builder());
    }

    /** Creates the stack with a target group of {@code type} declaring {@code targets}, and returns its ARN. */
    private String createGroup(String type, String... targets) {
        return output(stack.create(template(type, false, targets)), "TgArn");
    }

    /** The group's registrations, each its Id and, when it has one, its port. */
    private Set<String> registrations(String arn) {
        return elb().describeTargetHealth(request -> request.targetGroupArn(arn)).targetHealthDescriptions().stream()
                .map(description -> {
                    TargetDescription target = description.target();
                    return target.port() == null ? target.id() : target.id() + ":" + target.port();
                })
                .collect(Collectors.toSet());
    }

    /**
     * A target group of {@code type}, on port 80 unless it is a Lambda group, declaring {@code targets},
     * or no {@code Targets} when there are none; with a resource that fails after it when
     * {@code laterFails}.
     */
    private static String template(String type, boolean laterFails, String... targets) {
        String port = "lambda".equals(type) ? "" : ", \"Protocol\": \"HTTP\", \"Port\": 80";
        String declared = targets.length == 0 ? "" : ", \"Targets\": [" + String.join(", ", targets) + "]";
        return """
                {"Resources": {
                  "Tg": {"Type": "AWS::ElasticLoadBalancingV2::TargetGroup",
                    "Properties": {"TargetType": "%s"%s%s}}%s
                }, "Outputs": {"TgArn": {"Value": {"Ref": "Tg"}}}}
                """.formatted(type, port, declared, laterFails ? CfnStackWaits.failingResourceAfter("Tg") : "");
    }

    /** A declared target that names only its Id, so it takes the group's port. */
    private static String target(String id) {
        return "{\"Id\": \"" + id + "\"}";
    }

    /** A declared target with a port of its own. */
    private static String target(String id, int port) {
        return "{\"Id\": \"" + id + "\", \"Port\": " + port + "}";
    }

    private static String functionArn(String name) {
        return "arn:aws:lambda:us-east-1:000000000000:function:" + name;
    }
}
