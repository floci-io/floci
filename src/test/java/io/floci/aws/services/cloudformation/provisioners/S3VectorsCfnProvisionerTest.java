package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.CloudFormationTemplateEngine;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.s3vectors.S3VectorsService;
import io.floci.aws.services.s3vectors.model.VectorBucket;
import io.floci.aws.services.s3vectors.model.VectorIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3VectorsCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String BUCKET_ARN = "arn:aws:s3vectors:us-east-1:000000000000:bucket/my-bucket";
    private static final String INDEX_ARN = BUCKET_ARN + "/index/my-index";
    private static final String KMS_KEY_ARN = "arn:aws:kms:us-east-1:000000000000:key/1234abcd-12ab-34cd-56ef-1234567890ab";
    private static final Instant CREATION_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final S3VectorsService service = mock(S3VectorsService.class);
    private final S3VectorsCfnProvisioner provisioner = new S3VectorsCfnProvisioner(service);

    private ProvisionContext context(String prior) {
        CloudFormationTemplateEngine engine = mock(CloudFormationTemplateEngine.class);
        when(engine.resolve(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return node == null || node.isNull() || node.isMissingNode() ? null : node.asText();
        });
        when(engine.resolveNode(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(engine.resolveStringList(any())).thenAnswer(invocation -> {
            JsonNode node = invocation.getArgument(0);
            return MAPPER.convertValue(node, MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
        });
        return new ProvisionContext(engine, REGION, "000000000000", "MyStack", prior);
    }

    private static StackResource resource(String type) {
        StackResource resource = new StackResource();
        resource.setLogicalId("Vectors");
        resource.setResourceType("AWS::S3Vectors::" + type);
        return resource;
    }

    private static ObjectNode props(String json) throws Exception {
        return (ObjectNode) MAPPER.readTree(json);
    }

    private static ObjectNode indexProps() throws Exception {
        return props("""
                {"VectorBucketArn":"%s","IndexName":"my-index","Dimension":3,
                 "DataType":"float32","DistanceMetric":"cosine"}
                """.formatted(BUCKET_ARN));
    }

    private void stubCreates() {
        when(service.createVectorBucket(anyString(), any(), anyMap(), eq(REGION)))
                .thenAnswer(invocation -> {
                    VectorBucket bucket = new VectorBucket(invocation.getArgument(0),
                            BUCKET_ARN.replace("my-bucket", invocation.getArgument(0)), invocation.getArgument(1));
                    bucket.setCreationTime(CREATION_TIME);
                    return bucket;
                });
        when(service.createIndex(anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyList(), any(), anyMap(), eq(REGION))).thenAnswer(invocation -> {
                    VectorIndex index = new VectorIndex(invocation.getArgument(1),
                            BUCKET_ARN + "/index/" + invocation.getArgument(1), invocation.getArgument(0),
                            invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4));
                    index.setCreationTime(CREATION_TIME);
                    return index;
                });
    }

    @Test
    void bucketMapsEncryptionAndTagsAndExposesArnAndCreationTime() throws Exception {
        stubCreates();
        StackResource resource = resource("VectorBucket");
        provisioner.provision(resource, props("""
                {"VectorBucketName":"my-bucket","EncryptionConfiguration":{"SseType":"aws:kms","KmsKeyArn":"%s"},
                 "Tags":[{"Key":"team","Value":"search"}]}
                """.formatted(KMS_KEY_ARN)), context(null));

        verify(service).createVectorBucket("my-bucket", Map.of("sseType", "aws:kms", "kmsKeyArn", KMS_KEY_ARN),
                Map.of("team", "search"), REGION);
        assertEquals(BUCKET_ARN, resource.getPhysicalId());
        assertEquals(BUCKET_ARN, resource.getAttributes().get("VectorBucketArn"));
        assertEquals(CREATION_TIME.toString(), resource.getAttributes().get("CreationTime"));
        assertEquals(Set.of("VectorBucketArn", "CreationTime", "__FlociS3VectorsProperties"),
                resource.getAttributes().keySet());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"AES256\"", "[]", "42", "true"})
    void malformedEncryptionConfigurationsFailBeforeCreatingAnything(String configuration) throws Exception {
        for (String type : List.of("VectorBucket", "Index")) {
            ObjectNode props = encryptionProps(type, MAPPER.readTree(configuration));
            AwsException error = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource(type), props, context(null)));
            assertEquals("ValidationError", error.getErrorCode());
            assertTrue(error.getMessage().contains("EncryptionConfiguration must be an object"));
        }
        verifyNoInteractions(service);
    }

    static Stream<String> invalidKmsKeyArns() {
        return Stream.of("key", "1234abcd-12ab-34cd-56ef-1234567890ab", "alias/my-key", "", " ",
                KMS_KEY_ARN.replace(":kms:", ":s3:"), KMS_KEY_ARN.replace(":key/", ":alias/"),
                KMS_KEY_ARN.replace("000000000000", "123"), KMS_KEY_ARN.replace("000000000000", "abcdefghijkl"),
                KMS_KEY_ARN.replace("us-east-1", "US-EAST-1"), KMS_KEY_ARN.replace("arn:aws:", "arn:invalid:"),
                "arn:aws:kms:us-east-1:000000000000:key/", KMS_KEY_ARN + "a".repeat(2049 - KMS_KEY_ARN.length()));
    }

    @ParameterizedTest
    @MethodSource("invalidKmsKeyArns")
    void invalidKmsKeyArnsFailBeforeCreatingAnything(String key) throws Exception {
        ObjectNode configuration = MAPPER.createObjectNode().put("SseType", "aws:kms").put("KmsKeyArn", key);
        for (String type : List.of("VectorBucket", "Index")) {
            ObjectNode props = encryptionProps(type, configuration);
            AwsException error = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource(type), props, context(null)));
            assertEquals("ValidationError", error.getErrorCode());
            assertTrue(error.getMessage().contains("KmsKeyArn"));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1", "us-gov-west-1", "cn-north-1", "us-iso-east-1", "us-isob-east-1",
            "eu-isoe-west-1", "us-isof-south-1", "eusc-de-east-1"})
    void kmsKeyArnsArePreservedAcrossPartitions(String region) throws Exception {
        stubCreates();
        String key = AwsArnUtils.Arn.of("kms", region, "000000000000",
                "key/1234abcd-12ab-34cd-56ef-1234567890ab").toString();
        ObjectNode configuration = MAPPER.createObjectNode().put("SseType", "aws:kms").put("KmsKeyArn", key);
        for (String type : List.of("VectorBucket", "Index")) {
            provisioner.provision(resource(type), encryptionProps(type, configuration), context(null));
        }
        Map<String, String> expected = Map.of("sseType", "aws:kms", "kmsKeyArn", key);
        verify(service).createVectorBucket("my-bucket", expected, Map.of(), REGION);
        verify(service).createIndex("my-bucket", "my-index", 3, "float32", "cosine",
                List.of(), expected, Map.of(), REGION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "{}", "{\"SseType\":null}"})
    void optionalEncryptionConfigurationPreservesDefaults(String configuration) throws Exception {
        stubCreates();
        JsonNode config = MAPPER.readTree(configuration);
        for (String type : List.of("VectorBucket", "Index")) {
            provisioner.provision(resource(type), encryptionProps(type, config), context(null));
        }
        Map<String, String> expected = config.isNull() ? null : Map.of("sseType", "AES256");
        verify(service).createVectorBucket("my-bucket", expected, Map.of(), REGION);
        verify(service).createIndex("my-bucket", "my-index", 3, "float32", "cosine",
                List.of(), null, Map.of(), REGION);
    }

    private static ObjectNode encryptionProps(String type, JsonNode configuration) throws Exception {
        ObjectNode props = "Index".equals(type) ? indexProps()
                : MAPPER.createObjectNode().put("VectorBucketName", "my-bucket");
        props.set("EncryptionConfiguration", configuration);
        return props;
    }

    @Test
    void indexMapsBucketArnAndMetadataConfiguration() throws Exception {
        stubCreates();
        StackResource resource = resource("Index");
        ObjectNode props = indexProps();
        props.set("MetadataConfiguration", MAPPER.readTree("{\"NonFilterableMetadataKeys\":[\"text\"]}"));
        provisioner.provision(resource, props, context(null));

        verify(service).createIndex("my-bucket", "my-index", 3, "float32", "cosine",
                List.of("text"), null, Map.of(), REGION);
        assertEquals(INDEX_ARN, resource.getPhysicalId());
        assertEquals(INDEX_ARN, resource.getAttributes().get("IndexArn"));
        assertEquals(CREATION_TIME.toString(), resource.getAttributes().get("CreationTime"));
    }

    @Test
    void missingRequiredIndexPropertiesFailBeforeCreatingAnything() throws Exception {
        for (String required : List.of("DataType", "Dimension", "DistanceMetric")) {
            ObjectNode props = indexProps();
            props.remove(required);
            AwsException error = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource("Index"), props, context(null)));
            assertTrue(error.getMessage().contains(required));
        }
        verifyNoInteractions(service);
    }

    @Test
    void invalidBucketSelectorsFailBeforeCreatingAnything() throws Exception {
        for (String arn : List.of("not-an-arn", BUCKET_ARN + "/index/x",
                BUCKET_ARN.replace("us-east-1", "us-west-2"), BUCKET_ARN.replace("000000000000", "111111111111"))) {
            ObjectNode props = indexProps().put("VectorBucketArn", arn);
            assertThrows(AwsException.class, () -> provisioner.provision(resource("Index"), props, context(null)));
        }
        ObjectNode missing = indexProps();
        missing.remove("VectorBucketArn");
        assertThrows(AwsException.class, () -> provisioner.provision(resource("Index"), missing, context(null)));
        ObjectNode both = indexProps().put("VectorBucketName", "my-bucket");
        assertThrows(AwsException.class, () -> provisioner.provision(resource("Index"), both, context(null)));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t", "Uppercase", "under_score", "dot.name", "ab", "-bucket", "bucket-",
            "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijkl"})
    void invalidBucketNamesAreRejectedForBothSelectors(String name) throws Exception {
        for (String selector : List.of("VectorBucketName", "VectorBucketArn")) {
            ObjectNode props = indexProps();
            props.remove("VectorBucketArn");
            props.put(selector, "VectorBucketName".equals(selector) ? name : BUCKET_ARN.replace("my-bucket", name));
            AwsException error = assertThrows(AwsException.class,
                    () -> provisioner.provision(resource("Index"), props, context(null)));
            assertEquals("ValidationError", error.getErrorCode());
        }
        verifyNoInteractions(service);
    }

    @Test
    void blankExplicitResourceNamesAreRejected() throws Exception {
        for (String name : List.of("", " ")) {
            assertThrows(AwsException.class, () -> provisioner.provision(resource("VectorBucket"),
                    MAPPER.createObjectNode().put("VectorBucketName", name), context(null)));
            assertThrows(AwsException.class, () -> provisioner.provision(resource("Index"),
                    indexProps().put("IndexName", name), context(null)));
        }
        assertThrows(AwsException.class, () -> provisioner.provision(resource("Index"),
                indexProps().put("VectorBucketName", ""), context(null)));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VectorBucket", "Index"})
    void deletionSkipsOnlyConfirmedLegacyStubs(String type) {
        StackResource resource = legacyStub(type);
        assertDoesNotThrow(() -> provisioner.delete(resource, REGION));
        resource.getAttributes().clear();
        assertThrows(AwsException.class, () -> provisioner.delete(resource, REGION));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyBucketsBecomeRealResourcesAndCanRollBack(boolean named) throws Exception {
        stubCreates();
        StackResource resource = legacyStub("VectorBucket");
        ObjectNode props = named ? props("{\"VectorBucketName\":\"my-bucket\"}") : props("{}");
        provisioner.provision(resource, props, context(resource.getPhysicalId()));
        String name = resource.getPhysicalId().substring(resource.getPhysicalId().lastIndexOf('/') + 1);
        assertEquals(BUCKET_ARN.replace("my-bucket", name), resource.getPhysicalId());
        assertEquals(resource.getPhysicalId(), resource.getAttributes().get("VectorBucketArn"));
        assertNotNull(resource.getAttributes().get("CreationTime"));
        verify(service).createVectorBucket(name, null, Map.of(), REGION);
        verify(service, never()).getVectorBucket(anyString(), anyString());
        assertTrue(provisioner.rollbackUpdate(resource));
        assertTrue(CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes()));
        assertFalse(resource.getAttributes().containsKey("__FlociS3VectorsProperties"));
        verify(service).deleteVectorBucket(name, REGION);
        provisioner.delete(resource, REGION);
        verifyNoMoreInteractions(service);
    }

    @Test
    void legacyIndexMigrationDoesNotTryToDeleteThePlaceholder() throws Exception {
        stubCreates();
        StackResource resource = legacyStub("Index");
        provisioner.provision(resource, indexProps(), context(resource.getPhysicalId()));
        assertEquals(INDEX_ARN, resource.getPhysicalId());
        assertEquals(INDEX_ARN, resource.getAttributes().get("IndexArn"));
        assertNotNull(resource.getAttributes().get("CreationTime"));
        assertTrue(provisioner.completeUpdate(resource).complete());
        provisioner.clearUpdate(resource);
        verify(service).createIndex("my-bucket", "my-index", 3, "float32", "cosine",
                List.of(), null, Map.of(), REGION);
        verifyNoMoreInteractions(service);
    }

    private static StackResource legacyStub(String type) {
        StackResource resource = resource(type);
        resource.setPhysicalId("Vectors-1a2b3c4d");
        resource.getAttributes().put("Arn", "arn:aws:stub:::Vectors");
        return resource;
    }

    @Test
    void unnamedBucketKeepsItsIdentityAndCreationTimeDuringTagUpdateAndRollback() throws Exception {
        stubCreates();
        StackResource resource = resource("VectorBucket");
        provisioner.provision(resource, props("{}"), context(null));
        String arn = resource.getPhysicalId();
        String name = arn.substring(arn.lastIndexOf('/') + 1);
        assertTrue(name.matches("mystack-vectors-[0-9a-f]{12}"));
        String created = resource.getAttributes().get("CreationTime");
        VectorBucket bucket = new VectorBucket(name, arn, null);
        bucket.setCreationTime(Instant.parse(created));
        bucket.setTags(Map.of("old", "value"));
        when(service.getVectorBucket(name, REGION)).thenReturn(bucket);

        provisioner.provision(resource, props("{\"Tags\":[{\"Key\":\"new\",\"Value\":\"value\"}]}"), context(arn));
        assertEquals(arn, resource.getPhysicalId());
        assertEquals(created, resource.getAttributes().get("CreationTime"));
        verify(service).replaceBucketTags(name, Map.of("new", "value"), REGION);
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service).replaceBucketTags(name, Map.of("old", "value"), REGION);
        verify(service, times(1)).createVectorBucket(anyString(), any(), anyMap(), eq(REGION));
    }

    @Test
    void indexTagUpdatesAndRollbackPreserveIdentity() throws Exception {
        stubCreates();
        StackResource resource = resource("Index");
        provisioner.provision(resource, indexProps(), context(null));
        VectorIndex index = new VectorIndex("my-index", INDEX_ARN, "my-bucket", 3, "float32", "cosine");
        index.setCreationTime(CREATION_TIME);
        index.setTags(Map.of("old", "value"));
        when(service.getIndex("my-bucket", "my-index", REGION)).thenReturn(index);
        ObjectNode props = indexProps();
        props.set("Tags", MAPPER.readTree("[{\"Key\":\"new\",\"Value\":\"value\"}]"));

        provisioner.provision(resource, props, context(INDEX_ARN));

        assertEquals(INDEX_ARN, resource.getPhysicalId());
        assertEquals(CREATION_TIME.toString(), resource.getAttributes().get("CreationTime"));
        verify(service).replaceIndexTags("my-bucket", "my-index", Map.of("new", "value"), REGION);
        assertTrue(provisioner.rollbackUpdate(resource));
        verify(service).replaceIndexTags("my-bucket", "my-index", Map.of("old", "value"), REGION);
        verify(service, times(1)).createIndex(anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyList(), any(), anyMap(), eq(REGION));
    }

    @Test
    void immutableUpdateReplacesGeneratedIndexAndCleansUpPriorIndex() throws Exception {
        stubCreates();
        StackResource resource = resource("Index");
        ObjectNode props = indexProps();
        props.remove("IndexName");
        provisioner.provision(resource, props, context(null));
        String oldArn = resource.getPhysicalId();
        provisioner.provision(resource, props.deepCopy().put("Dimension", 4), context(oldArn));

        assertNotEquals(oldArn, resource.getPhysicalId());
        assertTrue(provisioner.hasReplacementUpdate(resource));
        assertEquals(oldArn, provisioner.updateCleanupPhysicalId(resource));
        assertTrue(provisioner.completeUpdate(resource).complete());
        assertFalse(resource.getAttributes().containsKey("__FlociS3VectorsPropertiesBeforeUpdate"));
        assertEquals(props.deepCopy().put("Dimension", 4).toString(),
                resource.getAttributes().get("__FlociS3VectorsProperties"));
        verify(service).deleteIndex("my-bucket", oldArn.substring(oldArn.lastIndexOf('/') + 1), REGION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VectorBucket", "Index"})
    void tagUpdateAfterReplacementRollbackReusesOriginalResource(String type) throws Exception {
        stubCreates();
        boolean index = "Index".equals(type);
        StackResource resource = resource(type);
        ObjectNode original = index ? indexProps() : props("{\"VectorBucketName\":\"my-bucket\"}");
        provisioner.provision(resource, original, context(null));
        String originalArn = resource.getPhysicalId();
        ObjectNode replacement = original.deepCopy().put(index ? "IndexName" : "VectorBucketName", "replacement");
        provisioner.provision(resource, replacement, context(originalArn));

        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(originalArn, resource.getPhysicalId());
        assertEquals(original.toString(), resource.getAttributes().get("__FlociS3VectorsProperties"));
        assertFalse(resource.getAttributes().containsKey("__FlociS3VectorsPropertiesBeforeUpdate"));

        VectorBucket bucket = new VectorBucket("my-bucket", BUCKET_ARN, null);
        bucket.setCreationTime(CREATION_TIME);
        when(service.getVectorBucket("my-bucket", REGION)).thenReturn(bucket);
        VectorIndex vectorIndex = new VectorIndex("my-index", INDEX_ARN, "my-bucket", 3, "float32", "cosine");
        vectorIndex.setCreationTime(CREATION_TIME);
        when(service.getIndex("my-bucket", "my-index", REGION)).thenReturn(vectorIndex);
        ObjectNode tagged = original.deepCopy();
        tagged.set("Tags", MAPPER.readTree("[{\"Key\":\"version\",\"Value\":\"second\"}]"));

        provisioner.provision(resource, tagged, context(originalArn));

        assertEquals(originalArn, resource.getPhysicalId());
        assertEquals(CREATION_TIME.toString(), resource.getAttributes().get("CreationTime"));
        assertFalse(provisioner.hasReplacementUpdate(resource));
        if (index) {
            verify(service).replaceIndexTags("my-bucket", "my-index", Map.of("version", "second"), REGION);
            verify(service, times(2)).createIndex(anyString(), anyString(), anyInt(), anyString(), anyString(),
                    anyList(), any(), anyMap(), eq(REGION));
        } else {
            verify(service).replaceBucketTags("my-bucket", Map.of("version", "second"), REGION);
            verify(service, times(2)).createVectorBucket(anyString(), any(), anyMap(), eq(REGION));
        }
    }

    @Test
    void replacementRollbackRetainsPropertySnapshotUntilDeletionSucceeds() throws Exception {
        stubCreates();
        StackResource resource = resource("Index");
        provisioner.provision(resource, indexProps(), context(null));
        provisioner.provision(resource, indexProps().put("IndexName", "replacement"), context(INDEX_ARN));
        doThrow(new AwsException("ConflictException", "retry deletion", 409)).doNothing()
                .when(service).deleteIndex("my-bucket", "replacement", REGION);

        assertThrows(AwsException.class, () -> provisioner.rollbackUpdate(resource));
        assertEquals(indexProps().toString(),
                resource.getAttributes().get("__FlociS3VectorsPropertiesBeforeUpdate"));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(indexProps().toString(), resource.getAttributes().get("__FlociS3VectorsProperties"));
        assertFalse(resource.getAttributes().containsKey("__FlociS3VectorsPropertiesBeforeUpdate"));
        verify(service, times(2)).deleteIndex("my-bucket", "replacement", REGION);
    }

    @Test
    void namedReplacementRequiresANewNameAndCanBeRolledBack() throws Exception {
        stubCreates();
        StackResource resource = resource("Index");
        provisioner.provision(resource, indexProps(), context(null));
        ObjectNode replacement = indexProps().put("Dimension", 4);
        assertThrows(AwsException.class, () -> provisioner.provision(resource, replacement, context(INDEX_ARN)));
        provisioner.provision(resource, replacement.put("IndexName", "replacement"), context(INDEX_ARN));
        assertTrue(provisioner.rollbackUpdate(resource));
        assertEquals(INDEX_ARN, resource.getPhysicalId());
        verify(service).deleteIndex("my-bucket", "replacement", REGION);
        verify(service, never()).deleteIndex("my-bucket", "my-index", REGION);
    }

    @Test
    void deletionToleratesOnlyMissingResources() {
        doThrow(new AwsException("NotFoundException", "gone", 404))
                .when(service).deleteIndex("my-bucket", "my-index", REGION);
        assertDoesNotThrow(() -> provisioner.delete("AWS::S3Vectors::Index", INDEX_ARN, REGION));
        doThrow(new AwsException("ConflictException", "not empty", 409))
                .when(service).deleteVectorBucket("my-bucket", REGION);
        assertThrows(AwsException.class, () -> provisioner.delete("AWS::S3Vectors::VectorBucket", BUCKET_ARN, REGION));
    }
}
