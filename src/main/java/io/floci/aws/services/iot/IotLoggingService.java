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
        store.put(region, new IotV2LoggingOptions(request.path("roleArn").textValue(),
                request.path("defaultLogLevel").textValue(), request.path("disableAllLogs").asBoolean(false)));
    }

    public IotV2LoggingOptions getV2LoggingOptions(String region) {
        return store.get(region).orElseThrow(() ->
                new AwsException("NotConfiguredException", "V2 logging options are not configured.", 404));
    }
}
