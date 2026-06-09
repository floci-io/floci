package io.github.hectorvent.floci.services.cloudtrail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudtrail.model.DataResource;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.services.iam.model.AccessKey;
import io.github.hectorvent.floci.services.iam.model.IamUser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@ApplicationScoped
public class CloudTrailService {

    private static final Logger LOG = Logger.getLogger(CloudTrailService.class);

    private static final String EVENT_VERSION = "1.11";
    private static final String S3_EVENT_SOURCE = "s3.amazonaws.com";

    private final RegionResolver regionResolver;
    private final IamService iamService;
    private final ObjectMapper mapper;

    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Trail>> trailsByRegion = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, List<EventSelector>>> selectorsByRegion = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Boolean>> loggingByRegion = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> startTimeByRegion = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> stopTimeByRegion = new ConcurrentHashMap<>();

    /** Per-trail pending record buffers, keyed by (region, trailName). */
    private final ConcurrentHashMap<TrailKey, ConcurrentLinkedQueue<ObjectNode>> pendingRecordsByTrail =
            new ConcurrentHashMap<>();

    @Inject
    public CloudTrailService(RegionResolver regionResolver, IamService iamService, ObjectMapper mapper) {
        this.regionResolver = regionResolver;
        this.iamService = iamService;
        this.mapper = mapper;
    }

    // --- Control plane ---

    public Trail createTrail(String region, String name, String s3BucketName, String s3KeyPrefix,
                             String snsTopicArn, boolean includeGlobalServiceEvents,
                             boolean isMultiRegionTrail, boolean enableLogFileValidation,
                             boolean isOrganizationTrail) {
        if (name == null || name.isEmpty()) {
            throw new AwsException("InvalidTrailNameException", "Trail name is required.", 400);
        }
        if (s3BucketName == null || s3BucketName.isEmpty()) {
            throw new AwsException("S3BucketDoesNotExistException", "S3 bucket name is required.", 400);
        }
        ConcurrentHashMap<String, Trail> store = trailsFor(region);
        if (store.containsKey(name)) {
            throw new AwsException("TrailAlreadyExistsException",
                    "Trail " + name + " already exists.", 400);
        }
        String arn = AwsArnUtils.Arn.of("cloudtrail", region, regionResolver.getAccountId(),
                "trail/" + name).toString();
        Trail trail = new Trail(
                name, arn, s3BucketName, s3KeyPrefix, snsTopicArn,
                includeGlobalServiceEvents, isMultiRegionTrail, region,
                enableLogFileValidation, false, false, isOrganizationTrail);
        store.put(name, trail);
        return trail;
    }

    public void deleteTrail(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        for (ConcurrentHashMap<String, Trail> store : trailsByRegion.values()) {
            store.remove(trail.name());
        }
        for (ConcurrentHashMap<String, List<EventSelector>> store : selectorsByRegion.values()) {
            store.remove(trail.name());
        }
        for (ConcurrentHashMap<String, Boolean> store : loggingByRegion.values()) {
            store.remove(trail.name());
        }
        for (ConcurrentHashMap<String, Long> store : startTimeByRegion.values()) {
            store.remove(trail.name());
        }
        for (ConcurrentHashMap<String, Long> store : stopTimeByRegion.values()) {
            store.remove(trail.name());
        }
        // Drop any buffered records targeted at the deleted trail.
        pendingRecordsByTrail.keySet().removeIf(k -> k.trailName().equals(trail.name()));
    }

    public Trail updateTrail(String region, String trailNameOrArn,
                             String s3BucketName, String s3KeyPrefix, String snsTopicArn,
                             Boolean includeGlobalServiceEvents, Boolean isMultiRegionTrail,
                             Boolean enableLogFileValidation, Boolean isOrganizationTrail) {
        Trail existing = findTrailOrThrow(region, trailNameOrArn);
        Trail updated = new Trail(
                existing.name(),
                existing.trailArn(),
                s3BucketName != null ? s3BucketName : existing.s3BucketName(),
                s3KeyPrefix != null ? s3KeyPrefix : existing.s3KeyPrefix(),
                snsTopicArn != null ? snsTopicArn : existing.snsTopicArn(),
                includeGlobalServiceEvents != null ? includeGlobalServiceEvents : existing.includeGlobalServiceEvents(),
                isMultiRegionTrail != null ? isMultiRegionTrail : existing.isMultiRegionTrail(),
                existing.homeRegion(),
                enableLogFileValidation != null ? enableLogFileValidation : existing.logFileValidationEnabled(),
                existing.hasCustomEventSelectors(),
                existing.hasInsightSelectors(),
                isOrganizationTrail != null ? isOrganizationTrail : existing.isOrganizationTrail());
        // Find which region the trail lives in (it may be a multi-region trail)
        for (Map.Entry<String, ConcurrentHashMap<String, Trail>> e : trailsByRegion.entrySet()) {
            if (e.getValue().containsKey(existing.name())) {
                e.getValue().put(existing.name(), updated);
            }
        }
        return updated;
    }

    public List<Trail> describeTrails(String region, List<String> trailNameOrArnList) {
        List<Trail> results = new ArrayList<>();
        if (trailNameOrArnList == null || trailNameOrArnList.isEmpty()) {
            // Return all trails in the region
            ConcurrentHashMap<String, Trail> store = trailsByRegion.get(region);
            if (store != null) {
                results.addAll(store.values());
            }
            // Multi-region trails created in another region also surface here
            for (Map.Entry<String, ConcurrentHashMap<String, Trail>> e : trailsByRegion.entrySet()) {
                if (e.getKey().equals(region)) continue;
                for (Trail t : e.getValue().values()) {
                    if (t.isMultiRegionTrail()) results.add(t);
                }
            }
            return results;
        }
        for (String key : trailNameOrArnList) {
            Trail t = findTrail(region, key);
            if (t != null) results.add(t);
        }
        return results;
    }

    public List<EventSelector> putEventSelectors(String region, String trailNameOrArn, List<EventSelector> selectors) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        ConcurrentHashMap<String, List<EventSelector>> store = selectorsFor(region);
        List<EventSelector> normalized = selectors == null ? List.of() : List.copyOf(selectors);
        store.put(trail.name(), normalized);
        // Mark hasCustomEventSelectors=true on the trail
        trailsFor(region).put(trail.name(), new Trail(
                trail.name(), trail.trailArn(), trail.s3BucketName(), trail.s3KeyPrefix(),
                trail.snsTopicArn(), trail.includeGlobalServiceEvents(), trail.isMultiRegionTrail(),
                trail.homeRegion(), trail.logFileValidationEnabled(), true, trail.hasInsightSelectors(),
                trail.isOrganizationTrail()));
        return normalized;
    }

    public List<EventSelector> getEventSelectors(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        ConcurrentHashMap<String, List<EventSelector>> store = selectorsByRegion.get(region);
        if (store == null) return List.of();
        return store.getOrDefault(trail.name(), List.of());
    }

    public void startLogging(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        loggingFor(region).put(trail.name(), true);
        startTimesFor(region).put(trail.name(), System.currentTimeMillis());
    }

    public void stopLogging(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        loggingFor(region).put(trail.name(), false);
        stopTimesFor(region).put(trail.name(), System.currentTimeMillis());
    }

    public TrailStatus getTrailStatus(String region, String trailNameOrArn) {
        Trail trail = findTrailOrThrow(region, trailNameOrArn);
        boolean logging = loggingByRegion.getOrDefault(region, new ConcurrentHashMap<>())
                .getOrDefault(trail.name(), false);
        Long start = startTimeByRegion.getOrDefault(region, new ConcurrentHashMap<>())
                .get(trail.name());
        Long stop = stopTimeByRegion.getOrDefault(region, new ConcurrentHashMap<>())
                .get(trail.name());
        return new TrailStatus(logging, start, stop);
    }

    // --- Data plane: called by S3 (and other services) when an op happens ---

    public void emitS3DataEvent(S3EventInput in) {
        try {
            String region = in.region() != null ? in.region() : regionResolver.getDefaultRegion();
            List<MatchedTrail> matched = trailsMatching(region, in);
            if (matched.isEmpty()) {
                return;
            }
            ObjectNode record = buildS3Record(in);
            for (MatchedTrail mt : matched) {
                ObjectNode copy = record.deepCopy();
                copy.put("recipientAccountId", regionResolver.getAccountId());
                queueFor(new TrailKey(mt.region(), mt.trail().name())).add(copy);
                LOG.tracev("Emitted CloudTrail event {0} for trail {1}", in.eventName(), mt.trail().name());
            }
        } catch (Exception e) {
            // Never let emission take down an S3 op.
            LOG.warnv(e, "Failed to emit CloudTrail event for {0} {1}/{2}",
                    in.eventName(), in.bucketName(), in.key());
        }
    }

    /** Drain pending records for a specific trail (used by the log-file writer + tests). */
    public List<ObjectNode> drainPendingRecords(String region, String trailName) {
        ConcurrentLinkedQueue<ObjectNode> q = pendingRecordsByTrail.get(new TrailKey(region, trailName));
        if (q == null) return List.of();
        List<ObjectNode> drained = new ArrayList<>();
        ObjectNode r;
        while ((r = q.poll()) != null) {
            drained.add(r);
        }
        return drained;
    }

    public int pendingRecordCount(String region, String trailName) {
        ConcurrentLinkedQueue<ObjectNode> q = pendingRecordsByTrail.get(new TrailKey(region, trailName));
        return q == null ? 0 : q.size();
    }

    /** Snapshot of all (region, trailName) pairs that currently have records pending. */
    public List<TrailKey> trailsWithPendingRecords() {
        List<TrailKey> result = new ArrayList<>();
        for (Map.Entry<TrailKey, ConcurrentLinkedQueue<ObjectNode>> e : pendingRecordsByTrail.entrySet()) {
            if (!e.getValue().isEmpty()) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    public Trail getTrail(String region, String trailName) {
        ConcurrentHashMap<String, Trail> store = trailsByRegion.get(region);
        return store == null ? null : store.get(trailName);
    }

    private ConcurrentLinkedQueue<ObjectNode> queueFor(TrailKey key) {
        return pendingRecordsByTrail.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>());
    }

    public record TrailKey(String region, String trailName) {}

    // --- Helpers ---

    private List<MatchedTrail> trailsMatching(String region, S3EventInput in) {
        List<MatchedTrail> result = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, Trail>> regionEntry : trailsByRegion.entrySet()) {
            String trailRegion = regionEntry.getKey();
            boolean sameRegion = trailRegion.equals(region);
            for (Trail trail : regionEntry.getValue().values()) {
                if (!sameRegion && !trail.isMultiRegionTrail()) {
                    continue;
                }
                Boolean logging = loggingByRegion.getOrDefault(trailRegion, new ConcurrentHashMap<>())
                        .get(trail.name());
                if (logging == null || !logging) {
                    continue;
                }
                List<EventSelector> selectors = selectorsByRegion.getOrDefault(trailRegion, new ConcurrentHashMap<>())
                        .getOrDefault(trail.name(), List.of());
                if (matchesAnySelector(selectors, in)) {
                    result.add(new MatchedTrail(trail, trailRegion));
                }
            }
        }
        return result;
    }

    private boolean matchesAnySelector(List<EventSelector> selectors, S3EventInput in) {
        if (selectors.isEmpty()) {
            // No selectors configured → no data events captured (matches AWS default trail behavior).
            return false;
        }
        boolean isRead = isReadOnlyEvent(in.eventName());
        for (EventSelector sel : selectors) {
            String rwt = sel.readWriteType() == null ? "All" : sel.readWriteType();
            if ("ReadOnly".equalsIgnoreCase(rwt) && !isRead) continue;
            if ("WriteOnly".equalsIgnoreCase(rwt) && isRead) continue;

            List<DataResource> dataResources = sel.dataResources();
            if (dataResources == null || dataResources.isEmpty()) {
                continue;
            }
            for (DataResource dr : dataResources) {
                if (!"AWS::S3::Object".equals(dr.type())) continue;
                if (dr.values() == null) continue;
                for (String v : dr.values()) {
                    if (matchesS3DataResourceArn(v, in.bucketName(), in.key())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // Package-private for unit testing.
    static boolean matchesS3DataResourceArn(String configured, String bucketName, String key) {
        if (configured == null) return false;
        // Forms accepted:
        //   arn:aws:s3:::                    → all buckets, all keys
        //   arn:aws:s3:::bucket/             → all keys in bucket
        //   arn:aws:s3:::bucket/prefix       → keys with the given prefix in bucket
        //   arn:aws:s3:::bucket/prefix/key   → exact key match
        String prefix = "arn:aws:s3:::";
        if (!configured.startsWith(prefix)) return false;
        String tail = configured.substring(prefix.length());
        if (tail.isEmpty() || tail.equals("/")) {
            return true;
        }
        int slash = tail.indexOf('/');
        if (slash < 0) {
            return tail.equals(bucketName);
        }
        String configBucket = tail.substring(0, slash);
        if (!configBucket.equals(bucketName)) return false;
        String configKeyPart = tail.substring(slash + 1);
        if (configKeyPart.isEmpty()) {
            return true;
        }
        if (key == null) return false;
        return key.startsWith(configKeyPart);
    }

    // Package-private for unit testing.
    static boolean isReadOnlyEvent(String eventName) {
        if (eventName == null) return true;
        return switch (eventName) {
            case "GetObject", "HeadObject", "ListObjects", "ListObjectsV2",
                 "GetObjectAcl", "GetObjectTagging", "ListMultipartUploads" -> true;
            default -> false;
        };
    }

    private ObjectNode buildS3Record(S3EventInput in) {
        ObjectNode record = mapper.createObjectNode();
        record.put("eventVersion", EVENT_VERSION);
        record.set("userIdentity", buildUserIdentity(in.accessKeyId()));
        record.put("eventTime", DateTimeFormatter.ISO_INSTANT.format(
                Instant.ofEpochMilli((in.eventTimeMillis() == 0L
                        ? System.currentTimeMillis() : in.eventTimeMillis()))
                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)));
        record.put("eventSource", S3_EVENT_SOURCE);
        record.put("eventName", in.eventName());
        record.put("awsRegion", in.region());
        record.put("sourceIPAddress", in.sourceIp() == null ? "127.0.0.1" : in.sourceIp());
        record.put("userAgent", in.userAgent() == null ? "" : in.userAgent());

        if (in.errorCode() != null) {
            record.put("errorCode", in.errorCode());
            if (in.errorMessage() != null) {
                record.put("errorMessage", in.errorMessage());
            }
        }

        ObjectNode reqParams = mapper.createObjectNode();
        if (in.bucketName() != null) reqParams.put("bucketName", in.bucketName());
        reqParams.put("Host", in.bucketName() == null
                ? "s3.amazonaws.com"
                : in.bucketName() + ".s3.amazonaws.com");
        if (in.key() != null) reqParams.put("key", in.key());
        record.set("requestParameters", reqParams);
        record.set("responseElements", mapper.nullNode());

        ObjectNode addl = mapper.createObjectNode();
        addl.put("SignatureVersion", "SigV4");
        addl.put("CipherSuite", "TLS_AES_128_GCM_SHA256");
        addl.put("bytesTransferredIn", in.bytesIn());
        addl.put("AuthenticationMethod", "AuthHeader");
        addl.put("bytesTransferredOut", in.bytesOut());
        record.set("additionalEventData", addl);

        record.put("requestID", UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase());
        record.put("eventID", UUID.randomUUID().toString());
        record.put("readOnly", isReadOnlyEvent(in.eventName()));

        if (in.bucketName() != null) {
            ArrayNode resources = mapper.createArrayNode();
            ObjectNode bucketRes = mapper.createObjectNode();
            bucketRes.put("accountId", regionResolver.getAccountId());
            bucketRes.put("type", "AWS::S3::Bucket");
            bucketRes.put("ARN", "arn:aws:s3:::" + in.bucketName());
            resources.add(bucketRes);
            if (in.key() != null) {
                ObjectNode objRes = mapper.createObjectNode();
                objRes.put("type", "AWS::S3::Object");
                objRes.put("ARN", "arn:aws:s3:::" + in.bucketName() + "/" + in.key());
                resources.add(objRes);
            }
            record.set("resources", resources);
        }

        record.put("eventType", "AwsApiCall");
        record.put("managementEvent", false);
        record.put("eventCategory", "Data");

        ObjectNode tls = mapper.createObjectNode();
        tls.put("tlsVersion", "TLSv1.3");
        tls.put("cipherSuite", "TLS_AES_128_GCM_SHA256");
        tls.put("clientProvidedHostHeader", in.bucketName() == null
                ? "s3.amazonaws.com"
                : in.bucketName() + ".s3.amazonaws.com");
        record.set("tlsDetails", tls);

        return record;
    }

    private ObjectNode buildUserIdentity(String accessKeyId) {
        ObjectNode identity = mapper.createObjectNode();
        String accountId = regionResolver.getAccountId();

        if (accessKeyId == null || "test".equals(accessKeyId)) {
            // Anonymous / root-style: best-effort placeholder.
            identity.put("type", "IAMUser");
            identity.put("principalId", "AIDA" + repeat('A', 17));
            identity.put("arn", "arn:aws:iam::" + accountId + ":root");
            identity.put("accountId", accountId);
            identity.put("accessKeyId", accessKeyId == null ? "" : accessKeyId);
            identity.put("userName", "root");
            return identity;
        }

        AccessKey key = iamService.findAccessKey(accessKeyId).orElse(null);
        if (key != null) {
            IamUser user = iamService.findUser(key.getUserName()).orElse(null);
            if (user != null) {
                identity.put("type", "IAMUser");
                identity.put("principalId", user.getUserId());
                identity.put("arn", user.getArn());
                identity.put("accountId", accountId);
                identity.put("accessKeyId", accessKeyId);
                identity.put("userName", user.getUserName());
                return identity;
            }
        }

        // Unknown access key — fall back to anonymous shape.
        identity.put("type", "IAMUser");
        identity.put("principalId", "AIDA" + repeat('A', 17));
        identity.put("arn", "arn:aws:iam::" + accountId + ":user/anonymous");
        identity.put("accountId", accountId);
        identity.put("accessKeyId", accessKeyId);
        identity.put("userName", "anonymous");
        return identity;
    }

    private static String repeat(char c, int n) {
        char[] arr = new char[n];
        java.util.Arrays.fill(arr, c);
        return new String(arr);
    }

    private Trail findTrail(String region, String nameOrArn) {
        for (ConcurrentHashMap<String, Trail> store : trailsByRegion.values()) {
            for (Trail t : store.values()) {
                if (nameOrArn.equals(t.name()) || nameOrArn.equals(t.trailArn())) {
                    return t;
                }
            }
        }
        return null;
    }

    private Trail findTrailOrThrow(String region, String nameOrArn) {
        Trail t = findTrail(region, nameOrArn);
        if (t == null) {
            throw new AwsException("TrailNotFoundException",
                    "Unknown trail: " + nameOrArn, 400);
        }
        return t;
    }

    private ConcurrentHashMap<String, Trail> trailsFor(String region) {
        return trailsByRegion.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private ConcurrentHashMap<String, List<EventSelector>> selectorsFor(String region) {
        return selectorsByRegion.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private ConcurrentHashMap<String, Boolean> loggingFor(String region) {
        return loggingByRegion.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private ConcurrentHashMap<String, Long> startTimesFor(String region) {
        return startTimeByRegion.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    private ConcurrentHashMap<String, Long> stopTimesFor(String region) {
        return stopTimeByRegion.computeIfAbsent(region, r -> new ConcurrentHashMap<>());
    }

    public record TrailStatus(boolean logging, Long startLoggingTime, Long stopLoggingTime) {}

    private record MatchedTrail(Trail trail, String region) {}

    /** Input describing a single S3 op for emission. Use the builder for clarity. */
    public record S3EventInput(
            String region,
            String eventName,
            String bucketName,
            String key,
            String accessKeyId,
            String sourceIp,
            String userAgent,
            long bytesIn,
            long bytesOut,
            String errorCode,
            String errorMessage,
            long eventTimeMillis) {

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String region;
            private String eventName;
            private String bucketName;
            private String key;
            private String accessKeyId;
            private String sourceIp;
            private String userAgent;
            private long bytesIn;
            private long bytesOut;
            private String errorCode;
            private String errorMessage;
            private long eventTimeMillis;

            public Builder region(String v) { this.region = v; return this; }
            public Builder eventName(String v) { this.eventName = v; return this; }
            public Builder bucketName(String v) { this.bucketName = v; return this; }
            public Builder key(String v) { this.key = v; return this; }
            public Builder accessKeyId(String v) { this.accessKeyId = v; return this; }
            public Builder sourceIp(String v) { this.sourceIp = v; return this; }
            public Builder userAgent(String v) { this.userAgent = v; return this; }
            public Builder bytesIn(long v) { this.bytesIn = v; return this; }
            public Builder bytesOut(long v) { this.bytesOut = v; return this; }
            public Builder errorCode(String v) { this.errorCode = v; return this; }
            public Builder errorMessage(String v) { this.errorMessage = v; return this; }
            public Builder eventTimeMillis(long v) { this.eventTimeMillis = v; return this; }

            public S3EventInput build() {
                return new S3EventInput(region, eventName, bucketName, key, accessKeyId,
                        sourceIp, userAgent, bytesIn, bytesOut,
                        errorCode, errorMessage, eventTimeMillis);
            }
        }
    }
}
