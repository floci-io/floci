package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackResource;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The physical id and {@code Ref} of an {@code AWS::Events::Rule} through the AWS SDK. */
@QuarkusTest
class CloudFormationEventsRulePhysicalIdIntegrationTest {

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("cfn-rule-id");

    @Test
    void aRuleOnACustomBusIsReportedAsTheBusAndTheRuleNameAndTheStackDeleteRemovesIt() {
        String bus = stack.name() + "-bus";
        Stack created = stack.create("""
                {"Resources": {
                   "Bus": {"Type": "AWS::Events::EventBus", "Properties": {"Name": "%s"}},
                   "Queue": {"Type": "AWS::SQS::Queue"},
                   "Rule": {"Type": "AWS::Events::Rule",
                            "Properties": {"EventBusName": {"Ref": "Bus"}, "EventPattern": {"source": ["my.source"]},
                                           "Targets": [{"Id": "q", "Arn": {"Fn::GetAtt": ["Queue", "Arn"]}}]}},
                   "DefaultBusRule": {"Type": "AWS::Events::Rule",
                                      "Properties": {"EventPattern": {"source": ["my.source"]}}}},
                 "Outputs": {"RuleRef": {"Value": {"Ref": "Rule"}},
                             "RuleName": {"Value": {"Fn::GetAtt": ["Rule", "RuleName"]}},
                             "NameFromRef": {"Value": {"Fn::Select": [1, {"Fn::Split": ["|", {"Ref": "Rule"}]}]}},
                             "BusFromRef": {"Value": {"Fn::Select": [0, {"Fn::Split": ["|", {"Ref": "Rule"}]}]}}}}"""
                .formatted(bus));

        String ruleName = CfnStackWaits.output(created, "RuleName");
        assertEquals(bus + "|" + ruleName, CfnStackWaits.output(created, "RuleRef"));
        assertEquals(bus + "|" + ruleName, physicalId("Rule"));
        assertEquals(ruleName, CfnStackWaits.output(created, "NameFromRef"));
        assertEquals(bus, CfnStackWaits.output(created, "BusFromRef"));
        assertFalse(physicalId("DefaultBusRule").contains("|"));
        EventBridgeClient events = stack.client(EventBridgeClient.builder());
        assertEquals(ruleName, events.describeRule(r -> r.name(ruleName).eventBusName(bus)).name());

        CfnStackWaits.deleteStack(stack.client(CloudFormationClient.builder()), stack.name());

        assertThrows(ResourceNotFoundException.class,
                () -> events.describeRule(r -> r.name(ruleName).eventBusName(bus)));
        assertThrows(ResourceNotFoundException.class, () -> events.describeEventBus(r -> r.name(bus)));
    }

    private String physicalId(String logicalId) {
        return stack.client(CloudFormationClient.builder()).describeStackResources(r -> r.stackName(stack.name()))
                .stackResources().stream()
                .filter(resource -> logicalId.equals(resource.logicalResourceId()))
                .map(StackResource::physicalResourceId)
                .findFirst().orElseThrow();
    }
}
