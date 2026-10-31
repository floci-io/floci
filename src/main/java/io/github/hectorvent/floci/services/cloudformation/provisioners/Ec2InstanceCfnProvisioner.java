package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::EC2::Instance}. It delegates to {@link Ec2Service} so
 * the instance really launches, and sets the physical id to the real instance id so Ref and exports
 * resolve to a real {@code i-} id rather than a stub.
 */
@ApplicationScoped
public class Ec2InstanceCfnProvisioner implements CfnResourceProvisioner {

    private static final String INSTANCE = "AWS::EC2::Instance";

    // Declared values of createOnly properties, recorded so a later update compares against what
    // the template declared rather than what the instance carries: the launch does not apply
    // AvailabilityZone, and an instance a stack launched before PrivateIpAddress was applied holds
    // an auto-assigned address. The __Floci prefix keeps them off the published Fn::GetAtt attributes.
    private static final String DECLARED_PRIVATE_IP_ATTR = "__FlociDeclaredPrivateIpAddress";
    private static final String DECLARED_AZ_ATTR = "__FlociDeclaredAvailabilityZone";
    // The instance profile the template declared last time, so an update that drops the property
    // can be told from one that never declared it (a launch template may have set the profile).
    private static final String DECLARED_IAM_PROFILE_ATTR = "__FlociDeclaredIamInstanceProfile";
    // The MetadataOptions members declared last time, the instance's own or its launch template's, so
    // an update can return the ones it no longer declares to their launch defaults.
    private static final String DECLARED_METADATA_OPTIONS_ATTR = "__FlociDeclaredMetadataOptions";
    // The __FlociDeclared records as they stood before a replacing update, for rollbackUpdate to put
    // back with the instance it restores; ReplacementCleanup leaves __Floci attributes alone.
    private static final String PRIOR_DECLARATIONS_ATTR = "__FlociPriorDeclarations";
    // What an update that kept the instance changed it from: its region, its MetadataOptions if the
    // update changed them, its __FlociDeclared records, and whether the update also changed
    // something with no rollback here (type, groups, user data, profile, tags), for rollbackUpdate
    // to put back.
    private static final String PRIOR_IN_PLACE_ATTR = "__FlociPriorInPlaceUpdate";
    private static final String DECLARED_PREFIX = "__FlociDeclared";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Ec2Service ec2Service;
    /** How long {@link #delete} waits for a terminated instance; package-visible so tests can shorten it. */
    long terminateTimeoutMillis = 90_000L;
    long terminatePollMillis = 250L;

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
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        List<Tag> tags = Ec2Tags.validatedTags(desiredTags);
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
        LaunchTemplateData.MetadataOptions templateMetadataOptions = null;
        if (ltRef != null && ltRef.isObject() && (ltId != null || ltName != null)) {
            // A LaunchTemplate the stack references but that does not resolve is a real error, as on
            // AWS: let it fail the resource rather than silently launching with default config.
            LaunchTemplateData ltData = ec2Service.resolveLaunchTemplateData(region, ltId, ltName,
                    ltRef.path("Version").asText(null), false);
            if (imageId == null || imageId.isBlank()) {
                imageId = ltData.getImageId();
            }
            if (instanceType == null || instanceType.isBlank()) {
                instanceType = ltData.getInstanceType();
            }
            if (keyName == null || keyName.isBlank()) {
                keyName = ltData.getKeyName();
            }
            templateMetadataOptions = ltData.getMetadataOptions();
        }
        // The instance's own MetadataOptions win member by member over its template's, as
        // RunInstances merges a request over the launch template it names.
        LaunchTemplateData.MetadataOptions metadataOptions = Ec2CfnMetadataOptions.over(templateMetadataOptions,
                props != null ? Ec2CfnMetadataOptions.read("MetadataOptions", props.get("MetadataOptions"),
                        Ec2CfnMetadataOptions.INSTANCE_SHAPE, engine) : null);
        // Checked before anything changes: a reused instance gets its options last, after its
        // type, groups, user data, profile and tags, so a bad value found there would leave it half
        // updated.
        Ec2Service.validateMetadataOptions(metadataOptions);
        if (instanceType == null || instanceType.isBlank()) {
            instanceType = "t3.micro";
        }
        String subnetId = ctx.resolveOptional(props, "SubnetId");
        List<String> securityGroupIds = resolveSecurityGroupIds(props, engine);
        String privateIpAddress = ctx.resolveOptional(props, "PrivateIpAddress");
        String availabilityZone = ctx.resolveOptional(props, "AvailabilityZone");
        String userData = ctx.resolveOptional(props, "UserData");
        // The property names a profile; it is resolved to the ARN here, at provision time, as the
        // RunInstances handler resolves IamInstanceProfile.Name, so an unknown name fails the resource.
        String iamInstanceProfileArn = resolveIamInstanceProfileArn(ctx.resolveOptional(props, "IamInstanceProfile"));

        // On update, keep the existing instance unless a createOnly property changed: a changed
        // ImageId, SubnetId or KeyName replaces it (compared against the instance, which carries
        // them), as does a changed PrivateIpAddress or AvailabilityZone (compared against the value
        // declared last time, see DECLARED_PRIVATE_IP_ATTR). A mutable-only change (tags, and the
        // like) reuses it. Launching unconditionally would leak the prior instance every update. A
        // changed LaunchTemplate reuses it too, where AWS replaces the instance: the change-set
        // preview and the template's own versioning would have to agree on that property first.
        // What the template declares is reconciled in place instead, unless the ImageId or KeyName
        // it supplies changed, which replaces the instance as above.
        Instance prior = ctx.isUpdate() ? findInstance(region, ctx.priorPhysicalId()) : null;
        if (prior != null && !createOnlyChanged(prior, imageId, subnetId, keyName)
                && !declaredCreateOnlyChanged(attributesBefore, privateIpAddress, availabilityZone)) {
            // A reused instance still has its mutable properties reconciled to the template rather
            // than left at their prior values; re-read it so the published attributes reflect them.
            LaunchTemplateData.MetadataOptions metadataOptionsToApply = Ec2CfnMetadataOptions.toApply(
                    Ec2CfnMetadataOptions.parseDeclaredMembers(attributesBefore.get(DECLARED_METADATA_OPTIONS_ATTR)),
                    metadataOptions);
            LaunchTemplateData.MetadataOptions optionsBefore = prior.effectiveMetadataOptions();
            boolean metadataOptionsChange = metadataOptionsToApply != null
                    && Ec2CfnMetadataOptions.changes(optionsBefore, metadataOptionsToApply);
            boolean otherChange = reconcileMutableProperties(region, prior, instanceType, securityGroupIds,
                    userData, iamInstanceProfileArn, attributesBefore.get(DECLARED_IAM_PROFILE_ATTR));
            boolean tagsChanged = Ec2Tags.reconcile(ec2Service, region, prior.getInstanceId(), desiredTags);
            // Saved on every update that keeps the instance, also one that changes no EC2 setting but
            // only what is declared, so a rollback can always put the declarations back.
            r.getAttributes().put(PRIOR_IN_PLACE_ATTR, priorInPlaceUpdate(region,
                    metadataOptionsChange ? optionsBefore : null, attributesBefore, otherChange || tagsChanged));
            if (metadataOptionsChange) {
                // The last change the step makes: when an earlier one fails, the engine restores the
                // resource as it was, without the record saved above, so the options must not have
                // changed yet.
                ec2Service.modifyInstanceMetadataOptions(region, prior.getInstanceId(), metadataOptionsToApply);
            }
            Instance reconciled = findInstance(region, prior.getInstanceId());
            Instance current = reconciled != null ? reconciled : prior;
            r.setPhysicalId(current.getInstanceId());
            publishInstanceAttributes(r, current);
            storeDeclaredCreateOnly(r, privateIpAddress, availabilityZone);
            putOrRemove(r, DECLARED_IAM_PROFILE_ATTR, iamInstanceProfileArn);
            putOrRemove(r, DECLARED_METADATA_OPTIONS_ATTR, Ec2CfnMetadataOptions.declaredMembers(metadataOptions));
            ReplacementCleanup.record(r, ctx, attributesBefore);
            return;
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

        r.getAttributes().remove(PRIOR_IN_PLACE_ATTR);
        if (ctx.isUpdate()) {
            r.getAttributes().put(PRIOR_DECLARATIONS_ATTR, declarations(attributesBefore));
        }
        // The options a template cannot set keep the defaults the shorter runInstances overloads
        // give them. A replacement that keeps its PrivateIpAddress fails with InvalidIPAddress.InUse
        // while the prior instance still holds it, as on AWS, which creates the replacement first.
        String encodedUserData = userData == null ? null
                : Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));
        Reservation reservation = ec2Service.runInstances(region, imageId, instanceType, 1, 1, keyName,
                securityGroupIds, subnetId, null, tags, userData, iamInstanceProfileArn,
                associatePublicIp, null, 0, null, metadataOptions, null, encodedUserData, false, null, null, null,
                false, privateIpAddress);
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
        putOrRemove(r, DECLARED_IAM_PROFILE_ATTR, iamInstanceProfileArn);
        putOrRemove(r, DECLARED_METADATA_OPTIONS_ATTR, Ec2CfnMetadataOptions.declaredMembers(metadataOptions));
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
     * Applies the mutable properties CloudFormation allows to change without replacing the instance,
     * through the same Ec2Service calls the API actions use: a changed InstanceType is resized in
     * place, a changed SecurityGroupIds set is reattached, a changed UserData is rewritten the way
     * ModifyInstanceAttribute requires it (the instance stopped first and started again after, the
     * "some interruptions" the property is documented with), and a changed IamInstanceProfile is
     * associated or replaced, or disassociated when the template declared one last time and drops it
     * now. Only a declared SecurityGroupIds or UserData is reconciled, so a template that omits them
     * does not strip what the instance already has (a launch template may have set it). Returns
     * whether it changed anything, which a stack rollback has no way to put back here. MetadataOptions
     * is applied after it, as the property's "No interruption" update allows, by the caller, which
     * keeps what it replaces for {@link #rollbackUpdate}.
     */
    private boolean reconcileMutableProperties(String region, Instance prior, String instanceType,
                                               List<String> securityGroupIds, String userData,
                                               String iamInstanceProfileArn, String priorDeclaredProfileArn) {
        boolean changed = false;
        if (instanceType != null && !instanceType.isBlank()
                && !instanceType.equals(prior.getInstanceType())) {
            ec2Service.modifyInstanceAttribute(region, prior.getInstanceId(), "instanceType", instanceType);
            changed = true;
        }
        if (!securityGroupIds.isEmpty() && !sameSecurityGroups(prior, securityGroupIds)) {
            ec2Service.modifyInstanceGroups(region, prior.getInstanceId(), securityGroupIds);
            changed = true;
        }
        if (userData != null && !userData.equals(prior.getUserData())) {
            rewriteUserData(region, prior, userData);
            changed = true;
        }
        return reconcileIamInstanceProfile(region, prior, iamInstanceProfileArn, priorDeclaredProfileArn) || changed;
    }

    /**
     * User data can only be modified on a stopped instance, so a running one is stopped for the
     * rewrite and started again after it, as CloudFormation's "some interruptions" update does; an
     * instance that was already stopped is left stopped.
     */
    private void rewriteUserData(String region, Instance prior, String userData) {
        String instanceId = prior.getInstanceId();
        boolean wasStopped = prior.getState() != null && "stopped".equals(prior.getState().getName());
        if (!wasStopped) {
            ec2Service.stopInstances(region, List.of(instanceId));
        }
        ec2Service.modifyInstanceUserData(region, instanceId, userData);
        if (!wasStopped) {
            ec2Service.startInstances(region, List.of(instanceId));
            Instance started = findInstance(region, instanceId);
            ec2Service.awaitContainerLaunch(started != null ? started : prior);
        }
    }

    /** Returns whether it changed the instance's profile. */
    private boolean reconcileIamInstanceProfile(String region, Instance prior, String desiredArn,
                                                String priorDeclaredArn) {
        String currentArn = prior.getIamInstanceProfileArn();
        String associationId = Ec2Service.iamInstanceProfileAssociationId(prior.getInstanceId());
        if (desiredArn != null) {
            if (currentArn == null) {
                ec2Service.associateIamInstanceProfile(region, prior.getInstanceId(), desiredArn);
                return true;
            } else if (!desiredArn.equals(currentArn)) {
                ec2Service.replaceIamInstanceProfileAssociation(region, associationId, desiredArn);
                return true;
            }
        } else if (priorDeclaredArn != null && currentArn != null) {
            ec2Service.disassociateIamInstanceProfile(region, associationId);
            return true;
        }
        return false;
    }

    private String resolveIamInstanceProfileArn(String nameOrArn) {
        if (nameOrArn == null || nameOrArn.isBlank()) {
            return null;
        }
        return AwsArnUtils.looksLikeArn(nameOrArn) ? nameOrArn : ec2Service.resolveIamInstanceProfileName(nameOrArn);
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
     * Whether a createOnly property recorded as declared (PrivateIpAddress, AvailabilityZone) changed
     * since the last provision. It is compared against the value declared then and recorded on the
     * resource, not the instance's own, for the reasons given at {@link #DECLARED_PRIVATE_IP_ATTR}.
     * A property with no recorded prior value is not treated as changed, so adopting this behavior
     * does not replace instances whose template did not actually change; the value is recorded from
     * this provision on, and a later change to it then forces replacement.
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
        resource.getAttributes().remove(PRIOR_DECLARATIONS_ATTR);
        resource.getAttributes().remove(PRIOR_IN_PLACE_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(PRIOR_DECLARATIONS_ATTR);
        resource.getAttributes().remove(PRIOR_IN_PLACE_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * Undoes a replacement through the cleanup record, and puts back the {@code __FlociDeclared}
     * records of the instance it restores, so a retried update compares against what that instance
     * was declared with. The cleanup names the prior instance again before it deletes the
     * replacement, so when that delete throws the records go back too: a rollback that then skips
     * this resource, or is forced, never calls here again.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        String physicalIdBefore = resource.getPhysicalId();
        boolean replaced;
        try {
            replaced = ReplacementCleanup.rollback(resource, this::delete);
        } catch (RuntimeException e) {
            if (!Objects.equals(physicalIdBefore, resource.getPhysicalId())) {
                try {
                    restoreSavedDeclarations(resource);
                } catch (RuntimeException restoreFailure) {
                    e.addSuppressed(restoreFailure);
                }
            }
            throw e;
        }
        if (replaced) {
            restoreSavedDeclarations(resource);
            resource.getAttributes().remove(PRIOR_IN_PLACE_ATTR);
        }
        resource.getAttributes().remove(PRIOR_DECLARATIONS_ATTR);
        return replaced || restoreInPlaceUpdate(resource);
    }

    /**
     * Undoes an update that kept the instance: puts back the MetadataOptions it changed, if it
     * changed any, and the instance's {@code __FlociDeclared} records, so a stack rollback leaves
     * neither options nor declarations the stack no longer has. The rollback counts as done only
     * when nothing else changed in that update: the instance type, groups, user data, profile and
     * tags have no rollback here, and the stack says so for them. An update that changed no EC2
     * setting at all, only what is declared, is undone in full.
     */
    private boolean restoreInPlaceUpdate(StackResource resource) {
        String saved = resource.getAttributes().get(PRIOR_IN_PLACE_ATTR);
        if (saved == null) {
            return false;
        }
        JsonNode prior;
        try {
            prior = MAPPER.readTree(saved);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the saved in-place update of " + resource.getLogicalId(), e);
        }
        JsonNode options = prior.path("options");
        if (options.isObject()) {
            LaunchTemplateData.MetadataOptions restored = new LaunchTemplateData.MetadataOptions();
            restored.setHttpTokens(options.path("HttpTokens").asText(null));
            restored.setHttpEndpoint(options.path("HttpEndpoint").asText(null));
            restored.setHttpProtocolIpv6(options.path("HttpProtocolIpv6").asText(null));
            restored.setInstanceMetadataTags(options.path("InstanceMetadataTags").asText(null));
            if (options.path("HttpPutResponseHopLimit").isInt()) {
                restored.setHttpPutResponseHopLimit(options.path("HttpPutResponseHopLimit").intValue());
            }
            ec2Service.modifyInstanceMetadataOptions(prior.path("region").asText(), resource.getPhysicalId(), restored);
        }
        resource.getAttributes().keySet().removeIf(key -> key.startsWith(DECLARED_PREFIX));
        prior.path("declarations").fields()
                .forEachRemaining(e -> resource.getAttributes().put(e.getKey(), e.getValue().asText()));
        resource.getAttributes().remove(PRIOR_IN_PLACE_ATTR);
        return !prior.path("otherChanges").asBoolean(true);
    }

    /**
     * What {@link #restoreInPlaceUpdate} needs, as a JSON object: {@code options} is the instance's
     * MetadataOptions before the update, or null when the update left them as they were.
     */
    private static String priorInPlaceUpdate(String region, LaunchTemplateData.MetadataOptions options,
                                             Map<String, String> attributesBefore, boolean otherChanges) {
        ObjectNode saved = MAPPER.createObjectNode();
        saved.put("region", region);
        if (options != null) {
            ObjectNode members = saved.putObject("options");
            members.put("HttpTokens", options.getHttpTokens());
            members.put("HttpPutResponseHopLimit", options.getHttpPutResponseHopLimit());
            members.put("HttpEndpoint", options.getHttpEndpoint());
            members.put("HttpProtocolIpv6", options.getHttpProtocolIpv6());
            members.put("InstanceMetadataTags", options.getInstanceMetadataTags());
        }
        ObjectNode declared = saved.putObject("declarations");
        attributesBefore.forEach((key, value) -> {
            if (key.startsWith(DECLARED_PREFIX) && value != null) {
                declared.put(key, value);
            }
        });
        saved.put("otherChanges", otherChanges);
        return saved.toString();
    }

    /** The {@code __FlociDeclared} attributes of {@code attributes}, as a JSON object. */
    private static String declarations(Map<String, String> attributes) {
        ObjectNode declared = MAPPER.createObjectNode();
        attributes.forEach((key, value) -> {
            if (key.startsWith(DECLARED_PREFIX) && value != null) {
                declared.put(key, value);
            }
        });
        return declared.toString();
    }

    /** Puts back the records {@link #declarations} saved, if any, and drops the saved copy. */
    private static void restoreSavedDeclarations(StackResource resource) {
        String saved = resource.getAttributes().remove(PRIOR_DECLARATIONS_ATTR);
        if (saved == null) {
            return;
        }
        JsonNode prior;
        try {
            prior = MAPPER.readTree(saved);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the saved declarations of " + resource.getLogicalId(), e);
        }
        resource.getAttributes().keySet().removeIf(key -> key.startsWith(DECLARED_PREFIX));
        prior.fields().forEachRemaining(e -> resource.getAttributes().put(e.getKey(), e.getValue().asText()));
    }

    /**
     * Terminates the instance and waits until EC2 reports it {@code terminated} (or gone). With
     * real containers the instance sits in {@code shutting-down} until the container manager's
     * executor finishes, and a subnet or VPC delete that follows in the same stack delete refuses
     * a resident that is still shutting down. In mock mode the terminate is synchronous, so the
     * first describe already sees {@code terminated}.
     */
    @Override
    public void delete(String resourceType, String physicalId, String region) {
        // Tolerate an instance already terminated out of band, so DeleteStack does not fail on it.
        CfnDeletes.safeDelete("EC2 instance", physicalId,
                () -> ec2Service.terminateInstances(region, List.of(physicalId)),
                "InvalidInstanceID.NotFound");
        awaitTerminated(region, physicalId);
    }

    private void awaitTerminated(String region, String instanceId) {
        long deadline = System.currentTimeMillis() + terminateTimeoutMillis;
        while (true) {
            if (isTerminatedOrGone(region, instanceId)) {
                return;
            }
            if (System.currentTimeMillis() >= deadline) {
                throw new AwsException("ValidationError", "Instance " + instanceId
                        + " did not reach terminated within " + (terminateTimeoutMillis / 1000) + "s", 400);
            }
            try {
                Thread.sleep(terminatePollMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("ValidationError",
                        "Interrupted while waiting for instance " + instanceId + " to terminate", 400);
            }
        }
    }

    private boolean isTerminatedOrGone(String region, String instanceId) {
        List<Reservation> reservations;
        try {
            reservations = ec2Service.describeInstances(region, List.of(instanceId), null);
        } catch (AwsException e) {
            if ("InvalidInstanceID.NotFound".equals(e.getErrorCode())) {
                return true;
            }
            throw e;
        }
        if (reservations == null) {
            return true;
        }
        for (Reservation reservation : reservations) {
            for (Instance instance : reservation.getInstances()) {
                if (instanceId.equals(instance.getInstanceId())) {
                    InstanceState state = instance.getState();
                    return state != null && "terminated".equals(state.getName());
                }
            }
        }
        return true;
    }
}
