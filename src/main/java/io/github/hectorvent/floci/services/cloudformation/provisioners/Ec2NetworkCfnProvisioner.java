package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CidrCanonicalizer;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * CloudFormation provisioning for the EC2 networking types: {@code AWS::EC2::Subnet},
 * {@code InternetGateway}, {@code RouteTable}, {@code Route}, {@code NatGateway}, {@code EIP} and
 * {@code SubnetRouteTableAssociation}. Extracted from {@code CloudFormationResourceProvisioner} as
 * part of the per-service decomposition. The physical id is the EC2 id the service assigns, except
 * for a Route, whose id is the registry primary identifier {@code <RouteTableId>|<destination>} (what
 * Ref returns on AWS and what a delete needs), and an EIP, whose Ref is its public IP.
 *
 * <p>{@code provision} runs again on every {@code UpdateStack}. A resource whose create-only
 * properties (the schema's {@code createOnlyProperties} that Floci emulates) are unchanged is kept:
 * its prior entity is described and reused, and the mutable properties are applied in place. A
 * changed create-only property creates the replacement and the {@link ReplacementCleanup} record
 * deletes the displaced entity once the update commits or restores it on rollback, as CloudFormation
 * does. A prior entity that is gone out of band is created anew.
 *
 * <p>Deletes tolerate only the service's own not-found code for each type; a
 * {@code DependencyViolation} still fails the stack delete, as on AWS.
 */
@ApplicationScoped
public class Ec2NetworkCfnProvisioner implements CfnResourceProvisioner {

    private static final String SUBNET = "AWS::EC2::Subnet";
    private static final String INTERNET_GATEWAY = "AWS::EC2::InternetGateway";
    private static final String ROUTE_TABLE = "AWS::EC2::RouteTable";
    private static final String ROUTE = "AWS::EC2::Route";
    private static final String NAT_GATEWAY = "AWS::EC2::NatGateway";
    private static final String EIP = "AWS::EC2::EIP";
    private static final String SUBNET_ROUTE_TABLE_ASSOCIATION = "AWS::EC2::SubnetRouteTableAssociation";
    private static final String ROUTE_ID_SEPARATOR = "|";
    private static final Logger LOG = Logger.getLogger(Ec2NetworkCfnProvisioner.class);
    /**
     * What an in-place update changed on a reused entity, recorded before the change so a stack
     * rollback can put it back: the prior tags (and the keys the update wrote), a subnet's prior
     * MapPublicIpOnLaunch, a route's prior target. Internal, like the other {@code __Floci} attributes.
     */
    static final String IN_PLACE_PRIOR_ATTR = "__FlociEc2NetworkPrior";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Ec2Service ec2Service;

    @Inject
    public Ec2NetworkCfnProvisioner(Ec2Service ec2Service) {
        this.ec2Service = ec2Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(SUBNET, INTERNET_GATEWAY, ROUTE_TABLE, ROUTE, NAT_GATEWAY, EIP, SUBNET_ROUTE_TABLE_ASSOCIATION);
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        switch (r.getResourceType()) {
            case SUBNET -> provisionSubnet(r, props, ctx);
            case INTERNET_GATEWAY -> provisionInternetGateway(r, props, ctx);
            case ROUTE_TABLE -> provisionRouteTable(r, props, ctx);
            case ROUTE -> provisionRoute(r, props, ctx);
            case NAT_GATEWAY -> provisionNatGateway(r, props, ctx);
            case EIP -> provisionEip(r, props, ctx);
            case SUBNET_ROUTE_TABLE_ASSOCIATION -> provisionSubnetRouteTableAssociation(r, props, ctx);
            default -> throw new IllegalStateException("Ec2NetworkCfnProvisioner cannot handle " + r.getResourceType());
        }
        // A provision that left the resource with a new physical id replaced the entity: the
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
     * A replacement is undone through the cleanup record: the displaced entity was never touched.
     * A reused entity that an update changed in place (tags, a subnet's MapPublicIpOnLaunch, a
     * route's target) is put back from the snapshot taken before the change. Without either, the
     * update changed nothing about the entity. A provision that fails mid-way restores its own
     * snapshot before the failure propagates (see {@link #guarded}), so the previous metadata the
     * engine restores for that resource matches the entity again.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            resource.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
            return true;
        }
        String snapshot = resource.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
        if (snapshot != null) {
            restoreInPlace(resource, snapshot);
        }
        return true;
    }

    private void restoreInPlace(StackResource resource, String snapshot) {
        ObjectNode prior;
        try {
            prior = (ObjectNode) MAPPER.readTree(snapshot);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable in-place snapshot on " + resource.getLogicalId(), e);
        }
        String region = prior.path("region").asText();
        String entityId = prior.path("entityId").asText();
        if (prior.has("tags")) {
            Map<String, String> priorTags = new LinkedHashMap<>();
            prior.get("tags").fields().forEachRemaining(f -> priorTags.put(f.getKey(), f.getValue().asText()));
            List<Tag> added = new ArrayList<>();
            prior.path("appliedKeys").forEach(key -> {
                if (!priorTags.containsKey(key.asText())) {
                    added.add(new Tag(key.asText(), null));
                }
            });
            if (!added.isEmpty()) {
                ec2Service.deleteTags(region, List.of(entityId), added);
            }
            if (!priorTags.isEmpty()) {
                ec2Service.createTags(region, List.of(entityId), toTagList(priorTags));
            }
        }
        if (prior.has("mapPublicIpOnLaunch")) {
            ec2Service.modifySubnetAttribute(region, entityId, "mapPublicIpOnLaunch", prior.get("mapPublicIpOnLaunch").asText());
        }
        if (prior.has("route")) {
            ObjectNode target = (ObjectNode) prior.get("route");
            String routeTableId = target.path("routeTableId").asText();
            String cidr = text(target, "destinationCidrBlock");
            String ipv6 = text(target, "destinationIpv6CidrBlock");
            String prefixList = text(target, "destinationPrefixListId");
            String egressOnly = text(target, "egressOnlyInternetGatewayId");
            if (egressOnly != null) {
                ec2Service.deleteRoute(region, routeTableId, cidr, ipv6, prefixList);
                ec2Service.createRoute(region, routeTableId, cidr, ipv6, prefixList, null, null, egressOnly, null);
            } else {
                ec2Service.replaceRoute(region, routeTableId, cidr, ipv6, prefixList,
                        text(target, "gatewayId"), text(target, "natGatewayId"), text(target, "vpcPeeringConnectionId"));
            }
        }
    }

    private static String text(ObjectNode node, String field) {
        return node.hasNonNull(field) ? node.get(field).asText() : null;
    }

    /** The snapshot for this resource, created on first use so the fields of several changes accumulate. */
    private static ObjectNode inPlacePrior(StackResource r, String entityId, ProvisionContext ctx) {
        String existing = r.getAttributes().get(IN_PLACE_PRIOR_ATTR);
        try {
            ObjectNode node = existing == null ? MAPPER.createObjectNode() : (ObjectNode) MAPPER.readTree(existing);
            node.put("region", ctx.region());
            node.put("entityId", entityId);
            return node;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable in-place snapshot on " + r.getLogicalId(), e);
        }
    }

    private static void writeInPlacePrior(StackResource r, ObjectNode node) {
        r.getAttributes().put(IN_PLACE_PRIOR_ATTR, node.toString());
    }

    /** An EIP's Ref is its public IP, so its release needs the AllocationId recorded at create time. */
    @Override
    public void delete(StackResource resource, String region) {
        if (EIP.equals(resource.getResourceType()) && resource.getAttributes() != null
                && resource.getAttributes().get("AllocationId") != null) {
            String allocationId = resource.getAttributes().get("AllocationId");
            CfnDeletes.safeDelete("elastic IP", allocationId,
                    () -> ec2Service.releaseAddress(region, allocationId), "InvalidAllocationID.NotFound");
            return;
        }
        delete(resource.getResourceType(), resource.getPhysicalId(), region);
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        if (physicalId == null || physicalId.isBlank()) {
            return;
        }
        switch (resourceType) {
            case SUBNET -> CfnDeletes.safeDelete("subnet", physicalId,
                    () -> ec2Service.deleteSubnet(region, physicalId), "InvalidSubnetID.NotFound");
            case INTERNET_GATEWAY -> CfnDeletes.safeDelete("internet gateway", physicalId,
                    () -> ec2Service.deleteInternetGateway(region, physicalId), "InvalidInternetGatewayID.NotFound");
            case ROUTE_TABLE -> CfnDeletes.safeDelete("route table", physicalId,
                    () -> ec2Service.deleteRouteTable(region, physicalId), "InvalidRouteTableID.NotFound");
            case ROUTE -> deleteRoute(physicalId, region);
            case NAT_GATEWAY -> CfnDeletes.safeDelete("NAT gateway", physicalId,
                    () -> ec2Service.deleteNatGateway(region, physicalId), "NatGatewayNotFound");
            case EIP -> releaseByPublicIp(physicalId, region);
            case SUBNET_ROUTE_TABLE_ASSOCIATION -> CfnDeletes.safeDelete("route table association", physicalId,
                    () -> ec2Service.disassociateRouteTable(region, physicalId), "InvalidAssociationID.NotFound");
            default -> { }
        }
    }

    /**
     * The route's id names its table and destination; a route created by the switch before this
     * provisioner existed carries a generated id with no separator and cannot be located, so it is
     * left alone rather than guessed at. A table that is already gone took its routes with it.
     */
    private void deleteRoute(String physicalId, String region) {
        int separator = physicalId.indexOf(ROUTE_ID_SEPARATOR);
        if (separator <= 0 || separator == physicalId.length() - 1) {
            return;
        }
        String routeTableId = physicalId.substring(0, separator);
        String destination = physicalId.substring(separator + 1);
        CfnDeletes.safeDelete("route", physicalId, () -> ec2Service.deleteRoute(region, routeTableId,
                        isIpv4Cidr(destination) ? destination : null,
                        isIpv6Cidr(destination) ? destination : null,
                        isPrefixList(destination) ? destination : null),
                "InvalidRoute.NotFound", "InvalidRouteTableID.NotFound");
    }

    /** Without the recorded AllocationId (a rollback of a failed create, for instance), the address is looked up by IP. */
    private void releaseByPublicIp(String publicIp, String region) {
        ec2Service.describeAddresses(region, List.of(), Map.of("public-ip", List.of(publicIp))).stream()
                .findFirst()
                .ifPresent(address -> CfnDeletes.safeDelete("elastic IP", address.getAllocationId(),
                        () -> ec2Service.releaseAddress(region, address.getAllocationId()), "InvalidAllocationID.NotFound"));
    }

    /**
     * Reconciles the template's {@code Tags} onto an entity: keys the template dropped are removed,
     * the rest written. A template without a Tags property leaves whatever the entity carries.
     */
    private void applyTags(StackResource r, boolean reused, String resourceId, List<Tag> current, JsonNode props, ProvisionContext ctx) {
        if (props == null || !props.has("Tags")) {
            return;
        }
        Map<String, String> desired = ctx.resolveTags(props, "Tags");
        Map<String, String> currentTags = new LinkedHashMap<>();
        for (Tag tag : current == null ? List.<Tag>of() : current) {
            currentTags.put(tag.getKey(), tag.getValue());
        }
        List<String> stale = ProvisionContext.staleTagKeys(currentTags, desired);
        if (reused && ctx.isUpdate() && (!stale.isEmpty() || !desired.equals(currentTags))) {
            ObjectNode prior = inPlacePrior(r, resourceId, ctx);
            ObjectNode tags = prior.putObject("tags");
            currentTags.forEach(tags::put);
            var applied = prior.putArray("appliedKeys");
            desired.keySet().forEach(applied::add);
            writeInPlacePrior(r, prior);
        }
        if (!stale.isEmpty()) {
            ec2Service.deleteTags(ctx.region(), List.of(resourceId),
                    stale.stream().map(key -> new Tag(key, null)).toList());
        }
        if (!desired.isEmpty()) {
            ec2Service.createTags(ctx.region(), List.of(resourceId), toTagList(desired));
        }
    }

    private static List<Tag> tagList(JsonNode props, ProvisionContext ctx) {
        return toTagList(ctx.resolveTags(props, "Tags"));
    }

    private static List<Tag> toTagList(Map<String, String> tags) {
        List<Tag> out = new ArrayList<>();
        tags.forEach((key, value) -> out.add(new Tag(key, value)));
        return out;
    }

    private Subnet findSubnet(String subnetId, String region) {
        return tolerateNotFound(() -> ec2Service.describeSubnets(region, List.of(subnetId), Map.of()).stream()
                .findFirst().orElse(null), "InvalidSubnetID.NotFound", subnetId);
    }

    private InternetGateway findInternetGateway(String igwId, String region) {
        return tolerateNotFound(() -> ec2Service.describeInternetGateways(region, List.of(igwId), Map.of()).stream()
                .findFirst().orElse(null), "InvalidInternetGatewayID.NotFound", igwId);
    }

    private RouteTable findRouteTable(String routeTableId, String region) {
        return tolerateNotFound(() -> ec2Service.describeRouteTables(region, List.of(routeTableId), Map.of()).stream()
                .findFirst().orElse(null), "InvalidRouteTableID.NotFound", routeTableId);
    }

    private RouteTableAssociation findAssociation(String associationId, String region) {
        return ec2Service.describeRouteTables(region, List.of(),
                        Map.of("association.route-table-association-id", List.of(associationId))).stream()
                .flatMap(rt -> rt.getAssociations().stream())
                .filter(a -> associationId.equals(a.getRouteTableAssociationId()))
                .findFirst().orElse(null);
    }

    private NatGateway findNatGateway(String natGatewayId, String region) {
        return tolerateNotFound(() -> ec2Service.describeNatGateways(region, List.of(natGatewayId), Map.of()).stream()
                .filter(nat -> !"deleted".equals(nat.getState()))
                .findFirst().orElse(null), "NatGatewayNotFound", natGatewayId);
    }

    private Address findAddress(String allocationId, String publicIp, String region) {
        List<Address> matches = allocationId != null
                ? ec2Service.describeAddresses(region, List.of(allocationId), Map.of())
                : ec2Service.describeAddresses(region, List.of(), Map.of("public-ip", List.of(publicIp)));
        return matches.stream().findFirst().orElse(null);
    }

    private Route findRoute(String routeTableId, String destination, String region) {
        RouteTable table = findRouteTable(routeTableId, region);
        return table == null ? null : table.getRoutes().stream()
                .filter(route -> destination.equals(routeDestination(route)))
                .findFirst().orElse(null);
    }

    private static String routeDestination(Route route) {
        if (route.getDestinationCidrBlock() != null) {
            return route.getDestinationCidrBlock();
        }
        if (route.getDestinationIpv6CidrBlock() != null) {
            return route.getDestinationIpv6CidrBlock();
        }
        return route.getDestinationPrefixListId();
    }

    private static <T> T tolerateNotFound(Supplier<T> lookup, String notFoundCode, String id) {
        try {
            return lookup.get();
        } catch (AwsException e) {
            if (!notFoundCode.equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("{0} is gone; creating it anew", id);
            return null;
        }
    }

    /** The schema's required properties fail the resource with the repo's ValidationError wording, not a null into the service. */
    private static void require(String type, String property, String value) {
        if (value == null || value.isBlank()) {
            throw new AwsException("ValidationError", type + " requires " + property, 400);
        }
    }

    private static boolean isPrefixList(String destination) {
        return destination.startsWith("pl-");
    }

    private static boolean isIpv6Cidr(String destination) {
        return destination.contains(":");
    }

    private static boolean isIpv4Cidr(String destination) {
        return !isPrefixList(destination) && !isIpv6Cidr(destination);
    }

    private void provisionSubnet(StackResource r, JsonNode props, ProvisionContext ctx) {
        String vpcId = ctx.resolveOptional(props, "VpcId");
        String cidr = ctx.resolveOptional(props, "CidrBlock");
        String az = ctx.resolveOptional(props, "AvailabilityZone");
        String mapPublicIpOnLaunch = ctx.resolveOptional(props, "MapPublicIpOnLaunch");
        require(SUBNET, "VpcId", vpcId);
        // VpcId, CidrBlock and AvailabilityZone are createOnly: unchanged, the subnet is kept and
        // only MapPublicIpOnLaunch is applied; changed, a new subnet replaces it.
        Subnet existing = ctx.isUpdate() ? findSubnet(ctx.priorPhysicalId(), ctx.region()) : null;
        boolean reuse = existing != null && Objects.equals(vpcId, existing.getVpcId())
                && Objects.equals(cidr, existing.getCidrBlock())
                && (az == null || az.equals(existing.getAvailabilityZone()));
        Subnet subnet = reuse ? existing : ec2Service.createSubnet(ctx.region(), vpcId, cidr, az);
        r.setPhysicalId(subnet.getSubnetId());
        r.getAttributes().put("SubnetId", subnet.getSubnetId());
        r.getAttributes().put("VpcId", subnet.getVpcId());
        r.getAttributes().put("AvailabilityZone", subnet.getAvailabilityZone());
        guarded(r, ctx, !reuse, () -> ec2Service.deleteSubnet(ctx.region(), subnet.getSubnetId()), () -> {
            applyTags(r, reuse, subnet.getSubnetId(), reuse ? existing.getTags() : List.of(), props, ctx);
            if (mapPublicIpOnLaunch != null) {
                if (reuse && ctx.isUpdate() && existing.isMapPublicIpOnLaunch() != Boolean.parseBoolean(mapPublicIpOnLaunch)) {
                    ObjectNode prior = inPlacePrior(r, subnet.getSubnetId(), ctx);
                    prior.put("mapPublicIpOnLaunch", String.valueOf(existing.isMapPublicIpOnLaunch()));
                    writeInPlacePrior(r, prior);
                }
                ec2Service.modifySubnetAttribute(ctx.region(), subnet.getSubnetId(), "mapPublicIpOnLaunch", mapPublicIpOnLaunch);
            }
        });
    }

    /**
     * Runs the steps that follow an EC2 create or change a reused entity in place. If one of them
     * throws, what this provision did is undone before the failure propagates: a created entity is
     * deleted again, a reused one is put back from its snapshot. The engine only removes a failed
     * create it can see, and on a failed update it restores the previous resource metadata and
     * discards the attempted one, snapshot included, so the undo has to happen here.
     *
     * <p>A compensation that fails is reported, never hidden. A created entity carries
     * {@link CfnRollback#ROLLBACK_OWNED_ATTR} while the steps run, so the engine's create rollback
     * deletes it (and reports that failing) if the undo here could not, and during an update the
     * entity is also listed in the cleanup record, which the engine carries onto the restored
     * resource and runs before the next committed update or the stack delete; a reused entity keeps its
     * snapshot and the reason lands in {@link CfnRollback#UPDATE_ROLLBACK_FAILURE_ATTR}, which the
     * engine copies onto the committed resource so the rollback walker reports
     * UPDATE_ROLLBACK_FAILED instead of claiming the prior state is live.
     */
    private void guarded(StackResource r, ProvisionContext ctx, boolean created, Runnable undoCreate, Runnable steps) {
        if (created) {
            r.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
        }
        try {
            steps.run();
            r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
        } catch (RuntimeException e) {
            if (created) {
                try {
                    undoCreate.run();
                    r.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
                } catch (RuntimeException undo) {
                    e.addSuppressed(undo);
                    String reason = "Could not remove " + r.getPhysicalId() + ", created for " + r.getLogicalId()
                            + " by a failed update: " + undo.getMessage();
                    LOG.warn(reason);
                    r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
                    if (ctx.isUpdate()) {
                        // The engine restores the previous resource and keeps only what the cleanup
                        // record carries, so the replacement is listed there for the next cleanup.
                        ReplacementCleanup.recordOrphan(r, r.getPhysicalId(), r.getResourceType(), ctx.region());
                    }
                }
            } else {
                String snapshot = r.getAttributes().get(IN_PLACE_PRIOR_ATTR);
                if (snapshot != null) {
                    try {
                        restoreInPlace(r, snapshot);
                        r.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
                    } catch (RuntimeException undo) {
                        e.addSuppressed(undo);
                        String reason = "Could not restore " + r.getPhysicalId() + " for " + r.getLogicalId()
                                + " after a failed update: " + undo.getMessage();
                        LOG.warn(reason);
                        r.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR, reason);
                    }
                }
            }
            throw e;
        }
    }

    private void provisionInternetGateway(StackResource r, JsonNode props, ProvisionContext ctx) {
        InternetGateway existing = ctx.isUpdate() ? findInternetGateway(ctx.priorPhysicalId(), ctx.region()) : null;
        InternetGateway igw = existing != null ? existing : ec2Service.createInternetGateway(ctx.region());
        r.setPhysicalId(igw.getInternetGatewayId());
        r.getAttributes().put("InternetGatewayId", igw.getInternetGatewayId());
        guarded(r, ctx, existing == null, () -> ec2Service.deleteInternetGateway(ctx.region(), igw.getInternetGatewayId()),
                () -> applyTags(r, existing != null, igw.getInternetGatewayId(), existing != null ? existing.getTags() : List.of(), props, ctx));
    }

    private void provisionRouteTable(StackResource r, JsonNode props, ProvisionContext ctx) {
        String vpcId = ctx.resolveOptional(props, "VpcId");
        require(ROUTE_TABLE, "VpcId", vpcId);
        // VpcId is createOnly: a table in the same VPC is kept, one in another VPC is replaced.
        RouteTable existing = ctx.isUpdate() ? findRouteTable(ctx.priorPhysicalId(), ctx.region()) : null;
        boolean reuse = existing != null && Objects.equals(vpcId, existing.getVpcId());
        RouteTable rt = reuse ? existing : ec2Service.createRouteTable(ctx.region(), vpcId);
        r.setPhysicalId(rt.getRouteTableId());
        r.getAttributes().put("RouteTableId", rt.getRouteTableId());
        guarded(r, ctx, !reuse, () -> ec2Service.deleteRouteTable(ctx.region(), rt.getRouteTableId()),
                () -> applyTags(r, reuse, rt.getRouteTableId(), reuse ? existing.getTags() : List.of(), props, ctx));
    }

    private void provisionSubnetRouteTableAssociation(StackResource r, JsonNode props, ProvisionContext ctx) {
        String routeTableId = ctx.resolveOptional(props, "RouteTableId");
        String subnetId = ctx.resolveOptional(props, "SubnetId");
        require(SUBNET_ROUTE_TABLE_ASSOCIATION, "RouteTableId", routeTableId);
        require(SUBNET_ROUTE_TABLE_ASSOCIATION, "SubnetId", subnetId);
        // Both properties are createOnly: the same pair keeps the association, anything else is a
        // new association and the prior one is removed once the update commits.
        RouteTableAssociation existing = ctx.isUpdate() ? findAssociation(ctx.priorPhysicalId(), ctx.region()) : null;
        RouteTableAssociation assoc = existing != null && Objects.equals(routeTableId, existing.getRouteTableId())
                && Objects.equals(subnetId, existing.getSubnetId())
                ? existing : ec2Service.associateRouteTable(ctx.region(), routeTableId, subnetId);
        r.setPhysicalId(assoc.getRouteTableAssociationId());
        r.getAttributes().put("Id", assoc.getRouteTableAssociationId());
    }

    private void provisionRoute(StackResource r, JsonNode props, ProvisionContext ctx) {
        String routeTableId = ctx.resolveOptional(props, "RouteTableId");
        String destinationCidr = ctx.resolveOptional(props, "DestinationCidrBlock");
        String destinationIpv6Cidr = ctx.resolveOptional(props, "DestinationIpv6CidrBlock");
        String destinationPrefixListId = ctx.resolveOptional(props, "DestinationPrefixListId");
        String gatewayId = ctx.resolveOptional(props, "GatewayId");
        String natGatewayId = ctx.resolveOptional(props, "NatGatewayId");
        String egressOnlyInternetGatewayId = ctx.resolveOptional(props, "EgressOnlyInternetGatewayId");
        String vpcPeeringConnectionId = ctx.resolveOptional(props, "VpcPeeringConnectionId");
        require(ROUTE, "RouteTableId", routeTableId);
        long destinations = java.util.stream.Stream.of(destinationCidr, destinationIpv6Cidr, destinationPrefixListId)
                .filter(value -> value != null && !value.isBlank()).count();
        if (destinations != 1) {
            throw new AwsException("ValidationError", ROUTE + " requires exactly one of DestinationCidrBlock,"
                    + " DestinationIpv6CidrBlock or DestinationPrefixListId", 400);
        }
        // The registry primary identifier is RouteTableId|CidrBlock, which is also what Ref returns
        // on AWS, and the only id a later delete can act on. CidrBlock is the schema's read-only
        // attribute: the destination the route was created with, whichever property carried it.
        // Ec2Service stores an IPv4 destination in canonical form (10.0.0.18/24 is 10.0.0.0/24), so
        // the id, the attribute and the reuse check use that form too, or an unchanged update
        // would miss its own route and try to create it again.
        String canonicalCidr = destinationCidr == null ? null
                : CidrCanonicalizer.canonicalize(destinationCidr).orElse(destinationCidr);
        String destination = canonicalCidr != null ? canonicalCidr
                : destinationIpv6Cidr != null ? destinationIpv6Cidr : destinationPrefixListId;
        String physicalId = routeTableId + ROUTE_ID_SEPARATOR + destination;
        Supplier<Void> create = () -> {
            ec2Service.createRoute(ctx.region(), routeTableId, destinationCidr, destinationIpv6Cidr,
                    destinationPrefixListId, gatewayId, natGatewayId, egressOnlyInternetGatewayId,
                    vpcPeeringConnectionId);
            return null;
        };
        // The table and destination are createOnly: the same pair changes the target in place,
        // another pair is a new route and the prior one goes once the update commits.
        Route current = ctx.isUpdate() && physicalId.equals(ctx.priorPhysicalId())
                ? findRoute(routeTableId, destination, ctx.region()) : null;
        if (current != null) {
            ObjectNode prior = inPlacePrior(r, physicalId, ctx);
            ObjectNode target = prior.putObject("route");
            target.put("routeTableId", routeTableId);
            target.put("destinationCidrBlock", current.getDestinationCidrBlock());
            target.put("destinationIpv6CidrBlock", current.getDestinationIpv6CidrBlock());
            target.put("destinationPrefixListId", current.getDestinationPrefixListId());
            target.put("gatewayId", current.getGatewayId());
            target.put("natGatewayId", current.getNatGatewayId());
            target.put("egressOnlyInternetGatewayId", current.getEgressOnlyInternetGatewayId());
            target.put("vpcPeeringConnectionId", current.getVpcPeeringConnectionId());
            writeInPlacePrior(r, prior);
            guarded(r, ctx, false, () -> { }, () -> {
                if (egressOnlyInternetGatewayId != null) {
                    // ReplaceRoute has no egress-only target; re-creating the route is the same outcome.
                    ec2Service.deleteRoute(ctx.region(), routeTableId, destinationCidr, destinationIpv6Cidr, destinationPrefixListId);
                    create.get();
                } else {
                    ec2Service.replaceRoute(ctx.region(), routeTableId, destinationCidr, destinationIpv6Cidr,
                            destinationPrefixListId, gatewayId, natGatewayId, vpcPeeringConnectionId);
                }
            });
        } else {
            create.get();
        }
        r.setPhysicalId(physicalId);
        if (destination != null) {
            r.getAttributes().put("CidrBlock", destination);
        }
    }

    private void provisionNatGateway(StackResource r, JsonNode props, ProvisionContext ctx) {
        String subnetId = ctx.resolveOptional(props, "SubnetId");
        String allocationId = ctx.resolveOptional(props, "AllocationId");
        require(NAT_GATEWAY, "SubnetId", subnetId);
        // ConnectivityType defaults to public in the schema; a public gateway needs an EIP, a
        // private one must not have one, as AWS validates.
        String connectivityType = ctx.resolveOptional(props, "ConnectivityType");
        connectivityType = connectivityType == null || connectivityType.isBlank() ? "public" : connectivityType.toLowerCase();
        if ("public".equals(connectivityType) && (allocationId == null || allocationId.isBlank())) {
            throw new AwsException("ValidationError", "AWS::EC2::NatGateway requires AllocationId for a public gateway", 400);
        }
        if ("private".equals(connectivityType) && allocationId != null && !allocationId.isBlank()) {
            throw new AwsException("ValidationError", "AWS::EC2::NatGateway with ConnectivityType private cannot have an AllocationId", 400);
        }
        // SubnetId, AllocationId and ConnectivityType are createOnly: the same triple keeps the
        // gateway, a change replaces it.
        NatGateway existing = ctx.isUpdate() ? findNatGateway(ctx.priorPhysicalId(), ctx.region()) : null;
        boolean reuse = existing != null && Objects.equals(subnetId, existing.getSubnetId())
                && Objects.equals(allocationId, existing.getAllocationId())
                && connectivityType.equalsIgnoreCase(existing.getConnectivityType() == null ? "public" : existing.getConnectivityType());
        NatGateway nat = reuse ? existing
                : ec2Service.createNatGateway(ctx.region(), subnetId, allocationId, connectivityType, tagList(props, ctx));
        if (reuse) {
            guarded(r, ctx, false, () -> { }, () -> applyTags(r, true, nat.getNatGatewayId(), existing.getTags(), props, ctx));
        }
        r.setPhysicalId(nat.getNatGatewayId());
        r.getAttributes().put("NatGatewayId", nat.getNatGatewayId());
    }

    private void provisionEip(StackResource r, JsonNode props, ProvisionContext ctx) {
        // Nothing about an EIP that Floci emulates is mutable or create-only in a way a template can
        // change, so an address that is still allocated is simply kept.
        Address existing = ctx.isUpdate() ? findAddress(r.getAttributes().get("AllocationId"), ctx.priorPhysicalId(), ctx.region()) : null;
        Address addr = existing != null ? existing : ec2Service.allocateAddress(ctx.region());
        // Ref on AWS::EC2::EIP returns the public IP; AllocationId is exposed via Fn::GetAtt.
        r.setPhysicalId(addr.getPublicIp());
        r.getAttributes().put("AllocationId", addr.getAllocationId());
        r.getAttributes().put("PublicIp", addr.getPublicIp());
        guarded(r, ctx, existing == null, () -> ec2Service.releaseAddress(ctx.region(), addr.getAllocationId()),
                () -> applyTags(r, existing != null, addr.getAllocationId(), existing != null ? existing.getTags() : List.of(), props, ctx));
    }
}
