package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailService;
import io.github.hectorvent.floci.services.cloudtrail.CloudTrailSelectorJson;
import io.github.hectorvent.floci.services.cloudtrail.model.AdvancedEventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.EventSelector;
import io.github.hectorvent.floci.services.cloudtrail.model.Trail;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Set;

@ApplicationScoped
public class CloudTrailCfnProvisioner implements CfnResourceProvisioner {

    private static final String CLOUDTRAIL_TRAIL = "AWS::CloudTrail::Trail";

    private final CloudTrailService trailService;

    @Inject
    public CloudTrailCfnProvisioner(CloudTrailService trailService) {
        this.trailService = trailService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(CLOUDTRAIL_TRAIL);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        JsonNode resolved = ctx.engine().resolveNode(props);
        String previousPhysicalId = resource.getPhysicalId();
        boolean isCreate = previousPhysicalId == null;

        String name = text(resolved, "TrailName");
        if (name == null || name.isBlank()) {
            name = isCreate ? ctx.generatePhysicalName(resource.getLogicalId(), 128, false) : previousPhysicalId;
        }
        String s3BucketName = text(resolved, "S3BucketName");
        String s3KeyPrefix = text(resolved, "S3KeyPrefix");
        String snsTopicName = text(resolved, "SnsTopicName");

        Trail trail;
        if (isCreate) {
            boolean includeGlobalServiceEvents = resolved.path("IncludeGlobalServiceEvents").asBoolean(true);
            boolean isMultiRegionTrail = resolved.path("IsMultiRegionTrail").asBoolean(false);
            boolean enableLogFileValidation = resolved.path("EnableLogFileValidation").asBoolean(false);
            boolean isOrganizationTrail = resolved.path("IsOrganizationTrail").asBoolean(false);
            trail = trailService.createTrail(ctx.region(), name, s3BucketName, s3KeyPrefix, snsTopicName,
                    includeGlobalServiceEvents, isMultiRegionTrail, enableLogFileValidation, isOrganizationTrail);
            resource.setPhysicalId(trail.trailArn());
            applySelectors(resolved, ctx.region(), name);
            if (resolved.path("IsLogging").asBoolean(false)) {
                trailService.startLogging(ctx.region(), name);
            }
        } else {
            Boolean includeGlobalServiceEvents = resolved.has("IncludeGlobalServiceEvents")
                    ? resolved.path("IncludeGlobalServiceEvents").asBoolean() : null;
            Boolean isMultiRegionTrail = resolved.has("IsMultiRegionTrail")
                    ? resolved.path("IsMultiRegionTrail").asBoolean() : null;
            Boolean enableLogFileValidation = resolved.has("EnableLogFileValidation")
                    ? resolved.path("EnableLogFileValidation").asBoolean() : null;
            Boolean isOrganizationTrail = resolved.has("IsOrganizationTrail")
                    ? resolved.path("IsOrganizationTrail").asBoolean() : null;
            trail = trailService.updateTrail(ctx.region(), previousPhysicalId, s3BucketName, s3KeyPrefix,
                    snsTopicName, includeGlobalServiceEvents, isMultiRegionTrail, enableLogFileValidation,
                    isOrganizationTrail);
            applySelectors(resolved, ctx.region(), previousPhysicalId);
            if (resolved.has("IsLogging")) {
                if (resolved.path("IsLogging").asBoolean()) {
                    trailService.startLogging(ctx.region(), previousPhysicalId);
                } else {
                    trailService.stopLogging(ctx.region(), previousPhysicalId);
                }
            }
        }

        resource.setPhysicalId(trail.trailArn());
        resource.getAttributes().put("Arn", trail.trailArn());
        resource.getAttributes().put("SnsTopicArn", trail.snsTopicArn());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null) {
            return;
        }
        boolean exists = !trailService.describeTrails(region, List.of(physicalId)).isEmpty();
        if (exists) {
            trailService.deleteTrail(region, physicalId);
        }
    }

    private void applySelectors(JsonNode resolved, String region, String trailNameOrArn) {
        boolean hasBasic = resolved.has("EventSelectors") && resolved.path("EventSelectors").isArray()
                && !resolved.path("EventSelectors").isEmpty();
        boolean hasAdvanced = resolved.has("AdvancedEventSelectors") && resolved.path("AdvancedEventSelectors").isArray()
                && !resolved.path("AdvancedEventSelectors").isEmpty();
        if (hasBasic && hasAdvanced) {
            throw new AwsException("InvalidParameterCombinationException",
                    "EventSelectors and AdvancedEventSelectors are mutually exclusive on a single trail.", 400);
        }
        if (hasAdvanced) {
            List<AdvancedEventSelector> advanced =
                    CloudTrailSelectorJson.parseAdvancedEventSelectors(resolved.path("AdvancedEventSelectors"));
            trailService.putAdvancedEventSelectors(region, trailNameOrArn, advanced);
        } else if (hasBasic) {
            List<EventSelector> selectors = CloudTrailSelectorJson.parseEventSelectors(resolved.path("EventSelectors"));
            trailService.putEventSelectors(region, trailNameOrArn, selectors);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
