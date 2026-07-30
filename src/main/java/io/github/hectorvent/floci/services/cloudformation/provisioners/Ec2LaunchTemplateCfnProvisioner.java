package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
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
        String name = ctx.resolveOptional(props, "LaunchTemplateName");
        if (name == null || name.isBlank()) {
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
        var lt = ec2Service.createLaunchTemplate(ctx.region(), name, imageId, instanceType, keyName,
                securityGroupIds, null, encodedUserData, iamInstanceProfileArn, null, null);
        r.setPhysicalId(lt.getLaunchTemplateId());
        r.getAttributes().put("LaunchTemplateId", lt.getLaunchTemplateId());
        r.getAttributes().put("LatestVersionNumber", lt.getLatestVersionNumber());
        r.getAttributes().put("DefaultVersionNumber", lt.getDefaultVersionNumber());
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        ec2Service.deleteLaunchTemplate(region, physicalId, null);
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
