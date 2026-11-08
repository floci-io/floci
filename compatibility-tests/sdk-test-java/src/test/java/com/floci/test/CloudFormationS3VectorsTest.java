package com.floci.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.ResourceChange;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;
import software.amazon.awssdk.services.s3vectors.model.Index;
import software.amazon.awssdk.services.s3vectors.model.IndexSummary;
import software.amazon.awssdk.services.s3vectors.model.NotFoundException;
import software.amazon.awssdk.services.s3vectors.model.PutInputVector;
import software.amazon.awssdk.services.s3vectors.model.VectorBucket;
import software.amazon.awssdk.services.s3vectors.model.VectorData;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CloudFormation S3 Vectors resources")
class CloudFormationS3VectorsTest {

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

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void sdkStackLifecyclePreservesDataOnTagUpdatesAndReplacesImmutableIndexes(String region) throws Exception {
        String stackName = "cfn-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        try (CloudFormationClient cfn = cloudFormation(region); S3VectorsClient vectors = vectors(region)) {
            try {
                cfn.createStack(request -> request.stackName(stackName).templateBody(TEMPLATE)
                        .parameters(parameter("Tag", "first"), parameter("Dimension", "3")));
                Stack created = awaitStack(cfn, stackName, "CREATE_COMPLETE");
                Map<String, String> outputs = outputs(created);
                assertThat(outputs.get("BucketArn")).isEqualTo(outputs.get("BucketRef"));
                assertThat(outputs.get("IndexArn")).isEqualTo(outputs.get("IndexRef"));
                String partition = region.startsWith("us-gov-") ? "aws-us-gov" : "aws";
                assertThat(outputs.get("BucketArn")).startsWith("arn:" + partition + ":s3vectors:" + region + ":");
                String bucketName = leaf(outputs.get("BucketArn"));
                String indexName = leaf(outputs.get("IndexArn"));
                VectorBucket bucket = vectors.getVectorBucket(request -> request.vectorBucketName(bucketName)).vectorBucket();
                Index index = vectors.getIndex(request -> request.vectorBucketName(bucketName).indexName(indexName)).index();
                assertThat(bucket.vectorBucketArn()).isEqualTo(outputs.get("BucketArn"));
                assertThat(index.indexArn()).isEqualTo(outputs.get("IndexArn"));
                assertThat(bucket.creationTime().toEpochMilli()).isEqualTo(Instant.parse(outputs.get("BucketCreated")).toEpochMilli());
                assertThat(index.creationTime().toEpochMilli()).isEqualTo(Instant.parse(outputs.get("IndexCreated")).toEpochMilli());
                assertThat(index.encryptionConfiguration().sseTypeAsString()).isEqualTo("AES256");
                assertThat(index.metadataConfiguration().nonFilterableMetadataKeys()).containsExactly("text");
                vectors.putVectors(request -> request.vectorBucketName(bucketName).indexName(indexName)
                        .vectors(PutInputVector.builder().key("vector")
                                .data(VectorData.builder().float32(1.0f, 0.0f, 0.0f).build()).build()));

                cfn.updateStack(request -> request.stackName(stackName).templateBody(TEMPLATE)
                        .parameters(parameter("Tag", "second"), parameter("Dimension", "3")));
                assertThat(outputs(awaitStack(cfn, stackName, "UPDATE_COMPLETE"))).isEqualTo(outputs);
                assertThat(vectors.getVectors(request -> request.vectorBucketName(bucketName)
                        .indexName(indexName).keys("vector")).vectors()).hasSize(1);

                cfn.updateStack(request -> request.stackName(stackName).templateBody(TEMPLATE)
                        .parameters(parameter("Tag", "second"), parameter("Dimension", "4")));
                Map<String, String> replaced = outputs(awaitStack(cfn, stackName, "UPDATE_COMPLETE"));
                assertThat(replaced.get("BucketArn")).isEqualTo(outputs.get("BucketArn"));
                assertThat(replaced.get("IndexArn")).isNotEqualTo(outputs.get("IndexArn"));
                assertThat(vectors.getIndex(request -> request.vectorBucketName(bucketName)
                        .indexName(leaf(replaced.get("IndexArn")))).index().dimension()).isEqualTo(4);
                assertThatThrownBy(() -> vectors.getIndex(request -> request.vectorBucketName(bucketName).indexName(indexName)))
                        .isInstanceOf(NotFoundException.class);

                cfn.deleteStack(request -> request.stackName(stackName));
                awaitDeleted(cfn, stackName);
                assertThatThrownBy(() -> vectors.getVectorBucket(request -> request.vectorBucketName(bucketName)))
                        .isInstanceOf(NotFoundException.class);
            } finally {
                cfn.deleteStack(request -> request.stackName(stackName));
                awaitDeleted(cfn, stackName);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tagUpdateAfterReplacementRollbackPreservesIndexAndVectors(boolean named) throws Exception {
        String name = TestFixtures.uniqueName("cfn-vectors-rollback");
        ObjectNode original = (ObjectNode) MAPPER.readTree(TEMPLATE);
        if (named) {
            ((ObjectNode) original.path("Resources").path("Index").path("Properties"))
                    .put("IndexName", "original-index");
        }
        ObjectNode replacement = original.deepCopy();
        ObjectNode resources = (ObjectNode) replacement.path("Resources");
        if (named) {
            ((ObjectNode) resources.path("Index").path("Properties")).put("IndexName", "replacement-index");
        }
        ObjectNode invalid = resources.putObject("Broken").put("Type", "AWS::S3Vectors::Index")
                .put("DependsOn", "Index").putObject("Properties");
        invalid.putObject("VectorBucketArn").put("Ref", "Bucket");
        invalid.put("Dimension", 3).put("DataType", "float64").put("DistanceMetric", "cosine");
        try (CloudFormationClient cfn = TestFixtures.cloudFormationClient();
                S3VectorsClient vectors = TestFixtures.s3vectorsClient()) {
            try {
                cfn.createStack(request -> request.stackName(name).templateBody(original.toString())
                        .parameters(parameter("Tag", "first"), parameter("Dimension", "3")));
                Map<String, String> before = outputs(awaitStack(cfn, name, "CREATE_COMPLETE"));
                String bucketName = leaf(before.get("BucketArn"));
                String indexName = leaf(before.get("IndexArn"));
                vectors.putVectors(request -> request.vectorBucketName(bucketName).indexName(indexName)
                        .vectors(PutInputVector.builder().key("vector")
                                .data(VectorData.builder().float32(1.0f, 0.0f, 0.0f).build()).build()));

                cfn.updateStack(request -> request.stackName(name).templateBody(replacement.toString())
                        .parameters(parameter("Tag", "first"), parameter("Dimension", "4")));
                assertThat(outputs(awaitStack(cfn, name, "UPDATE_ROLLBACK_COMPLETE"))).isEqualTo(before);
                String replacementArn = cfn.describeStackEvents(request -> request.stackName(name)).stackEvents()
                        .stream().filter(event -> "Index".equals(event.logicalResourceId())
                                && "UPDATE_COMPLETE".equals(event.resourceStatusAsString())
                                && !before.get("IndexArn").equals(event.physicalResourceId()))
                        .findFirst().orElseThrow().physicalResourceId();
                assertThatThrownBy(() -> vectors.getIndex(request -> request.vectorBucketName(bucketName)
                        .indexName(leaf(replacementArn)))).isInstanceOf(NotFoundException.class);

                cfn.updateStack(request -> request.stackName(name).templateBody(original.toString())
                        .parameters(parameter("Tag", "second"), parameter("Dimension", "3")));
                assertThat(outputs(awaitStack(cfn, name, "UPDATE_COMPLETE"))).isEqualTo(before);
                assertThat(vectors.getIndex(request -> request.vectorBucketName(bucketName).indexName(indexName))
                        .index().dimension()).isEqualTo(3);
                assertThat(vectors.getVectors(request -> request.vectorBucketName(bucketName)
                        .indexName(indexName).keys("vector")).vectors())
                        .extracting(vector -> vector.key()).containsExactly("vector");
                assertThat(vectors.listIndexes(request -> request.vectorBucketName(bucketName)).indexes())
                        .extracting(index -> index.indexArn()).containsExactly(before.get("IndexArn"));
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"VectorBucketName", "VectorBucketArn"})
    void namedResourcesCanReferenceAnExistingBucketAndTolerateExternalDeletion(String selector) throws Exception {
        String bucketName = "cfn-existing-" + UUID.randomUUID().toString().substring(0, 8);
        String stackName = bucketName + "-stack";
        try (CloudFormationClient cfn = TestFixtures.cloudFormationClient(); S3VectorsClient vectors = TestFixtures.s3vectorsClient()) {
            String bucketArn = vectors.createVectorBucket(request -> request.vectorBucketName(bucketName)).vectorBucketArn();
            String template = """
                    {"Resources":{"Index":{"Type":"AWS::S3Vectors::Index","Properties":{
                    "%s":"%s","IndexName":"named-index","Dimension":3,"DataType":"float32","DistanceMetric":"euclidean"}}},
                    "Outputs":{"Arn":{"Value":{"Ref":"Index"}}}}
                    """.formatted(selector, "VectorBucketName".equals(selector) ? bucketName : bucketArn);
            try {
                cfn.createStack(request -> request.stackName(stackName).templateBody(template));
                assertThat(outputs(awaitStack(cfn, stackName, "CREATE_COMPLETE")).get("Arn")).isEqualTo(bucketArn + "/index/named-index");
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
                Arguments.of("float32", "cosine", List.of("a".repeat(64)), "NonFilterableMetadataKeys"),
                Arguments.of("float32", "cosine", Collections.nCopies(11, "key"), "NonFilterableMetadataKeys"));
    }

    static Stream<Arguments> invalidEncryptionSettings() {
        return Stream.of("VectorBucket", "Index").flatMap(type -> Stream.of(
                Arguments.of(type, "\"AES256\"", "EncryptionConfiguration"),
                Arguments.of(type, "[]", "EncryptionConfiguration"),
                Arguments.of(type, "{\"SseType\":\"aws:kms\",\"KmsKeyArn\":\"key\"}", "KmsKeyArn")));
    }

    @ParameterizedTest
    @MethodSource("invalidEncryptionSettings")
    void invalidEncryptionSettingsFailThroughCloudFormation(String type, String configuration,
                                                            String errorMember) throws Exception {
        String name = "invalid-encryption-" + UUID.randomUUID().toString().substring(0, 8);
        boolean index = "Index".equals(type);
        ObjectNode props = MAPPER.createObjectNode().put("VectorBucketName", name);
        props.set("EncryptionConfiguration", MAPPER.readTree(configuration));
        if (index) {
            props.put("IndexName", "index").put("Dimension", 3)
                    .put("DataType", "float32").put("DistanceMetric", "cosine");
        }
        String template = MAPPER.writeValueAsString(Map.of("Resources", Map.of("Vectors", Map.of(
                "Type", "AWS::S3Vectors::" + type, "Properties", props))));
        try (CloudFormationClient cfn = TestFixtures.cloudFormationClient(); S3VectorsClient vectors = TestFixtures.s3vectorsClient()) {
            if (index) {
                vectors.createVectorBucket(request -> request.vectorBucketName(name));
            }
            try {
                cfn.createStack(request -> request.stackName(name).templateBody(template));
                awaitStack(cfn, name, "ROLLBACK_COMPLETE");
                assertThat(cfn.describeStackEvents(request -> request.stackName(name)).stackEvents().stream()
                        .anyMatch(event -> "Vectors".equals(event.logicalResourceId())
                                && "CREATE_FAILED".equals(event.resourceStatusAsString())
                                && event.resourceStatusReason().contains(errorMember))).isTrue();
                if (index) {
                    assertThat(vectors.listIndexes(request -> request.vectorBucketName(name)).indexes()).isEmpty();
                } else {
                    assertThatThrownBy(() -> vectors.getVectorBucket(request -> request.vectorBucketName(name)))
                        .isInstanceOf(NotFoundException.class);
                }
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
                if (index) {
                    vectors.deleteVectorBucket(request -> request.vectorBucketName(name));
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1"})
    void kmsEncryptionResolvesIntrinsicsAndIsRetainedForBothResourceTypes(String region) throws Exception {
        String name = "kms-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        ObjectNode template = (ObjectNode) MAPPER.readTree(TEMPLATE);
        ObjectNode configuration = MAPPER.createObjectNode().put("SseType", "aws:kms");
        configuration.putObject("KmsKeyArn").put("Fn::Sub",
                "arn:${AWS::Partition}:kms:${AWS::Region}:${AWS::AccountId}:key/1234abcd-12ab-34cd-56ef-1234567890ab");
        for (String logicalId : List.of("Bucket", "Index")) {
            ((ObjectNode) template.path("Resources").path(logicalId).path("Properties"))
                    .set("EncryptionConfiguration", configuration);
        }
        try (CloudFormationClient cfn = cloudFormation(region); S3VectorsClient vectors = vectors(region)) {
            try {
                cfn.createStack(request -> request.stackName(name).templateBody(template.toString())
                        .parameters(parameter("Tag", "kms"), parameter("Dimension", "3")));
                Map<String, String> outputs = outputs(awaitStack(cfn, name, "CREATE_COMPLETE"));
                String bucketName = leaf(outputs.get("BucketArn"));
                VectorBucket bucket = vectors.getVectorBucket(request -> request.vectorBucketName(bucketName))
                        .vectorBucket();
                Index index = vectors.getIndex(request -> request.vectorBucketName(bucketName)
                        .indexName(leaf(outputs.get("IndexArn")))).index();
                String key = outputs.get("BucketArn").split(":bucket/")[0].replace(":s3vectors:", ":kms:")
                        + ":key/1234abcd-12ab-34cd-56ef-1234567890ab";
                assertThat(bucket.encryptionConfiguration().sseTypeAsString()).isEqualTo("aws:kms");
                assertThat(bucket.encryptionConfiguration().kmsKeyArn()).isEqualTo(key);
                assertThat(index.encryptionConfiguration().sseTypeAsString()).isEqualTo("aws:kms");
                assertThat(index.encryptionConfiguration().kmsKeyArn()).isEqualTo(key);
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
            }
        }
    }

    @ParameterizedTest
    @MethodSource("invalidIndexSettings")
    void invalidIndexSettingsFailThroughCloudFormation(String dataType, String distanceMetric,
                                                                   List<String> keys, String errorMember) throws Exception {
        String name = "invalid-vectors-" + UUID.randomUUID().toString().substring(0, 8);
        try (CloudFormationClient cfn = TestFixtures.cloudFormationClient(); S3VectorsClient vectors = TestFixtures.s3vectorsClient()) {
            vectors.createVectorBucket(request -> request.vectorBucketName(name));
            try {
                String template = MAPPER.writeValueAsString(Map.of("Resources", Map.of("Index", Map.of(
                        "Type", "AWS::S3Vectors::Index", "Properties", Map.of(
                                "VectorBucketName", name, "IndexName", "index", "Dimension", 3,
                                "DataType", dataType, "DistanceMetric", distanceMetric,
                                "MetadataConfiguration", Map.of("NonFilterableMetadataKeys", keys))))));
                cfn.createStack(request -> request.stackName(name).templateBody(template));
                awaitStack(cfn, name, "ROLLBACK_COMPLETE");
                assertThat(cfn.describeStackEvents(request -> request.stackName(name)).stackEvents().stream()
                        .anyMatch(event -> "Index".equals(event.logicalResourceId())
                                && "CREATE_FAILED".equals(event.resourceStatusAsString())
                                && event.resourceStatusReason().contains(errorMember))).isTrue();
                assertThat(vectors.listIndexes(request -> request.vectorBucketName(name)).indexes()).isEmpty();
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
                for (IndexSummary index : vectors.listIndexes(request -> request.vectorBucketName(name)).indexes()) {
                    vectors.deleteIndex(request -> request.vectorBucketName(name).indexName(index.indexName()));
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
        try (CloudFormationClient cfn = TestFixtures.cloudFormationClient(); S3VectorsClient vectors = TestFixtures.s3vectorsClient()) {
            try {
                cfn.createStack(request -> request.stackName(name).templateBody(stubTemplate)
                        .parameters(parameter("Tag", "first"), parameter("Dimension", "3")));
                Map<String, String> before = outputs(awaitStack(cfn, name, "CREATE_COMPLETE"));
                assertThat(before.get("BucketRef").matches("Bucket-[0-9a-f]{8}")).isTrue();
                assertThat(before.get("IndexRef").matches("Index-[0-9a-f]{8}")).isTrue();

                cfn.updateStack(request -> request.stackName(name).templateBody(realTemplate)
                        .parameters(parameter("Tag", "second"), parameter("Dimension", "3")));
                Map<String, String> after = outputs(awaitStack(cfn, name, "UPDATE_COMPLETE"));
                assertThat(after.get("BucketRef")).isNotEqualTo(before.get("BucketRef"));
                assertThat(after.get("BucketArn")).isEqualTo(after.get("BucketRef"));
                assertThat(after.get("IndexArn")).isEqualTo(after.get("IndexRef"));
                String bucketName = leaf(after.get("BucketArn"));
                Index index = vectors.getIndex(request -> request.vectorBucketName(bucketName)
                        .indexName(leaf(after.get("IndexArn")))).index();
                assertThat(index.indexArn()).isEqualTo(after.get("IndexArn"));
                assertThat(index.creationTime().toEpochMilli()).isEqualTo(Instant.parse(after.get("IndexCreated")).toEpochMilli());
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
                assertThatThrownBy(() -> vectors.getVectorBucket(request -> request.vectorBucketName(bucketName)))
                        .isInstanceOf(NotFoundException.class);
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
            }
        }
    }

    static Stream<Arguments> changeSetProperties() {
        return Stream.of(
                Arguments.of("Index", "Dimension", "4", "True"),
                Arguments.of("Bucket", "EncryptionConfiguration", """
                        {"SseType":"aws:kms","KmsKeyArn":{"Fn::Sub":
                        "arn:${AWS::Partition}:kms:${AWS::Region}:${AWS::AccountId}:key/1234abcd-12ab-34cd-56ef-1234567890ab"}}
                        """, "True"),
                Arguments.of("Bucket", "Tags", "[{\"Key\":\"version\",\"Value\":\"second\"}]", "False"),
                Arguments.of("Index", "Tags", "[{\"Key\":\"version\",\"Value\":\"second\"}]", "False"));
    }

    @ParameterizedTest
    @MethodSource("changeSetProperties")
    void changeSetsReportReplacementForImmutableProperties(String logicalId, String property,
                                                           String value, String replacement) throws Exception {
        String name = TestFixtures.uniqueName("cfn-vectors-change-set");
        ObjectNode template = (ObjectNode) MAPPER.readTree(TEMPLATE);
        ((ObjectNode) template.path("Resources").path(logicalId).path("Properties"))
                .set(property, MAPPER.readTree(value));
        try (CloudFormationClient cfn = TestFixtures.cloudFormationClient()) {
            try {
                cfn.createStack(request -> request.stackName(name).templateBody(TEMPLATE)
                        .parameters(parameter("Tag", "first"), parameter("Dimension", "3")));
                awaitStack(cfn, name, "CREATE_COMPLETE");
                String changeSet = cfn.createChangeSet(request -> request.stackName(name).changeSetName("update")
                        .changeSetType(ChangeSetType.UPDATE).templateBody(template.toString())
                        .parameters(parameter("Tag", "first"), parameter("Dimension", "3"))).id();
                TestFixtures.await(() -> {
                    DescribeChangeSetResponse response = cfn.describeChangeSet(request -> request.changeSetName(changeSet));
                    assertThat(response.statusAsString()).as(response.statusReason()).isNotEqualTo("FAILED");
                    return "CREATE_COMPLETE".equals(response.statusAsString());
                }, changeSet, "CREATE_COMPLETE", Duration.ofSeconds(60).toNanos(), 100);
                ResourceChange change = cfn.describeChangeSet(request -> request.changeSetName(changeSet)).changes()
                        .stream().map(item -> item.resourceChange())
                        .filter(item -> logicalId.equals(item.logicalResourceId())).findFirst().orElseThrow();
                assertThat(change.actionAsString()).isEqualTo("Modify");
                assertThat(change.replacementAsString()).isEqualTo(replacement);
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"absent", "{}", "{\"SseType\":\"AES256\"}"})
    void indexesInheritBucketEncryptionUnlessExplicitlyOverridden(String configuration) throws Exception {
        String name = TestFixtures.uniqueName("cfn-vectors-inherit");
        ObjectNode template = (ObjectNode) MAPPER.readTree(TEMPLATE);
        ObjectNode bucketProps = (ObjectNode) template.path("Resources").path("Bucket").path("Properties");
        ObjectNode encryption = bucketProps.putObject("EncryptionConfiguration").put("SseType", "aws:kms");
        encryption.putObject("KmsKeyArn").put("Fn::Sub",
                "arn:${AWS::Partition}:kms:${AWS::Region}:${AWS::AccountId}:key/1234abcd-12ab-34cd-56ef-1234567890ab");
        if (!"absent".equals(configuration)) {
            ((ObjectNode) template.path("Resources").path("Index").path("Properties"))
                    .set("EncryptionConfiguration", MAPPER.readTree(configuration));
        }
        try (CloudFormationClient cfn = TestFixtures.cloudFormationClient();
                S3VectorsClient vectors = TestFixtures.s3vectorsClient()) {
            try {
                cfn.createStack(request -> request.stackName(name).templateBody(template.toString())
                        .parameters(parameter("Tag", "kms"), parameter("Dimension", "3")));
                Map<String, String> outputs = outputs(awaitStack(cfn, name, "CREATE_COMPLETE"));
                String bucketName = leaf(outputs.get("BucketArn"));
                VectorBucket bucket = vectors.getVectorBucket(request -> request.vectorBucketName(bucketName)).vectorBucket();
                Index index = vectors.getIndex(request -> request.vectorBucketName(bucketName)
                        .indexName(leaf(outputs.get("IndexArn")))).index();
                assertThat(bucket.encryptionConfiguration().sseTypeAsString()).isEqualTo("aws:kms");
                if (configuration.contains("AES256")) {
                    assertThat(index.encryptionConfiguration().sseTypeAsString()).isEqualTo("AES256");
                    assertThat(index.encryptionConfiguration().kmsKeyArn()).isNull();
                } else {
                    assertThat(index.encryptionConfiguration()).isEqualTo(bucket.encryptionConfiguration());
                }
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
            }
        }
    }

    @Test
    void propertySnapshotsAreNotExposedThroughGetAtt() throws Exception {
        String name = TestFixtures.uniqueName("cfn-vectors-private");
        ObjectNode template = (ObjectNode) MAPPER.readTree(TEMPLATE);
        ObjectNode outputs = (ObjectNode) template.path("Outputs");
        for (String logicalId : List.of("Bucket", "Index")) {
            for (String key : List.of("FlociS3VectorsProperties", "__FlociS3VectorsProperties")) {
                String output = logicalId + (key.startsWith("__") ? "Private" : "Legacy");
                outputs.putObject(output).putObject("Value")
                        .putArray("Fn::GetAtt").add(logicalId).add(key);
            }
        }
        try (CloudFormationClient cfn = TestFixtures.cloudFormationClient()) {
            try {
                cfn.createStack(request -> request.stackName(name).templateBody(template.toString())
                        .parameters(parameter("Tag", "first"), parameter("Dimension", "3")));
                Map<String, String> values = outputs(awaitStack(cfn, name, "CREATE_COMPLETE"));
                for (String logicalId : List.of("Bucket", "Index")) {
                    assertThat(values.get(logicalId + "Private"))
                            .isEqualTo(logicalId + ".__FlociS3VectorsProperties");
                    assertThat(values.get(logicalId + "Legacy"))
                            .isEqualTo(logicalId + ".FlociS3VectorsProperties");
                }
            } finally {
                cfn.deleteStack(request -> request.stackName(name));
                awaitDeleted(cfn, name);
            }
        }
    }

    private CloudFormationClient cloudFormation(String region) {
        return CloudFormationClient.builder().endpointOverride(TestFixtures.endpoint()).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private S3VectorsClient vectors(String region) {
        return S3VectorsClient.builder().endpointOverride(TestFixtures.endpoint()).region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    private static Parameter parameter(String name, String value) {
        return Parameter.builder().parameterKey(name).parameterValue(value).build();
    }

    private static Stack awaitStack(CloudFormationClient client, String name, String status) throws InterruptedException {
        TestFixtures.await(() -> {
            Stack stack = client.describeStacks(request -> request.stackName(name)).stacks().get(0);
            String actual = stack.stackStatusAsString();
            if (!status.equals(actual) && (actual.endsWith("_FAILED") || actual.endsWith("ROLLBACK_COMPLETE"))) {
                throw new AssertionError(name + " reached " + actual + ": " + stack.stackStatusReason());
            }
            return status.equals(actual);
        }, name, status, Duration.ofSeconds(60).toNanos(), 100);
        return client.describeStacks(request -> request.stackName(name)).stacks().get(0);
    }

    private static void awaitDeleted(CloudFormationClient client, String name) throws InterruptedException {
        TestFixtures.await(() -> {
            try {
                client.describeStacks(request -> request.stackName(name));
                return false;
            } catch (CloudFormationException error) {
                if (!error.getMessage().contains("does not exist")) {
                    throw error;
                }
                return true;
            }
        }, name, "deleted", Duration.ofSeconds(60).toNanos(), 100);
    }

    private static Map<String, String> outputs(Stack stack) {
        return stack.outputs().stream().collect(Collectors.toMap(Output::outputKey, Output::outputValue));
    }

    private static String leaf(String arn) {
        return arn.substring(arn.lastIndexOf('/') + 1);
    }
}
