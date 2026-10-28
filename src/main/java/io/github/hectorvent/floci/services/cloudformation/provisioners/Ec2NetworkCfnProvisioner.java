package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.CidrCanonicalizer;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Address;
import io.github.hectorvent.floci.services.ec2.model.InternetGateway;
import io.github.hectorvent.floci.services.ec2.model.ManagedPrefixList;
import io.github.hectorvent.floci.services.ec2.model.NatGateway;
import io.github.hectorvent.floci.services.ec2.model.PrefixListEntry;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * CloudFormation provisioning for the EC2 networking types: {@code AWS::EC2::Subnet},
 * {@code InternetGateway}, {@code RouteTable}, {@code Route}, {@code NatGateway}, {@code EIP}, {@code PrefixList} and
 * {@code SubnetRouteTableAssociation}. Extracted from the former CloudFormation monolith as
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
    private static final String PREFIX_LIST = "AWS::EC2::PrefixList";
    private static final String SUBNET_ROUTE_TABLE_ASSOCIATION = "AWS::EC2::SubnetRouteTableAssociation";
    private static final String ROUTE_ID_SEPARATOR = "|";
    private static final Logger LOG = Logger.getLogger(Ec2NetworkCfnProvisioner.class);
    /**
     * What an in-place update changed on a reused entity, recorded before the change so a stack
     * rollback can put it back: the prior tags (and the keys the update wrote), a subnet's prior
     * MapPublicIpOnLaunch, a route's prior target, a prefix list's prior name, capacity and entries.
     * Internal, like the other {@code __Floci} attributes.
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
        return Set.of(SUBNET, INTERNET_GATEWAY, ROUTE_TABLE, ROUTE, NAT_GATEWAY, EIP, PREFIX_LIST,
                SUBNET_ROUTE_TABLE_ASSOCIATION);
    }

    @Override
    public Map<String, Set<String>> integerAttributes() {
        return Map.of(PREFIX_LIST, Set.of("Version"));
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
            case PREFIX_LIST -> provisionPrefixList(r, props, ctx);
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
     * route's target, a prefix list's name, capacity or entries) is put back from the snapshot
     * taken before the change; the snapshot is removed only after the restore succeeds, so a
     * restore that throws keeps it for a ContinueUpdateRollback retry. Without either, the update
     * changed nothing about the entity.
     * A provision that fails mid-way restores its own snapshot before the failure propagates (see
     * {@link #guarded}), so the previous metadata the engine restores for that resource matches
     * the entity again.
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
            prior.get("tags").fields().forEachRemaining(f -> {
                if (!Ec2Service.isAwsTagKey(f.getKey())) {
                    priorTags.put(f.getKey(), f.getValue().asText());
                }
            });
            List<Tag> added = new ArrayList<>();
            prior.path("appliedKeys").forEach(key -> {
                if (!Ec2Service.isAwsTagKey(key.asText()) && !priorTags.containsKey(key.asText())) {
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
        if (prior.has("prefixList")) {
            // Putting the entries back is a modification like any other, so the list moves on to a
            // new version rather than returning to the old one, and Fn::GetAtt Version follows it.
            JsonNode before = prior.get("prefixList");
            List<PrefixListEntry> entries = new ArrayList<>();
            before.path("entries").forEach(entry -> entries.add(
                    new PrefixListEntry(entry.path("cidr").asText(), entry.path("description").asText(null))));
            ManagedPrefixList current = ec2Service.describeManagedPrefixLists(region, List.of(entityId), Map.of())
                    .getFirst();
            ManagedPrefixList restored = modifyPrefixList(region, current, before.path("name").asText(),
                    before.path("maxEntries").asInt(), EntryChanges.between(current.currentEntries(), entries));
            resource.getAttributes().put("Version", Long.toString(restored.getVersion()));
        }
        if (prior.has("route")) {
            ObjectNode target = (ObjectNode) prior.get("route");
            String routeTableId = target.path("routeTableId").asText();
            String cidr = text(target, "destinationCidrBlock");
            String ipv6 = text(target, "destinationIpv6CidrBlock");
            String prefixList = text(target, "destinationPrefixListId");
            String egressOnly = text(target, "egressOnlyInternetGatewayId");
            if (egressOnly != null) {
                CfnDeletes.safeDelete("route", routeTableId, () -> ec2Service.deleteRoute(region, routeTableId, cidr,
                        ipv6, prefixList), "InvalidRoute.NotFound");
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
            case PREFIX_LIST -> CfnDeletes.safeDelete("prefix list", physicalId,
                    () -> ec2Service.deleteManagedPrefixList(region, physicalId), "InvalidPrefixListID.NotFound");
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
            if (!Ec2Service.isAwsTagKey(tag.getKey())) {
                currentTags.put(tag.getKey(), tag.getValue());
            }
        }
        List<String> stale = ProvisionContext.staleTagKeys(currentTags, desired);
        if (reused && ctx.isUpdate() && (!stale.isEmpty() || !desired.equals(currentTags))) {
            ObjectNode prior = inPlacePrior(r, resourceId, ctx);
            ObjectNode tags = prior.putObject("tags");
            currentTags.forEach(tags::put);
            ArrayNode applied = prior.putArray("appliedKeys");
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

    private ManagedPrefixList findPrefixList(String prefixListId, String region) {
        return tolerateNotFound(() -> ec2Service.describeManagedPrefixLists(region, List.of(prefixListId), Map.of())
                .stream().findFirst().orElse(null), "InvalidPrefixListID.NotFound", prefixListId);
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
                // Both sides canonical: a subnet saved before canonicalization may still hold host bits.
                && CidrCanonicalizer.sameBlock(cidr, existing.getCidrBlock())
                && (az == null || az.equals(existing.getAvailabilityZone()));
        Subnet subnet = reuse ? existing : ec2Service.createSubnet(ctx.region(), vpcId, cidr, az);
        r.setPhysicalId(subnet.getSubnetId());
        r.getAttributes().put("SubnetId", subnet.getSubnetId());
        r.getAttributes().put("VpcId", subnet.getVpcId());
        r.getAttributes().put("AvailabilityZone", subnet.getAvailabilityZone());
        CfnRollback.guarded(r, ctx, !reuse, () -> ec2Service.deleteSubnet(ctx.region(), subnet.getSubnetId()),
                () -> undoInPlace(r), () -> {
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
     * The in-place undo handed to {@link CfnRollback#guarded}: puts a reused entity back from its
     * snapshot and spends it, leaving it in place when the restore fails so the next rollback can
     * retry.
     */
    private void undoInPlace(StackResource r) {
        String snapshot = r.getAttributes().get(IN_PLACE_PRIOR_ATTR);
        if (snapshot != null) {
            restoreInPlace(r, snapshot);
            r.getAttributes().remove(IN_PLACE_PRIOR_ATTR);
        }
    }

    private void provisionInternetGateway(StackResource r, JsonNode props, ProvisionContext ctx) {
        InternetGateway existing = ctx.isUpdate() ? findInternetGateway(ctx.priorPhysicalId(), ctx.region()) : null;
        InternetGateway igw = existing != null ? existing : ec2Service.createInternetGateway(ctx.region());
        r.setPhysicalId(igw.getInternetGatewayId());
        r.getAttributes().put("InternetGatewayId", igw.getInternetGatewayId());
        CfnRollback.guarded(r, ctx, existing == null,
                () -> ec2Service.deleteInternetGateway(ctx.region(), igw.getInternetGatewayId()), () -> undoInPlace(r),
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
        CfnRollback.guarded(r, ctx, !reuse, () -> ec2Service.deleteRouteTable(ctx.region(), rt.getRouteTableId()),
                () -> undoInPlace(r),
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
        long destinations = Stream.of(destinationCidr, destinationIpv6Cidr, destinationPrefixListId)
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
            CfnRollback.guarded(r, ctx, false, () -> { }, () -> undoInPlace(r), () -> {
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
        if ("failed".equals(nat.getState())) {
            // The resource fails and its failed gateway is not left behind.
            ec2Service.deleteNatGateway(ctx.region(), nat.getNatGatewayId());
            throw new AwsException(nat.getFailureCode(), nat.getFailureMessage(), 400);
        }
        if (reuse) {
            CfnRollback.guarded(r, ctx, false, () -> { }, () -> undoInPlace(r), () -> applyTags(r, true, nat.getNatGatewayId(), existing.getTags(), props, ctx));
        }
        r.setPhysicalId(nat.getNatGatewayId());
        r.getAttributes().put("NatGatewayId", nat.getNatGatewayId());
        // The gateway holds its subnet and Elastic IP until it is deleted; an EIP's physical id is
        // its public IP.
        if (nat.getSubnetId() != null) {
            r.getAttributes().put(CfnRollback.USES_ATTR_PREFIX + "SubnetId", nat.getSubnetId());
        }
        Address eip = allocationId == null || allocationId.isBlank() ? null : findAddress(allocationId, null, ctx.region());
        if (eip != null && eip.getPublicIp() != null) {
            r.getAttributes().put(CfnRollback.USES_ATTR_PREFIX + "PublicIp", eip.getPublicIp());
        } else {
            r.getAttributes().remove(CfnRollback.USES_ATTR_PREFIX + "PublicIp");
        }
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
        CfnRollback.guarded(r, ctx, existing == null,
                () -> ec2Service.releaseAddress(ctx.region(), addr.getAllocationId()), () -> undoInPlace(r),
                () -> applyTags(r, existing != null, addr.getAllocationId(), existing != null ? existing.getTags() : List.of(), props, ctx));
    }

    /**
     * A customer-managed prefix list. Its schema has no create-only property, so an update changes
     * the list it already has in place, and only a list gone out of band is created anew. MaxEntries
     * is required to create a list; an update that leaves it out keeps the list's capacity.
     */
    private void provisionPrefixList(StackResource r, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "PrefixListName");
        String family = ctx.resolveOptional(props, "AddressFamily");
        require(PREFIX_LIST, "PrefixListName", name);
        require(PREFIX_LIST, "AddressFamily", family);
        Integer maxEntries = ctx.resolveOptionalInt(props, "MaxEntries");
        List<PrefixListEntry> entries = prefixListEntries(props, ctx);
        ManagedPrefixList existing = ctx.isUpdate() ? findPrefixList(ctx.priorPhysicalId(), ctx.region()) : null;
        if (existing == null) {
            if (maxEntries == null) {
                throw new AwsException("ValidationError", PREFIX_LIST + " requires MaxEntries", 400);
            }
            ManagedPrefixList created = ec2Service.createManagedPrefixList(ctx.region(), name, family, maxEntries,
                    entries == null ? List.of() : entries, List.of());
            setPrefixListAttributes(r, created);
            // Tags go through CreateTags, as on the other types here, so EC2 checks them all; a tag it
            // refuses removes the new list again.
            CfnRollback.guarded(r, ctx, true,
                    () -> ec2Service.deleteManagedPrefixList(ctx.region(), created.getPrefixListId()),
                    () -> undoInPlace(r), () -> applyTags(r, false, created.getPrefixListId(), List.of(), props, ctx));
        } else {
            updatePrefixList(r, existing, name, family,
                    maxEntries != null ? maxEntries : existing.getMaxEntries(), entries, props, ctx);
            setPrefixListAttributes(r, ec2Service.describeManagedPrefixLists(ctx.region(),
                    List.of(existing.getPrefixListId()), Map.of()).getFirst());
        }
    }

    private static void setPrefixListAttributes(StackResource r, ManagedPrefixList list) {
        r.setPhysicalId(list.getPrefixListId());
        r.getAttributes().put("PrefixListId", list.getPrefixListId());
        r.getAttributes().put("Arn", list.getPrefixListArn());
        r.getAttributes().put("OwnerId", list.getOwnerId());
        r.getAttributes().put("Version", Long.toString(list.getVersion()));
    }

    /**
     * Applies an update to a kept prefix list. Two changes fail it before anything is touched: another
     * AddressFamily, which EC2 has no way to change on an existing list, and a new MaxEntries together
     * with changed Entries, which CloudFormation does not apply in one update. The tags go first and
     * the ModifyManagedPrefixList calls last, so a failure undoes at most the tags and leaves the
     * list on the version it had.
     *
     * <p>Entries and Tags are each brought to the template only when it declares them, or the
     * previous template did: a template that drops them clears them, and entries or tags no template
     * ever declared are left alone. The other types here keep their tags when Tags is dropped.
     */
    private void updatePrefixList(StackResource r, ManagedPrefixList existing, String name, String family,
                                  int maxEntries, List<PrefixListEntry> entries, JsonNode props, ProvisionContext ctx) {
        if (!family.equals(existing.getAddressFamily())) {
            throw new AwsException("ValidationError", PREFIX_LIST + " cannot change the AddressFamily of prefix list "
                    + existing.getPrefixListId() + " from " + existing.getAddressFamily() + " to " + family, 400);
        }
        if (entries != null) {
            entries.forEach(entry -> ec2Service.validatePrefixListEntry(entry, family));
        }
        List<PrefixListEntry> desired = entries != null ? entries
                : ctx.previouslyDeclared("Entries") ? List.of() : existing.currentEntries();
        EntryChanges changes = EntryChanges.between(existing.currentEntries(), desired);
        boolean resized = !Objects.equals(maxEntries, existing.getMaxEntries());
        if (!changes.isEmpty() && resized) {
            throw new AwsException("ValidationError", PREFIX_LIST + " cannot change MaxEntries (from "
                    + existing.getMaxEntries() + " to " + maxEntries + ") and Entries ("
                    + (changes.add().size() + changes.readd().size()) + " to add, "
                    + (changes.remove().size() + changes.redescribe().size()) + " to remove) in the same update", 400);
        }
        String prefixListId = existing.getPrefixListId();
        if (!changes.isEmpty() || resized || !name.equals(existing.getPrefixListName())) {
            ObjectNode prior = inPlacePrior(r, prefixListId, ctx);
            ObjectNode before = prior.putObject("prefixList");
            before.put("name", existing.getPrefixListName());
            before.put("maxEntries", existing.getMaxEntries());
            ArrayNode saved = before.putArray("entries");
            for (PrefixListEntry entry : existing.currentEntries()) {
                ObjectNode row = saved.addObject().put("cidr", entry.getCidr());
                if (entry.getDescription() != null) {
                    row.put("description", entry.getDescription());
                }
            }
            writeInPlacePrior(r, prior);
        }
        JsonNode desiredTags = !props.has("Tags") && ctx.previouslyDeclared("Tags") ? untagged(props) : props;
        CfnRollback.guarded(r, ctx, false, () -> { }, () -> undoInPlace(r), () -> {
            applyTags(r, true, prefixListId, existing.getTags(), desiredTags, ctx);
            modifyPrefixList(ctx.region(), existing, name, maxEntries, changes);
        });
    }

    /** {@code props} with an empty Tags list, which removes the tags a previous template declared. */
    private static JsonNode untagged(JsonNode props) {
        ObjectNode copy = props.deepCopy();
        copy.putArray("Tags");
        return copy;
    }

    /**
     * Gives a prefix list {@code name} and {@code maxEntries} and applies {@code changes}, or does
     * nothing when none of them changes it. EC2 refuses a request that removes and adds the same CIDR,
     * which is how an entry's description changes, so such an entry is added back by a second call.
     * The first call carries everything EC2 could refuse, and the capacity the second needs is checked
     * before the first, so a change EC2 refuses leaves the list as it was; only another change to the
     * list landing between the two calls can still fail the second. Each call that changes entries
     * makes a version.
     */
    private ManagedPrefixList modifyPrefixList(String region, ManagedPrefixList list, String name, int maxEntries,
                                               EntryChanges changes) {
        boolean resized = !Objects.equals(maxEntries, list.getMaxEntries());
        if (changes.isEmpty() && !resized && name.equals(list.getPrefixListName())) {
            return list;
        }
        if (!changes.readd().isEmpty()) {
            int capacity = resized ? maxEntries : list.getMaxEntries();
            int after = list.currentEntries().size() - changes.remove().size() - changes.redescribe().size()
                    + changes.add().size() + changes.readd().size();
            if (after > capacity) {
                throw new AwsException("InvalidParameterValue",
                        "The number of entries exceeds the maximum of " + capacity + ".", 400);
            }
        }
        List<String> removals = new ArrayList<>(changes.remove());
        removals.addAll(changes.redescribe());
        ManagedPrefixList current = ec2Service.modifyManagedPrefixList(region, list.getPrefixListId(),
                list.getVersion(), name, resized ? maxEntries : null, changes.add(), removals);
        if (!changes.readd().isEmpty()) {
            current = ec2Service.modifyManagedPrefixList(region, current.getPrefixListId(), current.getVersion(),
                    null, null, changes.readd(), List.of());
        }
        return current;
    }

    /**
     * The template's Entries, with what {@code AWS::NoValue} stands for left out, or null when it does
     * not declare them. A CIDR is passed on as written, as Floci's CreateManagedPrefixList takes it.
     */
    private static List<PrefixListEntry> prefixListEntries(JsonNode props, ProvisionContext ctx) {
        JsonNode rows = props.has("Entries") ? ctx.engine().resolveNodeOmittingNoValue(props.get("Entries")) : null;
        if (rows == null || rows.isNull() || rows.isMissingNode()) {
            return null;
        }
        if (!rows.isArray()) {
            throw new AwsException("ValidationError", PREFIX_LIST + " Entries must be a list", 400);
        }
        List<PrefixListEntry> entries = new ArrayList<>();
        for (JsonNode row : rows) {
            String cidr = row.path("Cidr").asText(null);
            require(PREFIX_LIST, "Entries.Cidr", cidr);
            entries.add(new PrefixListEntry(cidr, row.path("Description").asText(null)));
        }
        return entries;
    }

    /**
     * What takes a prefix list from its current entries to the desired ones: the entries to add and the
     * CIDRs to remove, with an entry whose block is both removed and added back (a changed description)
     * kept apart as a CIDR to {@code redescribe} and an entry to {@code readd}. Validated entries match
     * when their CIDRs identify the same block and their descriptions are the same.
     */
    private record EntryChanges(List<PrefixListEntry> add, List<String> remove, List<String> redescribe,
                                List<PrefixListEntry> readd) {

        static EntryChanges between(List<PrefixListEntry> current, List<PrefixListEntry> desired) {
            Set<List<String>> held = current.stream().map(EntryChanges::key).collect(Collectors.toSet());
            Set<List<String>> wanted = desired.stream().map(EntryChanges::key).collect(Collectors.toSet());
            List<PrefixListEntry> leaving = current.stream().filter(entry -> !wanted.contains(key(entry))).toList();
            List<PrefixListEntry> arriving = desired.stream().filter(entry -> !held.contains(key(entry))).toList();
            Set<String> leavingBlocks = leaving.stream().map(entry -> block(entry.getCidr())).collect(Collectors.toSet());
            Set<String> arrivingBlocks = arriving.stream().map(entry -> block(entry.getCidr())).collect(Collectors.toSet());
            List<PrefixListEntry> add = new ArrayList<>();
            List<PrefixListEntry> readd = new ArrayList<>();
            for (PrefixListEntry entry : arriving) {
                if (leavingBlocks.contains(block(entry.getCidr()))) {
                    readd.add(entry);
                } else {
                    add.add(entry);
                }
            }
            List<String> remove = new ArrayList<>();
            List<String> redescribe = new ArrayList<>();
            for (PrefixListEntry entry : leaving) {
                if (arrivingBlocks.contains(block(entry.getCidr()))) {
                    redescribe.add(entry.getCidr());
                } else {
                    remove.add(entry.getCidr());
                }
            }
            return new EntryChanges(add, remove, redescribe, readd);
        }

        boolean isEmpty() {
            return add.isEmpty() && remove.isEmpty() && redescribe.isEmpty() && readd.isEmpty();
        }

        /** An entry's block and description; a list, which unlike {@code List.of} holds a null description. */
        private static List<String> key(PrefixListEntry entry) {
            return Arrays.asList(block(entry.getCidr()), entry.getDescription());
        }

        private static String block(String cidr) {
            return CidrCanonicalizer.canonicalize(cidr).orElse(cidr);
        }
    }
}
