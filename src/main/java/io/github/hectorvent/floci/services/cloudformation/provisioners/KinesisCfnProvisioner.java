package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.kinesis.KinesisService;
import io.github.hectorvent.floci.services.kinesis.model.KinesisStream;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Provisions {@code AWS::Kinesis::Stream}. */
@ApplicationScoped
public class KinesisCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(KinesisCfnProvisioner.class);

    private static final int STREAM_NAME_MAX_LENGTH = 128;
    private static final int DEFAULT_SHARD_COUNT = 1;
    private static final String DEFAULT_STREAM_MODE = "PROVISIONED";

    private final KinesisService kinesisService;

    public KinesisCfnProvisioner(KinesisService kinesisService) {
        this.kinesisService = kinesisService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::Kinesis::Stream");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String explicitName = ctx.resolveOptional(props, "Name");
        String priorPhysicalId = ctx.priorPhysicalId();
        String name;
        if (explicitName != null && !explicitName.isBlank()) {
            name = explicitName;
        } else if (priorPhysicalId != null) {
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), STREAM_NAME_MAX_LENGTH, false);
        }

        String streamMode = null;
        if (props != null && props.has("StreamModeDetails")) {
            streamMode = ctx.engine().resolve(props.get("StreamModeDetails").path("StreamMode"));
            if (streamMode != null && streamMode.isBlank()) {
                streamMode = null;
            }
        }
        // ShardCount is required for PROVISIONED streams; default to 1 when unset (ON_DEMAND ignores it).
        int shardCount = DEFAULT_SHARD_COUNT;
        String shards = ctx.resolveOptional(props, "ShardCount");
        if (shards != null && !shards.isBlank()) {
            try {
                shardCount = Integer.parseInt(shards.trim());
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
        Integer retention = null;
        String retentionProp = ctx.resolveOptional(props, "RetentionPeriodHours");
        if (retentionProp != null && !retentionProp.isBlank()) {
            try {
                retention = Integer.parseInt(retentionProp.trim());
            } catch (NumberFormatException ignored) {
                // leave default
            }
        }
        Map<String, String> tags = new LinkedHashMap<>();
        if (props != null && props.has("Tags") && props.get("Tags").isArray()) {
            for (JsonNode tag : props.get("Tags")) {
                String key = ctx.engine().resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    tags.put(key, ctx.engine().resolve(tag.path("Value")));
                }
            }
        }

        // provision() re-runs on every UpdateStack, so a same-named stream already on file must be
        // reconciled instead of re-created (createStream throws ResourceInUseException). ShardCount
        // changes aren't reconciled here: KinesisService has no UpdateShardCount support to call into.
        KinesisStream stream = sameNameExistingResource(priorPhysicalId, name,
                n -> kinesisService.describeStream(n, ctx.region()));
        if (stream != null) {
            kinesisService.updateStreamMode(name,
                    streamMode != null ? streamMode : DEFAULT_STREAM_MODE, ctx.region());
            if (retention != null) {
                if (retention > stream.getRetentionPeriodHours()) {
                    kinesisService.increaseStreamRetentionPeriod(name, retention, ctx.region());
                } else if (retention < stream.getRetentionPeriodHours()) {
                    kinesisService.decreaseStreamRetentionPeriod(name, retention, ctx.region());
                }
            }
            Map<String, String> existingTags = kinesisService.listTagsForStream(name, ctx.region());
            List<String> tagsToRemove = existingTags.keySet().stream()
                    .filter(key -> !tags.containsKey(key))
                    .toList();
            if (!tagsToRemove.isEmpty()) {
                kinesisService.removeTagsFromStream(name, tagsToRemove, ctx.region());
            }
            if (!tags.isEmpty()) {
                kinesisService.addTagsToStream(name, tags, ctx.region());
            }
            stream = kinesisService.describeStream(name, ctx.region());
        } else {
            stream = kinesisService.createStream(name, shardCount, streamMode, ctx.region());
            if (retention != null) {
                stream.setRetentionPeriodHours(retention);
            }
            if (!tags.isEmpty()) {
                stream.getTags().putAll(tags);
            }
            deleteRenamedResource(priorPhysicalId, name,
                    id -> kinesisService.deleteStream(id, ctx.region()), "Kinesis stream");
        }

        // Ref returns the stream name; Fn::GetAtt Arn returns the stream ARN.
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", stream.getStreamArn());
    }

    /**
     * The resource already on file under this same name, or null when this is a create or a rename.
     * Copied from the monolith, which still has callers for it.
     */
    private <T> T sameNameExistingResource(String priorPhysicalId, String name, Function<String, T> lookup) {
        if (priorPhysicalId == null || !priorPhysicalId.equals(name)) {
            return null;
        }
        try {
            return lookup.apply(name);
        } catch (AwsException notFound) {
            // Expected when the resource was deleted out of band since the prior update; the
            // caller falls back to creating it fresh under the same name.
            LOG.debugv(notFound, "No existing {0} found on file, falling back to create", name);
            return null;
        }
    }

    /** Best-effort removal of the old resource after a rename. Copied, as above. */
    private void deleteRenamedResource(String priorPhysicalId, String newName, Consumer<String> delete,
                                       String resourceKind) {
        if (priorPhysicalId == null || priorPhysicalId.equals(newName)) {
            return;
        }
        try {
            delete.accept(priorPhysicalId);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Failed to delete renamed {0} {1} after replacement by {2}",
                    resourceKind, priorPhysicalId, newName);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        kinesisService.deleteStream(physicalId, region);
    }
}
