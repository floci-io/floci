package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::Instance}. It delegates to {@link Ec2Service} so
 * the instance really launches, and sets the physical id to the real instance id so Ref and exports
 * resolve to a real {@code i-} id rather than a stub.
 */
@ApplicationScoped
public class Ec2InstanceCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(Ec2InstanceCfnProvisioner.class);

    private static final String INSTANCE = "AWS::EC2::Instance";

    private final Ec2Service ec2Service;

    @Inject
    public Ec2InstanceCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(INSTANCE);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        String imageId = ctx.resolveOptional(props, "ImageId");
        String instanceType = ctx.resolveOptional(props, "InstanceType");
        String keyName = ctx.resolveOptional(props, "KeyName");

        // An instance may reference a LaunchTemplate for its config; fields the
        // properties don't set resolve from the template's data, as on AWS.
        if (props != null && props.has("LaunchTemplate")) {
            JsonNode ltRef = engine.resolveNode(props.get("LaunchTemplate"));
            try {
                LaunchTemplateData ltData = ec2Service.resolveLaunchTemplateData(region,
                        ltRef.path("LaunchTemplateId").asText(null),
                        ltRef.path("LaunchTemplateName").asText(null),
                        ltRef.path("Version").asText(null));
                if (imageId == null || imageId.isBlank()) {
                    imageId = ltData.getImageId();
                }
                if (instanceType == null || instanceType.isBlank()) {
                    instanceType = ltData.getInstanceType();
                }
                if (keyName == null || keyName.isBlank()) {
                    keyName = ltData.getKeyName();
                }
            } catch (Exception e) {
                LOG.debugv("Could not resolve launch template for instance {0}: {1}",
                        r.getLogicalId(), e.getMessage());
            }
        }
        if (instanceType == null || instanceType.isBlank()) {
            instanceType = "t3.micro";
        }
        String subnetId = ctx.resolveOptional(props, "SubnetId");
        String userData = ctx.resolveOptional(props, "UserData");
        String iamInstanceProfile = ctx.resolveOptional(props, "IamInstanceProfile");

        List<String> securityGroupIds = new ArrayList<>();
        if (props != null && props.has("SecurityGroupIds") && props.get("SecurityGroupIds").isArray()) {
            for (JsonNode sg : props.get("SecurityGroupIds")) {
                securityGroupIds.add(engine.resolve(sg));
            }
        }

        List<Tag> tags = new ArrayList<>();
        JsonNode tagsNode = props != null ? engine.resolveNode(props.get("Tags")) : null;
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = engine.resolve(tag.path("Key"));
                if (!key.isEmpty()) {
                    tags.add(new Tag(key, engine.resolve(tag.path("Value"))));
                }
            }
        }

        // The launch-time public-IP override rides on the primary network
        // interface spec; absent means the subnet's MapPublicIpOnLaunch default.
        Boolean associatePublicIp = null;
        if (props != null) {
            JsonNode networkInterfaces = props.path("NetworkInterfaces");
            if (networkInterfaces.isArray() && !networkInterfaces.isEmpty()) {
                String assocRaw = engine.resolve(networkInterfaces.get(0).path("AssociatePublicIpAddress"));
                if (assocRaw != null && !assocRaw.isBlank()) {
                    associatePublicIp = Boolean.parseBoolean(assocRaw);
                }
            }
        }

        Reservation reservation = ec2Service.runInstances(region, imageId, instanceType, 1, 1, keyName,
                securityGroupIds, subnetId, null, tags, userData, iamInstanceProfile,
                associatePublicIp);
        Instance instance = reservation.getInstances().get(0);
        r.setPhysicalId(instance.getInstanceId());
        r.getAttributes().put("InstanceId", instance.getInstanceId());
        r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        ec2Service.awaitContainerLaunch(instance);
        r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
        if (instance.getPrivateIpAddress() != null) {
            r.getAttributes().put("PrivateIp", instance.getPrivateIpAddress());
        }
        if (instance.getPublicIpAddress() != null) {
            r.getAttributes().put("PublicIp", instance.getPublicIpAddress());
        }
        if (instance.getPrivateDnsName() != null) {
            r.getAttributes().put("PrivateDnsName", instance.getPrivateDnsName());
        }
        if (instance.getPublicDnsName() != null) {
            r.getAttributes().put("PublicDnsName", instance.getPublicDnsName());
        }
        if (instance.getPlacement() != null && instance.getPlacement().getAvailabilityZone() != null) {
            r.getAttributes().put("AvailabilityZone", instance.getPlacement().getAvailabilityZone());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // Tolerate an instance already terminated out of band, so DeleteStack does not fail on it.
        CfnDeletes.safeDelete("EC2 instance", physicalId,
                () -> ec2Service.terminateInstances(region, List.of(physicalId)),
                "InvalidInstanceID.NotFound");
    }
}
