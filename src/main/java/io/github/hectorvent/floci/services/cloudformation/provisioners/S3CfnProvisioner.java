package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Provisions {@code AWS::S3::Bucket} and {@code AWS::S3::BucketPolicy}.
 *
 * <p>Each declared bucket property is translated into the XML its own S3 API call takes and handed
 * to the same service method, so the service validates and stores it exactly as it would a direct
 * {@code PutBucket*} request. A property the template does not declare is left alone.
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
            case BUCKET_POLICY -> provisionBucketPolicy(r, props, ctx);
            default -> throw new IllegalStateException(
                    "S3CfnProvisioner cannot provision " + r.getResourceType());
        }
    }

    /**
     * The policy's physical id, kept across updates rather than regenerated.
     *
     * <p>{@code provision} runs again on every UpdateStack, so minting a fresh id each time made an
     * unchanged policy look like a replaced resource and changed what {@code Ref} returned.
     *
     * <p>The generated value itself is left alone deliberately. The sources disagree on what it
     * should be: the current registry schema gives {@code primaryIdentifier} as
     * {@code /properties/Bucket}, while the older schema localstack embeds gives
     * {@code /properties/Id} as an md5 of the policy document. Changing what Ref resolves to on that
     * evidence would be guessing; keeping the id stable fixes the defect either way.
     */
    private String bucketPolicyId(ProvisionContext ctx) {
        return ctx.isUpdate()
                ? ctx.priorPhysicalId()
                : "bucket-policy-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void provisionBucket(StackResource r, JsonNode props, ProvisionContext ctx) {
        String bucketName = ctx.stablePhysicalName(ctx.resolveOptional(props, "BucketName"),
                r.getLogicalId(), BUCKET_NAME_MAX_LENGTH, true);
        // provision is also the update path. CreateBucket on a bucket this account already owns is
        // idempotent only in us-east-1; every other region answers BucketAlreadyOwnedByYou, as on
        // AWS. With the name now stable across updates, the second UpdateStack must skip the create
        // and only reconcile the bucket's configuration. A replacing update derives a different
        // name and still creates, hence reusesPriorEntity rather than isUpdate.
        if (!ctx.reusesPriorEntity(bucketName)) {
            s3Service.createBucket(bucketName, ctx.region());
        }
        applyBucketCorsConfiguration(bucketName, props, ctx);
        applyBucketVersioningConfiguration(bucketName, props, ctx);
        applyPublicAccessBlockConfiguration(bucketName, props, ctx);
        applyBucketEncryption(bucketName, props, ctx);
        applyLifecycleConfiguration(bucketName, props, ctx);
        applyBucketTags(bucketName, props, ctx);
        r.setPhysicalId(bucketName);
        r.getAttributes().put("Arn", AwsArnUtils.Arn.of("s3", "", "", bucketName).toString());
        r.getAttributes().put("DomainName", bucketName + ".s3.amazonaws.com");
        r.getAttributes().put("RegionalDomainName", bucketName + ".s3." + ctx.region() + ".amazonaws.com");
        r.getAttributes().put("DualStackDomainName",
                bucketName + ".s3.dualstack." + ctx.region() + ".amazonaws.com");
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

    private void applyPublicAccessBlockConfiguration(String bucketName, JsonNode props, ProvisionContext ctx) {
        JsonNode pab = declared(props, "PublicAccessBlockConfiguration");
        if (pab == null) {
            return;
        }
        XmlBuilder xml = new XmlBuilder().start("PublicAccessBlockConfiguration", AwsNamespaces.S3);
        for (String flag : List.of("BlockPublicAcls", "IgnorePublicAcls", "BlockPublicPolicy",
                "RestrictPublicBuckets")) {
            appendResolved(xml, flag, pab, flag, ctx);
        }
        s3Service.putPublicAccessBlock(bucketName, xml.end("PublicAccessBlockConfiguration").build());
    }

    private void applyBucketEncryption(String bucketName, JsonNode props, ProvisionContext ctx) {
        JsonNode encryption = declared(props, "BucketEncryption");
        if (encryption == null || !encryption.has("ServerSideEncryptionConfiguration")) {
            return;
        }
        XmlBuilder xml = new XmlBuilder().start("ServerSideEncryptionConfiguration", AwsNamespaces.S3);
        for (JsonNode rule : encryption.get("ServerSideEncryptionConfiguration")) {
            xml.start("Rule");
            JsonNode byDefault = rule.get("ServerSideEncryptionByDefault");
            if (byDefault != null && !byDefault.isNull()) {
                xml.start("ApplyServerSideEncryptionByDefault");
                appendResolved(xml, "SSEAlgorithm", byDefault, "SSEAlgorithm", ctx);
                appendResolved(xml, "KMSMasterKeyID", byDefault, "KMSMasterKeyID", ctx);
                xml.end("ApplyServerSideEncryptionByDefault");
            }
            appendResolved(xml, "BucketKeyEnabled", rule, "BucketKeyEnabled", ctx);
            xml.end("Rule");
        }
        s3Service.putBucketEncryption(bucketName, xml.end("ServerSideEncryptionConfiguration").build());
    }

    private void applyLifecycleConfiguration(String bucketName, JsonNode props, ProvisionContext ctx) {
        JsonNode lifecycle = declared(props, "LifecycleConfiguration");
        if (lifecycle == null || !lifecycle.has("Rules")) {
            return;
        }
        s3Service.putBucketLifecycle(bucketName, lifecycleXml(lifecycle.get("Rules"), ctx), null);
    }

    private void applyBucketTags(String bucketName, JsonNode props, ProvisionContext ctx) {
        if (declared(props, "Tags") == null) {
            return;
        }
        s3Service.putBucketTagging(bucketName, ctx.resolveTags(props, "Tags"));
    }

    /** CloudFormation's lifecycle rule property names, spelled as the S3 API's XML. */
    private String lifecycleXml(JsonNode rules, ProvisionContext ctx) {
        XmlBuilder xml = new XmlBuilder().start("LifecycleConfiguration", AwsNamespaces.S3);
        for (JsonNode rule : rules) {
            xml.start("Rule");
            appendResolved(xml, "ID", rule, "Id", ctx);
            appendResolved(xml, "Status", rule, "Status", ctx);

            // S3 requires a Filter (or the deprecated top-level Prefix) on every rule; a rule that
            // declares neither applies to the whole bucket, which is an empty prefix filter.
            String prefix = ctx.resolveOptional(rule, "Prefix");
            JsonNode tagFilters = rule.get("TagFilters");
            boolean hasTags = tagFilters != null && tagFilters.isArray() && !tagFilters.isEmpty();
            xml.start("Filter");
            if (hasTags && (tagFilters.size() > 1 || (prefix != null && !prefix.isEmpty()))) {
                xml.start("And");
                if (prefix != null && !prefix.isEmpty()) {
                    xml.elem("Prefix", prefix);
                }
                for (JsonNode tag : tagFilters) {
                    appendLifecycleTag(xml, tag, ctx);
                }
                xml.end("And");
            } else if (hasTags) {
                appendLifecycleTag(xml, tagFilters.get(0), ctx);
            } else {
                xml.elem("Prefix", prefix == null ? "" : prefix);
            }
            xml.end("Filter");

            String expirationDays = ctx.resolveOptional(rule, "ExpirationInDays");
            String expirationDate = ctx.resolveOptional(rule, "ExpirationDate");
            String deleteMarker = ctx.resolveOptional(rule, "ExpiredObjectDeleteMarker");
            if (isSet(expirationDays) || isSet(expirationDate) || isSet(deleteMarker)) {
                xml.start("Expiration");
                if (isSet(expirationDays)) {
                    xml.elem("Days", expirationDays);
                }
                if (isSet(expirationDate)) {
                    xml.elem("Date", expirationDate);
                }
                if (isSet(deleteMarker)) {
                    xml.elem("ExpiredObjectDeleteMarker", deleteMarker);
                }
                xml.end("Expiration");
            }
            JsonNode noncurrent = rule.get("NoncurrentVersionExpiration");
            if (noncurrent != null && !noncurrent.isNull()) {
                xml.start("NoncurrentVersionExpiration");
                appendResolved(xml, "NoncurrentDays", noncurrent, "NoncurrentDays", ctx);
                appendResolved(xml, "NewerNoncurrentVersions", noncurrent, "NewerNoncurrentVersions", ctx);
                xml.end("NoncurrentVersionExpiration");
            }
            JsonNode abort = rule.get("AbortIncompleteMultipartUpload");
            if (abort != null && !abort.isNull()) {
                xml.start("AbortIncompleteMultipartUpload");
                appendResolved(xml, "DaysAfterInitiation", abort, "DaysAfterInitiation", ctx);
                xml.end("AbortIncompleteMultipartUpload");
            }
            appendLifecycleTransitions(xml, rule.get("Transitions"), "Transition", "Days", ctx);
            appendLifecycleTransitions(xml, rule.get("NoncurrentVersionTransitions"),
                    "NoncurrentVersionTransition", "NoncurrentDays", ctx);
            xml.end("Rule");
        }
        return xml.end("LifecycleConfiguration").build();
    }

    private void appendLifecycleTransitions(XmlBuilder xml, JsonNode transitions, String element,
                                            String daysElement, ProvisionContext ctx) {
        if (transitions == null || !transitions.isArray()) {
            return;
        }
        for (JsonNode transition : transitions) {
            xml.start(element);
            appendResolved(xml, daysElement, transition, "TransitionInDays", ctx);
            appendResolved(xml, "StorageClass", transition, "StorageClass", ctx);
            xml.end(element);
        }
    }

    private void appendLifecycleTag(XmlBuilder xml, JsonNode tag, ProvisionContext ctx) {
        xml.start("Tag");
        appendResolved(xml, "Key", tag, "Key", ctx);
        appendResolved(xml, "Value", tag, "Value", ctx);
        xml.end("Tag");
    }

    private void appendResolved(XmlBuilder xml, String element, JsonNode props, String property,
                                ProvisionContext ctx) {
        String value = ctx.resolveOptional(props, property);
        if (isSet(value)) {
            xml.elem(element, value);
        }
    }

    /** The named property when the template declares it with a value, null otherwise. */
    private static JsonNode declared(JsonNode props, String name) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return props.get(name);
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Puts the declared document on the bucket. Before this the policy resource took a physical id
     * and stopped, so a stack reported CREATE_COMPLETE for a bucket that carried no policy at all.
     */
    private void provisionBucketPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        r.setPhysicalId(bucketPolicyId(ctx));
        String bucketName = ctx.resolveOptional(props, "Bucket");
        JsonNode document = declared(props, "PolicyDocument");
        if (bucketName == null || bucketName.isBlank() || document == null) {
            return;
        }
        // A document given as a JSON string is already the policy; one given as an object may
        // carry Ref / Fn::GetAtt / Fn::Sub anywhere inside it.
        String policy = document.isTextual()
                ? document.asText()
                : ctx.engine().resolveNode(document).toString();
        s3Service.putBucketPolicy(bucketName, policy);
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
