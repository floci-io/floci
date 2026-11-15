package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.AwsRegions;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.s3vectors.S3VectorsService;
import io.floci.aws.services.s3vectors.model.VectorBucket;
import io.floci.aws.services.s3vectors.model.VectorIndex;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@ApplicationScoped
public class S3VectorsCfnProvisioner implements CfnResourceProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PROPERTIES = "__FlociS3VectorsProperties";
    private static final String PROPERTIES_PRIOR = "__FlociS3VectorsPropertiesBeforeUpdate";
    private static final String TAG_SNAPSHOT = "__FlociS3VectorsTagsBeforeUpdate";
    private static final Pattern KMS_KEY_ARN = Pattern.compile(
            "arn:" + AwsArnUtils.PARTITION_REGEX + ":kms:[-a-z0-9]*:[0-9]{12}:key/.+");

    private final S3VectorsService service;

    public S3VectorsCfnProvisioner(S3VectorsService service) {
        this.service = service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::S3Vectors::VectorBucket", "AWS::S3Vectors::Index");
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        ObjectNode resolved = props == null ? MAPPER.createObjectNode()
                : (ObjectNode) ctx.engine().resolveNode(props);
        ObjectNode immutable = resolved.deepCopy();
        immutable.remove("Tags");
        Map<String, String> before = new HashMap<>(resource.getAttributes());
        boolean updatesEntity = ctx.isUpdate() && !CfnResourceDispatcher.isStub(ctx.priorPhysicalId(), before);
        boolean reuse = updatesEntity && immutable.equals(readProperties(resource));
        Map<String, String> tags = ctx.resolveTags(resolved, "Tags");
        switch (resource.getResourceType()) {
            case "AWS::S3Vectors::VectorBucket" -> provisionBucket(resource, resolved, tags, reuse, updatesEntity, ctx);
            case "AWS::S3Vectors::Index" -> provisionIndex(resource, resolved, tags, reuse, updatesEntity, ctx);
            default -> throw new IllegalStateException("Unsupported S3 Vectors type: " + resource.getResourceType());
        }
        resource.getAttributes().put(PROPERTIES, immutable.toString());
        ReplacementCleanup.record(resource, ctx, before);
        if (ctx.isUpdate() && !ctx.priorPhysicalId().equals(resource.getPhysicalId())) {
            resource.getAttributes().put(PROPERTIES_PRIOR, before.getOrDefault(PROPERTIES, ""));
        }
    }

    private void provisionBucket(StackResource resource, JsonNode props, Map<String, String> tags,
                                 boolean reuse, boolean updatesEntity, ProvisionContext ctx) {
        VectorBucket bucket;
        if (reuse) {
            String name = arnParts(ctx.priorPhysicalId(), false)[1];
            bucket = service.getVectorBucket(name, ctx.region());
            snapshotTags(resource, bucket.getTags());
            service.replaceBucketTags(name, tags, ctx.region());
        } else {
            String name = physicalName(props, "VectorBucketName", resource, ctx, false);
            rejectSameNameReplacement(ctx, updatesEntity, "bucket/" + name);
            bucket = service.createVectorBucket(name, encryption(props, ctx, "AES256"), tags, ctx.region());
        }
        resource.setPhysicalId(bucket.getVectorBucketArn());
        resource.getAttributes().put("VectorBucketArn", bucket.getVectorBucketArn());
        resource.getAttributes().put("CreationTime", bucket.getCreationTime().toString());
    }

    private void provisionIndex(StackResource resource, JsonNode props, Map<String, String> tags,
                                boolean reuse, boolean updatesEntity, ProvisionContext ctx) {
        String bucketName = bucketName(props, ctx);
        String dataType = required(props, "DataType", ctx);
        String distanceMetric = required(props, "DistanceMetric", ctx);
        int dimension;
        try {
            dimension = Integer.parseInt(required(props, "Dimension", ctx));
        } catch (NumberFormatException e) {
            throw validation("Dimension must be an integer between 1 and 4096.");
        }
        VectorIndex index;
        if (reuse) {
            String name = arnParts(ctx.priorPhysicalId(), true)[3];
            index = service.getIndex(bucketName, name, ctx.region());
            snapshotTags(resource, index.getTags());
            service.replaceIndexTags(bucketName, name, tags, ctx.region());
        } else {
            String name = physicalName(props, "IndexName", resource, ctx, true);
            rejectSameNameReplacement(ctx, updatesEntity, "bucket/" + bucketName + "/index/" + name);
            JsonNode metadataKeys = props.path("MetadataConfiguration").path("NonFilterableMetadataKeys");
            List<String> keys = new ArrayList<>();
            if (!metadataKeys.isMissingNode()) {
                if (!metadataKeys.isArray()) {
                    throw validation("NonFilterableMetadataKeys must be a list of strings.");
                }
                for (JsonNode key : metadataKeys) {
                    keys.add(key.isTextual() ? key.textValue() : null);
                }
            }
            index = service.createIndex(bucketName, name, dimension, dataType, distanceMetric,
                    keys, encryption(props, ctx, null), tags, ctx.region());
        }
        resource.setPhysicalId(index.getIndexArn());
        resource.getAttributes().put("IndexArn", index.getIndexArn());
        resource.getAttributes().put("CreationTime", index.getCreationTime().toString());
    }

    private static String physicalName(JsonNode props, String property, StackResource resource,
                                       ProvisionContext ctx, boolean index) {
        String name = ctx.resolveOptional(props, property);
        if (name == null) {
            return ctx.generatePhysicalName(resource.getLogicalId(), 63, true);
        }
        return validateName(name, property, index);
    }

    private static String validateName(String name, String property, boolean index) {
        return S3VectorsService.validateName(name, property, index, "ValidationError");
    }

    private static void rejectSameNameReplacement(ProvisionContext ctx, boolean updatesEntity, String resourcePath) {
        if (updatesEntity && AwsArnUtils.parse(ctx.priorPhysicalId()).resource().equals(resourcePath)) {
            throw validation("Updating this custom-named S3 Vectors resource requires replacement. Specify a new name.");
        }
    }

    private static String bucketName(JsonNode props, ProvisionContext ctx) {
        S3VectorsService.ResolvedBucket resolved = S3VectorsService.resolveBucket(
                ctx.resolveOptional(props, "VectorBucketName"),
                ctx.resolveOptional(props, "VectorBucketArn"),
                ctx.region(), AwsRegions::partitionFor, "ValidationError");
        if (resolved.accountId() != null
                && (!ctx.region().equals(resolved.region()) || !ctx.accountId().equals(resolved.accountId()))) {
            throw validation("VectorBucketArn must belong to the stack's region and account.");
        }
        return resolved.bucketName();
    }

    private static String[] arnParts(String arn, boolean index) {
        return S3VectorsService.arnParts(arn, index, "ValidationError");
    }

    private static Object encryption(JsonNode props, ProvisionContext ctx, String defaultType) {
        JsonNode config = props.path("EncryptionConfiguration");
        if (config.isMissingNode() || config.isNull()) {
            return null;
        }
        if (!config.isObject()) {
            throw validation("EncryptionConfiguration must be an object.");
        }
        String type = ctx.resolveOrDefault(config, "SseType", defaultType);
        String key = ctx.resolveOptional(config, "KmsKeyArn");
        if ((type != null && !"AES256".equals(type) && !"aws:kms".equals(type))
                || (key != null && !"aws:kms".equals(type))) {
            throw validation("EncryptionConfiguration requires AES256 or aws:kms; KmsKeyArn requires aws:kms.");
        }
        if (type == null) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        result.put("sseType", type);
        if (key != null) {
            if (key.length() > 2048 || !KMS_KEY_ARN.matcher(key).matches()) {
                throw validation("KmsKeyArn must be a KMS key ARN of at most 2048 characters.");
            }
            result.put("kmsKeyArn", key);
        }
        return result;
    }

    private static String required(JsonNode props, String name, ProvisionContext ctx) {
        String value = ctx.resolveOrDefault(props, name, null);
        if (value == null) {
            throw validation(name + " is required for AWS::S3Vectors::Index.");
        }
        return value;
    }

    private static JsonNode readProperties(StackResource resource) {
        return readJson(resource.getAttributes().getOrDefault(PROPERTIES, "{}"));
    }

    private static JsonNode readJson(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot read S3 Vectors CloudFormation update state", e);
        }
    }

    private static void snapshotTags(StackResource resource, Map<String, String> tags) {
        resource.getAttributes().put(TAG_SNAPSHOT, MAPPER.valueToTree(tags).toString());
    }

    private static AwsException validation(String message) {
        return new AwsException("ValidationError", message, 400);
    }

    @Override
    public void delete(StackResource resource, String region) {
        if (!CfnResourceDispatcher.isStub(resource.getPhysicalId(), resource.getAttributes())) {
            delete(resource.getResourceType(), resource.getPhysicalId(), region);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        boolean index = "AWS::S3Vectors::Index".equals(resourceType);
        String[] parts = arnParts(physicalId, index);
        CfnDeletes.safeDelete("S3 Vectors resource", physicalId, () -> {
            if (index) {
                service.deleteIndex(parts[1], parts[3], region);
            } else {
                service.deleteVectorBucket(parts[1], region);
            }
        }, "NotFoundException");
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        boolean replaced = ReplacementCleanup.rollback(resource, this::delete);
        String prior = resource.getAttributes().remove(PROPERTIES_PRIOR);
        if (prior != null) {
            if (prior.isEmpty()) {
                resource.getAttributes().remove(PROPERTIES);
            } else {
                resource.getAttributes().put(PROPERTIES, prior);
            }
        }
        if (replaced) {
            return true;
        }
        String snapshot = resource.getAttributes().get(TAG_SNAPSHOT);
        if (snapshot == null) {
            return false;
        }
        Map<String, String> tags = new LinkedHashMap<>();
        readJson(snapshot).fields().forEachRemaining(entry -> tags.put(entry.getKey(), entry.getValue().asText()));
        String region = AwsArnUtils.parse(resource.getPhysicalId()).region();
        boolean index = "AWS::S3Vectors::Index".equals(resource.getResourceType());
        String[] parts = arnParts(resource.getPhysicalId(), index);
        if (index) {
            service.replaceIndexTags(parts[1], parts[3], tags, region);
        } else {
            service.replaceBucketTags(parts[1], tags, region);
        }
        resource.getAttributes().remove(TAG_SNAPSHOT);
        return true;
    }

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return ReplacementCleanup.cleanupPhysicalId(resource);
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        resource.getAttributes().remove(PROPERTIES_PRIOR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(PROPERTIES_PRIOR);
        resource.getAttributes().remove(TAG_SNAPSHOT);
        ReplacementCleanup.clear(resource);
    }
}
