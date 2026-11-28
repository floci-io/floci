package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.eventbridge.EventBridgeService;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.CfnTestStack;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

@QuarkusTest
class CloudFormationRuleRollbackIdentityIntegrationTest {

    @InjectSpy
    EventBridgeService eventBridgeService;

    @RegisterExtension
    final CfnTestStack stack = new CfnTestStack("cfn-rule-rollback");

    @Test
    void movingToDefaultBusRestoresTheOriginalIdentityAfterLaterFailure() {
        assertRestoredIdentity(false, false);
    }

    @Test
    void renamingOnCustomBusRestoresTheOriginalIdentityAfterLaterFailure() {
        assertRestoredIdentity(true, true);
    }

    private void assertRestoredIdentity(boolean customBus, boolean rename) {
        String original = stack.name() + "-original";
        String attempted = rename ? stack.name() + "-renamed" : original;
        EventBridgeClient events = stack.client(EventBridgeClient.builder());
        try {
            Stack created = stack.create(template(original, true, false, 30));
            String originalId = CfnStackWaits.output(created, "RuleRef");
            String originalArn = CfnStackWaits.output(created, "RuleArn");
            stack.update(template(attempted, customBus, true, 30), "UPDATE_ROLLBACK_COMPLETE");
            String physicalId = stack.client(CloudFormationClient.builder())
                    .describeStackResource(r -> r.stackName(stack.name()).logicalResourceId("Rule"))
                    .stackResourceDetail().physicalResourceId();
            Stack later = stack.update(template(original, true, false, 31), "UPDATE_COMPLETE");
            assertAll(
                    () -> assertEquals(originalId, physicalId, "rolled-back physical id"),
                    () -> assertEquals(originalId, CfnStackWaits.output(later, "RuleRef"), "subsequent Ref"),
                    () -> assertEquals(originalArn, CfnStackWaits.output(later, "RuleArn"), "subsequent Arn"),
                    () -> assertEquals(originalArn,
                            events.describeRule(r -> r.name(original).eventBusName(bus())).arn()));
        } finally {
            deleteRule(events, attempted, customBus ? bus() : "default");
            deleteRule(events, original, bus());
        }
    }

    @Test
    void committedMoveToDefaultBusIsDeletedFromTheDefaultBus() {
        String name = stack.name() + "-rule";
        EventBridgeClient events = stack.client(EventBridgeClient.builder());
        events.createEventBus(r -> r.name(bus()));
        try {
            stack.create(singleRuleTemplate(name, true));
            Stack moved = stack.update(singleRuleTemplate(name, false), "UPDATE_COMPLETE");
            assertEquals(name, CfnStackWaits.output(moved, "RuleRef"));
            assertEquals(name, events.describeRule(r -> r.name(name)).name());
            CfnStackWaits.deleteStack(stack.client(CloudFormationClient.builder()), stack.name());
            assertThrows(ResourceNotFoundException.class, () -> events.describeRule(r -> r.name(name)));
        } finally {
            deleteRule(events, name, "default");
            deleteRule(events, name, bus());
            events.deleteEventBus(r -> r.name(bus()));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void committedReplacementRemovesTheDisplacedRule(boolean rename) {
        String original = stack.name() + "-original";
        String replacement = rename ? stack.name() + "-replacement" : original;
        EventBridgeClient events = stack.client(EventBridgeClient.builder());
        try {
            stack.create(template(original, true, false, 30));
            stack.update(template(replacement, rename, false, 30), "UPDATE_COMPLETE");
            assertThrows(ResourceNotFoundException.class,
                    () -> events.describeRule(r -> r.name(original).eventBusName(bus())),
                    "the committed update must remove the displaced rule");
            assertEquals(replacement, events.describeRule(r -> r.name(replacement)
                    .eventBusName(rename ? bus() : "default")).name());
            CfnStackWaits.deleteStack(stack.client(CloudFormationClient.builder()), stack.name());
        } finally {
            deleteRule(events, replacement, rename ? bus() : "default");
            deleteRule(events, original, bus());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedReplacementRemovesOnlyTheNewRule(boolean retain) {
        String original = stack.name() + "-original";
        String replacement = stack.name() + "-replacement";
        EventBridgeClient events = stack.client(EventBridgeClient.builder());
        try {
            String before = template(original, true, false, 30);
            String after = template(replacement, true, true, 30);
            if (retain) {
                before = retainRule(before);
                after = retainRule(after);
            }
            stack.create(before);
            stack.update(after, "UPDATE_ROLLBACK_COMPLETE");
            assertThrows(ResourceNotFoundException.class,
                    () -> events.describeRule(r -> r.name(replacement).eventBusName(bus())),
                    "UpdateReplacePolicy does not retain a failed replacement");
            assertEquals(original, events.describeRule(r -> r.name(original).eventBusName(bus())).name());
        } finally {
            deleteRule(events, replacement, bus());
            deleteRule(events, original, bus());
        }
    }

    @Test
    void retainPolicyKeepsTheDisplacedRuleAfterCommit() {
        String original = stack.name() + "-original";
        String replacement = stack.name() + "-replacement";
        EventBridgeClient events = stack.client(EventBridgeClient.builder());
        try {
            stack.create(retainRule(template(original, true, false, 30)));
            stack.update(retainRule(template(replacement, true, false, 30)), "UPDATE_COMPLETE");
            assertEquals(original, events.describeRule(r -> r.name(original).eventBusName(bus())).name());
            assertEquals(replacement, events.describeRule(r -> r.name(replacement).eventBusName(bus())).name());
        } finally {
            deleteRule(events, replacement, bus());
            deleteRule(events, original, bus());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void foreignBusArnAliasPreservesTheLiveRuleAndTargets(boolean rollback) {
        String name = stack.name() + "-rule";
        String alias = "arn:aws:events:us-west-2:111122223333:event-bus/" + bus();
        EventBridgeClient events = stack.client(EventBridgeClient.builder());
        try {
            String before = template(name, true, false, 30);
            before = before.replace("\"EventBusName\":{\"Ref\":\"Bus\"}",
                    "\"EventBusName\":{\"Ref\":\"Bus\"},\"Targets\":[{\"Id\":\"queue\","
                            + "\"Arn\":{\"Fn::GetAtt\":[\"Queue\",\"Arn\"]}}]");
            Stack created = stack.create(before);
            String originalArn = CfnStackWaits.output(created, "RuleArn");
            String after = before.replace("\"EventBusName\":{\"Ref\":\"Bus\"}",
                    "\"EventBusName\":\"" + alias + "\"");
            if (rollback) {
                after = after.replace("},\"Outputs\"", CfnStackWaits.failingResourceAfter("Rule")
                        + "},\"Outputs\"");
            }
            Stack updated = stack.update(after, rollback ? "UPDATE_ROLLBACK_COMPLETE" : "UPDATE_COMPLETE");
            assertEquals((rollback ? bus() : alias) + "|" + name, CfnStackWaits.output(updated, "RuleRef"));
            assertEquals(originalArn, assertDoesNotThrow(
                    () -> events.describeRule(r -> r.name(name).eventBusName(bus()))).arn());
            assertEquals("queue", events.listTargetsByRule(r -> r.rule(name).eventBusName(bus()))
                    .targets().getFirst().id());
        } finally {
            try {
                events.removeTargets(r -> r.rule(name).eventBusName(bus()).ids("queue"));
            } catch (ResourceNotFoundException ignored) {
                // A failed lifecycle assertion may find the rule already removed.
            }
            deleteRule(events, name, bus());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void skippedDeleteDebtSurvivesALaterPreMutationFailure(boolean adoptDebtThroughAlias) {
        String original = stack.name() + "-original";
        String replacement = stack.name() + "-replacement";
        EventBridgeClient events = stack.client(EventBridgeClient.builder());
        CloudFormationClient cfn = stack.client(CloudFormationClient.builder());
        try {
            String before = template(original, true, false, 30);
            stack.create(before);
            doThrow(new AwsException("InternalException", "owned replacement delete failure", 500))
                    .when(eventBridgeService).deleteRule(replacement, bus(), "us-east-1");
            stack.update(template(replacement, true, true, 30), "UPDATE_ROLLBACK_FAILED");
            cfn.continueUpdateRollback(r -> r.stackName(stack.name()).resourcesToSkip("Rule"));
            CfnStackWaits.awaitStatus(cfn, stack.name(), "UPDATE_ROLLBACK_COMPLETE");
            doCallRealMethod().when(eventBridgeService).deleteRule(replacement, bus(), "us-east-1");

            String invalid = before.replace("\"EventBusName\":{\"Ref\":\"Bus\"}",
                    "\"EventBusName\":{\"Ref\":\"Bus\"},\"Targets\":\"invalid\"");
            stack.update(invalid, "UPDATE_ROLLBACK_COMPLETE");
            assertEquals(original, events.describeRule(r -> r.name(original).eventBusName(bus())).name());
            assertEquals(replacement, events.describeRule(r -> r.name(replacement).eventBusName(bus())).name());
            if (adoptDebtThroughAlias) {
                String alias = "arn:aws:events:us-west-2:111122223333:event-bus/" + bus();
                String next = template(replacement, true, false, 31)
                        .replace("\"EventBusName\":{\"Ref\":\"Bus\"}", "\"EventBusName\":\"" + alias + "\"");
                stack.update(next, "UPDATE_COMPLETE");
                assertEquals(replacement, assertDoesNotThrow(
                        () -> events.describeRule(r -> r.name(replacement).eventBusName(bus()))).name());
                assertThrows(ResourceNotFoundException.class,
                        () -> events.describeRule(r -> r.name(original).eventBusName(bus())));
            } else {
                stack.update(template(original, true, false, 31), "UPDATE_COMPLETE");
                assertThrows(ResourceNotFoundException.class,
                        () -> events.describeRule(r -> r.name(replacement).eventBusName(bus())));
                assertEquals(original, events.describeRule(r -> r.name(original).eventBusName(bus())).name());
            }
        } finally {
            doCallRealMethod().when(eventBridgeService).deleteRule(replacement, bus(), "us-east-1");
            deleteRule(events, replacement, bus());
            deleteRule(events, original, bus());
        }
    }

    private String retainRule(String template) {
        return template.replace("\"DependsOn\":\"Bus\"", "\"DependsOn\":\"Bus\",\"UpdateReplacePolicy\":\"Retain\"");
    }

    private String singleRuleTemplate(String name, boolean customBus) {
        return """
                {"Resources":{"Rule":{"Type":"AWS::Events::Rule","Properties":{
                  "Name":"%s","EventPattern":{"source":["rollback.test"]}%s}}},
                 "Outputs":{"RuleRef":{"Value":{"Ref":"Rule"}}}}
                """.formatted(name, customBus ? ",\"EventBusName\":\"" + bus() + "\"" : "");
    }

    private void deleteRule(EventBridgeClient events, String name, String eventBus) {
        try {
            events.deleteRule(r -> r.name(name).eventBusName(eventBus));
        } catch (ResourceNotFoundException ignored) {
            // A successful stack delete or a failed create already removed this test-owned rule.
        }
    }

    private String bus() {
        return stack.name() + "-bus";
    }

    private String template(String ruleName, boolean customBus, boolean fail, int visibilityTimeout) {
        return """
                {"Resources":{
                  "Bus":{"Type":"AWS::Events::EventBus","Properties":{"Name":"%s"}},
                  "Queue":{"Type":"AWS::SQS::Queue","Properties":{"VisibilityTimeout":%d}},
                  "Rule":{"Type":"AWS::Events::Rule","DependsOn":"Bus",
                    "Properties":{"Name":"%s","EventPattern":{"source":["rollback.test"]}%s}}%s
                },"Outputs":{"RuleRef":{"Value":{"Ref":"Rule"}},
                             "RuleArn":{"Value":{"Fn::GetAtt":["Rule","Arn"]}}}}
                """.formatted(bus(), visibilityTimeout, ruleName,
                customBus ? ",\"EventBusName\":{\"Ref\":\"Bus\"}" : "",
                fail ? CfnStackWaits.failingResourceAfter("Rule") : "");
    }
}
