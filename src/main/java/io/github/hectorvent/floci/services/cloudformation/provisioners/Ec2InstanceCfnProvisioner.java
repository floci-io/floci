package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        Map<String, String> attributesBefore = new HashMap<>(r.getAttributes());
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

        // On update, keep the existing instance unless a createOnly property changed: only a
        // changed ImageId, SubnetId or KeyName replaces it, a mutable-only change (tags, and the
        // like) reuses it. Launching unconditionally would leak the prior instance every update.
        Instance prior = ctx.isUpdate() ? findInstance(region, ctx.priorPhysicalId()) : null;
        if (prior != null && !createOnlyChanged(prior, imageId, subnetId, keyName)) {
            r.setPhysicalId(prior.getInstanceId());
            publishInstanceAttributes(r, prior);
            reconcileTags(prior.getInstanceId(), region, props, engine);
            ReplacementCleanup.record(r, ctx, attributesBefore);
            return;
        }

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
        publishInstanceAttributes(r, instance);
        // A createOnly change that landed a new instance id replaced the prior one: record it so
        // the stack cleans the displaced instance up after the update commits.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    private Instance findInstance(String region, String instanceId) {
        List<Reservation> reservations;
        try {
            reservations = ec2Service.describeInstances(region, List.of(instanceId), null);
        } catch (AwsException e) {
            if ("InvalidInstanceID.NotFound".equals(e.getErrorCode())) {
                // The prior instance was terminated out of band; treat it as gone so the update
                // creates a fresh one instead of failing outright, the same way delete() tolerates it.
                return null;
            }
            throw e;
        }
        for (Reservation reservation : reservations) {
            for (Instance instance : reservation.getInstances()) {
                if (instanceId.equals(instance.getInstanceId()) && !isTerminated(instance)) {
                    return instance;
                }
            }
        }
        return null;
    }

    // A terminated instance lingers in the store, so reusing it would point Ref at a dead instance.
    private static boolean isTerminated(Instance instance) {
        InstanceState state = instance.getState();
        String name = state == null ? null : state.getName();
        return "terminated".equals(name) || "shutting-down".equals(name);
    }

    private static boolean createOnlyChanged(Instance prior, String imageId, String subnetId, String keyName) {
        return changed(imageId, prior.getImageId())
                || changed(subnetId, prior.getSubnetId())
                || changed(keyName, prior.getKeyName());
    }

    /** A property forces replacement only when the template declares a value that differs. */
    private static boolean changed(String declared, String actual) {
        return declared != null && !declared.equals(actual);
    }

    private void publishInstanceAttributes(StackResource r, Instance instance) {
        // Each attribute is written when present and removed when absent, so a replacement never
        // inherits the prior instance's value: on update the resource still carries the old
        // attributes, so a new instance that lacks a PublicIp must not keep the old one. The put
        // calls stay literal so CfnSchemaCoverageTest still sees these attributes as published.
        r.getAttributes().put("InstanceId", instance.getInstanceId());
        String availabilityZone = instance.getPlacement() != null
                ? instance.getPlacement().getAvailabilityZone() : null;
        if (instance.getPrivateIpAddress() != null) {
            r.getAttributes().put("PrivateIp", instance.getPrivateIpAddress());
        } else {
            r.getAttributes().remove("PrivateIp");
        }
        if (instance.getPublicIpAddress() != null) {
            r.getAttributes().put("PublicIp", instance.getPublicIpAddress());
        } else {
            r.getAttributes().remove("PublicIp");
        }
        if (instance.getPrivateDnsName() != null) {
            r.getAttributes().put("PrivateDnsName", instance.getPrivateDnsName());
        } else {
            r.getAttributes().remove("PrivateDnsName");
        }
        if (instance.getPublicDnsName() != null) {
            r.getAttributes().put("PublicDnsName", instance.getPublicDnsName());
        } else {
            r.getAttributes().remove("PublicDnsName");
        }
        if (availabilityZone != null) {
            r.getAttributes().put("AvailabilityZone", availabilityZone);
        } else {
            r.getAttributes().remove("AvailabilityZone");
        }
    }

    /** Reconciles the instance tags to the template when the instance is kept in place. */
    private void reconcileTags(String instanceId, String region, JsonNode props,
                               CloudFormationTemplateEngine engine) {
        Map<String, String> desired = new LinkedHashMap<>();
        JsonNode tagsNode = props != null ? engine.resolveNode(props.get("Tags")) : null;
        if (tagsNode != null && tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String key = engine.resolve(tag.path("Key"));
                if (key != null && !key.isEmpty()) {
                    String value = engine.resolve(tag.path("Value"));
                    desired.put(key, value == null ? "" : value);
                }
            }
        }
        Map<String, String> current = new LinkedHashMap<>();
        for (Map<String, String> entry : ec2Service.describeTags(region, Map.of("resource-id", List.of(instanceId)))) {
            current.put(entry.get("key"), entry.get("value"));
        }
        List<String> stale = ProvisionContext.staleTagKeys(current, desired);
        if (!stale.isEmpty()) {
            List<Tag> remove = new ArrayList<>();
            for (String key : stale) {
                remove.add(new Tag(key, current.get(key)));
            }
            ec2Service.deleteTags(region, List.of(instanceId), remove);
        }
        if (!desired.isEmpty()) {
            List<Tag> add = new ArrayList<>();
            for (Map.Entry<String, String> entry : desired.entrySet()) {
                add.add(new Tag(entry.getKey(), entry.getValue()));
            }
            ec2Service.createTags(region, List.of(instanceId), add);
        }
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
        ReplacementCleanup.clear(resource);
    }

    @Override
    public boolean rollbackUpdate(StackResource resource) {
        return ReplacementCleanup.rollback(resource, this::delete);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // Tolerate an instance already terminated out of band, so DeleteStack does not fail on it.
        CfnDeletes.safeDelete("EC2 instance", physicalId,
                () -> ec2Service.terminateInstances(region, List.of(physicalId)),
                "InvalidInstanceID.NotFound");
    }
}
