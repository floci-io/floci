package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Provisions {@code AWS::Route53::HostedZone} and {@code AWS::Route53::RecordSet}.
 *
 * <p>A record set is written into its hosted zone through {@code ChangeResourceRecordSets}: the
 * declared record is applied as an UPSERT so create and update both converge, {@code Ref} is the
 * record name, and stack delete removes the record with a matching DELETE change. The schema-required
 * {@code Type} and {@code Name} are validated, and the zone is taken from {@code HostedZoneId} (or
 * resolved from {@code HostedZoneName}), since a record cannot be written without one. No
 * {@code Fn::GetAtt} attribute is published: the registry lists {@code Id} as read-only, but the
 * type has no registry handlers and the resource specification gives it no attributes, so
 * {@code Ref} is its only reference.
 */
@ApplicationScoped
public class Route53CfnProvisioner implements CfnResourceProvisioner {
    static final String HOSTED_ZONE = "AWS::Route53::HostedZone";
    static final String RECORD_SET = "AWS::Route53::RecordSet";

    // Create-time values a record's delete needs to build a matching DELETE change; kept off the
    // published attributes by the __Floci prefix, as the other provisioners do.
    private static final String RECORD_ZONE_ATTR = "__FlociRoute53RecordZoneId";
    private static final String RECORD_TYPE_ATTR = "__FlociRoute53RecordType";
    private static final String RECORD_SET_ID_ATTR = "__FlociRoute53RecordSetIdentifier";

    private static final Logger LOG = Logger.getLogger(Route53CfnProvisioner.class);

    private final Route53Service route53Service;

    @Inject
    public Route53CfnProvisioner(Route53Service route53Service) {
        this.route53Service = route53Service;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(HOSTED_ZONE, RECORD_SET);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        switch (resource.getResourceType()) {
            case "AWS::Route53::HostedZone" -> provisionHostedZone(resource, props, ctx);
            case "AWS::Route53::RecordSet" -> provisionRecordSet(resource, props, ctx);
            default -> throw new IllegalStateException(
                    "Route53CfnProvisioner received an unsupported type: " + resource.getResourceType());
        }
    }

    private void provisionRecordSet(StackResource resource, JsonNode props, ProvisionContext ctx) {
        Map<String, String> priorAttributes = new HashMap<>(resource.getAttributes());
        String name = ctx.resolveOptional(props, "Name");
        String type = ctx.resolveOptional(props, "Type");
        // Type and Name are the schema's required properties; reject rather than write a nameless
        // or typeless record the zone would refuse.
        if (name == null || name.isBlank()) {
            throw new AwsException("ValidationError", "AWS::Route53::RecordSet requires Name.", 400);
        }
        if (type == null || type.isBlank()) {
            throw new AwsException("ValidationError", "AWS::Route53::RecordSet requires Type.", 400);
        }
        String zoneId = resolveZoneId(props, ctx);

        ResourceRecordSet rrs = new ResourceRecordSet();
        rrs.setName(name);
        rrs.setType(type);
        String ttl = ctx.resolveOptional(props, "TTL");
        if (ttl != null && !ttl.isBlank()) {
            // TTL is a string in the schema, so a non-numeric value is valid template JSON; reject it
            // as a ValidationError like Name and Type rather than letting a raw NumberFormatException
            // escape.
            try {
                rrs.setTtl(Long.parseLong(ttl.trim()));
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError",
                        "AWS::Route53::RecordSet TTL must be an integer: " + ttl, 400);
            }
        }
        String setIdentifier = ctx.resolveOptional(props, "SetIdentifier");
        rrs.setSetIdentifier(setIdentifier);
        rrs.setRecords(parseResourceRecords(props, ctx));
        rrs.setAliasTarget(parseAliasTarget(props, ctx));

        // UPSERT: provision serves create and update, and re-applying the same record must not fail
        // with "already exists" the way a CREATE would on the second UpdateStack.
        Map<String, Object> change = new HashMap<>();
        change.put("action", "UPSERT");
        change.put("rrs", rrs);
        route53Service.changeResourceRecordSets(zoneId, List.of(change),
                "CloudFormation " + ctx.stackName() + "/" + resource.getLogicalId());

        // The record's identity is Name + Type + SetIdentifier within a zone. When an update changes
        // any of them, the UPSERT writes a new record and the prior one is orphaned, so remove it,
        // otherwise it lingers in the zone and later blocks the zone's own delete.
        removeSupersededRecord(ctx, priorAttributes, zoneId, name, type, setIdentifier);

        resource.setPhysicalId(name);
        resource.getAttributes().put(RECORD_ZONE_ATTR, zoneId);
        resource.getAttributes().put(RECORD_TYPE_ATTR, type);
        if (setIdentifier != null && !setIdentifier.isBlank()) {
            resource.getAttributes().put(RECORD_SET_ID_ATTR, setIdentifier);
        } else {
            resource.getAttributes().remove(RECORD_SET_ID_ATTR);
        }
    }

    /**
     * Removes the record a prior provision wrote when this update changed the record's identity
     * (zone, Name, Type or SetIdentifier). Reads the prior identity from the attributes the last
     * provision recorded plus the prior physical id (the prior Name). A no-op on create, and when
     * the identity is unchanged the UPSERT already replaced the record in place.
     */
    private void removeSupersededRecord(ProvisionContext ctx, Map<String, String> priorAttributes,
                                        String zoneId, String name, String type, String setIdentifier) {
        if (!ctx.isUpdate()) {
            return;
        }
        String priorZoneId = priorAttributes.get(RECORD_ZONE_ATTR);
        String priorName = ctx.priorPhysicalId();
        String priorType = priorAttributes.get(RECORD_TYPE_ATTR);
        String priorSetId = priorAttributes.get(RECORD_SET_ID_ATTR);
        if (priorZoneId == null || priorZoneId.isBlank() || priorName == null || priorName.isBlank()
                || priorType == null || priorType.isBlank()) {
            return;
        }
        boolean sameIdentity = priorZoneId.equals(zoneId) && priorName.equals(name)
                && priorType.equals(type) && Objects.equals(priorSetId, setIdentifier);
        if (sameIdentity) {
            return;
        }
        removeRecord(priorZoneId, priorName, priorType, priorSetId, "CloudFormation supersede");
    }

    /**
     * The hosted zone the record belongs to: {@code HostedZoneId} when given (a Ref resolves to the
     * real zone id), otherwise the zone matching {@code HostedZoneName}. One of the two is required,
     * since a record cannot be written without a zone.
     */
    private String resolveZoneId(JsonNode props, ProvisionContext ctx) {
        String zoneId = ctx.resolveOptional(props, "HostedZoneId");
        if (zoneId != null && !zoneId.isBlank()) {
            return zoneId;
        }
        String zoneName = ctx.resolveOptional(props, "HostedZoneName");
        if (zoneName != null && !zoneName.isBlank()) {
            return zoneIdByName(zoneName);
        }
        throw new AwsException("ValidationError",
                "AWS::Route53::RecordSet requires HostedZoneId or HostedZoneName.", 400);
    }

    /**
     * The id of the single hosted zone whose name equals {@code zoneName}. {@code listHostedZonesByName}
     * is a starts-at paginator, so it must be filtered to an exact match rather than trusting the first
     * result, or a record lands in whatever zone happens to sort at or after the name. AWS rejects the
     * stack both when no zone matches and when more than one carries the name, so only a single match
     * resolves.
     */
    private String zoneIdByName(String zoneName) {
        String normalized = zoneName.endsWith(".") ? zoneName : zoneName + ".";
        List<HostedZone> matches = route53Service.listHostedZonesByName(zoneName, 0).stream()
                .filter(zone -> normalized.equals(zone.getName()))
                .toList();
        if (matches.size() == 1) {
            return matches.get(0).getId();
        }
        throw new AwsException("InvalidChangeBatch", matches.isEmpty()
                ? "AWS::Route53::RecordSet references HostedZoneName " + zoneName
                        + " but no such hosted zone exists."
                : "AWS::Route53::RecordSet HostedZoneName " + zoneName
                        + " matches more than one hosted zone; use HostedZoneId.", 400);
    }

    private List<ResourceRecord> parseResourceRecords(JsonNode props, ProvisionContext ctx) {
        List<ResourceRecord> records = new ArrayList<>();
        for (String value : ctx.resolveStringList(props, "ResourceRecords")) {
            if (value != null && !value.isBlank()) {
                records.add(new ResourceRecord(value));
            }
        }
        return records;
    }

    private AliasTarget parseAliasTarget(JsonNode props, ProvisionContext ctx) {
        JsonNode node = ctx.engine().resolveNode(props != null ? props.get("AliasTarget") : null);
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        AliasTarget target = new AliasTarget();
        target.setDnsName(node.path("DNSName").asText(null));
        target.setHostedZoneId(node.path("HostedZoneId").asText(null));
        target.setEvaluateTargetHealth(node.path("EvaluateTargetHealth").asBoolean(false));
        return target;
    }

    private void provisionHostedZone(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String name = ctx.resolveOptional(props, "Name");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("AWS::Route53::HostedZone requires Name");
        }

        JsonNode resolved = ctx.engine().resolveNode(props);
        String comment = resolved.path("HostedZoneConfig").path("Comment").asText(null);
        List<VpcAssociation> vpcs = parseVpcs(resolved.path("VPCs"));
        String callerReference = ctx.stackName() + "/" + resource.getLogicalId();
        boolean isTrackedZoneMissing = resource.getPhysicalId() != null
                && !zoneExists(resource.getPhysicalId());
        if (isTrackedZoneMissing) {
            // The tracked physical ID may be a leftover from the retired monolith's HostedZone
            // stub, which minted a random Z-id with zero Route53Service backing (see commit
            // 76228741d). There is no real zone behind it to migrate, so recreate rather than
            // fail the update and roll back the stack.
            resource.setPhysicalId(null);
        }

        String id;
        if (resource.getPhysicalId() == null) {
            // CreateHostedZone only accepts a single VPC (a private zone becomes
            // resolvable from that VPC immediately); any further VPCs in the CFN
            // template's list are wired in afterward via AssociateVPCWithHostedZone,
            // matching how a real CloudFormation update converges an existing zone.
            VpcAssociation firstVpc = vpcs.isEmpty() ? null : vpcs.get(0);
            Route53Service.CreateZoneResult created = route53Service.createHostedZone(
                    name, callerReference, comment, firstVpc);
            id = created.zone().getId();
            // Record the physical ID before any follow-up call that can fail: once the
            // zone exists, the stack engine must be able to track and clean it up even
            // if a later VPC association or tag write throws. Marking it rollback-owned
            // too means a CREATE_FAILED status from that later failure still gets cleaned
            // up during stack-create rollback, which keys off that attribute rather than
            // physicalId alone (see CfnRollback.ROLLBACK_OWNED_ATTR).
            resource.setPhysicalId(id);
            resource.getAttributes().put(CfnRollback.ROLLBACK_OWNED_ATTR, "true");
            try {
                for (int i = 1; i < vpcs.size(); i++) {
                    route53Service.associateVpcWithHostedZone(id, vpcs.get(i), comment);
                }
            } catch (RuntimeException e) {
                // An update that recreates a missing legacy zone (isTrackedZoneMissing above)
                // fails through CloudFormationService's generic update-rollback path, which
                // restores the stack's previous StackResource wholesale and never learns this
                // zone's ID - orphaning it, so the next retry reuses the same caller reference
                // and fails with HostedZoneAlreadyExists. Since we own this zone (we just
                // created it), clean it up ourselves rather than depend on that path.
                // Once cleanup succeeds, physical state matches what it was before this update
                // started (tracked zone missing), so tell the generic rollback walker this
                // resource is restored - otherwise it falls through to "rollback is not
                // implemented" and strands the stack in UPDATE_ROLLBACK_FAILED for a resource
                // there is nothing left to reconcile.
                route53Service.deleteHostedZone(id);
                resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
                throw e;
            }
        } else {
            id = resource.getPhysicalId();
            for (VpcAssociation vpc : vpcs) {
                route53Service.associateVpcWithHostedZone(id, vpc, comment);
            }
        }

        resource.setPhysicalId(id);
        resource.getAttributes().put("Id", id);
        resource.getAttributes().put("NameServers", String.join(",", route53Service.getNameServers()));

        List<Map<String, String>> tags = parseTags(resolved.path("HostedZoneTags"));
        if (!tags.isEmpty()) {
            try {
                route53Service.changeTagsForResource("hostedzone", id, tags, List.of());
            } catch (RuntimeException e) {
                if ("true".equals(resource.getAttributes().get(CfnRollback.ROLLBACK_OWNED_ATTR))) {
                    route53Service.deleteHostedZone(id);
                    resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
                }
                throw e;
            }
        }
        resource.getAttributes().remove(CfnRollback.ROLLBACK_OWNED_ATTR);
    }

    private boolean zoneExists(String id) {
        try {
            route53Service.getHostedZone(id);
            return true;
        } catch (AwsException e) {
            if (!"NoSuchHostedZone".equals(e.getErrorCode())) {
                throw e;
            }
            return false;
        }
    }

    /**
     * A record set's delete needs the zone id and type recorded at create time to build a matching
     * DELETE change; the physical id alone only carries the name. The zone-backed types fall through
     * to the id-only overload.
     */
    @Override
    public void delete(StackResource resource, String region) {
        if (RECORD_SET.equals(resource.getResourceType())) {
            deleteRecordSet(resource);
        } else {
            delete(resource.getResourceType(), resource.getPhysicalId(), region);
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case "AWS::Route53::HostedZone" -> route53Service.deleteHostedZone(physicalId);
            // The record's zone and type live on the StackResource, not the physical id, so the
            // id-only path has nothing to match a DELETE change against; delete(resource, region)
            // is the real removal.
            case "AWS::Route53::RecordSet" -> LOG.warnv(
                    "No delete from the physical id alone for resource type {0}: {1} is removed via "
                            + "delete(resource, region).", resourceType, physicalId);
            default -> throw new IllegalStateException(
                    "Route53CfnProvisioner received an unsupported type: " + resourceType);
        }
    }

    private void deleteRecordSet(StackResource resource) {
        Map<String, String> attributes = resource.getAttributes() != null
                ? resource.getAttributes() : Map.of();
        String zoneId = attributes.get(RECORD_ZONE_ATTR);
        String type = attributes.get(RECORD_TYPE_ATTR);
        String name = resource.getPhysicalId();
        if (zoneId == null || zoneId.isBlank() || type == null || type.isBlank()
                || name == null || name.isBlank()) {
            // A record from before this write path stored no zone, so there is nothing to remove.
            LOG.warnv("No stored zone for Route53 record {0}; nothing to delete.", resource.getLogicalId());
            return;
        }
        String setIdentifier = attributes.get(RECORD_SET_ID_ATTR);
        // Removing a record the zone no longer has is a no-op, and a zone already gone tolerated; any
        // other failure must reach the stack as DELETE_FAILED.
        removeRecord(zoneId, name, type, setIdentifier, "CloudFormation delete " + resource.getLogicalId());
    }

    /**
     * Removes a record by its identity, tolerating a zone or record already gone. A zone still
     * holding the record blocks its own delete, so a superseded or deleted record must actually go.
     */
    private void removeRecord(String zoneId, String name, String type, String setIdentifier, String comment) {
        CfnDeletes.safeDelete("Route53 record set", name + " " + type, () -> {
            ResourceRecordSet existing = findRecord(zoneId, name, type, setIdentifier);
            if (existing == null) {
                return;
            }
            Map<String, Object> change = new HashMap<>();
            change.put("action", "DELETE");
            change.put("rrs", existing);
            route53Service.changeResourceRecordSets(zoneId, List.of(change), comment);
        }, "NoSuchHostedZone", "InvalidChangeBatch");
    }

    private ResourceRecordSet findRecord(String zoneId, String name, String type, String setIdentifier) {
        for (ResourceRecordSet rrs : route53Service.listResourceRecordSets(zoneId, null, null, 0)) {
            if (name.equals(rrs.getName()) && type.equals(rrs.getType())
                    && Objects.equals(setIdentifier, rrs.getSetIdentifier())) {
                return rrs;
            }
        }
        return null;
    }

    private List<VpcAssociation> parseVpcs(JsonNode node) {
        List<VpcAssociation> vpcs = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String id = item.path("VPCId").asText(null);
                String region = item.path("VPCRegion").asText(null);
                if (id == null || id.isBlank() || region == null || region.isBlank()) {
                    // AWS requires both fields on every VPCs entry; silently dropping an
                    // incomplete one would create an unassociated public zone while the
                    // stack still reports CREATE_COMPLETE.
                    throw new IllegalArgumentException(
                            "AWS::Route53::HostedZone VPCs entries require both VPCId and VPCRegion");
                }
                vpcs.add(new VpcAssociation(id, region));
            }
        }
        return vpcs;
    }

    private List<Map<String, String>> parseTags(JsonNode node) {
        List<Map<String, String>> tags = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                String key = item.path("Key").asText(null);
                // Matches ProvisionContext.resolveTags: a blank key is skipped rather than
                // persisted, since Route53Service.changeTagsForResource would otherwise write
                // a tag AWS itself would reject for having an empty key.
                if (key != null && !key.isBlank()) {
                    tags.add(Map.of("Key", key, "Value", item.path("Value").asText("")));
                }
            }
        }
        return tags;
    }
}
