package io.github.hectorvent.floci.services.dynamodb.backend;

import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.CheckpointLifetime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * The one immutable DynamoDB backend selection. Native is the only engine today; a configurable
 * choice arrives with the alternative engine. {@link NativeDynamoDbBackend} is typed to its concrete
 * class and {@link DynamoDbApiStreamReader} is not a bean, so these producers are the only
 * unqualified beans for the five seam interfaces.
 */
@ApplicationScoped
public class DynamoDbBackendSelector {

    private final NativeDynamoDbBackend nativeBackend;

    @Inject
    public DynamoDbBackendSelector(NativeDynamoDbBackend nativeBackend) {
        this.nativeBackend = nativeBackend;
    }

    @Produces
    DynamoDbOperations operations() {
        return nativeBackend;
    }

    @Produces
    DynamoDbItemAccess itemAccess() {
        return nativeBackend;
    }

    @Produces
    DynamoDbTableAccess tableAccess() {
        return nativeBackend;
    }

    @Produces
    DynamoDbBackendLifecycle lifecycle() {
        return nativeBackend;
    }

    /** Native stream history lives only in memory, so committed progress ends with the process. */
    @Produces
    DynamoDbStreamReader streamReader() {
        return new DynamoDbApiStreamReader(nativeBackend, CheckpointLifetime.PROCESS);
    }
}
