package com.floci.test;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DeadLetterConfig;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.RetryPolicy;
import software.amazon.awssdk.services.eventbridge.model.Target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("EventBridge target RoleArn")
class EventBridgeTargetRoleTest {

    private EventBridgeClient client;
    private String rule;
    private String role;
    private String stateMachineArn;
    private String crossAccountBusArn;
    private String deadLetterArn;

    @BeforeEach
    void createRule() {
        client = TestFixtures.eventBridgeClient();
        rule = TestFixtures.uniqueName("eb-role");
        String ruleArn = client.putRule(r -> r.name(rule).eventPattern("{}")).ruleArn();
        String[] parts = ruleArn.split(":", 6);
        role = "arn:" + parts[1] + ":iam::" + parts[4] + ":role/eventbridge-target";
        stateMachineArn = "arn:" + parts[1] + ":states:" + parts[3] + ":" + parts[4] + ":stateMachine:example";
        crossAccountBusArn = "arn:" + parts[1] + ":events:" + parts[3] + ":111111111111:event-bus/destination";
        deadLetterArn = "arn:" + parts[1] + ":sqs:" + parts[3] + ":" + parts[4] + ":target-role-dlq";
    }

    @AfterEach
    void cleanup() {
        try {
            client.removeTargets(r -> r.rule(rule).ids("target"));
            client.deleteRule(r -> r.name(rule));
        } finally {
            client.close();
        }
    }

    @Test
    void roundTripAndReplaceRoleWithRetryAndDeadLetterSettings() {
        RetryPolicy retry = RetryPolicy.builder().maximumRetryAttempts(2).maximumEventAgeInSeconds(60).build();
        DeadLetterConfig dlq = DeadLetterConfig.builder().arn(deadLetterArn).build();
        putTarget(Target.builder().id("target").arn(stateMachineArn).roleArn(role)
                .retryPolicy(retry).deadLetterConfig(dlq).build());

        Target stored = storedTarget();
        assertThat(stored.roleArn()).isEqualTo(role);
        assertThat(stored.retryPolicy()).isEqualTo(retry);
        assertThat(stored.deadLetterConfig()).isEqualTo(dlq);

        String replacement = role + "-updated";
        putTarget(stored.toBuilder().roleArn(replacement).build());
        assertThat(storedTarget().roleArn()).isEqualTo(replacement);
        assertThat(client.listTargetsByRule(r -> r.rule(rule)).targets()).hasSize(1);
    }

    @Test
    void omittedRoleDoesNotInheritTheRuleRole() {
        client.putRule(r -> r.name(rule).eventPattern("{}").roleArn(role));
        putTarget(Target.builder().id("target").arn(stateMachineArn).build());

        assertThat(storedTarget().roleArn()).isNull();
    }

    @Test
    void omittedRoleOnSameCrossAccountBusTargetIsRetained() {
        putTarget(Target.builder().id("target").arn(crossAccountBusArn).roleArn(role).build());
        putTarget(Target.builder().id("target").arn(crossAccountBusArn).build());

        assertThat(storedTarget().roleArn()).isEqualTo(role);

        putTarget(Target.builder().id("target").arn(crossAccountBusArn + "-changed").build());
        assertThat(storedTarget().roleArn()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1601})
    void invalidRoleLengthReturnsValidationException(int length) {
        assertThatThrownBy(() -> putTarget(Target.builder().id("target").arn(stateMachineArn)
                .roleArn("r".repeat(length)).build()))
                .isInstanceOfSatisfying(EventBridgeException.class, error -> {
                    assertThat(error.statusCode()).isEqualTo(400);
                    assertThat(error.awsErrorDetails().errorCode()).isEqualTo("ValidationException");
                    assertThat(error.getMessage()).contains("targets.1.member.roleArn");
                });
    }

    private void putTarget(Target target) {
        assertThat(client.putTargets(r -> r.rule(rule).targets(target)).failedEntryCount()).isZero();
    }

    private Target storedTarget() {
        return client.listTargetsByRule(r -> r.rule(rule)).targets().get(0);
    }
}
