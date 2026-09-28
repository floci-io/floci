package io.github.hectorvent.floci.services.cloudwatch.logs;

/** Fired by {@link CloudWatchLogsService} once a log group is deleted, so what hangs off the group goes with it. */
public record LogGroupDeleted(String region, String logGroupName) {}
