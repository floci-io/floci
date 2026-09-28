package io.github.hectorvent.floci.services.ecs.exec;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

/**
 * One {@code ExecuteCommand} session: what to run, in which container, and the token the client
 * has to present when it opens the data channel.
 */
@RegisterForReflection
public record ExecSession(
        String sessionId,
        String tokenValue,
        String taskArn,
        String clusterArn,
        String containerName,
        String containerArn,
        String runtimeId,
        List<String> command,
        boolean interactive,
        Instant createdAt) {
}
