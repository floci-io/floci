package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A {@code managedAgents} entry of a described container:
 * {@code {"name": "ExecuteCommandAgent", "lastStatus": "RUNNING", "lastStartedAt": ...}}.
 *
 * <p>ECS Exec is gated on this agent reporting {@code RUNNING}, which is what
 * {@code aws ecs execute-command} and the {@code TasksRunning} waiter read.
 */
@RegisterForReflection
public record ManagedAgent(String name, String lastStatus, String reason, Instant lastStartedAt) {

    public static final String EXECUTE_COMMAND_AGENT = "ExecuteCommandAgent";
    public static final String RUNNING = "RUNNING";
    public static final String STOPPED = "STOPPED";

    public static ManagedAgent executeCommandAgent(Instant startedAt) {
        return new ManagedAgent(EXECUTE_COMMAND_AGENT, RUNNING, null, startedAt);
    }
}
