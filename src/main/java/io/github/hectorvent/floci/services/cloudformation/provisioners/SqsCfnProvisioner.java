package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.sqs.SqsService;
import io.github.hectorvent.floci.services.sqs.model.Queue;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.HashMap;
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
            case "AWS::SQS::QueuePolicy" -> provisionQueuePolicy(r, ctx);
            default -> throw new IllegalStateException("SqsCfnProvisioner cannot handle " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if ("AWS::SQS::Queue".equals(resourceType)) {
            sqsService.deleteQueue(physicalId, region);
        }
        // AWS::SQS::QueuePolicy has no backing resource to delete (matches prior behavior).
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
        // instead of being orphaned with its messages. FifoQueue is createOnly like QueueName, so a
        // prior name whose .fifo suffix no longer matches the flag is a replacing update and gets a
        // fresh name.
        String priorName = r.getAttributes() != null ? r.getAttributes().get("QueueName") : null;
        if (queueName == null || queueName.isBlank()) {
            if (priorName != null && !priorName.isBlank() && priorName.endsWith(".fifo") == fifo) {
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
            if (props.has("VisibilityTimeout")) {
                attrs.put("VisibilityTimeout", ctx.engine().resolve(props.get("VisibilityTimeout")));
            }
            if (props.has("ContentBasedDeduplication")) {
                attrs.put("ContentBasedDeduplication", ctx.engine().resolve(props.get("ContentBasedDeduplication")));
            }
            if (props.has("DeduplicationScope")) {
                attrs.put("DeduplicationScope", ctx.engine().resolve(props.get("DeduplicationScope")));
            }
            if (props.has("FifoThroughputLimit")) {
                attrs.put("FifoThroughputLimit", ctx.engine().resolve(props.get("FifoThroughputLimit")));
            }
            if (props.has("RedrivePolicy") && !props.path("RedrivePolicy").isNull()) {
                // Usually a JSON object in the template (deadLetterTargetArn is an Fn::GetAtt);
                // resolveNode resolves intrinsics in place and SqsService expects the JSON string.
                // CDK commonly emits RedrivePolicy as an already-serialized string via Fn::Join,
                // which resolveNode collapses to a TextNode — unwrap it instead of calling
                // toString(), which would JSON-re-encode (quote/escape) the string a second time.
                attrs.put("RedrivePolicy", ctx.engine().resolveJsonAttribute(props.path("RedrivePolicy")));
            }
        }
        // provision is also the update path. createQueue on a name that exists hands back the
        // queue only when every attribute matches and answers QueueAlreadyExists otherwise, so the
        // second UpdateStack that changes VisibilityTimeout must go through SetQueueAttributes, the
        // registry schema's update handler, rather than a second create. The prior physical id is
        // the queue URL SetQueueAttributes addresses. A replacing update derives a different name
        // and still creates.
        // FifoQueue is createOnly and encoded in the name. An explicitly named queue keeps its name
        // across updates, so a changed FifoQueue would need a replacement under the same name,
        // which CloudFormation refuses for a custom-named resource: the SQS registry schema says a
        // named queue cannot take an update that requires replacement. Fail it, as the other
        // provisioners do, instead of reconciling everything but the mode.
        if (ctx.isUpdate() && queueName.equals(priorName) && priorName.endsWith(".fifo") != fifo) {
            throw new AwsException("ValidationError",
                    "Updating FifoQueue requires resource replacement, which is not supported.", 400);
        }
        String queueUrl;
        if (ctx.isUpdate() && queueName.equals(priorName)) {
            attrs.remove("FifoQueue");
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

    private void provisionQueuePolicy(StackResource r, ProvisionContext ctx) {
        // A policy has no backing entity, so its id only has to stay put across updates.
        r.setPhysicalId(ctx.isUpdate()
                ? ctx.priorPhysicalId()
                : "queue-policy-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
