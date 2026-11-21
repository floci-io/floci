package io.floci.aws.services.emrserverless;

import java.time.Instant;

/** A Spark Connect session, EMR Serverless's or EMR's, as {@link SparkConnectSessionOwner} moves it between states. */
public interface SparkConnectSession {

    String getState();

    void setState(String state);

    Instant getStartedAt();

    void setStartedAt(Instant startedAt);

    Instant getIdleSince();

    void setIdleSince(Instant idleSince);

    void setUpdatedAt(Instant updatedAt);
}
