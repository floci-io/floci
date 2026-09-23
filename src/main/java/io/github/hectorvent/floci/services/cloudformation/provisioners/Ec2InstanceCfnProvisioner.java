package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.GroupIdentifier;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

    private static final String INSTANCE = "AWS::EC2::Instance";

    // Declared values of createOnly properties the launch does not apply to the instance, recorded
    // so a later update can tell a genuine change from the instance's auto-assigned value. The
    // __Floci prefix keeps them off the published Fn::GetAtt attributes.
    private static final String DECLARED_PRIVATE_IP_ATTR = "__FlociDeclaredPrivateIpAddress";
    private static final String DECLARED_AZ_ATTR = "__FlociDeclaredAvailabilityZone";

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
        JsonNode ltRef = props != null ? engine.resolveNode(props.get("LaunchTemplate")) : null;
        String ltId = ltRef != null ? ltRef.path("LaunchTemplateId").asText(null) : null;
        String ltName = ltRef != null ? ltRef.path("LaunchTemplateName").asText(null) : null;
        // Only look one up when the property actually names a template. An Fn::If that selects
        // AWS::NoValue resolves to an empty node here, which on AWS means the property is absent
        // (launch without a template), so it must not turn into an InvalidLaunchTemplateId.NotFound.
        if (ltRef != null && ltRef.isObject() && (ltId != null || ltName != null)) {
            // A LaunchTemplate the stack references but that does not resolve is a real error, as on
            // AWS: let it fail the resource rather than silently launching with default config.
            LaunchTemplateData ltData = ec2Service.resolveLaunchTemplateData(region, ltId, ltName,
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
        }
        if (instanceType == null || instanceType.isBlank()) {
            instanceType = "t3.micro";
        }
        String subnetId = ctx.resolveOptional(props, "SubnetId");
        List<String> securityGroupIds = resolveSecurityGroupIds(props, engine);
        String privateIpAddress = ctx.resolveOptional(props, "PrivateIpAddress");
        String availabilityZone = ctx.resolveOptional(props, "AvailabilityZone");

        // On update, keep the existing instance unless a createOnly property changed: a changed
        // ImageId, SubnetId or KeyName replaces it (compared against the instance, which carries
        // them), as does a changed PrivateIpAddress or AvailabilityZone (compared against the value
        // declared last time, since the launch does not apply those to the instance). A mutable-only
        // change (tags, and the like) reuses it. Launching unconditionally would leak the prior
        // instance every update.
        Instance prior = ctx.isUpdate() ? findInstance(region, ctx.priorPhysicalId()) : null;
        if (prior != null && !createOnlyChanged(prior, imageId, subnetId, keyName)
                && !declaredCreateOnlyChanged(attributesBefore, privateIpAddress, availabilityZone)) {
            // A reused instance still has its mutable properties reconciled to the template rather
            // than left at their prior values; re-read it so the published attributes reflect them.
            reconcileMutableProperties(region, prior, instanceType, securityGroupIds);
            Instance reconciled = findInstance(region, prior.getInstanceId());
            Instance current = reconciled != null ? reconciled : prior;
            r.setPhysicalId(current.getInstanceId());
            publishInstanceAttributes(r, current);
            storeDeclaredCreateOnly(r, privateIpAddress, availabilityZone);
            Ec2Tags.reconcile(ec2Service, region, current.getInstanceId(), ctx.resolveTags(props, "Tags"));
            ReplacementCleanup.record(r, ctx, attributesBefore);
            return;
        }

        String userData = ctx.resolveOptional(props, "UserData");
        String iamInstanceProfile = ctx.resolveOptional(props, "IamInstanceProfile");

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
        // Re-read once the launch settles, as CloudFormation's own stabilization does, so the
        // published State is the settled instance's rather than the pending one RunInstances returned.
        Instance launched = findInstance(region, instance.getInstanceId());
        publishInstanceAttributes(r, launched != null ? launched : instance);
        storeDeclaredCreateOnly(r, privateIpAddress, availabilityZone);
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

    private List<String> resolveSecurityGroupIds(JsonNode props, CloudFormationTemplateEngine engine) {
        List<String> securityGroupIds = new ArrayList<>();
        if (props != null && props.has("SecurityGroupIds") && props.get("SecurityGroupIds").isArray()) {
            for (JsonNode sg : props.get("SecurityGroupIds")) {
                securityGroupIds.add(engine.resolve(sg));
            }
        }
        return securityGroupIds;
    }

    /**
     * Applies the mutable properties CloudFormation allows to change without replacing the instance:
     * a changed InstanceType is resized in place and a changed SecurityGroupIds set is reattached,
     * through the same Ec2Service calls the ModifyInstanceAttribute API uses. Only a declared
     * SecurityGroupIds is reconciled, so a template that omits it does not strip the groups the
     * instance already has. UserData and IamInstanceProfile have no in-place Ec2Service path yet, so
     * they stay a follow-up: an instance kept on update still carries their prior values.
     */
    private void reconcileMutableProperties(String region, Instance prior, String instanceType,
                                            List<String> securityGroupIds) {
        if (instanceType != null && !instanceType.isBlank()
                && !instanceType.equals(prior.getInstanceType())) {
            ec2Service.modifyInstanceAttribute(region, prior.getInstanceId(), "instanceType", instanceType);
        }
        if (!securityGroupIds.isEmpty() && !sameSecurityGroups(prior, securityGroupIds)) {
            ec2Service.modifyInstanceGroups(region, prior.getInstanceId(), securityGroupIds);
        }
    }

    private static boolean sameSecurityGroups(Instance prior, List<String> desired) {
        List<GroupIdentifier> current = prior.getSecurityGroups();
        if (current == null) {
            return desired.isEmpty();
        }
        Set<String> currentIds = new HashSet<>();
        for (GroupIdentifier group : current) {
            currentIds.add(group.getGroupId());
        }
        return currentIds.equals(new HashSet<>(desired));
    }

    /**
     * Whether a createOnly property the launch does not apply to the instance (PrivateIpAddress,
     * AvailabilityZone) changed since the last provision. It is compared against the value declared
     * then and recorded on the resource, not the instance's auto-assigned value, which never matches
     * a declared one. A property with no recorded prior value is not treated as changed, so adopting
     * this behavior does not replace instances whose template did not actually change; the value is
     * recorded from this provision on, and a later change to it then forces replacement.
     */
    private static boolean declaredCreateOnlyChanged(Map<String, String> priorAttributes,
                                                     String privateIpAddress, String availabilityZone) {
        return recordedValueChanged(priorAttributes.get(DECLARED_PRIVATE_IP_ATTR), privateIpAddress)
                || recordedValueChanged(priorAttributes.get(DECLARED_AZ_ATTR), availabilityZone);
    }

    private static boolean recordedValueChanged(String priorDeclared, String nowDeclared) {
        String now = (nowDeclared == null || nowDeclared.isBlank()) ? null : nowDeclared;
        return priorDeclared != null && !priorDeclared.equals(now);
    }

    private static void storeDeclaredCreateOnly(StackResource r, String privateIpAddress,
                                                String availabilityZone) {
        putOrRemove(r, DECLARED_PRIVATE_IP_ATTR, privateIpAddress);
        putOrRemove(r, DECLARED_AZ_ATTR, availabilityZone);
    }

    private static void putOrRemove(StackResource r, String key, String value) {
        if (value != null && !value.isBlank()) {
            r.getAttributes().put(key, value);
        } else {
            r.getAttributes().remove(key);
        }
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
        if (instance.getVpcId() != null) {
            r.getAttributes().put("VpcId", instance.getVpcId());
        } else {
            r.getAttributes().remove("VpcId");
        }
        // The schema's State is a nested object, so Fn::GetAtt exposes its members in dotted form
        // (State.Code, State.Name), the same shape as an RDS Endpoint; there is no bare State.
        InstanceState state = instance.getState();
        if (state != null && state.getName() != null) {
            r.getAttributes().put("State.Code", String.valueOf(state.getCode()));
            r.getAttributes().put("State.Name", state.getName());
        } else {
            r.getAttributes().remove("State.Code");
            r.getAttributes().remove("State.Name");
        }
    }

    /** Reconciles the instance tags to the template when the instance is kept in place. */
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
