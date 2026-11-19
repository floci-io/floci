package io.floci.aws.services.s3vectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3vectors.model.VectorBucket;
import io.floci.aws.services.s3vectors.model.VectorIndex;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class S3VectorsServiceTest {

    private static final String REGION = "us-east-1";
    private static final Instant CREATION_TIME = Instant.parse("2026-02-01T00:00:00Z");
    private S3VectorsService service;

    @BeforeEach
    void setUp() {
        StorageFactory factory = inMemoryFactory();
        service = new S3VectorsService(factory, new RegionResolver(REGION, "000000000000"),
                Clock.fixed(CREATION_TIME, ZoneOffset.UTC));
    }

    private static StorageFactory inMemoryFactory() {
        return new StorageFactory(null, null) {
            @Override
            public <V> AccountAwareStorageBackend<V> create(String serviceName, String fileName,
                                                            TypeReference<Map<String, V>> typeReference) {
                return AccountAwareStorageBackend.inMemory("000000000000");
            }
        };
    }

    /**
     * IAM's lookup reads the tags of the vector bucket or the index the ARN names, an index's own and not its bucket's,
     * as a replace leaves them, and nothing for another account's, another Region's or an unknown one.
     */
    @Test
    void storedTagsAreTheTagsOfTheBucketOrTheIndexTheArnNames() {
        VectorBucket bucket = service.createVectorBucket("bucket", null, Map.of("team", "data"), REGION);
        VectorIndex index = service.createIndex("bucket", "index", 3, "float32", "cosine", List.of(), null,
                Map.of("team", "web"), REGION);
        service.replaceIndexTags("bucket", "index", Map.of("team", "ops"), REGION);
        String bucketArn = bucket.getVectorBucketArn();
        String indexArn = index.getIndexArn();

        assertEquals("s3vectors", service.namespace());
        assertEquals(Map.of("team", "data"), service.storedTags(bucketArn));
        assertEquals(Map.of("team", "ops"), service.storedTags(indexArn));
        assertTrue(service.storedTags(bucketArn.replace("000000000000", "111122223333")).isEmpty());
        assertTrue(service.storedTags(indexArn.replace(REGION, "us-west-2")).isEmpty());
        assertTrue(service.storedTags(bucketArn + "/index/missing").isEmpty());
        assertTrue(service.storedTags(bucketArn.replace("bucket/bucket", "bucket/missing")).isEmpty());
        assertTrue(service.storedTags("arn:aws:s3:::bucket").isEmpty());
    }

    /** A bucket stored before storage was account-aware is read in place: the lookup migrates nothing. */
    @Test
    void storedTagsReadAPreAccountAwareBucketInPlace() {
        InMemoryStorage<String, VectorBucket> raw = new InMemoryStorage<>();
        S3VectorsService legacyState = new S3VectorsService(
                new InMemoryStorageFactory("000000000000").withStore("s3vectors.json", raw),
                new RegionResolver(REGION, "000000000000"), Clock.fixed(CREATION_TIME, ZoneOffset.UTC));
        VectorBucket legacy = new VectorBucket("legacy", "arn:aws:s3vectors:us-east-1:000000000000:bucket/legacy",
                Map.of("sseType", "AES256"));
        legacy.setTags(Map.of("team", "data"));
        raw.put(REGION + "::legacy", legacy);

        assertEquals(Map.of("team", "data"), legacyState.storedTags(legacy.getVectorBucketArn()));
        assertEquals(Set.of(REGION + "::legacy"), raw.keys());
    }

    /**
     * A bucket and an index an older Floci stored under names S3 Vectors' rules refuse, which the create calls now
     * refuse too, stay reachable by those names and by their ARNs in the calls that read a name as given.
     */
    @Test
    void aBucketAndAnIndexStoredUnderNamesTheRulesRefuseStayReachable() {
        String bucketArn = "arn:aws:s3vectors:us-east-1:000000000000:bucket/Legacy_Bucket";
        VectorBucket legacy = new VectorBucket("Legacy_Bucket", bucketArn, Map.of("sseType", "AES256"));
        legacy.setCreationTime(CREATION_TIME);
        legacy.getIndexes().put("My_Index",
                new VectorIndex("My_Index", bucketArn + "/index/My_Index", "Legacy_Bucket", 2, "float32", "cosine"));
        S3VectorsService upgraded = storing(legacy);

        assertEquals(new S3VectorsService.ResolvedBucket("Legacy_Bucket", REGION, null),
                upgraded.bucketTarget("Legacy_Bucket", null, REGION));
        assertEquals(new S3VectorsService.ResolvedBucket("Legacy_Bucket", REGION, "000000000000"),
                upgraded.bucketTarget(null, bucketArn, REGION));
        assertEquals(new S3VectorsService.ResolvedIndex("Legacy_Bucket", "My_Index", REGION),
                upgraded.resolveIndexTarget(null, null, bucketArn + "/index/My_Index", REGION));
        assertEquals("My_Index", upgraded.getIndex("Legacy_Bucket", "My_Index", REGION).getIndexName());
        assertEquals(List.of("My_Index"), upgraded.listIndexes("Legacy_Bucket", REGION).stream()
                .map(VectorIndex::getIndexName).toList());
    }

    /**
     * A bucket an older Floci took under a name the create rules now reserve keeps its policy calls, by name and by
     * ARN: they check only that the bucket's name is one of dot-joined labels, as before.
     */
    @Test
    void aBucketStoredUnderAReservedNameKeepsItsPolicyCalls() {
        String bucketArn = "arn:aws:s3vectors:us-east-1:000000000000:bucket/sthree-logs";
        VectorBucket legacy = new VectorBucket("sthree-logs", bucketArn, Map.of("sseType", "AES256"));
        legacy.setCreationTime(CREATION_TIME);
        S3VectorsService upgraded = storing(legacy);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Read\",\"Effect\":\"Allow\"}]}";

        upgraded.putVectorBucketPolicy("sthree-logs", null, policy, REGION);
        assertEquals(policy, upgraded.getVectorBucketPolicy(null, bucketArn, REGION));
        upgraded.deleteVectorBucketPolicy(null, bucketArn, REGION);
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> upgraded.getVectorBucketPolicy("sthree-logs", null, REGION)).getErrorCode());
    }

    /** A service over a store holding {@code bucket}, as an older Floci stored it. */
    private static S3VectorsService storing(VectorBucket bucket) {
        AccountAwareStorageBackend<VectorBucket> stored = AccountAwareStorageBackend.inMemory("000000000000");
        stored.put(REGION + "::" + bucket.getVectorBucketName(), bucket);
        return new S3VectorsService(new InMemoryStorageFactory("000000000000").withStore("s3vectors.json", stored),
                new RegionResolver(REGION, "000000000000"), Clock.fixed(CREATION_TIME, ZoneOffset.UTC));
    }

    @Test
    void persistsCreationTimesConfigurationAndCreationTags() throws Exception {
        Map<String, String> encryption = Map.of("sseType", "aws:kms", "kmsKeyArn", "key");
        VectorBucket bucket = service.createVectorBucket("bucket", encryption, Map.of("old", "value"), REGION);
        VectorIndex index = service.createIndex("bucket", "index", 3, "float32", "cosine", List.of("text"),
                null, Map.of("old", "value"), REGION);
        assertEquals(CREATION_TIME, bucket.getCreationTime());
        assertEquals(CREATION_TIME, index.getCreationTime());

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        VectorBucket restored = mapper.readValue(mapper.writeValueAsString(bucket), VectorBucket.class);
        VectorIndex restoredIndex = restored.getIndexes().get("index");
        assertEquals(bucket.getCreationTime(), restored.getCreationTime());
        assertEquals(index.getCreationTime(), restoredIndex.getCreationTime());
        assertEquals(encryption, restoredIndex.getEncryptionConfiguration());
        assertEquals(List.of("text"), restoredIndex.getNonFilterableMetadataKeys());
        assertEquals(Map.of("old", "value"), restored.getTags());
        assertEquals(Map.of("old", "value"), restoredIndex.getTags());
    }

    @Test
    void persistsCreationTimesConfigurationAndReconciledTags() throws Exception {
        Map<String, String> encryption = Map.of("sseType", "aws:kms", "kmsKeyArn", "key");
        VectorBucket bucket = service.createVectorBucket("bucket", encryption, Map.of("old", "value"), REGION);
        VectorIndex index = service.createIndex("bucket", "index", 3, "float32", "cosine", List.of("text"),
                null, Map.of("old", "value"), REGION);
        service.replaceBucketTags("bucket", Map.of("new", "value"), REGION);
        service.replaceIndexTags("bucket", "index", Map.of(), REGION);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        VectorBucket restored = mapper.readValue(mapper.writeValueAsString(bucket), VectorBucket.class);
        VectorIndex restoredIndex = restored.getIndexes().get("index");
        assertEquals(bucket.getCreationTime(), restored.getCreationTime());
        assertEquals(index.getCreationTime(), restoredIndex.getCreationTime());
        assertEquals(encryption, restoredIndex.getEncryptionConfiguration());
        assertEquals(List.of("text"), restoredIndex.getNonFilterableMetadataKeys());
        assertEquals(Map.of("new", "value"), restored.getTags());
        assertEquals(Map.of(), restoredIndex.getTags());
    }

    @Test
    void indexEncryptionCanOverrideTheBucketDefault() {
        VectorBucket bucket = service.createVectorBucket("bucket", null, REGION);
        assertEquals(Map.of("sseType", "AES256"), bucket.getEncryptionConfiguration());
        Map<String, String> encryption = Map.of("sseType", "aws:kms", "kmsKeyArn", "key");
        VectorIndex index = service.createIndex("bucket", "index", 3, "float32", "cosine", List.of(),
                encryption, Map.of(), REGION);
        assertEquals(encryption, index.getEncryptionConfiguration());
    }

    @Test
    void nonFilterableMetadataKeysAreRejectedEvenInsideLogicalFilters() {
        service.createVectorBucket("bucket", null, REGION);
        service.createIndex("bucket", "index", 3, "float32", "cosine", List.of("text"), REGION);
        for (Object filter : List.of(Map.of("text", "hello"), Map.of("$and", List.of(Map.of("text", "hello"))))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.queryVectors("bucket", "index", List.of(1.0f, 0.0f, 0.0f), filter, 1, REGION));
            assertEquals("ValidationException", error.getErrorCode());
        }
        assertEquals(List.of(), service.queryVectors("bucket", "index", List.of(1.0f, 0.0f, 0.0f),
                Map.of("category", "hello"), 1, REGION));
    }

    @Test
    void rejectsInvalidIndexDimensions() {
        service.createVectorBucket("bucket", null, REGION);
        for (int dimension : List.of(0, 4097)) {
            assertThrows(AwsException.class,
                    () -> service.createIndex("bucket", "index", dimension, "float32", "cosine", List.of(), REGION));
        }
        assertTrue(service.listIndexes("bucket", REGION).isEmpty());
    }

    @Test
    void invalidMetadataKeysFailBeforeCreatingAnIndex() {
        service.createVectorBucket("bucket", null, REGION);
        for (List<String> keys : List.of(List.of(""), List.of(" "), List.of("a".repeat(64)),
                Collections.nCopies(11, "key"), List.of("text", "text"), Collections.<String>singletonList(null))) {
            AwsException error = assertThrows(AwsException.class,
                    () -> service.createIndex("bucket", "index", 3, "float32", "cosine", keys, REGION));
            assertEquals("ValidationException", error.getErrorCode());
        }
        assertTrue(service.listIndexes("bucket", REGION).isEmpty());
    }

    @Test
    void metadataKeyLimitsAreInclusive() {
        service.createVectorBucket("bucket", null, REGION);
        List<String> keys = List.of("a".repeat(63), "b", "c", "d", "e", "f", "g", "h", "i", "j");
        VectorIndex index = service.createIndex("bucket", "index", 3, "float32", "euclidean", keys, REGION);
        assertEquals(keys, index.getNonFilterableMetadataKeys());
    }

    @Test
    void vectorBucketPolicyLifecycleAndPersistenceByNameAndArn() throws Exception {
        VectorBucket bucket = service.createVectorBucket("bucket", null, REGION);
        String policyV1 = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        String policyV2 = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\"}]}";

        AwsException missingInitial = assertThrows(AwsException.class,
                () -> service.getVectorBucketPolicy("bucket", null, REGION));
        assertEquals("NotFoundException", missingInitial.getErrorCode());

        service.putVectorBucketPolicy("bucket", null, policyV1, REGION);
        assertEquals(policyV1, service.getVectorBucketPolicy("bucket", null, REGION));
        assertEquals(policyV1, service.getVectorBucketPolicy(null, bucket.getVectorBucketArn(), REGION));

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        VectorBucket restored = mapper.readValue(mapper.writeValueAsString(bucket), VectorBucket.class);
        assertEquals(policyV1, restored.getPolicy());

        service.putVectorBucketPolicy(null, bucket.getVectorBucketArn(), policyV2, REGION);
        assertEquals(policyV2, service.getVectorBucketPolicy("bucket", null, REGION));

        service.deleteVectorBucketPolicy(null, bucket.getVectorBucketArn(), REGION);
        AwsException afterDelete = assertThrows(AwsException.class,
                () -> service.getVectorBucketPolicy("bucket", null, REGION));
        assertEquals("NotFoundException", afterDelete.getErrorCode());

        // Deleting again when no policy is attached remains idempotent on an existing bucket.
        service.deleteVectorBucketPolicy("bucket", null, REGION);
    }

    /**
     * The policy calls take a bucket ARN of the request's Region and refuse one of another Region or partition, as S3
     * Vectors does (measured on AWS), leaving that bucket's policy alone.
     */
    @Test
    void vectorBucketPolicyByArnIsTakenOnlyInTheRequestsRegion() {
        VectorBucket westBucket = service.createVectorBucket("west-bucket", null, "us-west-2");
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        String westArn = westBucket.getVectorBucketArn();

        for (Runnable call : List.<Runnable>of(() -> service.putVectorBucketPolicy(null, westArn, policy, REGION),
                () -> service.getVectorBucketPolicy(null, westArn, REGION),
                () -> service.deleteVectorBucketPolicy(null, westArn, REGION))) {
            AwsException refused = assertThrows(AwsException.class, call::run);
            assertEquals("ValidationException", refused.getErrorCode());
            assertEquals("Unexpected value for ARN region component", refused.getMessage());
        }
        assertEquals("NotFoundException", assertThrows(AwsException.class,
                () -> service.getVectorBucketPolicy("west-bucket", null, "us-west-2")).getErrorCode());

        service.putVectorBucketPolicy(null, westArn, policy, "us-west-2");
        assertEquals(policy, service.getVectorBucketPolicy(null, westArn, "us-west-2"));

        // A custom Region of a non-commercial deployment is in the deployment's partition: its ARN is taken in that
        // Region, and a commercial Region's ARN is of another partition.
        S3VectorsService chinaService = new S3VectorsService(inMemoryFactory(),
                new RegionResolver("cn-north-1", "000000000000"),
                Clock.fixed(CREATION_TIME, ZoneOffset.UTC));
        VectorBucket customRegionBucket = chinaService.createVectorBucket("custom-bucket", null, "custom-region-1");
        assertTrue(customRegionBucket.getVectorBucketArn().startsWith("arn:aws-cn:s3vectors:custom-region-1:"));
        chinaService.putVectorBucketPolicy(null, customRegionBucket.getVectorBucketArn(), policy, "custom-region-1");
        assertEquals(policy, chinaService.getVectorBucketPolicy("custom-bucket", null, "custom-region-1"));
        assertEquals("Unexpected value for ARN partition component", assertThrows(AwsException.class,
                () -> chinaService.getVectorBucketPolicy(null, westArn, "cn-north-1")).getMessage());
    }

    @Test
    void vectorBucketPolicyRejectsInvalidInputsAndMissingBuckets() {
        VectorBucket bucket = service.createVectorBucket("bucket", null, REGION);
        VectorIndex index = service.createIndex("bucket", "index", 3, "float32", "cosine", List.of(), REGION);
        String validPolicy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        service.putVectorBucketPolicy("bucket", null, validPolicy, REGION);

        // A missing bucket returns NotFoundException (404), and another account's ARN AccessDeniedException (403), as
        // S3 Vectors answers it (measured on AWS); either leaves the local bucket untouched.
        for (Runnable action : List.<Runnable>of(
                () -> service.putVectorBucketPolicy("missing", null, validPolicy, REGION),
                () -> service.getVectorBucketPolicy("missing", null, REGION),
                () -> service.deleteVectorBucketPolicy("missing", null, REGION))) {
            assertRejected("NotFoundException", 404, validPolicy, action);
        }
        String foreignAccountArn = "arn:aws:s3vectors:us-east-1:111122223333:bucket/bucket";
        for (Runnable action : List.<Runnable>of(
                () -> service.putVectorBucketPolicy(null, foreignAccountArn, "{\"Statement\":[]}", REGION),
                () -> service.getVectorBucketPolicy(null, foreignAccountArn, REGION),
                () -> service.deleteVectorBucketPolicy(null, foreignAccountArn, REGION))) {
            assertRejected("AccessDeniedException", 403, validPolicy, action);
        }

        // Neither, both, or invalid bucket names return ValidationException (400).
        String overlongName = "a".repeat(64);
        for (Runnable action : List.<Runnable>of(
                () -> service.putVectorBucketPolicy(null, null, validPolicy, REGION),
                () -> service.putVectorBucketPolicy(" ", "", validPolicy, REGION),
                () -> service.putVectorBucketPolicy("ab", null, validPolicy, REGION),
                () -> service.putVectorBucketPolicy("Upper", null, validPolicy, REGION),
                () -> service.putVectorBucketPolicy(overlongName, null, validPolicy, REGION),
                () -> service.putVectorBucketPolicy("bucket", bucket.getVectorBucketArn(), validPolicy, REGION),
                () -> service.getVectorBucketPolicy(null, null, REGION),
                () -> service.getVectorBucketPolicy("ab", null, REGION),
                () -> service.getVectorBucketPolicy(overlongName, null, REGION),
                () -> service.getVectorBucketPolicy("bucket", bucket.getVectorBucketArn(), REGION),
                () -> service.deleteVectorBucketPolicy(null, null, REGION),
                () -> service.deleteVectorBucketPolicy("ab", null, REGION),
                () -> service.deleteVectorBucketPolicy(overlongName, null, REGION),
                () -> service.deleteVectorBucketPolicy("bucket", bucket.getVectorBucketArn(), REGION))) {
            assertRejected("ValidationException", 400, validPolicy, action);
        }

        // Malformed, regionless, wrong-resource, or partition-mismatched ARNs return ValidationException (400).
        for (String invalidArn : List.of(
                "not-an-arn",
                "arn:aws:s3:::bucket",
                "arn:aws:s3vectors:us-east-1:000000000000:bucket/",
                "arn:aws:s3vectors:us-east-1:000000000000:bucket/ab",
                "arn:aws:s3vectors::000000000000:bucket/bucket",
                "arn:aws:s3vectors:us-east-1::bucket/bucket",
                "arn:aws-cn:s3vectors:us-east-1:000000000000:bucket/bucket",
                index.getIndexArn())) {
            assertRejected("ValidationException", 400, validPolicy,
                    () -> service.putVectorBucketPolicy(null, invalidArn, validPolicy, REGION));
        }

        // Non-JSON or non-object policy documents return ValidationException (400) without mutating stored policy.
        for (String invalidPolicy : Arrays.asList(null, "", "   ", "not-json", "[]", "\"string\"", "{} trailing")) {
            assertRejected("ValidationException", 400, validPolicy,
                    () -> service.putVectorBucketPolicy("bucket", null, invalidPolicy, REGION));
        }
    }

    private void assertRejected(String errorCode, int status, String expectedPolicy, Runnable action) {
        AwsException error = assertThrows(AwsException.class, action::run);
        assertEquals(errorCode, error.getErrorCode());
        assertEquals(status, error.getHttpStatus());
        assertEquals(expectedPolicy, service.getVectorBucketPolicy("bucket", null, REGION));
    }
}
