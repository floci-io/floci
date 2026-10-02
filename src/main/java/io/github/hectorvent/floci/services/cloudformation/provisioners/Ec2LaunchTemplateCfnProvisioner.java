package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.Ec2UserDataDecoder;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::LaunchTemplate} (issue #1971).
 */
@ApplicationScoped
public class Ec2LaunchTemplateCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2LaunchTemplateCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        LaunchTemplateData data = new LaunchTemplateData();
        if (props != null && props.has("LaunchTemplateData")) {
            JsonNode node = ctx.engine().resolveNode(props.get("LaunchTemplateData"));
            data.setImageId(node.path("ImageId").asText(null));
            data.setInstanceType(node.path("InstanceType").asText(null));
            data.setKeyName(node.path("KeyName").asText(null));
            // CFN carries UserData already base64-encoded.
            String encodedUserData = node.path("UserData").asText(null);
            data.setEncodedUserData(encodedUserData);
            data.setUserData(Ec2UserDataDecoder.decode(encodedUserData));
            data.setIamInstanceProfile(iamInstanceProfile(node.path("IamInstanceProfile")));
            if (node.has("SecurityGroupIds")) {
                List<String> securityGroupIds = new ArrayList<>();
                for (JsonNode securityGroup : node.get("SecurityGroupIds")) {
                    securityGroupIds.add(securityGroup.asText());
                }
                data.setSecurityGroupIds(securityGroupIds);
            }
        }
        // UpdateStack re-provisions every resource. Creating unconditionally meant an explicit
        // LaunchTemplateName hit InvalidLaunchTemplateName.AlreadyExistsException, while an
        // omitted one minted a second randomly named template and orphaned the first. A template
        // whose name has not changed is updated in place by publishing a new version, which is how
        // AWS::EC2::LaunchTemplate behaves when only its LaunchTemplateData changes.
        LaunchTemplate lt;
        if (existing != null && name.equals(existing.getLaunchTemplateName())) {
            String priorLatest = existing.getLatestVersionNumber();
            String priorDefault = existing.getDefaultVersionNumber();
            lt = ec2Service.createLaunchTemplateVersion(ctx.region(), previousId, null, null, data);
            LaunchTemplateSnapshot snapshot = new LaunchTemplateSnapshot(
                    ctx.region(), lt.getLaunchTemplateId(), lt.getLatestVersionNumber(), priorLatest, priorDefault);
            try {
                r.getAttributes().put(CfnRollback.LAUNCH_TEMPLATE_UPDATE_SNAPSHOT_ATTR, MAPPER.writeValueAsString(snapshot));
            } catch (JsonProcessingException e) {
                LOG.warnv("Could not serialize Launch Template update snapshot for {0}: {1}",
                        r.getLogicalId(), e.getMessage());
            }
        } else {
            lt = ec2Service.createLaunchTemplate(ctx.region(), name, data, null);
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

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String rawSnapshot = resource.getAttributes().remove(CfnRollback.LAUNCH_TEMPLATE_UPDATE_SNAPSHOT_ATTR);
        if (rawSnapshot != null) {
            try {
                LaunchTemplateSnapshot snapshot = MAPPER.readValue(rawSnapshot, LaunchTemplateSnapshot.class);
                if (snapshot.createdVersion() != null) {
                    ec2Service.deleteLaunchTemplateVersion(snapshot.region(), snapshot.launchTemplateId(), snapshot.createdVersion());
                }
                resource.getAttributes().put("LatestVersionNumber", snapshot.priorLatestVersion());
                resource.getAttributes().put("DefaultVersionNumber", snapshot.priorDefaultVersion());
                return true;
            } catch (Exception e) {
                LOG.errorv("Could not restore Launch Template update snapshot for {0}: {1}",
                        resource.getLogicalId(), e.getMessage());
                return false;
            }
        }
        return false;
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(CfnRollback.LAUNCH_TEMPLATE_UPDATE_SNAPSHOT_ATTR);
    }

    public record LaunchTemplateSnapshot(
            String region,
            String launchTemplateId,
            String createdVersion,
            String priorLatestVersion,
            String priorDefaultVersion
    ) {}

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
     * Keeps the form CloudFormation supplied. EC2 stores {@code Arn} and {@code Name} as given and
     * derives the ARN only at launch time, so normalizing {@code Name} into an ARN here would put
     * back the drift this provisioner's template data is read back through.
     */
    private LaunchTemplateData.IamInstanceProfile iamInstanceProfile(JsonNode profile) {
        String arn = profile.path("Arn").asText(null);
        String profileName = profile.path("Name").asText(null);
        boolean hasArn = arn != null && !arn.isBlank();
        boolean hasName = profileName != null && !profileName.isBlank();
        if (!hasArn && !hasName) {
            return null;
        }
        return new LaunchTemplateData.IamInstanceProfile(hasArn ? arn : null, hasName ? profileName : null);
    }
}
