package io.floci.aws.core.common;

import io.floci.aws.services.eventbridge.ScheduleExpressionParser;
import io.floci.aws.services.scheduler.SchedulerExpressionParser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AwsRateExpressionValidationTest {

    @ParameterizedTest
    @ValueSource(strings = {"rate(1 minute)", "rate(2 minutes)", "rate(1 hour)",
            "rate(2 hours)", "rate(1 day)", "rate(2 days)", "RATE(1 HOUR)", "rate( 5 minutes )"})
    void bothServicesAcceptAwsRates(String expression) {
        assertDoesNotThrow(() -> ScheduleExpressionParser.validate(expression));
        assertDoesNotThrow(() -> SchedulerExpressionParser.validate(expression, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rate(1 minutes)", "rate(2 minute)", "rate(1 hours)",
            "rate(2 hour)", "rate(1 days)", "rate(2 day)", "rate(0 minutes)", "rate(0 minute)",
            "rate(1 week)", "rate(2 weeks)", "RATE(1 HOURS)", "RATE(2 WEEK)"})
    void bothServicesRejectTheSameInvalidRateWithTheSameReason(String expression) {
        IllegalArgumentException events = assertThrows(IllegalArgumentException.class,
                () -> ScheduleExpressionParser.validate(expression));
        IllegalArgumentException scheduler = assertThrows(IllegalArgumentException.class,
                () -> SchedulerExpressionParser.validate(expression, null));

        assertEquals(events.getMessage(), scheduler.getMessage());
    }
}
