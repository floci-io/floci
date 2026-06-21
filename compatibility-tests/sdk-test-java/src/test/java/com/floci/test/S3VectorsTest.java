package com.floci.test;

import org.junit.jupiter.api.*;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.*;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;

@DisplayName("S3 Vectors Compatibility Tests")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3VectorsTest {

    private static S3VectorsClient client;
    private static String bucketName;
    private static String indexName;

    @BeforeAll
    static void setup() {
        client = TestFixtures.s3vectorsClient();
        bucketName = TestFixtures.uniqueName("bucket");
        indexName = TestFixtures.uniqueName("index");
    }

    @AfterAll
    static void cleanup() {
        if (client != null) {
            try {
                client.deleteIndex(DeleteIndexRequest.builder()
                        .vectorBucketName(bucketName)
                        .indexName(indexName)
                        .build());
            } catch (Exception ignored) {}
            try {
                client.deleteVectorBucket(DeleteVectorBucketRequest.builder()
                        .vectorBucketName(bucketName)
                        .build());
            } catch (Exception ignored) {}
            client.close();
        }
    }

    @Test
    @Order(1)
    void createVectorBucket() {
        CreateVectorBucketResponse response = client.createVectorBucket(
                CreateVectorBucketRequest.builder()
                        .vectorBucketName(bucketName)
                        .build()
        );
        assertThat(response.vectorBucketArn()).isNotBlank().contains("arn:aws:s3vectors:");
    }

    @Test
    @Order(2)
    void getVectorBucket() {
        GetVectorBucketResponse response = client.getVectorBucket(
                GetVectorBucketRequest.builder()
                        .vectorBucketName(bucketName)
                        .build()
        );
        assertThat(response.vectorBucket().vectorBucketName()).isEqualTo(bucketName);
        assertThat(response.vectorBucket().vectorBucketArn()).isNotBlank();
    }

    @Test
    @Order(3)
    void listVectorBuckets() {
        ListVectorBucketsResponse response = client.listVectorBuckets(
                ListVectorBucketsRequest.builder().build()
        );
        assertThat(response.vectorBuckets())
                .anyMatch(b -> bucketName.equals(b.vectorBucketName()));
    }

    @Test
    @Order(4)
    void createIndex() {
        CreateIndexResponse response = client.createIndex(
                CreateIndexRequest.builder()
                        .vectorBucketName(bucketName)
                        .indexName(indexName)
                        .dimension(3)
                        .distanceMetric("cosine")
                        .dataType("float32")
                        .build()
        );
        assertThat(response.indexArn()).isNotBlank().contains("arn:aws:s3vectors:");
    }

    @Test
    @Order(5)
    void getIndex() {
        GetIndexResponse response = client.getIndex(
                GetIndexRequest.builder()
                        .vectorBucketName(bucketName)
                        .indexName(indexName)
                        .build()
        );
        assertThat(response.index().indexName()).isEqualTo(indexName);
        assertThat(response.index().dimension()).isEqualTo(3);
        assertThat(response.index().distanceMetric().toString()).isEqualTo("cosine");
    }

    @Test
    @Order(6)
    void listIndexes() {
        ListIndexesResponse response = client.listIndexes(
                ListIndexesRequest.builder()
                        .vectorBucketName(bucketName)
                        .build()
        );
        assertThat(response.indexes())
                .anyMatch(i -> indexName.equals(i.indexName()));
    }

    @Test
    @Order(7)
    void putVectors() {
        PutVectorsResponse response = client.putVectors(
                PutVectorsRequest.builder()
                        .vectorBucketName(bucketName)
                        .indexName(indexName)
                        .vectors(
                                PutInputVector.builder()
                                        .key("v1")
                                        .data(VectorData.builder().float32(1.0f, 0.0f, 0.0f).build())
                                        .metadata(Document.fromMap(Map.of("label", Document.fromString("first"))))
                                        .build(),
                                PutInputVector.builder()
                                        .key("v2")
                                        .data(VectorData.builder().float32(0.0f, 1.0f, 0.0f).build())
                                        .metadata(Document.fromMap(Map.of("label", Document.fromString("second"))))
                                        .build()
                        )
                        .build()
        );
        assertThat(response).isNotNull();
    }

    @Test
    @Order(8)
    void getVectors() {
        GetVectorsResponse response = client.getVectors(
                GetVectorsRequest.builder()
                        .vectorBucketName(bucketName)
                        .indexName(indexName)
                        .keys("v1", "v2")
                        .returnData(true)
                        .returnMetadata(true)
                        .build()
        );
        assertThat(response.vectors()).hasSize(2);
        GetOutputVector v1 = response.vectors().stream()
                .filter(v -> "v1".equals(v.key()))
                .findFirst()
                .orElse(null);
        assertThat(v1).isNotNull();
        assertThat(v1.data().float32()).containsExactly(1.0f, 0.0f, 0.0f);
        assertThat(v1.metadata().asMap().get("label").asString()).isEqualTo("first");
    }

    @Test
    @Order(9)
    void queryVectors() {
        QueryVectorsResponse response = client.queryVectors(
                QueryVectorsRequest.builder()
                        .vectorBucketName(bucketName)
                        .indexName(indexName)
                        .queryVector(VectorData.builder().float32(1.0f, 0.1f, 0.0f).build())
                        .topK(1)
                        .returnMetadata(true)
                        .build()
        );
        assertThat(response.vectors()).hasSize(1);
        QueryOutputVector res = response.vectors().get(0);
        assertThat(res.key()).isEqualTo("v1");
        assertThat(res.distance()).isNotNull();
        assertThat(res.metadata().asMap().get("label").asString()).isEqualTo("first");
    }

    @Test
    @Order(10)
    void deleteVectors() {
        client.deleteVectors(
                DeleteVectorsRequest.builder()
                        .vectorBucketName(bucketName)
                        .indexName(indexName)
                        .keys("v1")
                        .build()
        );

        GetVectorsResponse response = client.getVectors(
                GetVectorsRequest.builder()
                        .vectorBucketName(bucketName)
                        .indexName(indexName)
                        .keys("v1")
                        .returnData(true)
                        .build()
        );
        assertThat(response.vectors()).isEmpty();
    }

    @Test
    @Order(11)
    void testConflictException() {
        assertThatThrownBy(() -> client.createVectorBucket(
                CreateVectorBucketRequest.builder()
                        .vectorBucketName(bucketName)
                        .build()
        )).isInstanceOf(ConflictException.class);
    }

    @Test
    @Order(12)
    void testNotFoundException() {
        assertThatThrownBy(() -> client.getVectorBucket(
                GetVectorBucketRequest.builder()
                        .vectorBucketName("non-existent-bucket")
                        .build()
        )).isInstanceOf(NotFoundException.class);
    }

    @Test
    @Order(13)
    void testValidationException() {
        assertThatThrownBy(() -> client.createIndex(
                CreateIndexRequest.builder()
                        .vectorBucketName(bucketName)
                        .indexName("invalid-metric-index")
                        .dimension(3)
                        .distanceMetric("invalid_metric")
                        .dataType("float32")
                        .build()
        )).isInstanceOf(ValidationException.class);
    }
}
