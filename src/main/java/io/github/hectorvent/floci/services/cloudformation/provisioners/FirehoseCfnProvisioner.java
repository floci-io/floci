package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.firehose.FirehoseService;
import io.github.hectorvent.floci.services.firehose.model.DeliveryStreamDescription;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Provisions {@code AWS::KinesisFirehose::DeliveryStream}. */
@ApplicationScoped
public class FirehoseCfnProvisioner implements CfnResourceProvisioner {

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
        String name = ctx.resolveOptional(props, "DeliveryStreamName");
        if (name == null || name.isBlank()) {
            name = ctx.generatePhysicalName(r.getLogicalId(), DELIVERY_STREAM_NAME_MAX_LENGTH, false);
        }

        DeliveryStreamDescription.S3Destination s3 = null;
        JsonNode s3Node = props != null && props.has("ExtendedS3DestinationConfiguration")
                ? props.get("ExtendedS3DestinationConfiguration")
                : (props != null ? props.get("S3DestinationConfiguration") : null);
        if (s3Node != null && !s3Node.isNull()) {
            s3 = new DeliveryStreamDescription.S3Destination();

            s3.setCompressionFormat(
                blankToNull(ctx.engine().resolve(s3Node.path("CompressionFormat")))
            );
            s3.setBucketArn(blankToNull(ctx.engine().resolve(s3Node.path("BucketARN"))));
            s3.setPrefix(blankToNull(ctx.engine().resolve(s3Node.path("Prefix"))));
            if (s3Node.has("BufferingHints")) {
                JsonNode hints = s3Node.get("BufferingHints");
                var bufferingHints = new DeliveryStreamDescription.BufferingHints();
                bufferingHints.setSizeInMBs(parseIntProp(hints, "SizeInMBs", ctx, DEFAULT_BUFFER_SIZE_MB));
                bufferingHints.setIntervalInSeconds(
                        parseIntProp(hints, "IntervalInSeconds", ctx, DEFAULT_BUFFER_INTERVAL_SECONDS));
                s3.setBufferingHints(bufferingHints);
            }
        }

        List<DeliveryStreamDescription.Tag> tags = new ArrayList<>();
        if (props != null && props.has("Tags") && props.get("Tags").isArray()) {
            for (JsonNode tag : props.get("Tags")) {
                String key = ctx.engine().resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    tags.add(new DeliveryStreamDescription.Tag(key, ctx.engine().resolve(tag.path("Value"))));
                }
            }
        }

        String arn = firehoseService.createDeliveryStream(name, s3, tags);
        // Ref returns the delivery stream name; Fn::GetAtt Arn returns the stream ARN.
        r.setPhysicalId(name);
        r.getAttributes().put("Arn", arn);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        firehoseService.deleteDeliveryStream(physicalId);
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
