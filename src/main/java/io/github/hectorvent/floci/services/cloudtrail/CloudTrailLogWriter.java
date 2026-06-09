package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Periodically drains queued CloudTrail records from {@link CloudTrailService}
 * and writes them as gzipped JSON log files into each trail's destination
 * S3 bucket, using AWS's documented key layout:
 *
 * <pre>{@code
 * ${prefix}AWSLogs/${accountId}/CloudTrail/${region}/yyyy/MM/dd/
 *   ${accountId}_CloudTrail_${region}_${YYYYMMDDTHHMMZ}_${rand12}.json.gz
 * }</pre>
 *
 * Real CloudTrail delivers logs with ~5 minute latency. The flush cadence
 * here is configurable so local dev / CI loops stay fast.
 */
@Startup
@ApplicationScoped
public class CloudTrailLogWriter {

    private static final Logger LOG = Logger.getLogger(CloudTrailLogWriter.class);

    private static final DateTimeFormatter PATH_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");
    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'");
    private static final String FILENAME_RAND_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final EmulatorConfig config;
    private final CloudTrailService cloudTrailService;
    private final S3Service s3Service;
    private final RegionResolver regionResolver;
    private final ObjectMapper mapper;
    private final SecureRandom rng = new SecureRandom();

    private ScheduledExecutorService executor;

    @Inject
    public CloudTrailLogWriter(EmulatorConfig config,
                               CloudTrailService cloudTrailService,
                               S3Service s3Service,
                               RegionResolver regionResolver,
                               ObjectMapper mapper) {
        this.config = config;
        this.cloudTrailService = cloudTrailService;
        this.s3Service = s3Service;
        this.regionResolver = regionResolver;
        this.mapper = mapper;
    }

    @PostConstruct
    void start() {
        if (!config.services().cloudtrail().enabled()) {
            return;
        }
        int interval = Math.max(1, config.services().cloudtrail().flushIntervalSeconds());
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cloudtrail-log-writer");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::flushAll, interval, interval, TimeUnit.SECONDS);
        LOG.infov("CloudTrail log writer started (flushIntervalSeconds={0})", interval);
    }

    void stop(@Observes ShutdownEvent event) {
        if (executor != null) {
            // Best-effort final flush so shutdown doesn't lose buffered events.
            try { flushAll(); } catch (Exception ignored) { /* best effort */ }
            executor.shutdownNow();
            executor = null;
        }
    }

    /**
     * Force an immediate flush. Useful for tests and for deterministic CLI
     * verification.
     */
    public void flushNow() {
        flushAll();
    }

    private void flushAll() {
        try {
            for (CloudTrailService.TrailKey key : cloudTrailService.trailsWithPendingRecords()) {
                try {
                    flushTrail(key);
                } catch (RuntimeException e) {
                    LOG.warnv(e, "CloudTrail log flush failed for trail {0} in {1}",
                            key.trailName(), key.region());
                }
            }
        } catch (RuntimeException outer) {
            LOG.errorv(outer, "CloudTrail log writer iteration failed");
        }
    }

    private void flushTrail(CloudTrailService.TrailKey key) {
        Trail trail = cloudTrailService.getTrail(key.region(), key.trailName());
        if (trail == null) {
            // Trail was deleted while records were pending — drop them.
            cloudTrailService.drainPendingRecords(key.region(), key.trailName());
            return;
        }

        List<ObjectNode> records = cloudTrailService.drainPendingRecords(
                key.region(), key.trailName());
        if (records.isEmpty()) {
            return;
        }

        byte[] payload = serializeAndGzip(records);
        String accountId = regionResolver.getAccountId();
        String objectKey = buildObjectKey(trail, accountId, key.region());

        try {
            s3Service.putObject(trail.s3BucketName(), objectKey, payload,
                    "application/x-gzip", Map.of());
            LOG.debugv("CloudTrail wrote {0} records to s3://{1}/{2}",
                    records.size(), trail.s3BucketName(), objectKey);
        } catch (RuntimeException e) {
            LOG.warnv(e, "CloudTrail failed to write log file s3://{0}/{1} ({2} records)",
                    trail.s3BucketName(), objectKey, records.size());
            throw e;
        }
    }

    private byte[] serializeAndGzip(List<ObjectNode> records) {
        ObjectNode envelope = mapper.createObjectNode();
        ArrayNode arr = envelope.putArray("Records");
        for (ObjectNode r : records) {
            arr.add(r);
        }
        try {
            byte[] json = mapper.writeValueAsBytes(envelope);
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, json.length / 4));
            try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
                gz.write(json);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize CloudTrail records", e);
        }
    }

    private String buildObjectKey(Trail trail, String accountId, String region) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        String prefix = trail.s3KeyPrefix() == null || trail.s3KeyPrefix().isEmpty()
                ? "" : trail.s3KeyPrefix() + (trail.s3KeyPrefix().endsWith("/") ? "" : "/");
        String datePath = PATH_DATE.format(now);
        String fileTs = FILE_TS.format(now);
        String rand = randomFilenameSuffix(16);
        return prefix
                + "AWSLogs/" + accountId
                + "/CloudTrail/" + region
                + "/" + datePath + "/"
                + accountId + "_CloudTrail_" + region + "_" + fileTs + "_" + rand + ".json.gz";
    }

    private String randomFilenameSuffix(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(FILENAME_RAND_ALPHABET.charAt(rng.nextInt(FILENAME_RAND_ALPHABET.length())));
        }
        return sb.toString();
    }
}
