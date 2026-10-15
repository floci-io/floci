package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.CidrCanonicalizer;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.NetworkAcl;
import io.github.hectorvent.floci.services.ec2.model.Vpc;
import io.github.hectorvent.floci.services.ec2.model.VpcCidrBlockAssociation;
import io.github.hectorvent.floci.services.ec2.model.VpcIpv6CidrBlockAssociation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CloudFormation provisioning for {@code AWS::EC2::VPC}.
 *
 * <p>Property and attribute contract follows
 * https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-resource-ec2-vpc.html:
 * {@code CidrBlock} is create-only and a change replaces the VPC; {@code InstanceTenancy} is
 * applied in place only for the {@code dedicated} to {@code default} direction AWS allows, any
 * other change replaces; {@code EnableDnsSupport}, {@code EnableDnsHostnames} and {@code Tags} are
 * applied in place on every provision. {@code Ref} is the VPC id; {@code Fn::GetAtt} exposes
 * VpcId, CidrBlock, CidrBlockAssociations, DefaultNetworkAcl, DefaultSecurityGroup and
 * Ipv6CidrBlocks. List-valued attributes are comma-joined like the other provisioners' lists.
 *
 * <p>Tags are reconciled to the template's {@code Tags} the way {@link Ec2Tags} does for the
 * instance and security-group provisioners: a key the template dropped is deleted. Stack-level
 * tags and the {@code aws:cloudformation:*} system tags are not applied here, matching every other
 * EC2 provisioner in this package.
 *
 * <p>A replacement leaves the displaced VPC to the {@link ReplacementCleanup} record: it is deleted
 * once the update commits, or the resource is pointed back at it when a later resource fails the
 * update and it rolls back. An in-place change to a reused VPC is snapshotted before it is written
 * (tenancy, the DNS attributes and the tag set), so the same rollback puts them back; a provision
 * that fails mid-way restores its own snapshot before the failure propagates. The stack delete
 * removes the VPC; it tolerates only {@code InvalidVpcID.NotFound}, so a VPC that still has
 * dependents outside the stack fails the delete with {@code DependencyViolation}, as on AWS.
 */
@ApplicationScoped
public class Ec2VpcCfnProvisioner implements CfnResourceProvisioner {

    private static final String DEFAULT_TENANCY = "default";
    private static final String DEDICATED_TENANCY = "dedicated";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * What an in-place update changed on a reused VPC, recorded before the change so a stack
     * rollback can put it back. Internal, like the other {@code __Floci} attributes.
     */
    static final String IN_PLACE_PRIOR_ATTR = "__FlociEc2VpcPrior";

    private final Ec2Service ec2Service;

    @Inject
    public Ec2VpcCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of("AWS::EC2::VPC");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String region = ctx.region();
        String cidr = ctx.resolveOptional(props, "CidrBlock");
        String tenancy = ctx.resolveOptional(props, "InstanceTenancy");
        String desiredTenancy = tenancy == null || tenancy.isBlank() ? DEFAULT_TENANCY : tenancy;
        // AWS defaults for a non-default VPC: DnsSupport on, DnsHostnames off. A template that
        // omits the property (or resolves it to AWS::NoValue, which the engine renders blank) gets
        // the default, as CloudFormation applies it on every update too. Every property resolves
        // before the VPC exists, so a resolution failure cannot leave a VPC outside the guard.
        boolean dnsSupport = Boolean.parseBoolean(ctx.resolveOrDefault(props, "EnableDnsSupport", "true"));
        boolean dnsHostnames = Boolean.parseBoolean(ctx.resolveOrDefault(props, "EnableDnsHostnames", "false"));
        Map<String, String> desiredTags = ctx.resolveTags(props, "Tags");
        Vpc reconciled = existingVpcToReconcile(ctx.isUpdate() ? ctx.priorPhysicalId() : null, cidr, desiredTenancy, region);
        boolean reused = reconciled != null;
        Vpc vpc = reused ? reconciled : ec2Service.createVpc(region, cidr, false, false, desiredTenancy);
        String vpcId = vpc.getVpcId();
        r.setPhysicalId(vpcId);
        r.getAttributes().put("VpcId", vpcId);

        CfnRollback.guarded(r, ctx, !reused, () -> ec2Service.deleteVpc(region, vpcId), () -> undoInPlace(r), () -> {
            if (reused) {
                snapshotInPlace(r, vpc, desiredTenancy, dnsSupport, dnsHostnames, desiredTags, region);
            }
            if (reused && isDedicatedToDefault(vpc, desiredTenancy)) {
                ec2Service.modifyVpcTenancy(region, vpcId, desiredTenancy);
            }
            ec2Service.modifyVpcAttribute(region, vpcId, "enableDnsSupport", String.valueOf(dnsSupport));
            ec2Service.modifyVpcAttribute(region, vpcId, "enableDnsHostnames", String.valueOf(dnsHostnames));
            Ec2Tags.reconcile(ec2Service, region, vpcId, desiredTags);
        });

        if (vpc.getCidrBlock() != null) {
            r.getAttributes().put("CidrBlock", vpc.getCidrBlock());
        }
        r.getAttributes().put("CidrBlockAssociations", vpc.getCidrBlockAssociationSet().stream()
                .map(VpcCidrBlockAssociation::getAssociationId)
                .collect(Collectors.joining(",")));
        r.getAttributes().put("Ipv6CidrBlocks", vpc.getIpv6CidrBlockAssociationSet().stream()
                .map(VpcIpv6CidrBlockAssociation::getIpv6CidrBlock)
                .collect(Collectors.joining(",")));
        // Fn::GetAtt DefaultSecurityGroup: CDK's Custom::VpcRestrictDefaultSG handler
        // depends on it resolving to the VPC's default security group id.
        ec2Service.describeSecurityGroups(region, List.of(), List.of("default"), Map.of()).stream()
                .filter(sg -> vpcId.equals(sg.getVpcId()))
                .findFirst()
                .ifPresent(sg -> r.getAttributes().put("DefaultSecurityGroup", sg.getGroupId()));
        ec2Service.describeNetworkAcls(region, List.of(),
                        Map.of("vpc-id", List.of(vpcId), "default", List.of("true"))).stream()
                .map(NetworkAcl::getNetworkAclId)
                .findFirst()
                .ifPresent(aclId -> r.getAttributes().put("DefaultNetworkAcl", aclId));
        // A provision that left the resource with a new physical id replaced the VPC: the
        // displaced one is deleted once the update commits, or restored if the update rolls back.
        ReplacementCleanup.record(r, ctx, attributesBefore);
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
        resource.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record: the displaced VPC was never touched. A
     * reused VPC that an update changed in place is put back from the snapshot taken before the
     * change; the snapshot is removed only after the restore succeeds, so a restore that throws
     * keeps it for a ContinueUpdateRollback retry. Without either, the update changed nothing
     * about the VPC.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            resource.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
            return true;
        }
        undoInPlace(resource);
        return true;
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        CfnDeletes.safeDelete("VPC", physicalId, () -> ec2Service.deleteVpc(region, physicalId), "InvalidVpcID.NotFound");
    }

    /**
     * Records what the update is about to change on a reused VPC, only when something differs: an
     * unchanged reuse leaves no snapshot and rolls back as complete. The tag set comes from
     * DescribeTags, the same source {@link Ec2Tags#reconcile} compares against.
     */
    private void snapshotInPlace(StackResource r, Vpc vpc, String desiredTenancy, boolean dnsSupport,
                                 boolean dnsHostnames, Map<String, String> desiredTags, String region) {
        Map<String, String> currentTags = Ec2Tags.current(ec2Service, region, vpc.getVpcId());
        boolean tenancyChanges = isDedicatedToDefault(vpc, desiredTenancy);
        boolean changed = tenancyChanges
                || vpc.isEnableDnsSupport() != dnsSupport
                || vpc.isEnableDnsHostnames() != dnsHostnames
                || !currentTags.equals(desiredTags);
        if (!changed) {
            return;
        }
        ObjectNode prior = MAPPER.createObjectNode();
        prior.put("region", region);
        prior.put("vpcId", vpc.getVpcId());
        if (tenancyChanges) {
            prior.put("instanceTenancy", vpc.getInstanceTenancy());
        }
        prior.put("enableDnsSupport", vpc.isEnableDnsSupport());
        prior.put("enableDnsHostnames", vpc.isEnableDnsHostnames());
        ObjectNode tags = prior.putObject("tags");
        currentTags.forEach(tags::put);
        r.getAttributes().put(IN_PLACE_PRIOR_ATTR, prior.toString());
    }

    /**
     * Puts the DNS attributes and the tag set back, then the tenancy last. The only tenancy change
     * applied in place is {@code dedicated} to {@code default}, and EC2 refuses the reverse, so a
     * rollback of that change fails on the ModifyVpcTenancy call after everything else is restored
     * and the stack reports UPDATE_ROLLBACK_FAILED for the VPC rather than claiming it is back.
     */
    private void restoreInPlace(StackResource resource, String snapshot) {
        ObjectNode prior;
        try {
            prior = (ObjectNode) MAPPER.readTree(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable in-place snapshot on " + resource.getLogicalId(), e);
        }
        String region = prior.path("region").asText();
        String vpcId = prior.path("vpcId").asText();
        ec2Service.modifyVpcAttribute(region, vpcId, "enableDnsSupport", prior.path("enableDnsSupport").asText());
        ec2Service.modifyVpcAttribute(region, vpcId, "enableDnsHostnames", prior.path("enableDnsHostnames").asText());
        Map<String, String> priorTags = new LinkedHashMap<>();
        prior.path("tags").fields().forEachRemaining(f -> priorTags.put(f.getKey(), f.getValue().asText()));
        Ec2Tags.reconcile(ec2Service, region, vpcId, priorTags);
        if (prior.hasNonNull("instanceTenancy")) {
            ec2Service.modifyVpcTenancy(region, vpcId, prior.get("instanceTenancy").asText());
        }
    }

    /**
     * The in-place undo handed to {@link CfnRollback#guarded}: puts a reused VPC back from its
     * snapshot. The snapshot is removed only after a successful restore, so a failed one keeps it
     * for the rollback walker's retry.
     */
    private void undoInPlace(StackResource r) {
        String snapshot = r.getAttributes().get(IN_PLACE_PRIOR_ATTR);
        if (snapshot != null) {
            restoreInPlace(r, snapshot);
            r.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
        }
    }

    /**
     * The VPC this stack resource already points at, when an UpdateStack re-invocation left it
     * unchanged. {@code provision()} re-runs for every resource on every update, so creating
     * unconditionally would mint a fresh VPC id and silently orphan every subnet, route table and
     * security group that referenced the old one.
     *
     * <p>Returns {@code null} for a fresh create, for a CidrBlock change or an InstanceTenancy
     * change other than {@code dedicated} to {@code default} (which AWS treats as a replacement),
     * or when the VPC is gone from the backend - the caller then creates.
     *
     * <p>The prior id is the one the context captured, not the one on the {@link StackResource}:
     * {@code provision} assigns the new id onto that resource as it runs, so a resource-derived
     * check flips from create to update mid-method.
     */
    private Vpc existingVpcToReconcile(String priorPhysicalId, String cidr, String tenancy, String region) {
        if (priorPhysicalId == null || priorPhysicalId.isBlank()) {
            return null;
        }
        Vpc existing = ec2Service.describeVpcs(region, List.of(priorPhysicalId), Map.of())
                .stream().findFirst().orElse(null);
        if (existing == null) {
            return null;
        }
        // A changed CidrBlock is a replacement on AWS, so let the caller create a new VPC. Compare both
        // sides canonically (10.0.0.5/16 is 10.0.0.0/16): a VPC saved before canonicalization may
        // still hold host bits.
        if (cidr != null && !cidr.isBlank()
                && !CidrCanonicalizer.sameBlock(cidr, existing.getCidrBlock())) {
            return null;
        }
        // ModifyVpcTenancy only goes dedicated -> default; every other tenancy change replaces.
        if (!tenancy.equals(existing.getInstanceTenancy()) && !isDedicatedToDefault(existing, tenancy)) {
            return null;
        }
        return existing;
    }

    private static boolean isDedicatedToDefault(Vpc existing, String desiredTenancy) {
        return DEDICATED_TENANCY.equals(existing.getInstanceTenancy()) && DEFAULT_TENANCY.equals(desiredTenancy);
    }
}
