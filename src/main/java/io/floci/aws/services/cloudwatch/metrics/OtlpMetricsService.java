package io.floci.aws.services.cloudwatch.metrics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.protobuf.InvalidProtocolBufferException;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.core.storage.StorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.core.storage.WriteProfile;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class OtlpMetricsService {

    private static final long RETENTION_MILLIS = Duration.ofDays(14).toMillis();
    private static final long SWEEP_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();
    private final StorageBackend<String, String> store;
    private final RequestContext requestContext;
    private final Clock clock;
    private final Map<String, Long> nextSweeps = new HashMap<>();

    @Inject
    public OtlpMetricsService(StorageFactory storageFactory, RequestContext requestContext) {
        this(storageFactory, requestContext, Clock.systemUTC());
    }

    OtlpMetricsService(StorageFactory storageFactory) {
        this(storageFactory, null, Clock.systemUTC());
    }

    OtlpMetricsService(StorageFactory storageFactory, Clock clock) {
        this(storageFactory, null, clock);
    }

    OtlpMetricsService(StorageFactory storageFactory, RequestContext requestContext, Clock clock) {
        this.store = storageFactory.create("cloudwatchmetrics", "cw-otlp-metrics.json",
                new TypeReference<Map<String, String>>() {}, WriteProfile.APPEND_HEAVY);
        this.requestContext = requestContext;
        this.clock = clock;
    }

    void ingest(String region, ExportMetricsServiceRequest request) {
        if (request.getResourceMetricsCount() != 0) {
            long now = clock.millis();
            pruneExpired(region, now);
            store.put(region + "::" + now + "::" + UUID.randomUUID(),
                    Base64.getEncoder().encodeToString(request.toByteArray()));
        }
    }

    private synchronized void pruneExpired(String region, long now) {
        String account = requestContext == null ? "" : requestContext.getAccountId();
        String scope = account + "/" + region;
        if (now < nextSweeps.getOrDefault(scope, Long.MIN_VALUE)) {
            return;
        }
        String prefix = region + "::";
        for (String key : store.keys()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String suffix = key.substring(prefix.length());
            int delimiter = suffix.indexOf("::");
            if (delimiter < 0) {
                migrateLegacyKey(key, prefix, suffix, now);
                continue;
            }
            try {
                long created = Long.parseLong(suffix.substring(0, delimiter));
                if (created < now - RETENTION_MILLIS) {
                    store.delete(key);
                }
            } catch (NumberFormatException ignored) {
                // Preserve unrecognized keys rather than deleting data with an unknown age.
            }
        }
        nextSweeps.put(scope, now + SWEEP_INTERVAL_MILLIS);
    }

    private void migrateLegacyKey(String key, String prefix, String suffix, long now) {
        try {
            UUID.fromString(suffix);
        } catch (IllegalArgumentException ignored) {
            // Preserve keys that do not match the previous UUID-only format.
            return;
        }
        store.get(key).ifPresent(value -> {
            store.put(prefix + now + "::" + suffix, value);
            store.delete(key);
        });
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
