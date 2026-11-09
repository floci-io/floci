package io.floci.aws.services.s3vectors;

import io.floci.aws.services.s3vectors.model.VectorIndex;
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
import software.amazon.awssdk.services.s3vectors.model.NotFoundException;
import software.amazon.awssdk.services.s3vectors.model.PutInputVector;
import software.amazon.awssdk.services.s3vectors.model.QueryVectorsResponse;
import software.amazon.awssdk.services.s3vectors.model.ValidationException;
import software.amazon.awssdk.services.s3vectors.model.VectorBucket;
import software.amazon.awssdk.services.s3vectors.model.VectorData;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
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
                Arguments.of("float32", "cosine", List.of("text", "text"), "NonFilterableMetadataKeys"),
                Arguments.of("float32", "cosine", Collections.nCopies(11, "key"), "NonFilterableMetadataKeys"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"$and", "$or"})
    void logicalOperatorsAreNotTreatedAsNonFilterableFields(String operator) {
        String name = "logical-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3VectorsClient vectors = vectors("us-east-1")) {
            vectors.createVectorBucket(request -> request.vectorBucketName(name));
            try {
                vectors.createIndex(request -> request.vectorBucketName(name).indexName("index")
                        .dataType("float32").dimension(1).distanceMetric("cosine")
                        .metadataConfiguration(metadata -> metadata.nonFilterableMetadataKeys("$and", "$or", "text")));
                vectors.putVectors(request -> request.vectorBucketName(name).indexName("index")
                        .vectors(PutInputVector.builder().key("match")
                                .data(VectorData.builder().float32(1.0f).build())
                                .metadata(Document.fromMap(Map.of("category", Document.fromString("x")))).build()));
                Document filter = Document.fromMap(Map.of(operator, Document.fromList(List.of(
                        Document.fromMap(Map.of("category", Document.fromString("x")))))));
                QueryVectorsResponse result = vectors.queryVectors(request -> request.vectorBucketName(name)
                        .indexName("index").queryVector(VectorData.builder().float32(1.0f).build()).topK(1).filter(filter));
                assertEquals(List.of("match"), result.vectors().stream().map(item -> item.key()).toList());

                Document forbidden = Document.fromMap(Map.of(operator, Document.fromList(List.of(
                        Document.fromMap(Map.of("$and", Document.fromList(List.of(
                                Document.fromMap(Map.of("text", Document.fromString("hidden")))))))))));
                ValidationException error = assertThrows(ValidationException.class, () -> vectors.queryVectors(request -> request
                        .vectorBucketName(name).indexName("index").queryVector(VectorData.builder().float32(1.0f).build())
                        .topK(1).filter(forbidden)));
                assertEquals(400, error.statusCode());
                assertTrue(error.getMessage().contains("text"));
            } finally {
                for (VectorIndex index : service.listIndexes(name, "us-east-1")) {
                    vectors.deleteIndex(request -> request.vectorBucketName(name).indexName(index.getIndexName()));
                }
                vectors.deleteVectorBucket(request -> request.vectorBucketName(name));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configurationAppearsOnlyInGetIndexAndEmptyMetadataIsOmitted(boolean configured) {
        String name = "response-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        try (S3VectorsClient vectors = vectors("us-east-1")) {
            vectors.createVectorBucket(request -> request.vectorBucketName(name));
            try {
                vectors.createIndex(request -> {
                    request.vectorBucketName(name).indexName("index").dimension(3)
                            .dataType("float32").distanceMetric("cosine");
                    if (configured) {
                        request.metadataConfiguration(metadata -> metadata.nonFilterableMetadataKeys("text"));
                    }
                });
                Index index = vectors.getIndex(request -> request.vectorBucketName(name).indexName("index")).index();
                assertEquals("AES256", index.encryptionConfiguration().sseTypeAsString());
                if (configured) {
                    assertEquals(List.of("text"), index.metadataConfiguration().nonFilterableMetadataKeys());
                } else {
                    assertNull(index.metadataConfiguration());
                }

                Map<String, Object> detail = given().baseUri(endpoint.toString()).contentType("application/json")
                        .body(Map.of("vectorBucketName", name, "indexName", "index"))
                        .post("/GetIndex").then().statusCode(200).extract().path("index");
                assertEquals(configured, detail.containsKey("metadataConfiguration"));
                assertTrue(detail.containsKey("encryptionConfiguration"));
                // SDK clients ignore unknown fields, so check the wire response as well.
                Map<String, Object> summary = given().baseUri(endpoint.toString()).contentType("application/json")
                        .body(Map.of("vectorBucketName", name))
                        .post("/ListIndexes").then().statusCode(200).extract().path("indexes[0]");
                assertEquals(index.indexArn(), summary.get("indexArn"));
                assertEquals(Set.of("creationTime", "vectorBucketName", "indexName", "indexArn"), summary.keySet());
                assertEquals(3, detail.get("dimension"));
                assertEquals("float32", detail.get("dataType"));
                assertEquals("cosine", detail.get("distanceMetric"));

                List<Map<String, Object>> buckets = given().baseUri(endpoint.toString()).contentType("application/json")
                        .body(Map.of()).post("/ListVectorBuckets").then().statusCode(200).extract().path("vectorBuckets");
                Map<String, Object> bucketSummary = buckets.stream()
                        .filter(bucket -> name.equals(bucket.get("vectorBucketName"))).findFirst().orElseThrow();
                assertEquals(Set.of("creationTime", "vectorBucketName", "vectorBucketArn"), bucketSummary.keySet());
                VectorBucket bucket = vectors.getVectorBucket(request -> request.vectorBucketName(name)).vectorBucket();
                assertEquals(bucket.vectorBucketArn(), bucketSummary.get("vectorBucketArn"));
                assertEquals("AES256", bucket.encryptionConfiguration().sseTypeAsString());
            } finally {
                for (VectorIndex index : service.listIndexes(name, "us-east-1")) {
                    vectors.deleteIndex(request -> request.vectorBucketName(name).indexName(index.getIndexName()));
                }
                vectors.deleteVectorBucket(request -> request.vectorBucketName(name));
            }
        }
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

    @Test
    void vectorBucketPolicyLifecycleAndValidationThroughTheSdk() {
        String name = "policy-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        String policyV1 = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Read\",\"Effect\":\"Allow\"}]}";
        String policyV2 = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Sid\":\"Write\",\"Effect\":\"Deny\"}]}";
        try (S3VectorsClient vectors = vectors("us-east-1")) {
            String bucketArn = vectors.createVectorBucket(request -> request.vectorBucketName(name)).vectorBucketArn();
            try {
                NotFoundException initialMissing = assertThrows(NotFoundException.class,
                        () -> vectors.getVectorBucketPolicy(request -> request.vectorBucketName(name)));
                assertEquals(404, initialMissing.statusCode());

                vectors.putVectorBucketPolicy(request -> request.vectorBucketName(name).policy(policyV1));
                assertEquals(policyV1,
                        vectors.getVectorBucketPolicy(request -> request.vectorBucketName(name)).policy());
                assertEquals(policyV1,
                        vectors.getVectorBucketPolicy(request -> request.vectorBucketArn(bucketArn)).policy());

                vectors.putVectorBucketPolicy(request -> request.vectorBucketArn(bucketArn).policy(policyV2));
                assertEquals(policyV2,
                        vectors.getVectorBucketPolicy(request -> request.vectorBucketName(name)).policy());

                ValidationException invalidJson = assertThrows(ValidationException.class,
                        () -> vectors.putVectorBucketPolicy(request -> request.vectorBucketName(name).policy("not-json")));
                assertEquals(400, invalidJson.statusCode());

                ValidationException bothIdentifiers = assertThrows(ValidationException.class,
                        () -> vectors.getVectorBucketPolicy(request -> request
                                .vectorBucketName(name).vectorBucketArn(bucketArn)));
                assertEquals(400, bothIdentifiers.statusCode());

                vectors.deleteVectorBucketPolicy(request -> request.vectorBucketArn(bucketArn));
                NotFoundException deletedMissing = assertThrows(NotFoundException.class,
                        () -> vectors.getVectorBucketPolicy(request -> request.vectorBucketName(name)));
                assertEquals(404, deletedMissing.statusCode());
            } finally {
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
