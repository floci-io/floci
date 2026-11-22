package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.sqs.SqsService;
import io.floci.aws.services.sqs.model.Queue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CloudFormation provisioning for SQS: {@code AWS::SQS::Queue} and {@code AWS::SQS::QueuePolicy}.
 * Extracted verbatim from the former CloudFormation monolith (item 15 decomposition).
 */
@ApplicationScoped
public class SqsCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(SqsCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String POLICY_QUEUES_ATTR = "__FlociQueuePolicyQueues";
    /** The schema's mutable properties that map one to one onto a queue attribute of the same name. */
    private static final List<String> SCALAR_ATTRIBUTES = List.of(
            "ContentBasedDeduplication", "DeduplicationScope", "DelaySeconds", "FifoThroughputLimit",
            "KmsDataKeyReusePeriodSeconds", "KmsMasterKeyId", "MaximumMessageSize", "MessageRetentionPeriod",
            "ReceiveMessageWaitTimeSeconds", "SqsManagedSseEnabled", "VisibilityTimeout");
    /** The mutable properties a template gives as a JSON document and SQS stores as a JSON string. */
    private static final List<String> JSON_ATTRIBUTES = List.of("RedriveAllowPolicy", "RedrivePolicy");
    private static final Set<String> FIFO_ONLY_ATTRIBUTES =
            Set.of("ContentBasedDeduplication", "DeduplicationScope", "FifoThroughputLimit");
    /**
     * Properties AWS keeps when an update drops them from the template, rather than resetting: in
     * LocalStack's AWS-recorded parity test
     * {@code test_update_fifo_queue_remove_all_properties_except_queuename} all three are unchanged
     * after an in-place name-only update, while every other property is back at its default.
     */
    private static final Set<String> RETAINED_ON_UPDATE =
            Set.of("SqsManagedSseEnabled", "DeduplicationScope", "FifoThroughputLimit");
    /**
     * Resets that cannot be an empty value: a fresh FIFO queue stores ContentBasedDeduplication as
     * false, and SetQueueAttributes rejects an empty MessageRetentionPeriod, whose default is fixed.
     */
    private static final Map<String, String> EXPLICIT_RESETS =
            Map.of("ContentBasedDeduplication", "false", "MessageRetentionPeriod", "345600");

    private final SqsService sqsService;

    @Inject
    public SqsCfnProvisioner(SqsService sqsService) {
        this.sqsService = sqsService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::SQS::Queue", "AWS::SQS::QueuePolicy");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        switch (r.getResourceType()) {
            case "AWS::SQS::Queue" -> provisionQueue(r, props, ctx);
            case "AWS::SQS::QueuePolicy" -> provisionQueuePolicy(r, props, ctx);
            default -> throw new IllegalStateException("SqsCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if ("AWS::SQS::Queue".equals(resourceType)) {
            sqsService.deleteQueue(physicalId, region);
        }
        // AWS::SQS::QueuePolicy's physical id does not name its queues; see delete(StackResource, String).
    }

    @Override
    public void delete(StackResource resource, String region) {
        if ("AWS::SQS::QueuePolicy".equals(resource.getResourceType())) {
            for (String queueUrl : readQueueUrls(resource)) {
                clearQueuePolicy(queueUrl, region);
            }
            return;
        }
        delete(resource.getResourceType(), resource.getPhysicalId(), region);
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
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.SQS_UPDATE_SNAPSHOT_ATTR);
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        String rawSnapshot = resource.getAttributes().remove(CfnRollback.SQS_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot != null) {
            try {
                JsonNode snapshot = MAPPER.readTree(rawSnapshot);
                String queueUrl = snapshot.get("queueUrl").asText();
                String region = snapshot.get("region").asText();
                Map<String, String> restoreAttrs = new HashMap<>();
                snapshot.path("attributes").fields().forEachRemaining(e -> restoreAttrs.put(e.getKey(), e.getValue().asText()));
                if (!restoreAttrs.isEmpty()) {
                    sqsService.setQueueAttributes(queueUrl, restoreAttrs, region);
                }
                Map<String, String> restoreTags = new HashMap<>();
                snapshot.path("tags").fields().forEachRemaining(e -> restoreTags.put(e.getKey(), e.getValue().asText()));
                reconcileTags(queueUrl, restoreTags, region);
                return true;
            } catch (JsonProcessingException e) {
                LOG.errorv("Could not parse SQS update snapshot for {0}: {1}", resource.getLogicalId(), e.getMessage());
                return false;
            }
        }
        return false;
    }

    private void provisionQueue(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = r.getAttributes() != null ? new HashMap<>(r.getAttributes()) : new HashMap<>();
        String fifoFlag = props != null && props.has("FifoQueue")
                ? ctx.engine().resolve(props.get("FifoQueue"))
                : null;
        boolean fifo = "true".equalsIgnoreCase(fifoFlag);
        String queueName = ctx.resolveOptional(props, "QueueName");
        // The physical id is the queue URL, so ctx.stablePhysicalName does not fit: the prior name
        // comes from the QueueName attribute recorded at create time, as SnsCfnProvisioner reads
        // the topic name beside its ARN. Keeping it means an unnamed queue survives an update
        // instead of being orphaned with its messages. The engine's change decides whether the
        // update replaces the queue, as it does for the change set's Replacement: QueueName and
        // FifoQueue are create-only in the registry. A replacing update of an unnamed queue gets a
        // fresh generated name; a named one cannot be replaced under the same name.
        String priorName = r.getAttributes() != null ? r.getAttributes().get("QueueName") : null;
        // SQS cannot change a queue's mode in place and a FIFO queue's name always ends in .fifo, so
        // the prior name tells the live mode; the engine reads an added FifoQueue as no change, which
        // would apply the update in place and leave the mode as it was.
        boolean replaces = ctx.isUpdate() && priorName != null && !priorName.isBlank()
                && (ctx.changes("AWS::SQS::Queue", props).requiresReplacement() || priorName.endsWith(".fifo") != fifo);
        if (replaces && queueName != null && queueName.equals(priorName)) {
            throw CfnExceptions.customNamedReplacement(queueName);
        }
        if (queueName == null || queueName.isBlank()) {
            if (priorName != null && !priorName.isBlank() && !replaces) {
                queueName = priorName;
            } else {
                // Like real CloudFormation, generated names of FIFO queues must end in .fifo
                // (SqsService rejects FifoQueue=true otherwise). Keep within the 80-char limit.
                queueName = fifo
                        ? ctx.generatePhysicalName(r.getLogicalId(), 75, false) + ".fifo"
                        : ctx.generatePhysicalName(r.getLogicalId(), 80, false);
            }
        }
        Map<String, String> attrs = new HashMap<>();
        if (props != null) {
            if (fifoFlag != null) {
                attrs.put("FifoQueue", fifoFlag);
            }
            // A property that resolves to blank is absent: the engine resolves AWS::NoValue to blank,
            // which is how Fn::If [cond, value, AWS::NoValue] drops a property. Stored as an empty
            // attribute it would replace the queue's default rather than leave it in place.
            for (String attribute : SCALAR_ATTRIBUTES) {
                putUnlessBlank(attrs, attribute, ctx.resolveOptional(props, attribute));
            }
            for (String attribute : JSON_ATTRIBUTES) {
                if (props.has(attribute) && !props.path(attribute).isNull()) {
                    // Usually a JSON object in the template (deadLetterTargetArn is an Fn::GetAtt);
                    // resolveNode resolves intrinsics in place and SqsService expects the JSON string.
                    // CDK commonly emits RedrivePolicy as an already-serialized string via Fn::Join,
                    // which resolveNode collapses to a TextNode: unwrap it instead of calling
                    // toString(), which would JSON-re-encode (quote/escape) the string a second time.
                    putUnlessBlank(attrs, attribute, ctx.engine().resolveJsonAttribute(props.path(attribute)));
                }
            }
        }
        // provision is also the update path. createQueue on a name that exists hands back the
        // queue only when every attribute matches and answers QueueAlreadyExists otherwise, so the
        // second UpdateStack that changes VisibilityTimeout must go through SetQueueAttributes, the
        // registry schema's update handler, rather than a second create. The prior physical id is
        // the queue URL SetQueueAttributes addresses. A replacing update derives a different name
        // and still creates.
        String queueUrl;
        if (ctx.isUpdate() && queueName.equals(priorName)) {
            attrs.remove("FifoQueue");
            resetDroppedAttributes(attrs, fifo);
            Map<String, String> currentAttrs = sqsService.getQueueAttributes(ctx.priorPhysicalId(), List.of("All"), ctx.region());
            Map<String, String> currentTags = sqsService.listQueueTags(ctx.priorPhysicalId(), ctx.region());
            ObjectNode snapshot = MAPPER.createObjectNode();
            snapshot.put("queueUrl", ctx.priorPhysicalId());
            snapshot.put("region", ctx.region());
            ObjectNode attrsNode = snapshot.putObject("attributes");
            for (String attrKey : attrs.keySet()) {
                attrsNode.put(attrKey, currentAttrs.getOrDefault(attrKey, ""));
            }
            ObjectNode tagsNode = snapshot.putObject("tags");
            currentTags.forEach(tagsNode::put);
            r.getAttributes().put(CfnRollback.SQS_UPDATE_SNAPSHOT_ATTR, snapshot.toString());

            sqsService.setQueueAttributes(ctx.priorPhysicalId(), attrs, ctx.region());
            queueUrl = ctx.priorPhysicalId();
        } else {
            Queue queue = sqsService.createQueue(queueName, attrs, ctx.region());
            queueUrl = queue.getQueueUrl();
        }
        // QueueArn is computed on demand in SqsService#getQueueAttributes and is not stored on the
        // Queue object, so build it here from region + accountId + queueName. Without this,
        // Fn::GetAtt [Queue, Arn] references resolve to an empty string.
        String queueArn = AwsArnUtils.Arn.of("sqs", ctx.region(), ctx.accountId(), queueName).toString();
        reconcileTags(queueUrl, ctx.resolveTags(props, "Tags"), ctx.region());
        r.setPhysicalId(queueUrl);
        r.getAttributes().put("Arn", queueArn);
        r.getAttributes().put("QueueName", queueName);
        r.getAttributes().put("QueueUrl", queueUrl);
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private static void putUnlessBlank(Map<String, String> attrs, String attribute, String value) {
        if (value != null && !value.isBlank()) {
            attrs.put(attribute, value);
        }
    }

    /**
     * Puts every mutable property the template does not declare back to its default, which is how
     * CloudFormation applies an update: the desired state is the whole template, so a property dropped
     * from it no longer holds its old value. An empty value removes the stored attribute, leaving the
     * queue as a fresh create would, with {@code SqsService} reporting the default for it; the
     * {@link #EXPLICIT_RESETS} are written out instead. The FIFO-only attributes are left alone on a
     * standard queue, which cannot hold them, and the {@link #RETAINED_ON_UPDATE} ones keep their
     * stored value, as AWS does.
     */
    private static void resetDroppedAttributes(Map<String, String> attrs, boolean fifo) {
        for (String attribute : SCALAR_ATTRIBUTES) {
            if (attrs.containsKey(attribute) || RETAINED_ON_UPDATE.contains(attribute)
                    || (!fifo && FIFO_ONLY_ATTRIBUTES.contains(attribute))) {
                continue;
            }
            attrs.put(attribute, EXPLICIT_RESETS.getOrDefault(attribute, ""));
        }
        for (String attribute : JSON_ATTRIBUTES) {
            attrs.putIfAbsent(attribute, "");
        }
    }

    /**
     * Drives the queue's tags to the template's desired set, which is what CloudFormation does on
     * update: a key the template drops is untagged, and a template with no {@code Tags} leaves the
     * queue untagged. SQS has no replace-tags call, so the removal has to be computed, the same
     * shape {@code AcmCfnProvisioner} uses.
     *
     * <p>Serves the create path too, where {@code listQueueTags} is empty and this is a plain tag.
     * One path for both keeps a newly created queue and an updated one in the same state for the
     * same template.
     */
    private void reconcileTags(String queueUrl, Map<String, String> desired, String region) {
        List<String> stale = ProvisionContext.staleTagKeys(sqsService.listQueueTags(queueUrl, region), desired);
        if (!stale.isEmpty()) {
            sqsService.untagQueue(queueUrl, stale, region);
        }
        if (!desired.isEmpty()) {
            sqsService.tagQueue(queueUrl, desired, region);
        }
    }

    private void provisionQueuePolicy(StackResource r, JsonNode props, ProvisionContext ctx) {
        List<String> queues = ctx.resolveStringList(props, "Queues");
        if (queues.isEmpty()) {
            throw new AwsException("ValidationError",
                    "AWS::SQS::QueuePolicy requires at least one queue URL in Queues.", 400);
        }
        if (props == null || !props.hasNonNull("PolicyDocument")) {
            throw new AwsException("ValidationError",
                    "AWS::SQS::QueuePolicy requires a PolicyDocument.", 400);
        }
        String policy = ctx.engine().resolveJsonAttribute(props.path("PolicyDocument"));
        if (policy == null || policy.isBlank()) {
            throw new AwsException("ValidationError",
                    "AWS::SQS::QueuePolicy requires a PolicyDocument.", 400);
        }
        List<String> priorQueues = ctx.isUpdate() ? readQueueUrls(r) : List.of();
        for (String queueUrl : queues) {
            sqsService.setQueueAttributes(queueUrl, Map.of("Policy", policy), ctx.region());
        }
        Set<String> currentQueues = new HashSet<>(queues);
        for (String priorQueue : priorQueues) {
            if (!currentQueues.contains(priorQueue)) {
                clearQueuePolicy(priorQueue, ctx.region());
            }
        }
        // A policy has no backing entity, so its id only has to stay put across updates.
        r.setPhysicalId(ctx.isUpdate()
                ? ctx.priorPhysicalId()
                : "queue-policy-" + UUID.randomUUID().toString().substring(0, 8));
        r.getAttributes().put(POLICY_QUEUES_ATTR, writeQueueUrls(queues));
    }

    private void clearQueuePolicy(String queueUrl, String region) {
        CfnDeletes.safeDelete("SQS queue policy", queueUrl,
                () -> sqsService.setQueueAttributes(queueUrl, Map.of("Policy", ""), region),
                "AWS.SimpleQueueService.NonExistentQueue", "QueueDoesNotExist");
    }

    private static String writeQueueUrls(List<String> queues) {
        try {
            return MAPPER.writeValueAsString(queues);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize queue policy URLs", e);
        }
    }

    private static List<String> readQueueUrls(StackResource r) {
        if (r.getAttributes() == null) {
            return List.of();
        }
        String raw = r.getAttributes().get(POLICY_QUEUES_ATTR);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isArray()) {
                return List.of();
            }
            List<String> urls = new ArrayList<>();
            for (JsonNode entry : node) {
                if (entry.isTextual() && !entry.asText().isBlank()) {
                    urls.add(entry.asText());
                }
            }
            return urls;
        } catch (JsonProcessingException e) {
            LOG.errorv("Could not parse queue policy URLs for {0}: {1}", r.getLogicalId(), e.getMessage());
            return List.of();
        }
    }
}
