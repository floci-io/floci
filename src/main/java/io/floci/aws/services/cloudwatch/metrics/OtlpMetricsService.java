package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.protobuf.InvalidProtocolBufferException;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.WriteProfile;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class OtlpMetricsService {

    private final StorageBackend<String, String> store;

    @Inject
    public OtlpMetricsService(StorageFactory storageFactory) {
        this.store = storageFactory.create("cloudwatchmetrics", "cw-otlp-metrics.json",
                new TypeReference<Map<String, String>>() {}, WriteProfile.APPEND_HEAVY);
    }

    void ingest(String region, ExportMetricsServiceRequest request) {
        if (request.getResourceMetricsCount() != 0) {
            store.put(region + "::" + UUID.randomUUID(), Base64.getEncoder().encodeToString(request.toByteArray()));
        }
    }

    List<ExportMetricsServiceRequest> requests(String region) {
        return store.scan(key -> key.startsWith(region + "::")).stream().map(encoded -> {
            try {
                return ExportMetricsServiceRequest.parseFrom(Base64.getDecoder().decode(encoded));
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalStateException("Stored OTLP metrics could not be decoded", e);
            }
        }).toList();
    }
}
