package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3vectors.S3VectorsService;
import io.github.hectorvent.floci.services.s3vectors.model.VectorBucket;
import io.github.hectorvent.floci.services.s3vectors.model.VectorIndex;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class S3VectorsCfnProvisionerTest {

    private static final String REGION = "us-east-1";
    private static final String BUCKET_ARN = "arn:aws:s3vectors:us-east-1:000000000000:bucket/my-bucket";
    private static final String INDEX_ARN = BUCKET_ARN + "/index/my-index";
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
                .thenAnswer(invocation -> new VectorBucket(invocation.getArgument(0),
                        BUCKET_ARN.replace("my-bucket", invocation.getArgument(0)), invocation.getArgument(1)));
        when(service.createIndex(anyString(), anyString(), anyInt(), anyString(), anyString(),
                anyList(), any(), anyMap(), eq(REGION))).thenAnswer(invocation ->
                new VectorIndex(invocation.getArgument(1), BUCKET_ARN + "/index/" + invocation.getArgument(1),
                        invocation.getArgument(0), invocation.getArgument(2), invocation.getArgument(3),
                        invocation.getArgument(4)));
    }

    @Test
    void bucketMapsEncryptionAndTagsAndExposesArnAndCreationTime() throws Exception {
        stubCreates();
        StackResource resource = resource("VectorBucket");
        provisioner.provision(resource, props("""
                {"VectorBucketName":"my-bucket","EncryptionConfiguration":{"SseType":"aws:kms","KmsKeyArn":"key"},
                 "Tags":[{"Key":"team","Value":"search"}]}
                """), context(null));

        verify(service).createVectorBucket("my-bucket", Map.of("sseType", "aws:kms", "kmsKeyArn", "key"),
                Map.of("team", "search"), REGION);
        assertEquals(BUCKET_ARN, resource.getPhysicalId());
        assertEquals(BUCKET_ARN, resource.getAttributes().get("VectorBucketArn"));
        assertNotNull(resource.getAttributes().get("CreationTime"));
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
        assertNotNull(resource.getAttributes().get("CreationTime"));
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
        verify(service).deleteIndex("my-bucket", oldArn.substring(oldArn.lastIndexOf('/') + 1), REGION);
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
