package io.floci.aws.services.cloudwatch.metrics;

import com.google.protobuf.InvalidProtocolBufferException;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.testing.StorageFactories;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtlpMetricsStorageReloadTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void storedRequestsSurviveReloadAndRemainRegionScoped(String mode) throws InvalidProtocolBufferException {
        ExportMetricsServiceRequest request = ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder().setSchemaUrl("https://example.org/schema"))
                .build();
        StorageFactory first = StorageFactories.open(directory, mode, "111111111111");
        try {
            new OtlpMetricsService(first).ingest("eu-west-1", request.toByteArray());
        } finally {
            first.shutdownAll();
        }
        StorageFactory second = StorageFactories.open(directory, mode, "111111111111");
        try {
            OtlpMetricsService service = new OtlpMetricsService(second);
            List<byte[]> stored = service.requests("eu-west-1");
            assertEquals(1, stored.size());
            assertEquals(request, ExportMetricsServiceRequest.parseFrom(stored.get(0)));
            assertTrue(service.requests("us-east-1").isEmpty());
        } finally {
            second.shutdownAll();
        }
        StorageFactory otherAccount = StorageFactories.open(directory, mode, "222222222222");
        try {
            assertTrue(new OtlpMetricsService(otherAccount).requests("eu-west-1").isEmpty());
        } finally {
            otherAccount.shutdownAll();
        }
    }
}
