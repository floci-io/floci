package io.floci.aws.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.SmithyValidationErrors;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iot.model.IotV2LoggingOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.floci.aws.services.iot.IotRequestBody.requireObject;
import static io.floci.aws.services.iot.IotRequestBody.serializationError;
import static io.floci.aws.services.iot.IotRequestBody.text;

@ApplicationScoped
public class IotLoggingService {

    private static final List<String> LOG_LEVELS = List.of("DEBUG", "INFO", "ERROR", "WARN", "DISABLED");

    private final StorageBackend<String, IotV2LoggingOptions> store;
    private final Object lock = new Object();

    @Inject
    public IotLoggingService(StorageFactory storageFactory) {
        this(storageFactory.create("iot", "iot-v2-logging-options.json",
                new TypeReference<Map<String, IotV2LoggingOptions>>() {}));
    }

    IotLoggingService(StorageBackend<String, IotV2LoggingOptions> store) {
        this.store = store;
    }

    public void setV2LoggingOptions(JsonNode request, String region) {
        requireObject(request);
        String roleArn = text(request.path("roleArn"), "roleArn");
        String defaultLogLevel = text(request.path("defaultLogLevel"), "defaultLogLevel");
        Boolean disableAllLogs = bool(request.path("disableAllLogs"), "disableAllLogs");
        if (defaultLogLevel != null && !LOG_LEVELS.contains(defaultLogLevel)) {
            throw SmithyValidationErrors.exception("InvalidRequestException",
                    SmithyValidationErrors.notInEnum(defaultLogLevel, "defaultLogLevel", LOG_LEVELS));
        }
        synchronized (lock) {
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
    }

    public IotV2LoggingOptions getV2LoggingOptions(String region) {
        return store.get(region).orElseThrow(() ->
                new AwsException("NotConfiguredException", "V2 logging options are not configured.", 404));
    }

    private static Boolean bool(JsonNode node, String member) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isBoolean()) {
            throw serializationError(member, JsonNodeType.BOOLEAN);
        }
        return node.booleanValue();
    }
}
