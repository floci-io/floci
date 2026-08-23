package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::LaunchTemplate} (issue #1971).
 */
@ApplicationScoped
public class Ec2LaunchTemplateCfnProvisioner implements CfnResourceProvisioner {

    private final Ec2Service ec2Service;

    @Inject
    public Ec2LaunchTemplateCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::LaunchTemplate");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        String previousId = r.getPhysicalId();
        LaunchTemplate existing = previousId == null ? null : findExisting(ctx.region(), previousId);
        String declaredName = ctx.resolveOptional(props, "LaunchTemplateName");
        String name;
        if (declaredName != null && !declaredName.isBlank()) {
            name = declaredName;
        } else if (existing != null) {
            // Keep the name generated at create time; generating a fresh one on every update is
            // what made an unnamed template multiply.
            name = existing.getLaunchTemplateName();
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), 128, false);
        }
        String imageId = null;
        String instanceType = null;
        String keyName = null;
        String encodedUserData = null;
        String iamInstanceProfileArn = null;
        List<String> securityGroupIds = null;
        if (props != null && props.has("LaunchTemplateData")) {
            JsonNode data = ctx.engine().resolveNode(props.get("LaunchTemplateData"));
            imageId = data.path("ImageId").asText(null);
            instanceType = data.path("InstanceType").asText(null);
            keyName = data.path("KeyName").asText(null);
            // CFN carries UserData already base64-encoded.
            encodedUserData = data.path("UserData").asText(null);
            iamInstanceProfileArn = resolveIamInstanceProfileArn(data.path("IamInstanceProfile"), ctx);
            if (data.has("SecurityGroupIds")) {
                securityGroupIds = new ArrayList<>();
                for (JsonNode sg : data.get("SecurityGroupIds")) {
                    securityGroupIds.add(sg.asText());
                }
            }
        }
        // UpdateStack re-provisions every resource. Creating unconditionally meant an explicit
        // LaunchTemplateName hit InvalidLaunchTemplateName.AlreadyExistsException, while an
        // omitted one minted a second randomly named template and orphaned the first. A template
        // whose name has not changed is updated in place by publishing a new version, which is how
        // AWS::EC2::LaunchTemplate behaves when only its LaunchTemplateData changes.
        LaunchTemplate lt;
        if (existing != null && name.equals(existing.getLaunchTemplateName())) {
            lt = ec2Service.createLaunchTemplateVersion(ctx.region(), previousId, null, null,
                    imageId, instanceType, keyName, securityGroupIds, null, encodedUserData,
                    iamInstanceProfileArn, null, null, null);
        } else {
            lt = ec2Service.createLaunchTemplate(ctx.region(), name, imageId, instanceType, keyName,
                    securityGroupIds, null, encodedUserData, iamInstanceProfileArn, null, null, null, null);
            // A changed name is a replacement: drop the template the previous execution created,
            // once the new one exists, so the old one is not left behind.
            if (existing != null) {
                try {
                    ec2Service.deleteLaunchTemplate(ctx.region(), previousId, null);
                } catch (RuntimeException ignored) {
                    // already gone — nothing to clean up
                }
            }
        }
        r.setPhysicalId(lt.getLaunchTemplateId());
        r.getAttributes().put("LaunchTemplateId", lt.getLaunchTemplateId());
        r.getAttributes().put("LatestVersionNumber", lt.getLatestVersionNumber());
        r.getAttributes().put("DefaultVersionNumber", lt.getDefaultVersionNumber());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        ec2Service.deleteLaunchTemplate(region, physicalId, null);
    }

    /** The template a previous execution created, or null when it is gone. */
    private LaunchTemplate findExisting(String region, String launchTemplateId) {
        try {
            return ec2Service.describeLaunchTemplates(region, List.of(launchTemplateId), List.of(), Map.of())
                    .stream().findFirst().orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A profile given only by {@code Name} is normalized to its instance-profile ARN, matching
     * what the EC2 query API does for {@code IamInstanceProfile.Name} request parameters
     * (see {@code Ec2QueryHandler#resolveIamInstanceProfileArn}).
     */
    private String resolveIamInstanceProfileArn(JsonNode profile, ProvisionContext ctx) {
        String arn = profile.path("Arn").asText(null);
        if (arn != null && !arn.isBlank()) {
            return arn;
        }
        String profileName = profile.path("Name").asText(null);
        if (profileName == null || profileName.isBlank()) {
            return null;
        }
        return AwsArnUtils.Arn.of("iam", "", ctx.accountId(), "instance-profile/" + profileName).toString();
    }
}
