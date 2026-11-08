package io.floci.aws.services.s3vectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.s3vectors.model.VectorBucket;
import io.floci.aws.services.s3vectors.model.VectorIndex;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.GetVectorBucketResponse;
import software.amazon.awssdk.services.s3vectors.model.Index;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class S3VectorsLegacySdkIntegrationTest {
    @TestHTTPResource
    URI endpoint;

    @Inject
    S3VectorsService service;

    @Inject
    StorageFactory factory;

    @Inject
    ObjectMapper mapper;

    @Test
    void legacyBucketAndIndexMetadataIsAvailableThroughSdkGetAndList() throws Exception {
        String region = "us-east-1";
        String name = "legacy-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        service.createVectorBucket(name, null, region);
        service.createIndex(name, "old", 1, "float32", "cosine", List.of(), region);
        ObjectNode legacyJson = mapper.valueToTree(service.getVectorBucket(name, region));
        legacyJson.remove(List.of("creationTime", "encryptionConfiguration"));
        ObjectNode legacyIndex = (ObjectNode) legacyJson.path("indexes").path("old");
        legacyIndex.remove(List.of("creationTime", "encryptionConfiguration"));
        AccountAwareStorageBackend<VectorBucket> store = factory.create("s3vectors", "s3vectors.json",
                new TypeReference<Map<String, VectorBucket>>() {});
        store.put(region + "::" + name, mapper.treeToValue(legacyJson, VectorBucket.class));

        try (S3VectorsClient vectors = S3VectorsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            try {
                // Creating an index must also resolve the legacy bucket's missing encryption default.
                vectors.createIndex(request -> request.vectorBucketName(name).indexName("new")
                        .dimension(1).dataType("float32").distanceMetric("cosine"));
                GetVectorBucketResponse bucket = vectors.getVectorBucket(request -> request.vectorBucketName(name));
                assertEquals("AES256", bucket.vectorBucket().encryptionConfiguration().sseTypeAsString());
                assertNotNull(bucket.vectorBucket().creationTime());
                assertEquals(bucket.vectorBucket().creationTime(), vectors.listVectorBuckets(request -> request.maxResults(100))
                        .vectorBuckets().stream().filter(item -> name.equals(item.vectorBucketName()))
                        .findFirst().orElseThrow().creationTime());
                for (String indexName : List.of("old", "new")) {
                    Index index = vectors.getIndex(request -> request.vectorBucketName(name).indexName(indexName)).index();
                    assertEquals("AES256", index.encryptionConfiguration().sseTypeAsString());
                    assertNotNull(index.creationTime());
                    assertEquals(index.creationTime(), vectors.listIndexes(request -> request.vectorBucketName(name))
                            .indexes().stream().filter(item -> indexName.equals(item.indexName()))
                            .findFirst().orElseThrow().creationTime());
                }
            } finally {
                for (VectorIndex index : service.listIndexes(name, region)) {
                    vectors.deleteIndex(request -> request.vectorBucketName(name).indexName(index.getIndexName()));
                }
                vectors.deleteVectorBucket(request -> request.vectorBucketName(name));
            }
        }
    }
}
