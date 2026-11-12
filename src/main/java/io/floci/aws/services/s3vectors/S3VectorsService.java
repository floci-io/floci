package io.floci.aws.services.s3vectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3vectors.model.VectorBucket;
import io.floci.aws.services.s3vectors.model.VectorData;
import io.floci.aws.services.s3vectors.model.VectorIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class S3VectorsService {
    private static final Logger LOG = Logger.getLogger(S3VectorsService.class);
    private static final ObjectReader STRICT_JSON_READER =
            new ObjectMapper().reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final AccountAwareStorageBackend<VectorBucket> store;
    private final RegionResolver regionResolver;
    private final Clock clock;
    // Bounded, stable monitors also serialize deletion and recreation of the same bucket.
    private final Object[] bucketLocks = new Object[256];

    @Inject
    public S3VectorsService(StorageFactory factory, RegionResolver regionResolver) {
        this(factory, regionResolver, Clock.systemUTC());
    }

    S3VectorsService(StorageFactory factory, RegionResolver regionResolver, Clock clock) {
        this.store = factory.create("s3vectors", "s3vectors.json",
                new TypeReference<Map<String, VectorBucket>>() {});
        this.regionResolver = regionResolver;
        this.clock = clock;
        for (int i = 0; i < bucketLocks.length; i++) {
            bucketLocks[i] = new Object();
        }
    }

    private String buildStorageKey(String region, String bucketName) {
        return region + "::" + bucketName;
    }

    private Object lockFor(String bucketName, String region) {
        String key = store.accountId() + "/" + buildStorageKey(region, bucketName);
        return bucketLocks[Math.floorMod(key.hashCode(), bucketLocks.length)];
    }

    private String buildBucketArn(String region, String bucketName) {
        return regionResolver.buildArn("s3vectors", region, "bucket/" + bucketName);
    }

    private String buildIndexArn(String region, String bucketName, String indexName) {
        return buildBucketArn(region, bucketName) + "/index/" + indexName;
    }

    public VectorBucket createVectorBucket(String bucketName, Object encryptionConfiguration, String region) {
        return createVectorBucket(bucketName, encryptionConfiguration, Map.of(), region);
    }

    public VectorBucket createVectorBucket(String bucketName, Object encryptionConfiguration,
                                          Map<String, String> tags, String region) {
        synchronized (lockFor(bucketName, region)) {
            String key = buildStorageKey(region, bucketName);
            if (store.get(key).isPresent()) {
                throw new AwsException("ConflictException", "The vector bucket " + bucketName + " already exists.", 409);
            }
            String arn = buildBucketArn(region, bucketName);
            VectorBucket bucket = new VectorBucket(bucketName, arn,
                    encryptionConfiguration != null ? encryptionConfiguration : Map.of("sseType", "AES256"));
            bucket.setCreationTime(clock.instant());
            bucket.setTags(new ConcurrentHashMap<>(tags));
            store.put(key, bucket);
            LOG.infov("Created Vector Bucket: {0} in region {1}", bucketName, region);
            return bucket;
        }
    }

    public VectorBucket getVectorBucket(String bucketName, String region) {
        return findVectorBucket(bucketName, region).orElseThrow(() ->
                new AwsException("NotFoundException", "The vector bucket " + bucketName + " does not exist.", 404));
    }

    public List<VectorBucket> listVectorBuckets(String region) {
        String prefix = region + "::";
        return store.keys().stream().filter(key -> key.startsWith(prefix))
                .flatMap(key -> findVectorBucket(key.substring(prefix.length()), region).stream())
                .sorted(Comparator.comparing(VectorBucket::getVectorBucketName))
                .collect(Collectors.toList());
    }

    private Optional<VectorBucket> findVectorBucket(String bucketName, String region) {
        synchronized (lockFor(bucketName, region)) {
            String key = buildStorageKey(region, bucketName);
            return store.get(key).map(bucket -> {
                if (backfillLegacyMetadata(bucket)) {
                    store.put(key, bucket);
                }
                return bucket;
            });
        }
    }

    private boolean backfillLegacyMetadata(VectorBucket bucket) {
        boolean changed = false;
        Instant migrationTime = clock.instant();
        if (bucket.getCreationTime() == null) {
            bucket.setCreationTime(migrationTime);
            changed = true;
        }
        if (bucket.getEncryptionConfiguration() == null) {
            bucket.setEncryptionConfiguration(Map.of("sseType", "AES256"));
            changed = true;
        }
        for (VectorIndex index : bucket.getIndexes().values()) {
            if (index.getCreationTime() == null) {
                index.setCreationTime(migrationTime);
                changed = true;
            }
            if (index.getEncryptionConfiguration() == null) {
                index.setEncryptionConfiguration(bucket.getEncryptionConfiguration());
                changed = true;
            }
        }
        return changed;
    }

    public void deleteVectorBucket(String bucketName, String region) {
        synchronized (lockFor(bucketName, region)) {
            String key = buildStorageKey(region, bucketName);
            VectorBucket bucket = store.get(key).orElseThrow(() ->
                    new AwsException("NotFoundException", "The vector bucket " + bucketName + " does not exist.", 404));

            if (!bucket.getIndexes().isEmpty()) {
                throw new AwsException("ConflictException", "The vector bucket " + bucketName + " is not empty.", 409);
            }
            store.delete(key);
            LOG.infov("Deleted Vector Bucket: {0}", bucketName);
        }
    }

    public void putVectorBucketPolicy(String bucketName, String bucketArn, String policy, String region) {
        ResolvedBucket target = resolveBucket(bucketName, bucketArn, region);
        validatePolicyDocument(policy);
        synchronized (lockFor(target.bucketName(), target.region())) {
            VectorBucket bucket = getVectorBucket(target.bucketName(), target.region());
            bucket.setPolicy(policy);
            store.put(buildStorageKey(target.region(), target.bucketName()), bucket);
            LOG.infov("Put policy on Vector Bucket: {0} in region {1}", target.bucketName(), target.region());
        }
    }

    public String getVectorBucketPolicy(String bucketName, String bucketArn, String region) {
        ResolvedBucket target = resolveBucket(bucketName, bucketArn, region);
        synchronized (lockFor(target.bucketName(), target.region())) {
            VectorBucket bucket = getVectorBucket(target.bucketName(), target.region());
            String policy = bucket.getPolicy();
            if (policy == null) {
                throw new AwsException("NotFoundException",
                        "The vector bucket policy for " + target.bucketName() + " does not exist.", 404);
            }
            return policy;
        }
    }

    public void deleteVectorBucketPolicy(String bucketName, String bucketArn, String region) {
        ResolvedBucket target = resolveBucket(bucketName, bucketArn, region);
        synchronized (lockFor(target.bucketName(), target.region())) {
            VectorBucket bucket = getVectorBucket(target.bucketName(), target.region());
            if (bucket.getPolicy() != null) {
                bucket.setPolicy(null);
                store.put(buildStorageKey(target.region(), target.bucketName()), bucket);
            }
            LOG.infov("Deleted policy from Vector Bucket: {0} in region {1}", target.bucketName(), target.region());
        }
    }

    private ResolvedBucket resolveBucket(String bucketName, String bucketArn, String region) {
        ResolvedBucket resolved = resolveBucket(bucketName, bucketArn, region,
                regionResolver::partitionForRegion, "ValidationException");
        if (resolved.accountId() != null && !store.accountId().equals(resolved.accountId())) {
            throw new AwsException("NotFoundException",
                    "The vector bucket " + resolved.bucketName() + " does not exist.", 404);
        }
        return resolved;
    }

    public static ResolvedBucket resolveBucket(String bucketName, String bucketArn,
                                               String defaultRegion, Function<String, String> partitionForRegion,
                                               String errorCode) {
        if ((bucketName == null) == (bucketArn == null)) {
            throw new AwsException(errorCode,
                    "Specify exactly one of VectorBucketName or VectorBucketArn.", 400);
        }
        if (bucketArn == null) {
            return new ResolvedBucket(validateName(bucketName, "VectorBucketName", false, errorCode),
                    defaultRegion, null);
        }
        String[] parts = arnParts(bucketArn, false, errorCode);
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(bucketArn);
        String name = validateName(parts[1], "VectorBucketArn bucket name", false, errorCode);
        if (parsed.region().isEmpty() || parsed.accountId().isEmpty()
                || !partitionForRegion.apply(parsed.region()).equals(parsed.partition())) {
            throw new AwsException(errorCode, "Invalid S3 Vectors ARN: " + bucketArn, 400);
        }
        return new ResolvedBucket(name, parsed.region(), parsed.accountId());
    }

    public static String validateName(String name, String property, boolean index, String errorCode) {
        String pattern = index ? "[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]" : "[a-z0-9][a-z0-9-]{1,61}[a-z0-9]";
        if (name == null || !name.matches(pattern)) {
            throw new AwsException(errorCode,
                    property + " must be a valid lowercase name of 3 to 63 characters.", 400);
        }
        return name;
    }

    public static String[] arnParts(String arn, boolean index, String errorCode) {
        try {
            AwsArnUtils.Arn parsed = AwsArnUtils.parse(arn);
            String[] parts = parsed.resource().split("/", -1);
            if (!"s3vectors".equals(parsed.service()) || parts.length != (index ? 4 : 2)
                    || !"bucket".equals(parts[0]) || parts[1].isBlank()
                    || (index && (!"index".equals(parts[2]) || parts[3].isBlank()))) {
                throw new AwsException(errorCode, "Invalid S3 Vectors ARN: " + arn, 400);
            }
            return parts;
        } catch (IllegalArgumentException e) {
            throw new AwsException(errorCode, "Invalid S3 Vectors ARN: " + arn, 400);
        }
    }

    private static void validatePolicyDocument(String policy) {
        if (policy == null || policy.isBlank()) {
            throw new AwsException("ValidationException", "Policy must be a non-empty JSON object.", 400);
        }
        try {
            JsonNode parsed = STRICT_JSON_READER.readTree(policy);
            if (parsed == null || !parsed.isObject()) {
                throw new AwsException("ValidationException", "Policy must be a valid JSON object.", 400);
            }
        } catch (IOException e) {
            throw new AwsException("ValidationException", "Policy must be a valid JSON object.", 400);
        }
    }

    public record ResolvedBucket(String bucketName, String region, String accountId) {}

    public VectorIndex createIndex(String bucketName, String indexName, int dimension, String dataType,
                                   String distanceMetric, List<String> nonFilterableMetadataKeys, String region) {
        return createIndex(bucketName, indexName, dimension, dataType, distanceMetric,
                nonFilterableMetadataKeys, null, Map.of(), region);
    }

    public VectorIndex createIndex(String bucketName, String indexName, int dimension, String dataType,
                                   String distanceMetric, List<String> nonFilterableMetadataKeys,
                                   Object encryptionConfiguration, Map<String, String> tags, String region) {
        if (dimension < 1 || dimension > 4096) {
            throw new AwsException("ValidationException", "Dimension must be between 1 and 4096.", 400);
        }
        if (!"float32".equals(dataType)) {
            throw new AwsException("ValidationException", "Unsupported dataType: " + dataType + ". Only float32 is supported.", 400);
        }
        if (!"cosine".equals(distanceMetric) && !"euclidean".equals(distanceMetric)) {
            throw new AwsException("ValidationException", "Unsupported distanceMetric: " + distanceMetric + ". Supported metrics are cosine and euclidean.", 400);
        }
        if (nonFilterableMetadataKeys == null || nonFilterableMetadataKeys.size() > 10
                || nonFilterableMetadataKeys.stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 63)
                || new HashSet<>(nonFilterableMetadataKeys).size() != nonFilterableMetadataKeys.size()) {
            throw new AwsException("ValidationException",
                    "NonFilterableMetadataKeys must contain at most 10 unique keys of 1 to 63 characters.", 400);
        }

        synchronized (lockFor(bucketName, region)) {
            VectorBucket bucket = getVectorBucket(bucketName, region);
            if (bucket.getIndexes().containsKey(indexName)) {
                throw new AwsException("ConflictException", "The index " + indexName + " already exists.", 409);
            }

            String arn = buildIndexArn(region, bucketName, indexName);
            VectorIndex index = new VectorIndex(indexName, arn, bucketName, dimension, dataType, distanceMetric);
            index.setCreationTime(clock.instant());
            index.setNonFilterableMetadataKeys(List.copyOf(nonFilterableMetadataKeys));
            index.setEncryptionConfiguration(encryptionConfiguration != null
                    ? encryptionConfiguration : bucket.getEncryptionConfiguration());
            index.setTags(new ConcurrentHashMap<>(tags));
            bucket.getIndexes().put(indexName, index);
            store.put(buildStorageKey(region, bucketName), bucket);
            LOG.infov("Created Index: {0} in bucket {1}", indexName, bucketName);
            return index;
        }
    }

    public VectorIndex getIndex(String bucketName, String indexName, String region) {
        return getIndex(getVectorBucket(bucketName, region), indexName);
    }

    private VectorIndex getIndex(VectorBucket bucket, String indexName) {
        VectorIndex index = bucket.getIndexes().get(indexName);
        if (index == null) {
            throw new AwsException("NotFoundException",
                    "The index " + indexName + " does not exist in bucket " + bucket.getVectorBucketName() + ".", 404);
        }
        return index;
    }

    public List<VectorIndex> listIndexes(String bucketName, String region) {
        VectorBucket bucket = getVectorBucket(bucketName, region);
        return bucket.getIndexes().values().stream()
                .sorted(Comparator.comparing(VectorIndex::getIndexName))
                .collect(Collectors.toList());
    }

    public void deleteIndex(String bucketName, String indexName, String region) {
        synchronized (lockFor(bucketName, region)) {
            VectorBucket bucket = getVectorBucket(bucketName, region);
            if (!bucket.getIndexes().containsKey(indexName)) {
                throw new AwsException("NotFoundException", "The index " + indexName + " does not exist.", 404);
            }
            bucket.getIndexes().remove(indexName);
            store.put(buildStorageKey(region, bucketName), bucket);
            LOG.infov("Deleted Index: {0}", indexName);
        }
    }

    public void replaceBucketTags(String bucketName, Map<String, String> tags, String region) {
        synchronized (lockFor(bucketName, region)) {
            VectorBucket bucket = getVectorBucket(bucketName, region);
            bucket.setTags(new ConcurrentHashMap<>(tags));
            store.put(buildStorageKey(region, bucketName), bucket);
        }
    }

    public void replaceIndexTags(String bucketName, String indexName, Map<String, String> tags, String region) {
        synchronized (lockFor(bucketName, region)) {
            VectorBucket bucket = getVectorBucket(bucketName, region);
            VectorIndex index = getIndex(bucket, indexName);
            index.setTags(new ConcurrentHashMap<>(tags));
            store.put(buildStorageKey(region, bucketName), bucket);
        }
    }

    public void putVectors(String bucketName, String indexName, List<VectorData> vectors, String region) {
        synchronized (lockFor(bucketName, region)) {
            VectorBucket bucket = getVectorBucket(bucketName, region);
            VectorIndex index = getIndex(bucket, indexName);

            for (VectorData v : vectors) {
                if (v.getData() != null && v.getData().size() != index.getDimension()) {
                    throw new AwsException("ValidationException",
                            "Vector dimension " + v.getData().size() + " does not match index dimension " + index.getDimension(), 400);
                }
                index.getVectors().put(v.getKey(), v);
            }
            store.put(buildStorageKey(region, bucketName), bucket);
            LOG.infov("Put {0} vectors into index {1}", vectors.size(), indexName);
        }
    }

    public List<VectorData> getVectors(String bucketName, String indexName, List<String> keys, String region) {
        VectorIndex index = getIndex(bucketName, indexName, region);
        List<VectorData> result = new ArrayList<>();
        for (String key : keys) {
            VectorData v = index.getVectors().get(key);
            if (v != null) {
                result.add(v);
            }
        }
        return result;
    }

    /**
     * Lists the vectors stored in an index with cursor-based pagination, ordered by key.
     * The index may be identified by {@code bucketName}/{@code indexName} or by {@code indexArn} alone,
     * matching the ListVectors input contract.
     */
    public ListVectorsResult listVectors(String bucketName, String indexName, String indexArn,
                                          int maxResults, String nextToken, String region) {
        VectorIndex index = resolveIndex(bucketName, indexName, indexArn, region);
        int limit = maxResults > 0 ? Math.min(maxResults, 1000) : 500;
        String lastKey = decodeToken(nextToken);

        List<VectorData> allVectors = index.getVectors().values().stream()
                .sorted(Comparator.comparing(VectorData::getKey))
                .collect(Collectors.toList());

        int startIndex = 0;
        if (lastKey != null) {
            for (int i = 0; i < allVectors.size(); i++) {
                if (allVectors.get(i).getKey().compareTo(lastKey) > 0) {
                    startIndex = i;
                    break;
                }
                if (i == allVectors.size() - 1) {
                    startIndex = allVectors.size();
                }
            }
        }

        List<VectorData> page = allVectors.stream()
                .skip(startIndex)
                .limit(limit)
                .collect(Collectors.toList());

        String newNextToken = null;
        if (startIndex + limit < allVectors.size() && !page.isEmpty()) {
            newNextToken = encodeToken(page.get(page.size() - 1).getKey());
        }

        return new ListVectorsResult(page, newNextToken);
    }

    /**
     * Resolves an index by name or by ARN (format: {@code <bucketArn>/index/<indexName>}), matching
     * ListVectors' "vectorBucketName + indexName, or indexArn alone" input contract.
     */
    private VectorIndex resolveIndex(String bucketName, String indexName, String indexArn, String region) {
        if (indexName != null && !indexName.isBlank()) {
            return getIndex(bucketName, indexName, region);
        }
        if (indexArn == null || indexArn.isBlank()) {
            throw new AwsException("ValidationException", "Either indexName or indexArn is required.", 400);
        }
        String[] parts = arnParts(indexArn, true, "ValidationException");
        AwsArnUtils.Arn parsed = AwsArnUtils.parse(indexArn);
        // The ARN carries its own region — an index in one region must resolve there regardless
        // of which region the ListVectors request itself was signed for.
        String arnRegion = parsed.region().isEmpty() ? region : parsed.region();
        return getIndex(parts[1], parts[3], arnRegion);
    }

    /**
     * Encodes a pagination cursor as an opaque Base64 token over the raw key — no JSON wrapping, so
     * keys containing quotes or other JSON-significant characters round-trip correctly.
     */
    private String encodeToken(String lastKey) {
        if (lastKey == null) {
            return null;
        }
        return Base64.getEncoder().encodeToString(lastKey.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AwsException("InvalidNextTokenException", "Invalid pagination token", 400);
        }
    }

    public record ListVectorsResult(List<VectorData> vectors, String nextToken) {}

    public void deleteVectors(String bucketName, String indexName, List<String> keys, String region) {
        synchronized (lockFor(bucketName, region)) {
            VectorBucket bucket = getVectorBucket(bucketName, region);
            VectorIndex index = getIndex(bucket, indexName);
            for (String key : keys) {
                index.getVectors().remove(key);
            }
            store.put(buildStorageKey(region, bucketName), bucket);
            LOG.infov("Deleted {0} vectors from index {1}", keys.size(), indexName);
        }
    }

    public List<QueryResult> queryVectors(String bucketName, String indexName, List<Float> queryVector,
                                          Object filter, int topK, String region) {
        VectorIndex index = getIndex(bucketName, indexName, region);
        if (queryVector.size() != index.getDimension()) {
            throw new AwsException("ValidationException",
                    "Query vector dimension " + queryVector.size() + " does not match index dimension " + index.getDimension(), 400);
        }

        S3VectorsMetadataFilter metadataFilter = S3VectorsMetadataFilter.compile(filter);
        validateFilterKeys(filter, index.getNonFilterableMetadataKeys());
        String metric = index.getDistanceMetric() != null ? index.getDistanceMetric().toLowerCase() : "cosine";
        List<QueryResult> results = new ArrayList<>();

        for (VectorData v : index.getVectors().values()) {
            if (!metadataFilter.matches(v.getMetadata())) {
                continue;
            }
            double distance = 0.0;
            switch (metric) {
                case "euclidean":
                    distance = calculateEuclideanDistance(queryVector, v.getData());
                    break;
                case "cosine":
                default:
                    distance = calculateCosineDistance(queryVector, v.getData());
                    break;
            }
            results.add(new QueryResult(v, distance));
        }

        results.sort(Comparator.comparingDouble(QueryResult::getDistance));

        return results.stream().limit(topK).collect(Collectors.toList());
    }

    private static void validateFilterKeys(Object filter, List<String> nonFilterableKeys) {
        if (!(filter instanceof Map<?, ?> clauses)) {
            return;
        }
        for (Map.Entry<?, ?> clause : clauses.entrySet()) {
            if ("$and".equals(clause.getKey()) || "$or".equals(clause.getKey())) {
                if (clause.getValue() instanceof List<?> children) {
                    for (Object child : children) {
                        validateFilterKeys(child, nonFilterableKeys);
                    }
                }
            } else if (nonFilterableKeys.contains(clause.getKey())) {
                throw new AwsException("ValidationException", "Cannot filter on non-filterable metadata key: " + clause.getKey(), 400);
            }
        }
    }

    private double calculateCosineSimilarity(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) return 0.0;
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            float a = v1.get(i);
            float b = v2.get(i);
            dotProduct += a * b;
            normA += a * a;
            normB += b * b;
        }
        if (normA == 0.0 || normB == 0.0) return 0.0;
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private double calculateCosineDistance(List<Float> v1, List<Float> v2) {
        double similarity = calculateCosineSimilarity(v1, v2);
        return 1.0 - Math.max(-1.0, Math.min(1.0, similarity));
    }

    private double calculateEuclideanDistance(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) return Double.MAX_VALUE;
        double sum = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            double diff = v1.get(i) - v2.get(i);
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    public static class QueryResult {
        private final VectorData vector;
        private final double distance;

        public QueryResult(VectorData vector, double distance) {
            this.vector = vector;
            this.distance = distance;
        }

        public VectorData getVector() { return vector; }
        public double getDistance() { return distance; }
    }
}
