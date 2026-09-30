package io.github.hectorvent.floci.services.dynamodb.backend;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbStreamReader.CheckpointLifetime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

import java.util.Locale;

/**
 * The one immutable DynamoDB backend selection, read from {@code floci.services.dynamodb.backend}:
 * {@code native} (the default) runs the in-process engine, {@code local} forwards to an external
 * DynamoDB Local. Both engines are {@code @ApplicationScoped} client proxies, so the unselected one
 * is never constructed. Each engine is typed to its concrete class and {@link DynamoDbApiStreamReader}
 * is not a bean, so these producers are the only unqualified beans for the five seam interfaces.
 */
@ApplicationScoped
public class DynamoDbBackendSelector {

    private final String selected;
    private final DynamoDbBackend backend;

    @Inject
    public DynamoDbBackendSelector(EmulatorConfig config, NativeDynamoDbBackend nativeBackend,
                                   DynamoDbLocalBackend localBackend) {
        String configured = config.services().dynamodb().backend();
        selected = configured.trim().toLowerCase(Locale.ROOT);
        backend = switch (selected) {
            case "native" -> nativeBackend;
            case "local" -> localBackend;
            default -> throw new IllegalStateException(
                    "floci.services.dynamodb.backend must be 'native' or 'local', got '" + configured + "'");
        };
    }

    /** The selected engine name, {@code native} or {@code local}. */
    public String selected() {
        return selected;
    }

    @Produces
    DynamoDbOperations operations() {
        return backend;
    }

    @Produces
    DynamoDbItemAccess itemAccess() {
        return backend;
    }

    @Produces
    DynamoDbTableAccess tableAccess() {
        return backend;
    }

    @Produces
    DynamoDbBackendLifecycle lifecycle() {
        return backend;
    }

    /**
     * Native stream history lives only in memory, so committed progress ends with the process;
     * DynamoDB Local keeps stream history outside it, so progress lasts as long as the stream.
     */
    @Produces
    DynamoDbStreamReader streamReader() {
        return new DynamoDbApiStreamReader(backend,
                "local".equals(selected) ? CheckpointLifetime.STREAM : CheckpointLifetime.PROCESS);
    }
}
