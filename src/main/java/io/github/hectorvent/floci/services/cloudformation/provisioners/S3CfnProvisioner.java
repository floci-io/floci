package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsEndpoints;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.AwsNamespaces;
import io.github.hectorvent.floci.core.common.AwsPartitions;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.XmlBuilder;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.FilterRule;
import io.github.hectorvent.floci.services.s3.model.LambdaNotification;
import io.github.hectorvent.floci.services.s3.model.NotificationConfiguration;
import io.github.hectorvent.floci.services.s3.model.QueueNotification;
import io.github.hectorvent.floci.services.s3.model.TopicNotification;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Provisions {@code AWS::S3::Bucket} and {@code AWS::S3::BucketPolicy}.
 *
 * <p>A property this class covers is translated into the XML its own S3 API call takes and handed
 * to the same S3 service method used by a direct {@code PutBucket*} request. Ownership controls
 * are validated here before the service stores their XML. A property the template does not declare is left alone, except that
 * an update removing {@code NotificationConfiguration} or {@code OwnershipControls} clears it.
 *
 * <p>Covered on {@code AWS::S3::Bucket}: {@code BucketName}, {@code CorsConfiguration},
 * {@code VersioningConfiguration}, {@code PublicAccessBlockConfiguration},
 * {@code BucketEncryption}, {@code LifecycleConfiguration},
 * {@code OwnershipControls}, {@code NotificationConfiguration} and
 * {@code Tags}. {@code NotificationConfiguration} is the exception to the XML translation: its
 * service method takes the parsed model, so the provisioner builds that model instead.
 *
 * <p><b>Silently dropped</b>, each of which has a service method waiting for it:
 * {@code WebsiteConfiguration}, {@code LoggingConfiguration},
 * {@code ObjectLockConfiguration}, {@code ReplicationConfiguration},
 * {@code AccelerateConfiguration}, {@code AccessControl}, and the four numbered configuration
 * lists ({@code AnalyticsConfigurations}, {@code IntelligentTieringConfigurations},
 * {@code InventoryConfigurations}, {@code MetricsConfigurations}). Declaring one of these still
 * reports CREATE_COMPLETE for a bucket that does not carry it.
 *
 * <p>{@code WebsiteURL} is reported whether or not website hosting is configured. That is not a
 * gap: the registry schema lists it under {@code readOnlyProperties} and AWS returns the URL for
 * every bucket, hosting or no hosting.
 */
@ApplicationScoped
public class S3CfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(S3CfnProvisioner.class);

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

    private void provisionBucket(StackResource r, JsonNode props, ProvisionContext ctx) {
        String bucketName = ctx.stablePhysicalName(ctx.resolveOptional(props, "BucketName"),
                r.getLogicalId(), BUCKET_NAME_MAX_LENGTH, true);
        // provision is also the update path. CreateBucket on a bucket this account already owns is
        // idempotent only in us-east-1; every other region answers BucketAlreadyOwnedByYou, as on
        // AWS. With the name now stable across updates, the second UpdateStack must skip the create
        // and only reconcile the bucket's configuration. A replacing update derives a different
        // name and still creates, hence reusesPriorEntity rather than isUpdate.
        Unwind unwind = new Unwind();
        r.getAttributes().remove(CfnRollback.S3_BUCKET_UPDATE_SNAPSHOT_ATTR);
        try {
            // Built before anything is created, so a configuration that fails validation creates nothing.
            NotificationConfiguration notifications = notificationConfiguration(props, ctx);
            String ownershipControlsXml = ownershipControlsXml(props, ctx);
            if (ctx.reusesPriorEntity(bucketName)) {
                r.getAttributes().put(CfnRollback.S3_BUCKET_UPDATE_SNAPSHOT_ATTR,
                        s3Service.snapshotBucketConfiguration(bucketName));
            }
            if (!ctx.reusesPriorEntity(bucketName)) {
                // In us-east-1 CreateBucket also answers for a bucket the account already owns,
                // which this resource must never delete.
                boolean owned = s3Service.ownsBucket(bucketName);
                s3Service.createBucket(bucketName, ctx.region());
                unwind.created = !owned;
            }
            // Ahead of the other properties, so a destination S3 cannot validate fails the resource
            // before any of them has changed the bucket. An unchanged configuration is not put again:
            // each put sends a fresh s3:TestEvent to every queue and topic.
            if (notifications != null) {
                NotificationConfiguration current = s3Service.getBucketNotificationConfiguration(bucketName);
                if (!sameConfiguration(notifications, current)) {
                    s3Service.putBucketNotificationConfiguration(bucketName, notifications);
                    unwind.replacedNotifications = current;
                }
            }
            applyBucketCorsConfiguration(bucketName, props, ctx);
            applyBucketVersioningConfiguration(bucketName, props, ctx);
            applyPublicAccessBlockConfiguration(bucketName, props, ctx);
            applyBucketEncryption(bucketName, props, ctx);
            applyLifecycleConfiguration(bucketName, props, ctx);
            applyBucketTags(bucketName, props, ctx);
            applyBucketOwnershipControls(bucketName, ownershipControlsXml, ctx);
        } catch (RuntimeException failure) {
            unwind(r, bucketName, unwind, ctx, failure);
            throw failure;
        }
        r.setPhysicalId(bucketName);
        r.getAttributes().put("Arn",
                AwsArnUtils.Arn.global(AwsRegions.partitionFor(ctx.region()), "s3", "", bucketName).toString());
        r.getAttributes().put("DomainName", AwsEndpoints.s3Host(bucketName, ctx.region()));
        r.getAttributes().put("RegionalDomainName", AwsEndpoints.s3RegionalHost(bucketName, ctx.region()));
        if (AwsPartitions.forRegionOrCommercial(ctx.region()).supportsS3DualStack(ctx.region())) {
            r.getAttributes().put("DualStackDomainName", AwsEndpoints.s3DualStackHost(bucketName, ctx.region()));
        } else {
            // An update arrives with the prior attributes already on the resource.
            r.getAttributes().remove("DualStackDomainName");
        }
        r.getAttributes().put("WebsiteURL", AwsEndpoints.s3WebsiteUrl(bucketName, ctx.region()));
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

    private String ownershipControlsXml(JsonNode props, ProvisionContext ctx) {
        JsonNode declaredNode = declared(props, "OwnershipControls");
        if (declaredNode == null) {
            return null;
        }
        JsonNode controls = ctx.engine().resolveNodeOmittingNoValue(declaredNode);
        if (controls == null || controls.isMissingNode() || controls.isNull()) {
            return null;
        }
        String path = "#/OwnershipControls";
        requireObject(controls, path);
        JsonNode rules = declaredArray(controls, "Rules", path);
        XmlBuilder xml = new XmlBuilder().start("OwnershipControls", AwsNamespaces.S3);
        for (int index = 0; rules != null && index < rules.size(); index++) {
            String rulePath = path + "/Rules/" + index;
            JsonNode rule = requireObject(rules.get(index), rulePath);
            String ownership = required(rule, "ObjectOwnership", rulePath);
            xml.start("Rule");
            xml.elem("ObjectOwnership", ownership);
            xml.end("Rule");
        }
        String document = xml.end("OwnershipControls").build();
        S3Service.validateOwnershipControls(document);
        return document;
    }

    private void applyBucketOwnershipControls(String bucketName, String ownershipControlsXml,
                                              ProvisionContext ctx) {
        if (ownershipControlsXml != null) {
            s3Service.putBucketOwnershipControls(bucketName, ownershipControlsXml);
        } else if (ctx.reusesPriorEntity(bucketName) && ctx.previouslyDeclared("OwnershipControls")) {
            s3Service.deleteBucketOwnershipControls(bucketName);
        }
    }
    private void applyBucketTags(String bucketName, JsonNode props, ProvisionContext ctx) {
        if (declared(props, "Tags") == null) {
            return;
        }
        s3Service.putBucketTagging(bucketName, ctx.resolveTags(props, "Tags"));
    }

    /** What a provision changed that a failure has to undo. */
    private static final class Unwind {
        private boolean created;
        private NotificationConfiguration replacedNotifications;
    }

    /** Restores an in-place bucket update or removes a newly created bucket after a failed provision. */
    private void unwind(StackResource r, String bucketName, Unwind unwind, ProvisionContext ctx,
                        RuntimeException failure) {
        try {
            if (unwind.created) {
                s3Service.deleteBucket(bucketName);
            } else if (r.getAttributes().containsKey(CfnRollback.S3_BUCKET_UPDATE_SNAPSHOT_ATTR)) {
                rollbackUpdate(r);
            } else if (unwind.replacedNotifications != null) {
                // Valid when it was put; its destinations are not checked again on the way back.
                s3Service.putBucketNotificationConfiguration(bucketName, unwind.replacedNotifications, true);
            }
        } catch (RuntimeException e) {
            LOG.warnv("Could not undo the failed provision of bucket {0}: {1}", bucketName, e.getMessage());
            failure.addSuppressed(e);
            if (ctx.isUpdate()) {
                r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                        "Could not restore bucket " + bucketName + ": " + e.getMessage());
            }
        }
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (!BUCKET.equals(resource.getResourceType())) {
            return false;
        }
        String snapshot = resource.getAttributes().get(CfnRollback.S3_BUCKET_UPDATE_SNAPSHOT_ATTR);
        if (snapshot == null) {
            return false;
        }
        s3Service.restoreBucketConfiguration(resource.getPhysicalId(), snapshot);
        resource.getAttributes().remove(CfnRollback.S3_BUCKET_UPDATE_SNAPSHOT_ATTR);
        return true;
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return resource.getAttributes().containsKey(CfnRollback.S3_BUCKET_UPDATE_SNAPSHOT_ATTR)
                ? new UpdateCleanupResult(true, true, null, 0, null)
                : UpdateCleanupResult.notApplicable();
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.S3_BUCKET_UPDATE_SNAPSHOT_ATTR);
    }

    private static boolean sameConfiguration(NotificationConfiguration desired, NotificationConfiguration current) {
        return desired.getQueueConfigurations().equals(current.getQueueConfigurations())
                && desired.getTopicConfigurations().equals(current.getTopicConfigurations())
                && desired.getLambdaFunctionConfigurations().equals(current.getLambdaFunctionConfigurations())
                && desired.isEventBridgeEnabled() == current.isEventBridgeEnabled();
    }

    /**
     * The configuration to put on the bucket through the same service method as
     * {@code PutBucketNotificationConfiguration}, whose destination validation then fails the
     * resource for a queue, topic or function that does not exist. Empty when the template declares
     * an empty one, and when an update removes the property, both of which clear the bucket's, as
     * on AWS. Null when the stack never declared it: the bucket's own configuration, one put
     * outside the stack, is left alone, as AWS leaves it.
     *
     * <p>CloudFormation names one {@code Event} per entry where the API takes a list, and has no
     * {@code Id}. Each entry becomes one API configuration with a blank id, which is what the
     * service stores for an API request that omits {@code Id}.
     */
    private NotificationConfiguration notificationConfiguration(JsonNode props, ProvisionContext ctx) {
        JsonNode declaredNode = declared(props, "NotificationConfiguration");
        JsonNode notifications = declaredNode == null
                ? null : ctx.engine().resolveNodeOmittingNoValue(declaredNode);
        if (notifications == null || notifications.isMissingNode() || notifications.isNull()) {
            return ctx.previouslyDeclared("NotificationConfiguration") ? new NotificationConfiguration() : null;
        }
        String path = "#/NotificationConfiguration";
        requireObject(notifications, path);
        NotificationConfiguration config = new NotificationConfiguration();
        for (NotificationEntry entry : notificationEntries(notifications, "QueueConfigurations", "Queue", path)) {
            config.getQueueConfigurations().add(
                    new QueueNotification("", entry.arn(), List.of(entry.event()), entry.filterRules()));
        }
        for (NotificationEntry entry : notificationEntries(notifications, "TopicConfigurations", "Topic", path)) {
            config.getTopicConfigurations().add(
                    new TopicNotification("", entry.arn(), List.of(entry.event()), entry.filterRules()));
        }
        for (NotificationEntry entry : notificationEntries(notifications, "LambdaConfigurations", "Function", path)) {
            config.getLambdaFunctionConfigurations().add(
                    new LambdaNotification("", entry.arn(), List.of(entry.event()), entry.filterRules()));
        }
        JsonNode eventBridge = declared(notifications, "EventBridgeConfiguration");
        if (eventBridge != null) {
            String eventBridgePath = path + "/EventBridgeConfiguration";
            JsonNode enabledNode = requiredNode(eventBridge, "EventBridgeEnabled", eventBridgePath);
            String enabled = enabledNode.asText();
            if (!"true".equalsIgnoreCase(enabled) && !"false".equalsIgnoreCase(enabled)) {
                throw typeMismatch(eventBridgePath + "/EventBridgeEnabled", "Boolean", enabledNode);
            }
            config.setEventBridgeEnabled(Boolean.parseBoolean(enabled));
        }
        return config;
    }

    private record NotificationEntry(String event, String arn, List<FilterRule> filterRules) {}

    private static List<NotificationEntry> notificationEntries(JsonNode notifications, String listName,
                                                               String arnProperty, String path) {
        JsonNode list = declaredArray(notifications, listName, path);
        List<NotificationEntry> entries = new ArrayList<>();
        if (list == null) {
            return entries;
        }
        for (int index = 0; index < list.size(); index++) {
            String entryPath = path + "/" + listName + "/" + index;
            JsonNode entry = requireObject(list.get(index), entryPath);
            String event = required(entry, "Event", entryPath);
            String arn = required(entry, arnProperty, entryPath);
            entries.add(new NotificationEntry(event, arn, filterRules(entry, entryPath)));
        }
        return entries;
    }

    private static List<FilterRule> filterRules(JsonNode entry, String entryPath) {
        JsonNode filter = declared(entry, "Filter");
        if (filter == null) {
            return List.of();
        }
        requireObject(filter, entryPath + "/Filter");
        JsonNode s3Key = requireObject(requiredNode(filter, "S3Key", entryPath + "/Filter"), entryPath + "/Filter/S3Key");
        requiredNode(s3Key, "Rules", entryPath + "/Filter/S3Key");
        JsonNode rules = declaredArray(s3Key, "Rules", entryPath + "/Filter/S3Key");
        List<FilterRule> filterRules = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            String rulePath = entryPath + "/Filter/S3Key/Rules/" + index;
            JsonNode rule = requireObject(rules.get(index), rulePath);
            filterRules.add(new FilterRule(required(rule, "Name", rulePath), required(rule, "Value", rulePath)));
        }
        return filterRules;
    }

    /**
     * A string the registry schema marks required. Reads a configuration already resolved, so the
     * value is not resolved a second time.
     */
    private static String required(JsonNode props, String name, String path) {
        JsonNode value = requiredNode(props, name, path);
        if (value.isContainerNode()) {
            throw typeMismatch(path + "/" + name, "String", value);
        }
        return value.asText();
    }

    /** A key the registry schema marks required, with its JSON pointer in the message. */
    private static JsonNode requiredNode(JsonNode props, String name, String path) {
        JsonNode value = declared(props, name);
        if (value == null) {
            throw new AwsException("InvalidRequest",
                    "Model validation failed (" + path + ": required key [" + name + "] not found)", 400);
        }
        return value;
    }

    /**
     * The named list, or null when it is not declared. Anything other than a list fails, as the
     * schema's array type does, rather than being read as an empty one: an empty rule list matches
     * every key.
     */
    private static JsonNode declaredArray(JsonNode props, String name, String path) {
        JsonNode value = declared(props, name);
        if (value != null && !value.isArray()) {
            throw typeMismatch(path + "/" + name, "JSONArray", value);
        }
        return value;
    }

    /** The value itself when it is an object. Anything else fails, rather than reading as an empty one. */
    private static JsonNode requireObject(JsonNode value, String pointer) {
        if (!value.isObject()) {
            throw typeMismatch(pointer, "JSONObject", value);
        }
        return value;
    }

    private static AwsException typeMismatch(String pointer, String expected, JsonNode value) {
        return new AwsException("InvalidRequest", "Model validation failed (" + pointer
                + ": expected type: " + expected + ", found: " + schemaTypeName(value) + ")", 400);
    }

    private static String schemaTypeName(JsonNode value) {
        return switch (value.getNodeType()) {
            case OBJECT -> "JSONObject";
            case ARRAY -> "JSONArray";
            case BOOLEAN -> "Boolean";
            case NUMBER -> value.isIntegralNumber() ? "Integer" : "Double";
            default -> "String";
        };
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
     *
     * <p>The physical id is the bucket name, which is what the registry schema declares:
     * {@code primaryIdentifier} is {@code /properties/Bucket}, and {@code Bucket} is create-only,
     * so a template that repoints the policy replaces the resource and the id follows. The type has
     * no {@code readOnlyProperties}, so it has no {@code Fn::GetAtt} and none is invented here.
     */
    private void provisionBucketPolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        String bucketName = ctx.resolveOptional(props, "Bucket");
        JsonNode document = declared(props, "PolicyDocument");
        if (bucketName == null || bucketName.isBlank()) {
            r.setPhysicalId(ctx.isUpdate() ? ctx.priorPhysicalId() : r.getLogicalId());
            return;
        }
        r.setPhysicalId(bucketName);
        if (document == null) {
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
        if (BUCKET.equals(resourceType)) {
            // Deleting a non-empty bucket raises BucketNotEmpty, which propagates so the stack
            // reports DELETE_FAILED as AWS does.
            s3Service.deleteBucket(physicalId);
            return;
        }
        if (BUCKET_POLICY.equals(resourceType)) {
            deleteBucketPolicyIfPresent(physicalId);
        }
    }

    /**
     * Clears the policy the stack put on the bucket. AWS removes it on stack delete unless the
     * resource carries {@code DeletionPolicy: Retain}, and leaving it behind is how a dropped
     * policy resource reported DELETE_COMPLETE while the bucket kept its policy.
     *
     * <p>A bucket that is already gone is tolerated: the bucket resource in the same stack may be
     * deleted first, and there is then nothing left to clear.
     */
    private void deleteBucketPolicyIfPresent(String bucketName) {
        if (bucketName == null || bucketName.isBlank()) {
            return;
        }
        try {
            s3Service.deleteBucketPolicy(bucketName);
        } catch (AwsException e) {
            if (!"NoSuchBucket".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("Bucket {0} is already gone, so its policy needs no removal", bucketName);
        }
    }
}
