package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** One DescribeAlarmHistory entry; {@code sequence} orders entries and {@code timestamp} is epoch milliseconds. */
@RegisterForReflection
public record AlarmHistoryItem(long sequence, String alarmName, String alarmType, long timestamp,
                               String historyItemType, String historySummary, String historyData) {}
