package io.github.hectorvent.floci.services.cloudwatch.logs;

import io.github.hectorvent.floci.services.cloudwatch.logs.model.LogEvent;

import java.util.List;

/**
 * Fired by {@link CloudWatchLogsService} once a PutLogEvents batch is stored, with the events as
 * stored, so the features AWS drives from ingestion (metric filters today) run without the log
 * service knowing about them. {@code accountId} is the account the batch was written for when the
 * writer named one, as a container streaming logs outside any request does, and null when the
 * batch belongs to the caller's own account.
 */
public record LogEventsIngested(String accountId, String region, String logGroupName, String logStreamName,
                                List<LogEvent> events) {}
