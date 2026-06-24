package io.github.hectorvent.floci.services.batch.model;

public record BatchRunResult(
        int exitCode,
        String reason,
        String logStreamName,
        long startedAt,
        long stoppedAt,
        boolean timedOut
) {
}
