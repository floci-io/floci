package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.Matcher;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;

import static io.floci.aws.testing.CfnStackWaits.output;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class CloudFormationTargetGroupHealthIntegrationTest {

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("cfn-tg-health");

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedUpdateRestoresHealthAndRetainsTheGroup(boolean laterResourceFails) {
        String arn = output(stack.create(template(false)), "GroupArn");
        ElasticLoadBalancingV2Client elb = stack.client(ElasticLoadBalancingV2Client.builder());
        TargetGroup before = elb.describeTargetGroups(request -> request.targetGroupArns(arn))
                .targetGroups().getFirst();
        stack.update(template(true, laterResourceFails, !laterResourceFails), "UPDATE_ROLLBACK_COMPLETE");

        assertEquals(before, elb.describeTargetGroups(request -> request.targetGroupArns(arn))
                .targetGroups().getFirst());
        assertEquals(arn, physicalId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void updatesHealthSettingsInPlace(boolean directApiControl) {
        String arn = output(stack.create(template(false)), "GroupArn");
        ElasticLoadBalancingV2Client elb = stack.client(ElasticLoadBalancingV2Client.builder());
        TargetGroup before = elb.describeTargetGroups(request -> request.targetGroupArns(arn))
                .targetGroups().getFirst();
        assertEquals("TCP", before.healthCheckProtocolAsString());
        assertEquals("7777", before.healthCheckPort());

        if (directApiControl) {
            elb.modifyTargetGroup(request -> request.targetGroupArn(arn)
                    .healthCheckProtocol("HTTP").healthCheckPort("8888")
                    .healthCheckPath("/ready").healthCheckIntervalSeconds(15)
                    .healthCheckTimeoutSeconds(4).healthyThresholdCount(4).unhealthyThresholdCount(5)
                    .matcher(Matcher.builder().httpCode("200-299").build()));
        } else {
            stack.update(template(true), "UPDATE_COMPLETE");
        }

        TargetGroup after = elb.describeTargetGroups(request -> request.targetGroupArns(arn))
                .targetGroups().getFirst();
        assertAll(
                () -> assertEquals(arn, physicalId()),
                () -> assertEquals("HTTP", after.healthCheckProtocolAsString()),
                () -> assertEquals("8888", after.healthCheckPort()),
                () -> assertEquals("/ready", after.healthCheckPath()),
                () -> assertEquals(15, after.healthCheckIntervalSeconds()),
                () -> assertEquals(4, after.healthCheckTimeoutSeconds()),
                () -> assertEquals(4, after.healthyThresholdCount()),
                () -> assertEquals(5, after.unhealthyThresholdCount()),
                () -> assertEquals("200-299", after.matcher().httpCode()));
    }

    private String physicalId() {
        return stack.client(CloudFormationClient.builder()).describeStackResource(request ->
                request.stackName(stack.name()).logicalResourceId("Group")).stackResourceDetail().physicalResourceId();
    }

    private static String template(boolean updated) {
        return template(updated, false, false);
    }

    private static String template(boolean updated, boolean laterFails, boolean invalidTarget) {
        String health = updated ? """
                "HealthCheckProtocol":"HTTP","HealthCheckPort":"8888","HealthCheckPath":"/ready",
                "HealthCheckIntervalSeconds":15,"HealthCheckTimeoutSeconds":4,
                "HealthyThresholdCount":4,"UnhealthyThresholdCount":5,"Matcher":{"HttpCode":"200-299"}
                """ : """
                "HealthCheckProtocol":"TCP","HealthCheckPort":"7777"
                """;
        return """
                {"Resources":{
                  "Vpc":{"Type":"AWS::EC2::VPC","Properties":{"CidrBlock":"10.99.0.0/16"}},
                  "Group":{"Type":"AWS::ElasticLoadBalancingV2::TargetGroup","Properties":{
                    "Protocol":"TCP","Port":25,"TargetType":"ip","VpcId":{"Ref":"Vpc"},%s%s}}%s
                }, "Outputs":{"GroupArn":{"Value":{"Ref":"Group"}}}}
                """.formatted(health,
                invalidTarget ? ",\"Targets\":[{\"Id\":\"10.99.0.10\",\"Port\":70000}]" : "",
                laterFails ? CfnStackWaits.failingResourceAfter("Group") : "");
    }
}
