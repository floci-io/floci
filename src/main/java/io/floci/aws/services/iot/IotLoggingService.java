package io.floci.aws.services.iot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.iot.model.IotV2LoggingOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class IotLoggingService {

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
        String roleArn = request.path("roleArn").textValue();
        String defaultLogLevel = request.path("defaultLogLevel").textValue();
        JsonNode disableAllLogs = request.path("disableAllLogs");
        Optional<IotV2LoggingOptions> current = store.get(region);
        if (current.isEmpty() && (roleArn == null || defaultLogLevel == null)) {
            throw new AwsException("InvalidRequestException",
                    "roleArn and defaultLogLevel are required for the initial setup.", 400);
        }
        IotV2LoggingOptions prior = current.orElse(new IotV2LoggingOptions(null, null, false));
        store.put(region, new IotV2LoggingOptions(roleArn != null ? roleArn : prior.roleArn(),
                defaultLogLevel != null ? defaultLogLevel : prior.defaultLogLevel(),
                disableAllLogs.isBoolean() ? disableAllLogs.booleanValue() : prior.disableAllLogs()));
    }

    public IotV2LoggingOptions getV2LoggingOptions(String region) {
        return store.get(region).orElseThrow(() ->
                new AwsException("NotConfiguredException", "V2 logging options are not configured.", 404));
    }
}
