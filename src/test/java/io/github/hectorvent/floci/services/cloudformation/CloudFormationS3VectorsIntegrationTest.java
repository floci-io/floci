package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.s3vectors.S3VectorsService;
import io.github.hectorvent.floci.services.s3vectors.model.VectorIndex;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.Index;
import software.amazon.awssdk.services.s3vectors.model.NotFoundException;
import software.amazon.awssdk.services.s3vectors.model.PutInputVector;
import software.amazon.awssdk.services.s3vectors.model.VectorBucket;
import software.amazon.awssdk.services.s3vectors.model.VectorData;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class CloudFormationS3VectorsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TEMPLATE = """
            {
              "Parameters":{"Tag":{"Type":"String"},"Dimension":{"Type":"Number"}},
              "Resources":{
                "Bucket":{"Type":"AWS::S3Vectors::VectorBucket","Properties":{
                  "EncryptionConfiguration":{"SseType":"AES256"},
                  "Tags":[{"Key":"version","Value":{"Ref":"Tag"}}]}},
                "Index":{"Type":"AWS::S3Vectors::Index","Properties":{
                  "VectorBucketArn":{"Ref":"Bucket"},"DataType":"float32",
                  "Dimension":{"Ref":"Dimension"},"DistanceMetric":"cosine",
                  "MetadataConfiguration":{"NonFilterableMetadataKeys":["text"]},
                  "Tags":[{"Key":"version","Value":{"Ref":"Tag"}}]}}
              },
              "Outputs":{
                "BucketRef":{"Value":{"Ref":"Bucket"}},
                "BucketArn":{"Value":{"Fn::GetAtt":["Bucket","VectorBucketArn"]}},
                "BucketCreated":{"Value":{"Fn::GetAtt":["Bucket","CreationTime"]}},
                "IndexRef":{"Value":{"Ref":"Index"}},
                "IndexArn":{"Value":{"Fn::GetAtt":["Index","IndexArn"]}},
                "IndexCreated":{"Value":{"Fn::GetAtt":["Index","CreationTime"]}}
              }
            }
            """;

    @TestHTTPResource
    URI endpoint;

    @Inject
    S3VectorsService service;

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkStackLifecyclePreservesDataOnTagUpdatesAndReplacesImmutableIndexes(String region) {
        String stackName = "cfn-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        try (CloudFormationClient cfn = cloudFormation(region); S3VectorsClient vectors = vectors(region)) {
            try {
                cfn.createStack(request -> request.stackName(stackName).templateBody(TEMPLATE)
                        .parameters(parameter("Tag", "first"), parameter("Dimension", "3")));
                Stack created = awaitStack(cfn, stackName, "CREATE_COMPLETE");
                Map<String, String> outputs = outputs(created);
                assertEquals(outputs.get("BucketRef"), outputs.get("BucketArn"));
                assertEquals(outputs.get("IndexRef"), outputs.get("IndexArn"));
                String partition = region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
                assertTrue(outputs.get("BucketArn").startsWith("arn:" + partition + ":s3vectors:" + region + ":"));
                String bucketName = leaf(outputs.get("BucketArn"));
                String indexName = leaf(outputs.get("IndexArn"));
                VectorBucket bucket = vectors.getVectorBucket(request -> request.vectorBucketName(bucketName)).vectorBucket();
                Index index = vectors.getIndex(request -> request.vectorBucketName(bucketName).indexName(indexName)).index();
                assertEquals(outputs.get("BucketArn"), bucket.vectorBucketArn());
                assertEquals(outputs.get("IndexArn"), index.indexArn());
                assertEquals(Instant.parse(outputs.get("BucketCreated")).toEpochMilli(), bucket.creationTime().toEpochMilli());
                assertEquals(Instant.parse(outputs.get("IndexCreated")).toEpochMilli(), index.creationTime().toEpochMilli());
                assertEquals("AES256", index.encryptionConfiguration().sseTypeAsString());
                assertEquals(List.of("text"), index.metadataConfiguration().nonFilterableMetadataKeys());
                vectors.putVectors(request -> request.vectorBucketName(bucketName).indexName(indexName)
                        .vectors(PutInputVector.builder().key("vector")
                                .data(VectorData.builder().float32(1.0f, 0.0f, 0.0f).build()).build()));

                cfn.updateStack(request -> request.stackName(stackName).templateBody(TEMPLATE)
                        .parameters(parameter("Tag", "second"), parameter("Dimension", "3")));
                assertEquals(outputs, outputs(awaitStack(cfn, stackName, "UPDATE_COMPLETE")));
                assertEquals(Map.of("version", "second"), service.getVectorBucket(bucketName, region).getTags());
                assertEquals(Map.of("version", "second"), service.getIndex(bucketName, indexName, region).getTags());
                assertEquals(1, vectors.getVectors(request -> request.vectorBucketName(bucketName)
                        .indexName(indexName).keys("vector")).vectors().size());

                cfn.updateStack(request -> request.stackName(stackName).templateBody(TEMPLATE)
                        .parameters(parameter("Tag", "second"), parameter("Dimension", "4")));
                Map<String, String> replaced = outputs(awaitStack(cfn, stackName, "UPDATE_COMPLETE"));
                assertEquals(outputs.get("BucketArn"), replaced.get("BucketArn"));
                assertNotEquals(outputs.get("IndexArn"), replaced.get("IndexArn"));
                assertEquals(4, vectors.getIndex(request -> request.vectorBucketName(bucketName)
                        .indexName(leaf(replaced.get("IndexArn")))).index().dimension());
                assertThrows(NotFoundException.class,
                        () -> vectors.getIndex(request -> request.vectorBucketName(bucketName).indexName(indexName)));

                cfn.deleteStack(request -> request.stackName(stackName));
                awaitDeleted(cfn, stackName);
                assertThrows(NotFoundException.class, () -> vectors.getVectorBucket(request -> request.vectorBucketName(bucketName)));
            } finally {
                cfn.deleteStack(request -> request.stackName(stackName));
                awaitDeleted(cfn, stackName);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"VectorBucketName", "VectorBucketArn"})
    void namedResourcesCanReferenceAnExistingBucketAndTolerateExternalDeletion(String selector) {
        String bucketName = "cfn-existing-" + UUID.randomUUID().toString().substring(0, 8);
        String stackName = bucketName + "-stack";
        try (CloudFormationClient cfn = cloudFormation("us-east-1"); S3VectorsClient vectors = vectors("us-east-1")) {
            String bucketArn = vectors.createVectorBucket(request -> request.vectorBucketName(bucketName)).vectorBucketArn();
            String template = """
                    {"Resources":{"Index":{"Type":"AWS::S3Vectors::Index","Properties":{
                    "%s":"%s","IndexName":"named-index","Dimension":3,"DataType":"float32","DistanceMetric":"euclidean"}}},
                    "Outputs":{"Arn":{"Value":{"Ref":"Index"}}}}
                    """.formatted(selector, "VectorBucketName".equals(selector) ? bucketName : bucketArn);
            try {
                cfn.createStack(request -> request.stackName(stackName).templateBody(template));
                assertEquals(bucketArn + "/index/named-index", outputs(awaitStack(cfn, stackName, "CREATE_COMPLETE")).get("Arn"));
                vectors.deleteIndex(request -> request.vectorBucketName(bucketName).indexName("named-index"));
                cfn.deleteStack(request -> request.stackName(stackName));
                awaitDeleted(cfn, stackName);
            } finally {
                cfn.deleteStack(request -> request.stackName(stackName));
                awaitDeleted(cfn, stackName);
                vectors.deleteVectorBucket(request -> request.vectorBucketName(bucketName));
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
    void invalidIndexSettingsFailThroughCloudFormation(String dataType, String distanceMetric,
                                                                   List<String> keys, String errorMember) throws Exception {
        String name = "invalid-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        try (CloudFormationClient cfn = cloudFormation("us-east-1"); S3VectorsClient vectors = vectors("us-east-1")) {
            vectors.createVectorBucket(request -> request.vectorBucketName(name));
            try {
                String template = MAPPER.writeValueAsString(Map.of("Resources", Map.of("Index", Map.of(
                        "Type", "AWS::S3Vectors::Index", "Properties", Map.of(
                                "VectorBucketName", name, "IndexName", "index", "Dimension", 3,
                                "DataType", dataType, "DistanceMetric", distanceMetric,
                                "MetadataConfiguration", Map.of("NonFilterableMetadataKeys", keys))))));
                cfn.createStack(request -> request.stackName(name).templateBody(template));
                awaitStack(cfn, name, "ROLLBACK_COMPLETE");
                assertTrue(cfn.describeStackEvents(request -> request.stackName(name)).stackEvents().stream()
                        .anyMatch(event -> "Index".equals(event.logicalResourceId())
                                && "CREATE_FAILED".equals(event.resourceStatusAsString())
                                && event.resourceStatusReason().contains(errorMember)));
                assertTrue(vectors.listIndexes(request -> request.vectorBucketName(name)).indexes().isEmpty());
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
                for (VectorIndex index : service.listIndexes(name, "us-east-1")) {
                    vectors.deleteIndex(request -> request.vectorBucketName(name).indexName(index.getIndexName()));
                }
                vectors.deleteVectorBucket(request -> request.vectorBucketName(name));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyStubStacksUpgradeToRealResources(boolean named) throws Exception {
        String name = "upgrade-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode template = (ObjectNode) MAPPER.readTree(TEMPLATE);
        if (named) {
            ((ObjectNode) template.path("Resources").path("Bucket").path("Properties")).put("VectorBucketName", name);
            ((ObjectNode) template.path("Resources").path("Index").path("Properties")).put("IndexName", "index");
        }
        String realTemplate = template.toString();
        String stubTemplate = realTemplate.replace("AWS::S3Vectors::VectorBucket", "AWS::Unsupported::VectorBucket")
                .replace("AWS::S3Vectors::Index", "AWS::Unsupported::Index");
        try (CloudFormationClient cfn = cloudFormation("us-east-1"); S3VectorsClient vectors = vectors("us-east-1")) {
            try {
                cfn.createStack(request -> request.stackName(name).templateBody(stubTemplate)
                        .parameters(parameter("Tag", "first"), parameter("Dimension", "3")));
                Map<String, String> before = outputs(awaitStack(cfn, name, "CREATE_COMPLETE"));
                assertTrue(before.get("BucketRef").matches("Bucket-[0-9a-f]{8}"));
                assertTrue(before.get("IndexRef").matches("Index-[0-9a-f]{8}"));

                cfn.updateStack(request -> request.stackName(name).templateBody(realTemplate)
                        .parameters(parameter("Tag", "second"), parameter("Dimension", "3")));
                Map<String, String> after = outputs(awaitStack(cfn, name, "UPDATE_COMPLETE"));
                assertNotEquals(before.get("BucketRef"), after.get("BucketRef"));
                assertEquals(after.get("BucketRef"), after.get("BucketArn"));
                assertEquals(after.get("IndexRef"), after.get("IndexArn"));
                String bucketName = leaf(after.get("BucketArn"));
                Index index = vectors.getIndex(request -> request.vectorBucketName(bucketName)
                        .indexName(leaf(after.get("IndexArn")))).index();
                assertEquals(after.get("IndexArn"), index.indexArn());
                assertEquals(Instant.parse(after.get("IndexCreated")).toEpochMilli(), index.creationTime().toEpochMilli());
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
                assertThrows(NotFoundException.class,
                        () -> vectors.getVectorBucket(request -> request.vectorBucketName(bucketName)));
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
            }
        }
    }

    private CloudFormationClient cloudFormation(String region) {
        return CloudFormationClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private S3VectorsClient vectors(String region) {
        return S3VectorsClient.builder().endpointOverride(endpoint).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build();
    }

    private static Parameter parameter(String name, String value) {
        return Parameter.builder().parameterKey(name).parameterValue(value).build();
    }

    private static Stack awaitStack(CloudFormationClient client, String name, String status) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            Stack stack = client.describeStacks(request -> request.stackName(name)).stacks().getFirst();
            assertEquals(status, stack.stackStatusAsString(), stack.stackStatusReason());
        });
        return client.describeStacks(request -> request.stackName(name)).stacks().getFirst();
    }

    private static void awaitDeleted(CloudFormationClient client, String name) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            CloudFormationException error = assertThrows(CloudFormationException.class,
                    () -> client.describeStacks(request -> request.stackName(name)));
            assertTrue(error.getMessage().contains("does not exist"), error.getMessage());
        });
    }

    private static Map<String, String> outputs(Stack stack) {
        return stack.outputs().stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static String leaf(String arn) {
        return arn.substring(arn.lastIndexOf('/') + 1);
    }
}
