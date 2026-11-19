package io.floci.aws.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iot.model.IotV2LoggingOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class IotLoggingService {

    private static final List<String> LOG_LEVELS = List.of("DEBUG", "INFO", "ERROR", "WARN", "DISABLED");

    private final StorageBackend<String, IotV2LoggingOptions> store;

    @Inject
    public IotLoggingService(StorageFactory storageFactory) {
        this(storageFactory.create("iot", "iot-v2-logging-options.json",
                new TypeReference<Map<String, IotV2LoggingOptions>>() {}));
    }

    IotLoggingService(StorageBackend<String, IotV2LoggingOptions> store) {
        this.store = store;
    }

    public void setV2LoggingOptions(JsonNode request, String region) {
        if (!request.isObject()) {
            throw serializationError("request body", "object");
        }
        String roleArn = text(request.path("roleArn"), "roleArn");
        String defaultLogLevel = text(request.path("defaultLogLevel"), "defaultLogLevel");
        Boolean disableAllLogs = bool(request.path("disableAllLogs"), "disableAllLogs");
        if (defaultLogLevel != null && !LOG_LEVELS.contains(defaultLogLevel)) {
            throw new AwsException("InvalidRequestException", "1 validation error detected: Value '" + defaultLogLevel
                    + "' at 'defaultLogLevel' failed to satisfy constraint: Member must satisfy enum value set: "
                    + LOG_LEVELS, 400);
        }
        Optional<IotV2LoggingOptions> current = store.get(region);
        if (current.isEmpty() && (roleArn == null || defaultLogLevel == null)) {
            throw new AwsException("InvalidRequestException",
                    "roleArn and defaultLogLevel are required for the initial setup.", 400);
        }
        IotV2LoggingOptions prior = current.orElse(new IotV2LoggingOptions(null, null, false));
        store.put(region, new IotV2LoggingOptions(roleArn != null ? roleArn : prior.roleArn(),
                defaultLogLevel != null ? defaultLogLevel : prior.defaultLogLevel(),
                disableAllLogs != null ? disableAllLogs : prior.disableAllLogs()));
    }

    public IotV2LoggingOptions getV2LoggingOptions(String region) {
        return store.get(region).orElseThrow(() ->
                new AwsException("NotConfiguredException", "V2 logging options are not configured.", 404));
    }

    private static String text(JsonNode node, String member) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw serializationError(member, "string");
        }
        return node.textValue();
    }

    private static Boolean bool(JsonNode node, String member) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw serializationError(member, "boolean");
        }
        return node.booleanValue();
    }

    private static AwsException serializationError(String member, String type) {
        return new AwsException("SerializationException", member + " must be a JSON " + type, 400);
    }
}
