package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.protobuf.InvalidProtocolBufferException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.WriteProfile;
import io.floci.aws.testing.StorageFactories;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OtlpMetricsServiceTest {

    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"memory", "persistent", "hybrid", "wal"})
    void expiresOldBatchesPerRegion(String mode) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        ExportMetricsServiceRequest old = request("old");
        ExportMetricsServiceRequest fresh = request("fresh");
        StorageFactory factory = StorageFactories.open(directory, mode, "111111111111");
        try {
            OtlpMetricsService initial = new OtlpMetricsService(factory, Clock.fixed(now, ZoneOffset.UTC));
            initial.ingest("eu-west-1", old.toByteArray());
            initial.ingest("us-east-1", old.toByteArray());
            assertEquals(1, initial.requests("eu-west-1").size());

            Instant boundary = now.plus(Duration.ofDays(14));
            OtlpMetricsService atBoundary = new OtlpMetricsService(factory, Clock.fixed(boundary, ZoneOffset.UTC));
            atBoundary.ingest("eu-west-1", fresh.toByteArray());
            assertEquals(2, atBoundary.requests("eu-west-1").size());

            OtlpMetricsService expired = new OtlpMetricsService(factory,
                    Clock.fixed(boundary.plusMillis(1), ZoneOffset.UTC));
            expired.ingest("eu-west-1", fresh.toByteArray());
            assertEquals(List.of(fresh, fresh), parsed(expired.requests("eu-west-1")));
            assertEquals(List.of(old), parsed(expired.requests("us-east-1")));
            expired.ingest("us-east-1", fresh.toByteArray());
            assertEquals(List.of(fresh), parsed(expired.requests("us-east-1")));
        } finally {
            factory.shutdownAll();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void expiresOnlyTheCurrentAccountsBatches() {
        RequestContext context = new RequestContext();
        Instance<RequestContext> contextInstance = mock(Instance.class);
        when(contextInstance.get()).thenReturn(context);
        AccountAwareStorageBackend<String> store = new AccountAwareStorageBackend<>(
                new InMemoryStorage<>(), contextInstance, "000000000000");
        StorageFactory factory = mock(StorageFactory.class);
        when(factory.create(eq("cloudwatchmetrics"), eq("cw-otlp-metrics.json"),
                any(TypeReference.class), eq(WriteProfile.APPEND_HEAVY))).thenReturn(store);
        Clock clock = mock(Clock.class);
        long now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli();
        when(clock.millis()).thenReturn(now);
        OtlpMetricsService service = new OtlpMetricsService(factory, context, clock);
        ExportMetricsServiceRequest old = request("old");
        ExportMetricsServiceRequest fresh = request("fresh");
        context.setAccountId("111111111111");
        service.ingest("eu-west-1", old.toByteArray());
        context.setAccountId("222222222222");
        service.ingest("eu-west-1", old.toByteArray());

        when(clock.millis()).thenReturn(now + Duration.ofDays(14).toMillis() + 1);
        context.setAccountId("111111111111");
        service.ingest("eu-west-1", fresh.toByteArray());
        assertEquals(List.of(fresh), parsed(service.requests("eu-west-1")));
        context.setAccountId("222222222222");
        assertEquals(List.of(old), parsed(service.requests("eu-west-1")));
        service.ingest("eu-west-1", fresh.toByteArray());
        assertEquals(List.of(fresh), parsed(service.requests("eu-west-1")));
    }

    private List<ExportMetricsServiceRequest> parsed(List<byte[]> raw) {
        return raw.stream().map(b -> {
            try {
                return ExportMetricsServiceRequest.parseFrom(b);
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError("Could not parse stored OTLP metrics", e);
            }
        }).toList();
    }

    private ExportMetricsServiceRequest request(String name) {
        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder().setSchemaUrl(name)).build();
    }
}
