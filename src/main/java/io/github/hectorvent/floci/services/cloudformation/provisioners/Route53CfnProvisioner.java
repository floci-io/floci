package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.services.route53.model.AliasTarget;
import io.github.hectorvent.floci.services.route53.model.HealthCheck;
import io.github.hectorvent.floci.services.route53.model.HealthCheckConfig;
import io.github.hectorvent.floci.services.route53.model.HostedZone;
import io.github.hectorvent.floci.services.route53.model.ResourceRecord;
import io.github.hectorvent.floci.services.route53.model.ResourceRecordSet;
import io.github.hectorvent.floci.services.route53.model.VpcAssociation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Provisions {@code AWS::Route53::HostedZone}, {@code AWS::Route53::RecordSet} and
 * {@code AWS::Route53::HealthCheck}.
 *
 * <p>A record set is written into its hosted zone through {@code ChangeResourceRecordSets}. On stack
 * create, and on an update that changes the record's identity (zone, Name, Type or SetIdentifier), the
 * record is sent as a CREATE, so a record another owner already holds at that identity fails the
 * stack. An update that keeps the identity is sent as an UPSERT, which replaces whatever record holds
 * that identity. {@code Ref} is the record name, and stack delete removes the record with a matching
 * DELETE change. The schema-required {@code Type} and {@code Name} are validated, and the zone is
 * taken from {@code HostedZoneId} (or resolved from {@code HostedZoneName}), since a record cannot be
 * written without one. No {@code Fn::GetAtt} attribute is published: the registry lists {@code Id} as
 * read-only, but the type has no registry handlers and the resource specification gives it no
 * attributes, so {@code Ref} is its only reference.
 */
@ApplicationScoped
public class Route53CfnProvisioner implements CfnResourceProvisioner {
    static final String HOSTED_ZONE = "AWS::Route53::HostedZone";
    static final String RECORD_SET = "AWS::Route53::RecordSet";
    static final String HEALTH_CHECK = "AWS::Route53::HealthCheck";

    /** The registry schema's create-only HealthCheckConfig members. */
    private static final List<String> HEALTH_CHECK_CREATE_ONLY = List.of("Type", "RequestInterval", "MeasureLatency");
    /** The registry schema's HealthCheckConfig.Type enum. */
    private static final List<String> HEALTH_CHECK_TYPES = List.of("CALCULATED", "CLOUDWATCH_METRIC", "HTTP",
            "HTTP_STR_MATCH", "HTTPS", "HTTPS_STR_MATCH", "TCP", "RECOVERY_CONTROL");
    // Describes the check as its public attributes do, so a replacement rollback puts it back.
    private static final String HEALTH_CHECK_CREATE_ONLY_ATTR = CfnRollback.USES_ATTR_PREFIX + "Route53HealthCheckCreateOnly";
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        return Set.of(HOSTED_ZONE, RECORD_SET, HEALTH_CHECK);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        switch (resource.getResourceType()) {
            case "AWS::Route53::HostedZone" -> provisionHostedZone(resource, props, ctx);
            case "AWS::Route53::RecordSet" -> provisionRecordSet(resource, props, ctx);
            case "AWS::Route53::HealthCheck" -> provisionHealthCheck(resource, props, ctx);
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
        rrs.setName(Route53Service.normalizeName(name));
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
        // Route 53 requires a set identifier of at least one character, so a blank one means none.
        String setIdentifier = ctx.resolveOrDefault(props, "SetIdentifier", null);
        rrs.setSetIdentifier(setIdentifier);
        rrs.setRecords(parseResourceRecords(props, ctx));
        rrs.setAliasTarget(parseAliasTarget(props, ctx));
        rrs.setRegion(ctx.resolveOrDefault(props, "Region", null));
        rrs.setFailover(ctx.resolveOrDefault(props, "Failover", null));
        rrs.setHealthCheckId(ctx.resolveOrDefault(props, "HealthCheckId", null));
        String weight = ctx.resolveOrDefault(props, "Weight", null);
        if (weight != null) {
            try {
                rrs.setWeight(Long.parseLong(weight.trim()));
            } catch (NumberFormatException e) {
                throw new AwsException("ValidationError",
                        "AWS::Route53::RecordSet Weight must be an integer: " + weight, 400);
            }
        }

        // The record's identity is Name + Type + SetIdentifier within a zone. Re-applying the identity
        // this resource wrote last time is an UPSERT, which replaces whatever record holds that
        // identity. Any other identity, on stack create or on an update that changes it, is a record
        // the stack does not own yet, so it is a CREATE: a record another owner already holds under
        // that identity fails the stack instead of being taken over.
        boolean sameIdentity = isPriorIdentity(ctx, priorAttributes, zoneId, name, type, setIdentifier);
        Map<String, Object> change = new HashMap<>();
        change.put("action", sameIdentity ? "UPSERT" : "CREATE");
        change.put("rrs", rrs);
        route53Service.changeResourceRecordSets(zoneId, List.of(change),
                "CloudFormation " + ctx.stackName() + "/" + resource.getLogicalId());

        // An update that changes the identity leaves the prior record orphaned, so remove it,
        // otherwise it lingers in the zone and later blocks the zone's own delete.
        if (!sameIdentity) {
            removeSupersededRecord(ctx, priorAttributes);
        }

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
     * Whether the last provision of this resource wrote the same record identity: zone, Name, Type
     * and SetIdentifier. Names are compared fully qualified, so a change that only adds or drops the
     * trailing dot is the same record. Reads the prior identity from the attributes the last
     * provision recorded plus the prior physical id (the prior Name). False on create.
     */
    private static boolean isPriorIdentity(ProvisionContext ctx, Map<String, String> priorAttributes,
                                           String zoneId, String name, String type, String setIdentifier) {
        return ctx.isUpdate()
                && zoneId.equals(priorAttributes.get(RECORD_ZONE_ATTR))
                && Route53Service.normalizeRecordName(name)
                        .equals(Route53Service.normalizeRecordName(ctx.priorPhysicalId()))
                && type.equals(priorAttributes.get(RECORD_TYPE_ATTR))
                && Objects.equals(priorAttributes.get(RECORD_SET_ID_ATTR), setIdentifier);
    }

    /**
     * Removes the record a prior provision wrote, once this update has changed the record's
     * identity. Reads the prior identity from the attributes the last provision recorded plus the
     * prior physical id (the prior Name). A no-op on create, and when no prior zone or type was
     * recorded.
     */
    private void removeSupersededRecord(ProvisionContext ctx, Map<String, String> priorAttributes) {
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

    /**
     * Creates the health check, or updates it in place on a stack update. {@code Type},
     * {@code RequestInterval} and {@code MeasureLatency} are create-only, so an update that changes
     * one creates a new check; the displaced check is deleted once the update commits, or kept
     * under {@code UpdateReplacePolicy: Retain}, through {@link ReplacementCleanup}. An in-place
     * update first snapshots the check's configuration and tags, so a failed stack update can put
     * them back. {@code Ref} and {@code Fn::GetAtt HealthCheckId} are the health check id.
     */
    private void provisionHealthCheck(StackResource resource, JsonNode props, ProvisionContext ctx) {
        // A snapshot describes the update in flight; one an earlier update left behind is stale.
        resource.getAttributes().remove(CfnRollback.HEALTH_CHECK_UPDATE_SNAPSHOT_ATTR);
        if (ctx.isUpdate()) {
            ReplacementCleanup.endRollbackRetry(resource);
        }
        Map<String, String> attributesBefore = Map.copyOf(resource.getAttributes());

        JsonNode resolved = ctx.engine().resolveNode(props);
        JsonNode configNode = resolved != null ? resolved.path("HealthCheckConfig") : null;
        if (configNode == null || !configNode.isObject()) {
            throw new AwsException("ValidationError",
                    "AWS::Route53::HealthCheck requires HealthCheckConfig.", 400);
        }
        HealthCheckConfig config = parseHealthCheckConfig(configNode);
        String createOnly = createOnlyValues(configNode);
        Map<String, String> tags = new LinkedHashMap<>();
        parseTags(resolved.path("HealthCheckTags")).forEach(tag -> tags.put(tag.get("Key"), tag.get("Value")));

        String priorId = ctx.isUpdate() ? ctx.priorPhysicalId() : null;
        HealthCheck prior = priorId != null ? findHealthCheck(priorId) : null;
        String id;
        if (prior != null && createOnly.equals(priorCreateOnlyValues(resource, prior))) {
            id = priorId;
            snapshotBeforeUpdate(resource, prior);
            try {
                route53Service.updateHealthCheck(id, config);
                reconcileHealthCheckTags(id, tags);
            } catch (RuntimeException failure) {
                unwindHealthCheckUpdate(resource, id, failure);
                throw failure;
            }
        } else {
            String callerReference = ctx.stackName() + "/" + resource.getLogicalId() + "/" + UUID.randomUUID();
            id = route53Service.createHealthCheck(callerReference, config, configNode.toString()).getId();
            try {
                reconcileHealthCheckTags(id, tags);
            } catch (RuntimeException failure) {
                // The check this provision created is not yet the resource's, so nothing else removes it.
                try {
                    route53Service.deleteHealthCheck(id);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        resource.setPhysicalId(id);
        resource.getAttributes().put("HealthCheckId", id);
        resource.getAttributes().put(HEALTH_CHECK_CREATE_ONLY_ATTR, createOnly);
        ReplacementCleanup.record(resource, ctx, attributesBefore);
    }

    private HealthCheck findHealthCheck(String id) {
        try {
            return route53Service.getHealthCheck(id);
        } catch (AwsException e) {
            if (!"NoSuchHealthCheck".equals(e.getErrorCode())) {
                throw e;
            }
            return null;
        }
    }

    private void reconcileHealthCheckTags(String id, Map<String, String> desired) {
        List<String> stale = ProvisionContext.staleTagKeys(
                route53Service.listTagsForResource("healthcheck", id), desired);
        List<Map<String, String>> add = new ArrayList<>();
        desired.forEach((key, value) -> add.add(Map.of("Key", key, "Value", value)));
        if (!add.isEmpty() || !stale.isEmpty()) {
            route53Service.changeTagsForResource("healthcheck", id, add, stale);
        }
    }

    /** The template's create-only members, as text, so a change to any of them is a replacement. */
    private static String createOnlyValues(JsonNode configNode) {
        ObjectNode values = MAPPER.createObjectNode();
        for (String member : HEALTH_CHECK_CREATE_ONLY) {
            JsonNode value = configNode.get(member);
            if (value == null || value.isNull() || value.isMissingNode()) {
                values.putNull(member);
            } else {
                values.put(member, value.asText());
            }
        }
        return values.toString();
    }

    /**
     * The create-only members the check was provisioned with. A check provisioned before they were
     * recorded falls back to what the check itself holds, which has no {@code MeasureLatency}.
     */
    private static String priorCreateOnlyValues(StackResource resource, HealthCheck prior) {
        String recorded = resource.getAttributes().get(HEALTH_CHECK_CREATE_ONLY_ATTR);
        if (recorded != null) {
            return recorded;
        }
        ObjectNode values = MAPPER.createObjectNode();
        values.put("Type", prior.getConfig().getType());
        Integer interval = prior.getConfig().getRequestInterval();
        if (interval == null) {
            values.putNull("RequestInterval");
        } else {
            values.put("RequestInterval", String.valueOf(interval));
        }
        values.putNull("MeasureLatency");
        return values.toString();
    }

    /** Keeps the check's configuration and tags before an in-place update changes them. */
    private void snapshotBeforeUpdate(StackResource resource, HealthCheck prior) {
        HealthCheckConfig config = prior.getConfig();
        ObjectNode snapshot = MAPPER.createObjectNode();
        snapshot.put("id", prior.getId());
        ObjectNode saved = snapshot.putObject("config");
        saved.put("Type", config.getType());
        saved.put("IPAddress", config.getIpAddress());
        saved.put("Port", config.getPort());
        saved.put("ResourcePath", config.getResourcePath());
        saved.put("FullyQualifiedDomainName", config.getFullyQualifiedDomainName());
        saved.put("RequestInterval", config.getRequestInterval());
        saved.put("FailureThreshold", config.getFailureThreshold());
        ObjectNode savedTags = snapshot.putObject("tags");
        route53Service.listTagsForResource("healthcheck", prior.getId()).forEach(savedTags::put);
        resource.getAttributes().put(CfnRollback.HEALTH_CHECK_UPDATE_SNAPSHOT_ATTR, snapshot.toString());
    }

    /**
     * An in-place update that changed the check and then failed undoes itself, as
     * {@code CloudWatchDashboardCfnProvisioner} does: the engine puts back the resource the stack
     * held before the attempt, which never carried this snapshot, so the rollback walker would
     * otherwise skip the restore.
     */
    private void unwindHealthCheckUpdate(StackResource resource, String id, RuntimeException failure) {
        try {
            rollbackUpdate(resource);
            resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_RESTORED_ATTR, "true");
        } catch (RuntimeException unwindFailure) {
            resource.getAttributes().put(CfnRollback.UPDATE_ROLLBACK_FAILURE_ATTR,
                    "Could not roll back the update of health check " + id + ": " + unwindFailure.getMessage());
            if (unwindFailure != failure) {
                failure.addSuppressed(unwindFailure);
            }
        }
    }

    private static HealthCheckConfig parseHealthCheckConfig(JsonNode node) {
        String type = node.path("Type").asText(null);
        if (type == null || type.isBlank()) {
            throw new AwsException("ValidationError",
                    "AWS::Route53::HealthCheck HealthCheckConfig requires Type.", 400);
        }
        if (!HEALTH_CHECK_TYPES.contains(type)) {
            throw new AwsException("ValidationError",
                    "AWS::Route53::HealthCheck HealthCheckConfig Type must be one of "
                            + String.join(", ", HEALTH_CHECK_TYPES) + ": " + type, 400);
        }
        HealthCheckConfig config = new HealthCheckConfig();
        config.setType(type);
        config.setIpAddress(node.path("IPAddress").asText(null));
        config.setPort(integerMember(node, "Port"));
        config.setResourcePath(node.path("ResourcePath").asText(null));
        config.setFullyQualifiedDomainName(node.path("FullyQualifiedDomainName").asText(null));
        config.setRequestInterval(integerMember(node, "RequestInterval"));
        config.setFailureThreshold(integerMember(node, "FailureThreshold"));
        return config;
    }

    private static HealthCheckConfig configFromSnapshot(JsonNode node) {
        HealthCheckConfig config = new HealthCheckConfig();
        config.setType(node.path("Type").asText(null));
        config.setIpAddress(node.path("IPAddress").asText(null));
        config.setPort(node.hasNonNull("Port") ? node.get("Port").asInt() : null);
        config.setResourcePath(node.path("ResourcePath").asText(null));
        config.setFullyQualifiedDomainName(node.path("FullyQualifiedDomainName").asText(null));
        config.setRequestInterval(node.hasNonNull("RequestInterval") ? node.get("RequestInterval").asInt() : null);
        config.setFailureThreshold(node.hasNonNull("FailureThreshold") ? node.get("FailureThreshold").asInt() : null);
        return config;
    }

    private static Integer integerMember(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return Integer.parseInt(value.asText().trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    "AWS::Route53::HealthCheck " + name + " must be an integer: " + value.asText(), 400);
        }
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

    @Override
    public boolean hasReplacementUpdate(StackResource resource) {
        return HEALTH_CHECK.equals(resource.getResourceType()) && ReplacementCleanup.hasReplacement(resource);
    }

    @Override
    public String updateCleanupPhysicalId(StackResource resource) {
        return HEALTH_CHECK.equals(resource.getResourceType())
                ? ReplacementCleanup.cleanupPhysicalId(resource) : null;
    }

    @Override
    public UpdateCleanupResult completeUpdate(StackResource resource) {
        return HEALTH_CHECK.equals(resource.getResourceType())
                ? ReplacementCleanup.complete(resource, this::delete) : UpdateCleanupResult.notApplicable();
    }

    @Override
    public void clearUpdate(StackResource resource) {
        if (HEALTH_CHECK.equals(resource.getResourceType())) {
            resource.getAttributes().remove(CfnRollback.HEALTH_CHECK_UPDATE_SNAPSHOT_ATTR);
            ReplacementCleanup.clear(resource);
        }
    }

    /**
     * A health check replacement is undone through the cleanup record, and an in-place update from
     * the snapshot taken before it. With neither on the resource the provision failed before it
     * changed anything. Hosted zones and record sets keep the engine's default.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        if (!HEALTH_CHECK.equals(resource.getResourceType())) {
            return false;
        }
        if (ReplacementCleanup.rollback(resource, this::delete)) {
            return true;
        }
        // Spent only once the restore succeeded, so a restore that throws can be retried.
        String raw = resource.getAttributes().get(CfnRollback.HEALTH_CHECK_UPDATE_SNAPSHOT_ATTR);
        if (raw == null) {
            return true;
        }
        JsonNode snapshot;
        try {
            snapshot = MAPPER.readTree(raw);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read the health check update snapshot for "
                    + resource.getLogicalId(), e);
        }
        String id = snapshot.path("id").asText();
        route53Service.updateHealthCheck(id, configFromSnapshot(snapshot.path("config")));
        Map<String, String> tags = new LinkedHashMap<>();
        snapshot.path("tags").fields().forEachRemaining(tag -> tags.put(tag.getKey(), tag.getValue().asText()));
        reconcileHealthCheckTags(id, tags);
        resource.getAttributes().remove(CfnRollback.HEALTH_CHECK_UPDATE_SNAPSHOT_ATTR);
        return true;
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
            case "AWS::Route53::HealthCheck" -> CfnDeletes.safeDelete("Route53 health check", physicalId,
                    () -> route53Service.deleteHealthCheck(physicalId), "NoSuchHealthCheck");
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

    /**
     * The physical id keeps the name as the template wrote it, so the lookup matches by Route 53's
     * record identity, which ignores the trailing dot. An earlier version stored that name as written
     * and could leave another owner's copy in the other form beside it, so prefer the exact name.
     */
    private ResourceRecordSet findRecord(String zoneId, String name, String type, String setIdentifier) {
        ResourceRecordSet wanted = new ResourceRecordSet();
        wanted.setName(name);
        wanted.setType(type);
        wanted.setSetIdentifier(setIdentifier);
        List<ResourceRecordSet> matches = route53Service.listResourceRecordSets(zoneId, null, null, 0).stream()
                .filter(rrs -> Route53Service.sameRecord(rrs, wanted))
                .toList();
        return matches.stream()
                .filter(rrs -> rrs.getName().equals(name))
                .findFirst()
                .orElse(matches.isEmpty() ? null : matches.get(0));
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
