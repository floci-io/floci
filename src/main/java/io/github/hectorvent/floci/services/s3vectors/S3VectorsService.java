package io.github.hectorvent.floci.services.s3vectors;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.s3vectors.model.VectorBucket;
import io.github.hectorvent.floci.services.s3vectors.model.VectorIndex;
import io.github.hectorvent.floci.services.s3vectors.model.VectorData;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@ApplicationScoped
public class S3VectorsService {
    private static final Logger LOG = Logger.getLogger(S3VectorsService.class);

    private final StorageBackend<String, VectorBucket> store;
    private final RegionResolver regionResolver;

    @Inject
    public S3VectorsService(StorageFactory factory, RegionResolver regionResolver) {
        this.store = factory.create("s3vectors", "s3vectors.json",
                new TypeReference<Map<String, VectorBucket>>() {});
        this.regionResolver = regionResolver;
    }

    private String buildStorageKey(String region, String bucketName) {
        return region + "::" + bucketName;
    }

    private String buildBucketArn(String region, String bucketName) {
        return AwsArnUtils.Arn.of("s3vectors", region, regionResolver.getAccountId(), "bucket/" + bucketName).toString();
    }

    private String buildIndexArn(String region, String bucketName, String indexName) {
        return buildBucketArn(region, bucketName) + "/index/" + indexName;
    }

    public VectorBucket createVectorBucket(String bucketName, Object encryptionConfiguration, String region) {
        String key = buildStorageKey(region, bucketName);
        if (store.get(key).isPresent()) {
            throw new AwsException("ConflictException", "The vector bucket " + bucketName + " already exists.", 409);
        }
        String arn = buildBucketArn(region, bucketName);
        VectorBucket bucket = new VectorBucket(bucketName, arn, encryptionConfiguration);
        store.put(key, bucket);
        LOG.infov("Created Vector Bucket: {0} in region {1}", bucketName, region);
        return bucket;
    }

    public VectorBucket getVectorBucket(String bucketName, String region) {
        String key = buildStorageKey(region, bucketName);
        return store.get(key).orElseThrow(() ->
                new AwsException("NotFoundException", "The vector bucket " + bucketName + " does not exist.", 404));
    }

    public List<VectorBucket> listVectorBuckets(String region) {
        return store.scan(k -> k.startsWith(region + "::")).stream()
                .sorted(Comparator.comparing(VectorBucket::getVectorBucketName))
                .collect(Collectors.toList());
    }

    public void deleteVectorBucket(String bucketName, String region) {
        String key = buildStorageKey(region, bucketName);
        VectorBucket bucket = store.get(key).orElseThrow(() ->
                new AwsException("NotFoundException", "The vector bucket " + bucketName + " does not exist.", 404));

        if (!bucket.getIndexes().isEmpty()) {
            throw new AwsException("ConflictException", "The vector bucket " + bucketName + " is not empty.", 409);
        }
        store.delete(key);
        LOG.infov("Deleted Vector Bucket: {0}", bucketName);
    }

    public VectorIndex createIndex(String bucketName, String indexName, int dimension, String dataType,
                                   String distanceMetric, List<String> nonFilterableMetadataKeys, String region) {
        if (!"float32".equals(dataType)) {
            throw new AwsException("ValidationException", "Unsupported dataType: " + dataType + ". Only float32 is supported.", 400);
        }
        if (!"cosine".equals(distanceMetric) && !"euclidean".equals(distanceMetric)) {
            throw new AwsException("ValidationException", "Unsupported distanceMetric: " + distanceMetric + ". Supported metrics are cosine and euclidean.", 400);
        }

        VectorBucket bucket = getVectorBucket(bucketName, region);
        if (bucket.getIndexes().containsKey(indexName)) {
            throw new AwsException("ConflictException", "The index " + indexName + " already exists.", 409);
        }

        String arn = buildIndexArn(region, bucketName, indexName);
        VectorIndex index = new VectorIndex(indexName, arn, bucketName, dimension, dataType, distanceMetric);
        bucket.getIndexes().put(indexName, index);
        store.put(buildStorageKey(region, bucketName), bucket);
        LOG.infov("Created Index: {0} in bucket {1}", indexName, bucketName);
        return index;
    }

    public VectorIndex getIndex(String bucketName, String indexName, String region) {
        VectorBucket bucket = getVectorBucket(bucketName, region);
        VectorIndex index = bucket.getIndexes().get(indexName);
        if (index == null) {
            throw new AwsException("NotFoundException", "The index " + indexName + " does not exist in bucket " + bucketName + ".", 404);
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
        VectorBucket bucket = getVectorBucket(bucketName, region);
        if (!bucket.getIndexes().containsKey(indexName)) {
            throw new AwsException("NotFoundException", "The index " + indexName + " does not exist.", 404);
        }
        bucket.getIndexes().remove(indexName);
        store.put(buildStorageKey(region, bucketName), bucket);
        LOG.infov("Deleted Index: {0}", indexName);
    }

    public void putVectors(String bucketName, String indexName, List<VectorData> vectors, String region) {
        VectorBucket bucket = getVectorBucket(bucketName, region);
        VectorIndex index = getIndex(bucketName, indexName, region);

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

    public void deleteVectors(String bucketName, String indexName, List<String> keys, String region) {
        VectorBucket bucket = getVectorBucket(bucketName, region);
        VectorIndex index = getIndex(bucketName, indexName, region);
        for (String key : keys) {
            index.getVectors().remove(key);
        }
        store.put(buildStorageKey(region, bucketName), bucket);
        LOG.infov("Deleted {0} vectors from index {1}", keys.size(), indexName);
    }

    public List<QueryResult> queryVectors(String bucketName, String indexName, List<Float> queryVector, int topK, String region) {
        VectorIndex index = getIndex(bucketName, indexName, region);
        if (queryVector.size() != index.getDimension()) {
            throw new AwsException("ValidationException",
                    "Query vector dimension " + queryVector.size() + " does not match index dimension " + index.getDimension(), 400);
        }

        String metric = index.getDistanceMetric() != null ? index.getDistanceMetric().toLowerCase() : "cosine";
        List<QueryResult> results = new ArrayList<>();

        for (VectorData v : index.getVectors().values()) {
            double distance = 0.0;
            switch (metric) {
                case "euclidean":
                    distance = calculateEuclideanDistance(queryVector, v.getData());
                    break;
                case "cosine":
                default:
                    distance = calculateCosineSimilarity(queryVector, v.getData());
                    break;
            }
            results.add(new QueryResult(v, distance));
        }

        // Sorting:
        // For Euclidean distance, smaller distance is closer (ascending)
        // For Cosine similarity, larger score is closer (descending)
        if ("euclidean".equals(metric)) {
            results.sort(Comparator.comparingDouble(QueryResult::getDistance));
        } else {
            results.sort((r1, r2) -> Double.compare(r2.getDistance(), r1.getDistance()));
        }

        return results.stream().limit(topK).collect(Collectors.toList());
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

    private double calculateEuclideanDistance(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) return Double.MAX_VALUE;
        double sum = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            double diff = v1.get(i) - v2.get(i);
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    private double calculateDotProduct(List<Float> v1, List<Float> v2) {
        if (v1 == null || v2 == null || v1.size() != v2.size()) return 0.0;
        double dotProduct = 0.0;
        for (int i = 0; i < v1.size(); i++) {
            dotProduct += v1.get(i) * v2.get(i);
        }
        return dotProduct;
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
