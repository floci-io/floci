package io.github.hectorvent.floci.services.s3vectors;

import io.github.hectorvent.floci.services.s3vectors.model.VectorIndex;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.Index;
import software.amazon.awssdk.services.s3vectors.model.ValidationException;
import software.amazon.awssdk.services.s3vectors.model.VectorBucket;
import software.amazon.awssdk.services.s3vectors.model.VectorData;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class S3VectorsSdkIntegrationTest {

    @TestHTTPResource
    URI endpoint;

    @Inject
    S3VectorsService service;

    @Test
    void sdkCreatedResourcesPreserveConfigurationAndRejectNonFilterableQueries() {
        String name = "sdk-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3VectorsClient vectors = vectors("us-east-1")) {
            vectors.createVectorBucket(request -> request.vectorBucketName(name)
                    .encryptionConfiguration(encryption -> encryption.sseType("AES256"))
                    .tags(Map.of("team", "search")));
            try {
                vectors.createIndex(request -> request.vectorBucketName(name).indexName("index")
                        .dataType("float32").dimension(3).distanceMetric("cosine")
                        .metadataConfiguration(metadata -> metadata.nonFilterableMetadataKeys("text"))
                        .tags(Map.of("team", "search")));
                Index index = vectors.getIndex(request -> request.vectorBucketName(name).indexName("index")).index();
                VectorBucket bucket = vectors.getVectorBucket(request -> request.vectorBucketName(name)).vectorBucket();
                assertNotNull(bucket.creationTime());
                assertNotNull(index.creationTime());
                assertEquals(service.getVectorBucket(name, "us-east-1").getCreationTime().toEpochMilli(),
                        bucket.creationTime().toEpochMilli());
                assertEquals(service.getIndex(name, "index", "us-east-1").getCreationTime().toEpochMilli(),
                        index.creationTime().toEpochMilli());
                assertEquals(bucket.creationTime(), vectors.listVectorBuckets(request -> request.maxResults(100))
                        .vectorBuckets().stream()
                        .filter(item -> name.equals(item.vectorBucketName())).findFirst().orElseThrow().creationTime());
                assertEquals(index.creationTime(), vectors.listIndexes(request -> request.vectorBucketName(name))
                        .indexes().getFirst().creationTime());
                assertEquals(List.of("text"), index.metadataConfiguration().nonFilterableMetadataKeys());
                assertEquals("AES256", index.encryptionConfiguration().sseTypeAsString());
                assertEquals(Map.of("team", "search"), service.getVectorBucket(name, "us-east-1").getTags());
                assertEquals(Map.of("team", "search"), service.getIndex(name, "index", "us-east-1").getTags());
                assertThrows(ValidationException.class, () -> vectors.queryVectors(request -> request
                        .vectorBucketName(name).indexName("index").topK(1)
                        .queryVector(VectorData.builder().float32(1.0f, 0.0f, 0.0f).build())
                        .filter(Document.fromMap(Map.of("text", Document.fromString("hello"))))));
            } finally {
                for (String index : service.listIndexes(name, "us-east-1").stream()
                        .map(VectorIndex::getIndexName).toList()) {
                    vectors.deleteIndex(request -> request.vectorBucketName(name).indexName(index));
                }
                vectors.deleteVectorBucket(request -> request.vectorBucketName(name));
            }
        }
    }

    static Stream<Arguments> invalidIndexSettings() {
        return Stream.of(
                Arguments.of("float64", "cosine", List.of(), "dataType"),
                Arguments.of("float32", "manhattan", List.of(), "distanceMetric"),
                Arguments.of("float32", "cosine", List.of(""), "NonFilterableMetadataKeys"),
                Arguments.of("float32", "cosine", List.of("   "), "NonFilterableMetadataKeys"),
                Arguments.of("float32", "cosine", Collections.singletonList(null), "NonFilterableMetadataKeys"),
                Arguments.of("float32", "cosine", List.of("a".repeat(64)), "NonFilterableMetadataKeys"),
                Arguments.of("float32", "cosine", Collections.nCopies(11, "key"), "NonFilterableMetadataKeys"));
    }

    @ParameterizedTest
    @MethodSource("invalidIndexSettings")
    void invalidIndexSettingsReturnValidationErrors(String dataType, String distanceMetric,
                                                    List<String> keys, String errorMember) {
        String name = "invalid-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3VectorsClient vectors = vectors("us-east-1")) {
            vectors.createVectorBucket(request -> request.vectorBucketName(name));
            try {
                ValidationException error = assertThrows(ValidationException.class,
                        () -> vectors.createIndex(request -> request.vectorBucketName(name).indexName("index")
                                .dimension(3).dataType(dataType).distanceMetric(distanceMetric)
                                .metadataConfiguration(metadata -> metadata.nonFilterableMetadataKeys(keys))));
                assertEquals(400, error.statusCode());
                assertTrue(error.getMessage().contains(errorMember), error.getMessage());
                assertTrue(vectors.listIndexes(request -> request.vectorBucketName(name)).indexes().isEmpty());
            } finally {
                for (VectorIndex index : service.listIndexes(name, "us-east-1")) {
                    vectors.deleteIndex(request -> request.vectorBucketName(name).indexName(index.getIndexName()));
                }
                vectors.deleteVectorBucket(request -> request.vectorBucketName(name));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4096, 4097})
    void dimensionBoundariesAreEnforcedThroughTheSdk(int dimension) {
        String name = "dimension-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3VectorsClient vectors = vectors("us-east-1")) {
            vectors.createVectorBucket(request -> request.vectorBucketName(name));
            try {
                if (dimension == 0 || dimension == 4097) {
                    ValidationException error = assertThrows(ValidationException.class,
                            () -> vectors.createIndex(request -> request.vectorBucketName(name).indexName("index")
                                    .dimension(dimension).dataType("float32").distanceMetric("cosine")));
                    assertEquals(400, error.statusCode());
                    assertTrue(vectors.listIndexes(request -> request.vectorBucketName(name)).indexes().isEmpty());
                } else {
                    vectors.createIndex(request -> request.vectorBucketName(name).indexName("index")
                            .dimension(dimension).dataType("float32").distanceMetric("cosine"));
                    assertEquals(dimension, vectors.getIndex(request -> request.vectorBucketName(name)
                            .indexName("index")).index().dimension());
                }
            } finally {
                for (VectorIndex index : service.listIndexes(name, "us-east-1")) {
                    vectors.deleteIndex(request -> request.vectorBucketName(name).indexName(index.getIndexName()));
                }
                vectors.deleteVectorBucket(request -> request.vectorBucketName(name));
            }
        }
    }

    private S3VectorsClient vectors(String region) {
        return S3VectorsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }
}
