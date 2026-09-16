package io.github.hectorvent.floci.core.common;

import com.cronutils.model.definition.CronDefinition;
import com.cronutils.model.definition.CronDefinitionBuilder;
import com.cronutils.parser.CronParser;

/**
 * Builds the cron-utils {@link CronParser} for AWS's six-field EventBridge cron dialect
 * (minute hour day-of-month month day-of-week year), shared by EventBridge's own schedule
 * expressions ({@code ScheduleExpressionParser}) and EventBridge Scheduler's {@code cron()}
 * expressions ({@code SchedulerExpressionParser}), which independently built the identical
 * definition before this class existed.
 *
 * <p>AWS numbers day-of-week 1-7 as SUN-SAT, not the Unix 0-6 SUN-SAT that cron-utils defaults
 * to, so Monday is 2. Without this every numeric day fires one day late and 7 (Saturday) does
 * not parse at all.
 */
public final class AwsCronDefinitions {

    private AwsCronDefinitions() {
    }

    public static CronParser newParser() {
        CronDefinition definition = CronDefinitionBuilder.defineCron()
                .withSeconds().and()
                .withMinutes().and()
                .withHours().and()
                .withDayOfMonth().supportsHash().supportsL().supportsW().supportsQuestionMark().and()
                .withMonth().and()
                .withDayOfWeek().withValidRange(1, 7).withMondayDoWValue(2)
                .supportsHash().supportsL().supportsW().supportsQuestionMark().and()
                .withYear().optional().and()
                .instance();
        return new CronParser(definition);
    }
}
