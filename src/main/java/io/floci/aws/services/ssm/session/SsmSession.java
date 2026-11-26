package io.floci.aws.services.ssm.session;

import io.floci.aws.services.ssmmessages.SsmMessageGateway.SessionState;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public record SsmSession(String sessionId, String target, String owner, String documentName,
                         String reason, SessionState state, Instant startDate, Instant endDate) {
    public boolean active() {
        return endDate == null;
    }

    public String status() {
        return switch (state) {
            case CONNECTED -> "Connected";
            case DISCONNECTED -> "Disconnected";
            case TERMINATED -> "Terminated";
            case FAILED -> "Failed";
        };
    }

    SsmSession transition(SessionState next, Instant now) {
        Instant end = switch (next) {
            case TERMINATED, FAILED -> now;
            case CONNECTED, DISCONNECTED -> null;
        };
        return new SsmSession(sessionId, target, owner, documentName, reason, next, startDate, end);
    }
}
