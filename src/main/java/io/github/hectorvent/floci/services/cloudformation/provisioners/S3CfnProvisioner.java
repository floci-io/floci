package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;
import java.util.UUID;

/**
 * Provisions {@code AWS::S3::Bucket} and {@code AWS::S3::BucketPolicy}.
 *
 * <p>The bucket policy is accepted and given a physical id but not enforced: Floci does not
 * evaluate S3 policies, and failing the resource would break every template that attaches one.
 */
@ApplicationScoped
public class S3CfnProvisioner implements CfnResourceProvisioner {

    private static final String BUCKET = "AWS::S3::Bucket";
    private static final String BUCKET_POLICY = "AWS::S3::BucketPolicy";
    private static final int BUCKET_NAME_MAX_LENGTH = 63;

    private final S3Service s3Service;

    public S3CfnProvisioner(S3Service s3Service) {
        this.s3Service = s3Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(BUCKET, BUCKET_POLICY);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case BUCKET -> provisionBucket(r, props, ctx);
            case BUCKET_POLICY ->
                    r.setPhysicalId("bucket-policy-" + UUID.randomUUID().toString().substring(0, 8));
            default -> throw new IllegalStateException(
                    "S3CfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    private void provisionBucket(StackResource r, JsonNode props, ProvisionContext ctx) {
        String bucketName = ctx.resolveOptional(props, "BucketName");
        if (bucketName == null || bucketName.isBlank()) {
            bucketName = ctx.generatePhysicalName(r.getLogicalId(), BUCKET_NAME_MAX_LENGTH, true);
        }
        s3Service.createBucket(bucketName, ctx.region());
        applyBucketCorsConfiguration(bucketName, props, ctx);
        applyBucketVersioningConfiguration(bucketName, props, ctx);
        r.setPhysicalId(bucketName);
        r.getAttributes().put("Arn", AwsArnUtils.Arn.of("s3", "", "", bucketName).toString());
        r.getAttributes().put("DomainName", bucketName + ".s3.amazonaws.com");
        r.getAttributes().put("RegionalDomainName", bucketName + ".s3." + ctx.region() + ".amazonaws.com");
        r.getAttributes().put("WebsiteURL",
                "http://" + bucketName + ".s3-website." + ctx.region() + ".amazonaws.com");
        r.getAttributes().put("BucketName", bucketName);
    }

    private void applyBucketCorsConfiguration(String bucketName, JsonNode props, ProvisionContext ctx) {
        JsonNode corsRules = null;
        if (props != null && props.has("CorsConfiguration") && !props.get("CorsConfiguration").isNull()) {
            corsRules = props.get("CorsConfiguration").get("CorsRules");
        }
        if (corsRules == null || !corsRules.isArray() || corsRules.isEmpty()) {
            s3Service.deleteBucketCors(bucketName);
            return;
        }
        XmlBuilder xml = new XmlBuilder().start("CORSConfiguration", AwsNamespaces.S3);
        for (JsonNode rule : corsRules) {
            xml.start("CORSRule");
            xml.elem("ID", ctx.resolveOptional(rule, "Id"));
            appendCorsRuleElements(xml, rule.get("AllowedHeaders"), "AllowedHeader", ctx);
            appendCorsRuleElements(xml, rule.get("AllowedMethods"), "AllowedMethod", ctx);
            appendCorsRuleElements(xml, rule.get("AllowedOrigins"), "AllowedOrigin", ctx);
            appendCorsRuleElements(xml, rule.get("ExposedHeaders"), "ExposeHeader", ctx);
            String maxAge = ctx.resolveOptional(rule, "MaxAge");
            if (maxAge != null && !maxAge.isBlank()) {
                xml.elem("MaxAgeSeconds", maxAge);
            }
            xml.end("CORSRule");
        }
        xml.end("CORSConfiguration");
        s3Service.putBucketCors(bucketName, xml.build());
    }

    private void appendCorsRuleElements(XmlBuilder xml, JsonNode values, String elementName,
                                        ProvisionContext ctx) {
        if (values == null || !values.isArray()) {
            return;
        }
        for (JsonNode value : values) {
            if (value != null && !value.isNull()) {
                String resolved = ctx.engine().resolve(value);
                if (resolved != null && !resolved.isBlank()) {
                    xml.elem(elementName, resolved);
                }
            }
        }
    }

    private void applyBucketVersioningConfiguration(String bucketName, JsonNode props,
                                                    ProvisionContext ctx) {
        if (props == null || !props.has("VersioningConfiguration")
                || props.get("VersioningConfiguration").isNull()) {
            return;
        }
        String status = ctx.resolveOptional(props.get("VersioningConfiguration"), "Status");
        if (status != null && !status.isBlank()) {
            s3Service.putBucketVersioning(bucketName, status);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // A bucket policy has no backing resource to remove. Deleting a non-empty bucket raises
        // BucketNotEmpty, which propagates so the stack reports DELETE_FAILED as AWS does.
        if (BUCKET.equals(resourceType)) {
            s3Service.deleteBucket(physicalId);
        }
    }
}
