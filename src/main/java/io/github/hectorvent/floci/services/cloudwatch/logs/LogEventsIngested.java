package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;

import java.util.List;

/**
 * Fired by {@link CloudWatchLogsService} once a PutLogEvents batch is stored, with the events as
 * stored, so the features AWS drives from ingestion (metric filters today) run without the log
 * service knowing about them.
 */
public record LogEventsIngested(String region, String logGroupName, String logStreamName, List<LogEvent> events) {}
