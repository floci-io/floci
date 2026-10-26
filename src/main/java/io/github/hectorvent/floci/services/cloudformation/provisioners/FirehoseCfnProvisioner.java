package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.S3DestinationValidator;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Provisions {@code AWS::KinesisFirehose::DeliveryStream}. */
@ApplicationScoped
public class FirehoseCfnProvisioner implements CfnResourceProvisioner {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DELIVERY_STREAM_NAME_MAX_LENGTH = 64;
    private static final int DEFAULT_BUFFER_SIZE_MB = 5;
    private static final int DEFAULT_BUFFER_INTERVAL_SECONDS = 300;

    private final FirehoseService firehoseService;

    public FirehoseCfnProvisioner(FirehoseService firehoseService) {
        this.firehoseService = firehoseService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::KinesisFirehose::DeliveryStream");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.stablePhysicalName(ctx.resolveOptional(props, "DeliveryStreamName"),
                r.getLogicalId(), DELIVERY_STREAM_NAME_MAX_LENGTH, false);

        DeliveryStreamDescription.S3Destination s3 = null;
        boolean extended = props != null && props.has("ExtendedS3DestinationConfiguration");
        JsonNode s3Node = extended
                ? props.get("ExtendedS3DestinationConfiguration")
                : (props != null ? props.get("S3DestinationConfiguration") : null);
        if (s3Node != null && !s3Node.isNull()) {
            s3 = new DeliveryStreamDescription.S3Destination();

            s3.setCompressionFormat(
                blankToNull(ctx.engine().resolve(s3Node.path("CompressionFormat")))
            );
            s3.setBucketArn(blankToNull(ctx.engine().resolve(s3Node.path("BucketARN"))));
            s3.setPrefix(blankToNull(ctx.engine().resolve(s3Node.path("Prefix"))));
            s3.setRoleArn(blankToNull(ctx.engine().resolve(s3Node.path("RoleARN"))));
            s3.setErrorOutputPrefix(blankToNull(ctx.engine().resolve(s3Node.path("ErrorOutputPrefix"))));
            s3.setCloudWatchLoggingOptions(structured(s3Node, "CloudWatchLoggingOptions",
                    DeliveryStreamDescription.CloudWatchLoggingOptions.class, ctx));
            // Only the extended shape models a processor, so the legacy one carries none, as on the API path.
            if (extended) {
                s3.setProcessingConfiguration(structured(s3Node, "ProcessingConfiguration",
                        DeliveryStreamDescription.ProcessingConfiguration.class, ctx));
            }
            if (s3Node.has("BufferingHints")) {
                JsonNode hints = s3Node.get("BufferingHints");
                DeliveryStreamDescription.BufferingHints bufferingHints = new DeliveryStreamDescription.BufferingHints();
                bufferingHints.setSizeInMBs(parseIntProp(hints, "SizeInMBs", ctx, DEFAULT_BUFFER_SIZE_MB));
                bufferingHints.setIntervalInSeconds(
                        parseIntProp(hints, "IntervalInSeconds", ctx, DEFAULT_BUFFER_INTERVAL_SECONDS));
                s3.setBufferingHints(bufferingHints);
            }
            S3DestinationValidator.validateWireShape(s3,
                    extended ? "extendedS3DestinationConfiguration" : "s3DestinationConfiguration");
        }

        List<DeliveryStreamDescription.Tag> tags = new ArrayList<>();
        JsonNode tagsNode = props != null ? ctx.engine().resolveNode(props.get("Tags")) : null;
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = ctx.engine().resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    tags.add(new DeliveryStreamDescription.Tag(key, ctx.engine().resolve(tag.path("Value"))));
                }
            }
        }

        // provision is also the update path. createDeliveryStream throws ResourceInUseException on
        // an existing name, and stablePhysicalName now keeps that name steady across updates, so a
        // second UpdateStack must reconcile the stream rather than recreate it. A replacing update
        // derives a different name and still creates, hence reusesPriorEntity rather than isUpdate.
        String arn;
        if (ctx.reusesPriorEntity(name)) {
            DeliveryStreamDescription existing = firehoseService.describeDeliveryStream(name);
            arn = existing.getDeliveryStreamARN();
            // Only the destination is updatable here; a template that declares none leaves the
            // stored one alone rather than clearing it.
            if (s3 != null) {
                String destinationId =
                        existing.getDestinations() != null && !existing.getDestinations().isEmpty()
                                ? existing.getDestinations().get(0).getDestinationId()
                                : null;
                firehoseService.updateDestination(name, existing.getVersionId(), destinationId, s3);
            }
            reconcileTags(name, existing, tags);
        } else {
            arn = firehoseService.createDeliveryStream(name, s3, tags);
        }
        // Ref returns the delivery stream name; Fn::GetAtt Arn returns the stream ARN.
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", arn);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        firehoseService.deleteDeliveryStream(physicalId);
    }

    /**
     * UpdateDestination carries no tags, so on the update path the template's Tags are driven to
     * their desired state through TagDeliveryStream and UntagDeliveryStream, the two calls the
     * registry schema's update handler declares. A key the template dropped is untagged rather than
     * left over from the previous revision.
     */
    private void reconcileTags(String name, DeliveryStreamDescription existing,
                               List<DeliveryStreamDescription.Tag> desired) {
        Map<String, String> desiredByKey = new LinkedHashMap<>();
        for (DeliveryStreamDescription.Tag tag : desired) {
            desiredByKey.put(tag.getKey(), tag.getValue());
        }
        Map<String, String> current = new LinkedHashMap<>();
        for (DeliveryStreamDescription.Tag tag : existing.getTags()) {
            current.put(tag.getKey(), tag.getValue());
        }
        List<String> stale = ProvisionContext.staleTagKeys(current, desiredByKey);
        if (!stale.isEmpty()) {
            firehoseService.untagDeliveryStream(name, stale);
        }
        if (!desired.isEmpty()) {
            firehoseService.tagDeliveryStream(name, desired);
        }
    }

    /**
     * A structured destination member with its intrinsics resolved, read into the type
     * CreateDeliveryStream reads it into. Null when the template leaves it out or AWS::NoValue
     * drops it, which on an update keeps the stored value, as UpdateDestination does. A member
     * present as anything but an object fails the resource, as CloudFormation rejects it.
     */
    private static <T> T structured(JsonNode destination, String name, Class<T> type, ProvisionContext ctx) {
        JsonNode resolved = ctx.engine().resolveNodeOmittingNoValue(destination.get(name));
        if (resolved == null || resolved.isMissingNode()) {
            return null;
        }
        try {
            return MAPPER.treeToValue(resolved, type);
        } catch (JsonProcessingException e) {
            throw new AwsException("ValidationError",
                    "AWS::KinesisFirehose::DeliveryStream has an invalid " + name + ": " + e.getOriginalMessage(), 400);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int parseIntProp(JsonNode props, String name, ProvisionContext ctx, int fallback) {
        String value = ctx.resolveOptional(props, name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
