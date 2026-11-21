package io.floci.aws.services.eventbridge;

import io.floci.aws.testing.AwsTestClients;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.DescribeRuleResponse;
import software.amazon.awssdk.services.eventbridge.model.EventBridgeException;
import software.amazon.awssdk.services.eventbridge.model.ResourceNotFoundException;
import software.amazon.awssdk.services.eventbridge.model.RuleState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class EventBridgeScheduleValidationIntegrationTest {

    private static final String ACCOUNT = "799600000001";
    private static final String PATTERN = "{\"source\":[\"schedule.validation\"]}";

    @ParameterizedTest
    @ValueSource(strings = {"rate(1 minutes)", "rate(2 minute)", "rate(1 hours)",
            "rate(2 hour)", "rate(1 days)", "rate(2 day)", "rate(0 minutes)",
            "rate(-1 minutes)", "rate(1.5 hours)", "rate(1 week)", "rate(2 weeks)",
            "rate(1 second)", "rate(5)", "rate(999999999999999999999 minutes)",
            "cron(invalid)", "at(2026-12-01T00:00:00)", "invalid"})
    void invalidSchedulesAreRejectedBeforeCreatingEvenADisabledRule(String expression) {
        try (EventBridgeClient client = client()) {
            EventBridgeException error = assertThrows(EventBridgeException.class, () -> client.putRule(request -> request
                    .name("invalid-schedule").scheduleExpression(expression).state(RuleState.DISABLED)));

            assertValidationError(error);
            assertThrows(ResourceNotFoundException.class,
                    () -> client.describeRule(request -> request.name("invalid-schedule")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rate(1 minute)", "rate(2 minutes)", "rate(1 hour)",
            "rate(2 hours)", "rate(1 day)", "rate(2 days)", "RATE(2 HOURS)",
            "rate( 5 minutes )", "cron(0 12 * * ? *)", "cron(0 12 * * ? 2020)"})
    void validSchedulesRoundTripUnchanged(String expression) {
        try (EventBridgeClient client = client()) {
            String name = "valid-schedule";
            client.putRule(request -> request.name(name).scheduleExpression(expression));
            try {
                DescribeRuleResponse rule = client.describeRule(request -> request.name(name));
                assertEquals(expression, rule.scheduleExpression());
                assertEquals(RuleState.ENABLED, rule.state());
            } finally {
                client.deleteRule(request -> request.name(name));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"rate(1 minutes)", "rate(2 minute)", "rate(1 hours)",
            "rate(2 hour)", "rate(1 days)", "rate(2 day)"})
    void invalidUpdatePreservesTheExistingRule(String expression) {
        try (EventBridgeClient client = client()) {
            String name = "preserved-schedule";
            client.putRule(request -> request.name(name).scheduleExpression("rate(5 minutes)")
                    .eventPattern(PATTERN).description("original"));
            try {
                EventBridgeException error = assertThrows(EventBridgeException.class, () -> client.putRule(request -> request
                        .name(name).scheduleExpression(expression).description("changed").state(RuleState.DISABLED)));

                assertValidationError(error);
                DescribeRuleResponse rule = client.describeRule(request -> request.name(name));
                assertEquals("rate(5 minutes)", rule.scheduleExpression());
                assertEquals(PATTERN, rule.eventPattern());
                assertEquals("original", rule.description());
                assertEquals(RuleState.ENABLED, rule.state());
            } finally {
                client.deleteRule(request -> request.name(name));
            }
        }
    }

    @Test
    void anEventPatternDoesNotBypassScheduleValidation() {
        try (EventBridgeClient client = client()) {
            EventBridgeException error = assertThrows(EventBridgeException.class, () -> client.putRule(request -> request
                    .name("invalid-combined-rule").eventPattern(PATTERN).scheduleExpression("rate(1 minutes)")));

            assertValidationError(error);
            assertThrows(ResourceNotFoundException.class,
                    () -> client.describeRule(request -> request.name("invalid-combined-rule")));
        }
    }

    private static EventBridgeClient client() {
        return AwsTestClients.create(EventBridgeClient.builder(), ACCOUNT, Region.US_EAST_1);
    }

    private static void assertValidationError(EventBridgeException error) {
        assertEquals(400, error.statusCode());
        assertEquals("ValidationException", error.awsErrorDetails().errorCode());
        assertEquals("Parameter ScheduleExpression is not valid.", error.awsErrorDetails().errorMessage());
    }
}
