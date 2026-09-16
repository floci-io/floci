package io.github.hectorvent.floci.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.CloudFormationTemplateEngine;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.rds.RdsService;
import io.github.hectorvent.floci.services.rds.model.DbCluster;
import io.github.hectorvent.floci.services.rds.model.DbClusterParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbInstance;
import io.github.hectorvent.floci.services.rds.model.DbParameterGroup;
import io.github.hectorvent.floci.services.rds.model.DbProxyAuth;
import io.github.hectorvent.floci.services.rds.model.DbSubnetGroup;
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

/**
 * CloudFormation provisioning for RDS: the two parameter-group types, {@code DBSubnetGroup},
 * {@code DBInstance}, {@code DBCluster}, {@code DBProxy} and {@code DBProxyTargetGroup}.
 * Extracted from {@code CloudFormationResourceProvisioner}.
 *
 * <p>Every type here deletes by physical id alone, so the id-only
 * {@link #delete(String, String, String)} serves all seven and none of them appears in the
 * engine's {@code DELETE_NEEDS_STACK_RESOURCE} set. {@code DBCluster} is the one type that
 * replaces through {@link ReplacementCleanup}; see {@code provisionDbCluster}.
 *
 * <p>Unlike most extractions this one does not take {@code RdsService} out of the monolith.
 * {@code AWS::SecretsManager::SecretTargetAttachment} is still served there and reads an
 * instance's or cluster's endpoint to build its connection detail, so the monolith keeps its own
 * reference to the service.
 */
@ApplicationScoped
public class RdsCfnProvisioner implements CfnResourceProvisioner {

    private static final Logger LOG = Logger.getLogger(RdsCfnProvisioner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The createOnlyProperties of {@code AWS::RDS::DBCluster} that nothing on the cluster on file
     * records. {@code DBClusterIdentifier} is the physical id, {@code EngineMode},
     * {@code StorageEncrypted} and {@code DatabaseName} are fields of the cluster, but Floci does not
     * emulate a KMS key, public access, a subnet group placement chosen by the template or a
     * restore source on a cluster, so a change to one of these can only be seen against what the
     * template said last time. The values the last successful provision resolved are kept on the
     * resource under {@link #DB_CLUSTER_CREATE_ONLY_ATTR}, which is how CloudFormation decides too:
     * on the template's change, not on the live entity.
     */
    static final List<String> DB_CLUSTER_RECORDED_CREATE_ONLY = List.of(
            "ClusterScalabilityType", "DBSubnetGroupName", "DBSystemId", "KmsKeyId",
            "PubliclyAccessible", "RestoreToTime", "RestoreType", "SnapshotIdentifier",
            "SourceDBClusterIdentifier", "SourceDbClusterResourceId", "SourceRegion",
            "UseLatestRestorableTime");
    /**
     * The booleans among {@link #DB_CLUSTER_RECORDED_CREATE_ONLY}, recorded as {@code true}/
     * {@code false} however the template spelled them. {@code UseLatestRestorableTime} defaults to
     * false, so an absent one is recorded as false. {@code PubliclyAccessible} has no fixed default
     * (AWS documents true without a subnet group or with the default one, false with a custom one),
     * so an absent one is recorded as absent and a template that later spells out either value is a
     * change: the cluster may have been created public.
     */
    private static final Set<String> DB_CLUSTER_BOOLEAN_CREATE_ONLY =
            Set.of("PubliclyAccessible", "UseLatestRestorableTime");
    private static final Set<String> DB_CLUSTER_DEFAULT_FALSE_CREATE_ONLY = Set.of("UseLatestRestorableTime");
    /**
     * The recorded createOnly values of a DB cluster resource, a JSON object keyed by property.
     * Internal, like the other {@code __Floci} attributes. A resource provisioned before these were
     * recorded has none, and its first update records them without replacing anything.
     */
    static final String DB_CLUSTER_CREATE_ONLY_ATTR = "__FlociDbClusterCreateOnly";
    /**
     * The record an update overwrote, kept until the update commits so a rollback puts it back
     * beside the prior cluster; an empty value means there was no record before.
     */
    static final String DB_CLUSTER_CREATE_ONLY_PRIOR_ATTR = "__FlociDbClusterCreateOnlyPrior";

    private final RdsService rdsService;
    private final CfnDynamicReferences dynamicReferences;

    @Inject
    public RdsCfnProvisioner(RdsService rdsService, CfnDynamicReferences dynamicReferences) {
        this.rdsService = rdsService;
        this.dynamicReferences = dynamicReferences;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(
                "AWS::RDS::DBSubnetGroup",
                "AWS::RDS::DBParameterGroup",
                "AWS::RDS::DBClusterParameterGroup",
                "AWS::RDS::DBInstance",
                "AWS::RDS::DBCluster",
                "AWS::RDS::DBProxy",
                "AWS::RDS::DBProxyTargetGroup");
    }

    @Override
    public void provision(StackResource r, JsonNode props, ProvisionContext ctx) {
        CloudFormationTemplateEngine engine = ctx.engine();
        String region = ctx.region();
        switch (r.getResourceType()) {
            case "AWS::RDS::DBSubnetGroup" -> provisionDbSubnetGroup(r, props, engine, ctx, region);
            case "AWS::RDS::DBParameterGroup" -> provisionDbParameterGroup(r, props, engine, ctx, region);
            case "AWS::RDS::DBClusterParameterGroup" ->
                    provisionDbClusterParameterGroup(r, props, engine, ctx, region);
            case "AWS::RDS::DBInstance" -> provisionDbInstance(r, props, engine, ctx, region);
            case "AWS::RDS::DBCluster" -> provisionDbCluster(r, props, engine, ctx, region);
            case "AWS::RDS::DBProxy" -> provisionDbProxy(r, props, engine, region);
            case "AWS::RDS::DBProxyTargetGroup" -> provisionDbProxyTargetGroup(r, props, engine, region);
            default -> throw new IllegalStateException(
                    "RdsCfnProvisioner received an unsupported type: " + r.getResourceType());
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        switch (resourceType) {
            case "AWS::RDS::DBInstance" -> rdsService.deleteDbInstance(physicalId, region);
            case "AWS::RDS::DBCluster" -> CfnDeletes.safeDelete("DB cluster", physicalId,
                    () -> rdsService.deleteDbCluster(physicalId, region), "DBClusterNotFoundFault");
            case "AWS::RDS::DBProxy" -> deleteDbProxySafe(physicalId, region);
            case "AWS::RDS::DBProxyTargetGroup" -> clearDbProxyTargetGroupSafe(physicalId, region);
            case "AWS::RDS::DBSubnetGroup" -> rdsService.deleteDbSubnetGroup(physicalId, region);
            case "AWS::RDS::DBParameterGroup" -> rdsService.deleteDbParameterGroup(physicalId, region);
            case "AWS::RDS::DBClusterParameterGroup" ->
                    rdsService.deleteDbClusterParameterGroup(physicalId, region);
            default -> throw new IllegalStateException(
                    "RdsCfnProvisioner received an unsupported type: " + resourceType);
        }
    }

    // ── replacement lifecycle: only AWS::RDS::DBCluster records one, so the other types answer
    // "no cleanup owed" through ReplacementCleanup's own empty-record handling ──

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
        resource.getAttributes().remove(DB_CLUSTER_CREATE_ONLY_PRIOR_ATTR);
        return ReplacementCleanup.complete(resource, this::delete);
    }

    @Override
    public void clearUpdate(StackResource resource) {
        resource.getAttributes().remove(DB_CLUSTER_CREATE_ONLY_PRIOR_ATTR);
        ReplacementCleanup.clear(resource);
    }

    /**
     * A replacement is undone through the cleanup record: the resource names the displaced cluster
     * again and the replacement is deleted. The createOnly record follows the cluster back, since
     * {@link ReplacementCleanup} leaves {@code __Floci} attributes alone. Without a record the
     * update was in place, and putting that back needs a snapshot this provisioner does not keep,
     * so the engine keeps reporting it as not rolled back, as before.
     */
    @Override
    public boolean rollbackUpdate(StackResource resource) {
        boolean replaced = ReplacementCleanup.rollback(resource, this::delete);
        String priorRecord = resource.getAttributes().remove(DB_CLUSTER_CREATE_ONLY_PRIOR_ATTR);
        if (priorRecord != null) {
            if (priorRecord.isEmpty()) {
                resource.getAttributes().remove(DB_CLUSTER_CREATE_ONLY_ATTR);
            } else {
                resource.getAttributes().put(DB_CLUSTER_CREATE_ONLY_ATTR, priorRecord);
            }
        }
        return replaced;
    }

    private void provisionDbSubnetGroup(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                        ProvisionContext ctx, String region) {
        String explicitName = resolveOptional(props, "DBSubnetGroupName", engine);
        String priorPhysicalId = r.getPhysicalId();
        String name;
        if (explicitName != null && !explicitName.isBlank()) {
            name = explicitName;
        } else if (priorPhysicalId != null) {
            // No explicit name: keep the name RDS already has on file instead of generating a fresh
            // one on every update, which would otherwise orphan the previously provisioned group.
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }
        String description = firstNonBlank(resolveOptional(props, "DBSubnetGroupDescription", engine),
                "Managed by CloudFormation");
        // SubnetIds may be a literal array, or a list-valued intrinsic such as CDK's
        // Fn::Split over a cross-stack Fn::ImportValue when the source VPC exports its
        // subnet ids as one comma-joined value (issue #2937).
        List<String> subnetIds = props != null && props.has("SubnetIds")
                ? engine.resolveStringList(props.get("SubnetIds"))
                : new ArrayList<>();

        // On UpdateStack, provision() is re-invoked for every resource regardless of whether its
        // properties actually changed, so a same-named group already on file must be reconciled in
        // place rather than re-created (createDbSubnetGroup throws DBSubnetGroupAlreadyExists).
        DbSubnetGroup existing = sameNameExistingResource(priorPhysicalId, name, n -> rdsService.getDbSubnetGroup(n, region));
        DbSubnetGroup group;
        if (existing != null) {
            group = rdsService.modifyDbSubnetGroup(name, subnetIds, region);
        } else {
            group = rdsService.createDbSubnetGroup(name, description, subnetIds, region);
            deleteRenamedResource(priorPhysicalId, name, id -> rdsService.deleteDbSubnetGroup(id), "DB subnet group");
        }
        r.setPhysicalId(group.getDbSubnetGroupName());
        r.getAttributes().put("DBSubnetGroupName", group.getDbSubnetGroupName());
    }

    private void provisionDbParameterGroup(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                           ProvisionContext ctx, String region) {
        String explicitName = resolveOptional(props, "DBParameterGroupName", engine);
        String priorPhysicalId = r.getPhysicalId();
        String name;
        if (explicitName != null && !explicitName.isBlank()) {
            name = explicitName;
        } else if (priorPhysicalId != null) {
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }
        String family = resolveOptional(props, "Family", engine);
        String description = firstNonBlank(resolveOptional(props, "Description", engine),
                "Managed by CloudFormation");

        // DBParameterGroupName, Family and Description are all immutable on real AWS (any change
        // replaces the resource), so a same-named group already on file is a no-op, not a re-create.
        DbParameterGroup existing = sameNameExistingResource(priorPhysicalId, name,
                n -> rdsService.getDbParameterGroup(n, region));
        DbParameterGroup group;
        if (existing != null) {
            group = existing;
        } else {
            group = rdsService.createDbParameterGroup(name, family, description, region);
            deleteRenamedResource(priorPhysicalId, name, id -> rdsService.deleteDbParameterGroup(id, region),
                    "DB parameter group");
        }
        r.setPhysicalId(group.getDbParameterGroupName());
        r.getAttributes().put("DBParameterGroupName", group.getDbParameterGroupName());
    }

    private void provisionDbClusterParameterGroup(StackResource r, JsonNode props,
                                                  CloudFormationTemplateEngine engine,
                                                  ProvisionContext ctx, String region) {
        String explicitName = resolveOptional(props, "DBClusterParameterGroupName", engine);
        String priorPhysicalId = r.getPhysicalId();
        String name;
        if (explicitName != null && !explicitName.isBlank()) {
            name = explicitName;
        } else if (priorPhysicalId != null) {
            name = priorPhysicalId;
        } else {
            name = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }
        String family = resolveOptional(props, "Family", engine);
        String description = firstNonBlank(resolveOptional(props, "Description", engine),
                "Managed by CloudFormation");

        // Same immutability rationale as provisionDbParameterGroup above.
        DbClusterParameterGroup existing = sameNameExistingResource(priorPhysicalId, name,
                n -> rdsService.getDbClusterParameterGroup(n, region));
        DbClusterParameterGroup group;
        if (existing != null) {
            group = existing;
        } else {
            group = rdsService.createDbClusterParameterGroup(name, family, description, region);
            deleteRenamedResource(priorPhysicalId, name, id -> rdsService.deleteDbClusterParameterGroup(id, region),
                    "DB cluster parameter group");
        }
        r.setPhysicalId(group.getDbClusterParameterGroupName());
        r.getAttributes().put("DBClusterParameterGroupName", group.getDbClusterParameterGroupName());
    }

    private void provisionDbInstance(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                     ProvisionContext ctx, String region) {
        String explicitId = resolveOptional(props, "DBInstanceIdentifier", engine);
        String priorPhysicalId = r.getPhysicalId();
        String id;
        if (explicitId != null && !explicitId.isBlank()) {
            id = explicitId;
        } else if (priorPhysicalId != null) {
            id = priorPhysicalId;
        } else {
            id = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }

        // provision() is re-invoked on every UpdateStack for every resource, so a same-id instance
        // already on file must be reconciled rather than re-created (createDbInstance throws
        // DBInstanceAlreadyExists). Only the properties RdsService.modifyDbInstance actually supports
        // (password, IAM auth, subnet group) are reconciled here; other property changes (engine,
        // instance class, allocated storage, ...) are a pre-existing gap in that method, not addressed
        // by this fix.
        DbInstance instance = sameNameExistingResource(priorPhysicalId, id, rdsService::getDbInstance);
        if (instance != null) {
            instance = rdsService.modifyDbInstance(
                    id,
                    resolveDynamicReferences(resolveOptionalWithoutDynamicReferences(props, "MasterUserPassword", engine), region, true),
                    parseBoolProp(props, "EnableIAMDatabaseAuthentication", engine),
                    resolveOptional(props, "DBSubnetGroupName", engine));
        } else {
            instance = rdsService.createDbInstance(
                    id,
                    resolveOptional(props, "Engine", engine),
                    resolveOptional(props, "EngineVersion", engine),
                    resolveDynamicReferences(resolveOptionalWithoutDynamicReferences(props, "MasterUsername", engine), region, false),
                    resolveDynamicReferences(resolveOptionalWithoutDynamicReferences(props, "MasterUserPassword", engine), region, true),
                    resolveOptional(props, "DBName", engine),
                    firstNonBlank(resolveOptional(props, "DBInstanceClass", engine), "db.t3.micro"),
                    parseIntProp(props, "AllocatedStorage", engine, 20),
                    parseBoolProp(props, "EnableIAMDatabaseAuthentication", engine),
                    resolveOptional(props, "DBParameterGroupName", engine),
                    resolveOptional(props, "DBSubnetGroupName", engine),
                    resolveOptional(props, "DBClusterIdentifier", engine),
                    null, false, false, null, Map.of(), region);
            deleteRenamedResource(priorPhysicalId, id, rdsService::deleteDbInstance, "DB instance");
        }
        r.setPhysicalId(instance.getDbInstanceIdentifier());
        r.getAttributes().put("DBInstanceIdentifier", instance.getDbInstanceIdentifier());
        if (instance.getEndpoint() != null) {
            r.getAttributes().put("Endpoint.Address", instance.getEndpoint().address());
            r.getAttributes().put("Endpoint.Port", String.valueOf(instance.getEndpoint().port()));
        }
        if (instance.getDbInstanceArn() != null) {
            r.getAttributes().put("DBInstanceArn", instance.getDbInstanceArn());
        }
        if (instance.getDbiResourceId() != null) {
            r.getAttributes().put("DbiResourceId", instance.getDbiResourceId());
        }
    }

    /**
     * A DB cluster follows CloudFormation's replacement lifecycle. A change to any of the
     * createOnlyProperties in the {@code AWS::RDS::DBCluster} schema ({@code DBClusterIdentifier},
     * {@code EngineMode}, {@code StorageEncrypted} and {@code DatabaseName} against the cluster on
     * file, the rest against what the template said last time, see
     * {@link #DB_CLUSTER_RECORDED_CREATE_ONLY}) creates the replacement under a distinct physical
     * id first and leaves the displaced cluster standing: it is deleted once the stack update
     * commits ({@link #completeUpdate}), or the resource is pointed back at it and the replacement
     * is removed when a later resource fails the update ({@link #rollbackUpdate}). A cluster the
     * template names explicitly has no distinct id to move to, which is the update CloudFormation
     * refuses for a custom-named resource, so it is refused here the same way.
     */
    private void provisionDbCluster(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                    ProvisionContext ctx, String region) {
        Map<String, String> attributesBefore = Map.copyOf(r.getAttributes());
        String explicitId = resolveOptional(props, "DBClusterIdentifier", engine);
        String priorPhysicalId = r.getPhysicalId();
        boolean customNamed = explicitId != null && !explicitId.isBlank();
        String id;
        if (customNamed) {
            id = explicitId;
        } else if (priorPhysicalId != null) {
            id = priorPhysicalId;
        } else {
            id = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
        }

        // Same re-invocation rationale as provisionDbInstance above; modifyDbCluster only reconciles
        // password and IAM auth, mirroring that method's existing scope.
        DbCluster cluster = sameNameExistingResource(priorPhysicalId, id, rdsService::getDbCluster);
        String engineMode = resolveOptional(props, "EngineMode", engine);
        boolean storageEncrypted = parseBoolProp(props, "StorageEncrypted", engine);
        String databaseName = resolveOptional(props, "DatabaseName", engine);
        Map<String, String> createOnly = dbClusterRecordedCreateOnly(props, engine);
        String replacementReason = cluster == null ? null : dbClusterReplacementReason(cluster,
                engineMode, storageEncrypted, databaseName,
                readDbClusterCreateOnly(attributesBefore.get(DB_CLUSTER_CREATE_ONLY_ATTR)), createOnly);
        if (replacementReason != null) {
            if (customNamed) {
                throw new AwsException("ValidationError",
                        "CloudFormation cannot update a stack when a custom-named resource requires "
                                + "replacing. Rename " + id + " and update the stack again.", 400);
            }
            id = ctx.generatePhysicalName(r.getLogicalId(), 60, true);
            LOG.infov("Replacing DB cluster {0} with {1}: {2}", priorPhysicalId, id, replacementReason);
            cluster = null;
        }
        if (cluster != null) {
            cluster = rdsService.modifyDbCluster(
                    id,
                    resolveDynamicReferences(resolveOptionalWithoutDynamicReferences(props, "MasterUserPassword", engine), region, true),
                    parseBoolProp(props, "EnableIAMDatabaseAuthentication", engine),
                    parseServerlessV2Capacity(props, "MinCapacity", engine),
                    parseServerlessV2Capacity(props, "MaxCapacity", engine),
                    parseServerlessV2SecondsUntilAutoPause(props, engine), region);
        } else {
            Double serverlessV2MinCapacity = parseServerlessV2Capacity(props, "MinCapacity", engine);
            Double serverlessV2MaxCapacity = parseServerlessV2Capacity(props, "MaxCapacity", engine);
            Integer serverlessV2SecondsUntilAutoPause =
                    parseServerlessV2SecondsUntilAutoPause(props, engine);
            String engineName = resolveOptional(props, "Engine", engine);
            String engineVersion = resolveOptional(props, "EngineVersion", engine);
            String masterUsername = resolveDynamicReferences(
                    resolveOptionalWithoutDynamicReferences(props, "MasterUsername", engine), region, false);
            String masterPassword = resolveDynamicReferences(
                    resolveOptionalWithoutDynamicReferences(props, "MasterUserPassword", engine), region, true);
            boolean iamEnabled = parseBoolProp(props, "EnableIAMDatabaseAuthentication", engine);
            String parameterGroup = resolveOptional(props, "DBClusterParameterGroupName", engine);
            cluster = rdsService.createDbCluster(id, engineName, engineVersion, masterUsername,
                    masterPassword, databaseName, iamEnabled, parameterGroup, null, null, false, region,
                    serverlessV2MinCapacity, serverlessV2MaxCapacity, serverlessV2SecondsUntilAutoPause,
                    false, null, engineMode, storageEncrypted);
        }
        r.setPhysicalId(cluster.getDbClusterIdentifier());
        r.getAttributes().put("DBClusterIdentifier", cluster.getDbClusterIdentifier());
        if (cluster.getEndpoint() != null) {
            r.getAttributes().put("Endpoint.Address", cluster.getEndpoint().address());
            r.getAttributes().put("Endpoint.Port", String.valueOf(cluster.getEndpoint().port()));
        }
        if (cluster.getReaderEndpoint() != null) {
            r.getAttributes().put("ReadEndpoint.Address", cluster.getReaderEndpoint().address());
        }
        if (cluster.getDbClusterArn() != null) {
            r.getAttributes().put("DBClusterArn", cluster.getDbClusterArn());
        }
        if (cluster.getDbClusterResourceId() != null) {
            r.getAttributes().put("DBClusterResourceId", cluster.getDbClusterResourceId());
        }
        recordDbClusterCreateOnly(r, ctx, attributesBefore, createOnly);
        // A provision that left the resource with a new physical id replaced the cluster: the
        // displaced one is deleted once the update commits, or restored if the update rolls back.
        ReplacementCleanup.record(r, ctx, attributesBefore);
    }

    /**
     * Why the template's createOnly properties call for a replacement of the cluster on file, or
     * null when they describe it. {@code EngineMode}, {@code StorageEncrypted} and
     * {@code DatabaseName} are read from the cluster itself: a template without {@code EngineMode}
     * means the RDS default {@code provisioned}, and a cluster persisted before the mode was tracked
     * reports no mode at all, which is the same default. The rest are read from the record the last
     * provision left ({@link #DB_CLUSTER_RECORDED_CREATE_ONLY}); a resource without one predates
     * the record, and nothing on file can say what it was created with, so it is kept.
     */
    private static String dbClusterReplacementReason(DbCluster existing, String engineMode,
                                                     boolean storageEncrypted, String databaseName,
                                                     Map<String, String> recorded,
                                                     Map<String, String> desired) {
        List<String> changed = new ArrayList<>();
        String desiredMode = firstNonBlank(engineMode, "provisioned");
        String currentMode = firstNonBlank(existing.getEngineMode(), "provisioned");
        if (!desiredMode.equalsIgnoreCase(currentMode)) {
            changed.add("EngineMode " + currentMode + " -> " + desiredMode);
        }
        if (existing.isStorageEncrypted() != storageEncrypted) {
            changed.add("StorageEncrypted " + existing.isStorageEncrypted() + " -> " + storageEncrypted);
        }
        String currentDatabase = firstNonBlank(existing.getDatabaseName(), null);
        String desiredDatabase = firstNonBlank(databaseName, null);
        if (!Objects.equals(currentDatabase, desiredDatabase)) {
            changed.add("DatabaseName " + currentDatabase + " -> " + desiredDatabase);
        }
        if (recorded != null) {
            for (String property : DB_CLUSTER_RECORDED_CREATE_ONLY) {
                if (!Objects.equals(recorded.get(property), desired.get(property))) {
                    changed.add(property + " " + recorded.get(property) + " -> " + desired.get(property));
                }
            }
        }
        return changed.isEmpty() ? null : String.join(", ", changed);
    }

    /**
     * The template's values for {@link #DB_CLUSTER_RECORDED_CREATE_ONLY}: a value only when set,
     * booleans normalised to {@code true}/{@code false}, and the ones with a fixed default of false
     * recorded as false when absent (see {@link #DB_CLUSTER_BOOLEAN_CREATE_ONLY}).
     */
    private Map<String, String> dbClusterRecordedCreateOnly(JsonNode props,
                                                            CloudFormationTemplateEngine engine) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String property : DB_CLUSTER_RECORDED_CREATE_ONLY) {
            String value = resolveOptional(props, property, engine);
            boolean set = value != null && !value.isBlank();
            if (DB_CLUSTER_BOOLEAN_CREATE_ONLY.contains(property)) {
                if (set) {
                    values.put(property, String.valueOf(Boolean.parseBoolean(value.trim())));
                } else if (DB_CLUSTER_DEFAULT_FALSE_CREATE_ONLY.contains(property)) {
                    values.put(property, "false");
                }
            } else if (set) {
                values.put(property, value);
            }
        }
        return values;
    }

    /**
     * Writes the createOnly record the next update compares against. On an update that changed it
     * (a replacement, or the first record of a resource that predates it) the previous record is
     * kept beside it until the update commits, so {@link #rollbackUpdate} can put it back.
     */
    private static void recordDbClusterCreateOnly(StackResource r, ProvisionContext ctx,
                                                  Map<String, String> attributesBefore,
                                                  Map<String, String> createOnly) {
        String priorRecord = attributesBefore.get(DB_CLUSTER_CREATE_ONLY_ATTR);
        if (ctx.isUpdate() && !createOnly.equals(readDbClusterCreateOnly(priorRecord))) {
            r.getAttributes().put(DB_CLUSTER_CREATE_ONLY_PRIOR_ATTR, priorRecord == null ? "" : priorRecord);
        }
        ObjectNode record = MAPPER.createObjectNode();
        createOnly.forEach(record::put);
        r.getAttributes().put(DB_CLUSTER_CREATE_ONLY_ATTR, record.toString());
    }

    /** The record as a map, or null when the resource carries none or it cannot be read. */
    private static Map<String, String> readDbClusterCreateOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(raw);
            if (!node.isObject()) {
                return null;
            }
            Map<String, String> values = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> values.put(e.getKey(), e.getValue().asText()));
            return values;
        } catch (Exception e) {
            LOG.warnv("Ignoring unreadable DB cluster createOnly record: {0}", raw);
            return null;
        }
    }

    private Double parseServerlessV2Capacity(JsonNode props, String field,
                                             CloudFormationTemplateEngine engine) {
        JsonNode config = props.get("ServerlessV2ScalingConfiguration");
        if (config == null || config.isNull()) {
            return null;
        }
        String resolved = resolveOptional(config, field, engine);
        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(resolved.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    "ServerlessV2ScalingConfiguration " + field + " must be a number.", 400);
        }
    }

    private Integer parseServerlessV2SecondsUntilAutoPause(
            JsonNode props, CloudFormationTemplateEngine engine) {
        JsonNode config = props.get("ServerlessV2ScalingConfiguration");
        if (config == null || config.isNull()) {
            return null;
        }
        String resolved = resolveOptional(config, "SecondsUntilAutoPause", engine);
        if (resolved == null || resolved.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(resolved.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("ValidationError",
                    "ServerlessV2ScalingConfiguration SecondsUntilAutoPause must be an integer.", 400);
        }
    }

    private void provisionDbProxy(StackResource r, JsonNode props, CloudFormationTemplateEngine engine,
                                  String region) {
        String name = resolveOptional(props, "DBProxyName", engine);
        String engineFamily = resolveOptional(props, "EngineFamily", engine);
        String defaultAuthScheme = resolveOptional(props, "DefaultAuthScheme", engine);
        if (defaultAuthScheme == null) {
            defaultAuthScheme = "NONE";
        } else if (defaultAuthScheme.isBlank()) {
            throw new AwsException("InvalidParameterValue",
                    "DefaultAuthScheme must be NONE or IAM_AUTH.", 400);
        }
        String endpointNetworkType = resolveOptional(props, "EndpointNetworkType", engine);
        String targetConnectionNetworkType = resolveOptional(
                props, "TargetConnectionNetworkType", engine);
        validateDbProxyNetworkType(endpointNetworkType,
                "EndpointNetworkType", true, "IPV4, IPV6, or DUAL");
        validateDbProxyNetworkType(targetConnectionNetworkType,
                "TargetConnectionNetworkType", false, "IPV4 or IPV6");
        boolean requireTls = parseBoolProp(props, "RequireTLS", engine);
        boolean debugLogging = parseBoolProp(props, "DebugLogging", engine);
        Integer configuredIdleClientTimeout = parseOptionalIntProp(props, "IdleClientTimeout", engine);
        int idleClientTimeout = configuredIdleClientTimeout != null ? configuredIdleClientTimeout : 1800;
        String roleArn = resolveOptional(props, "RoleArn", engine);
        List<String> subnetIds = resolveStringList(props, "VpcSubnetIds", engine);
        if (subnetIds.stream().distinct().count() < 2) {
            throw new AwsException("InvalidParameterValue",
                    "AWS::RDS::DBProxy VpcSubnetIds must contain at least two distinct subnet IDs.", 400);
        }
        List<String> sgIds = resolveStringList(props, "VpcSecurityGroupIds", engine);
        List<DbProxyAuth> auth = parseProxyAuth(props, engine);
        boolean iamAuth = "IAM_AUTH".equalsIgnoreCase(defaultAuthScheme)
                || auth.stream().anyMatch(a ->
                "REQUIRED".equalsIgnoreCase(a.getIamAuth())
                        || "ENABLED".equalsIgnoreCase(a.getIamAuth()));
        Map<String, String> tags = parseCfnTags(props != null ? props.get("Tags") : null, engine);
        var proxy = r.getPhysicalId() == null
                ? rdsService.createDbProxy(name, engineFamily, requireTls, iamAuth,
                defaultAuthScheme, roleArn, subnetIds, sgIds, auth, idleClientTimeout,
                debugLogging, tags, region, endpointNetworkType, targetConnectionNetworkType)
                : updateDbProxy(r, name, engineFamily, defaultAuthScheme, requireTls,
                idleClientTimeout, debugLogging, roleArn, subnetIds, sgIds, auth, tags, region,
                endpointNetworkType, targetConnectionNetworkType);
        r.setPhysicalId(proxy.getDbProxyName());              // Ref -> DBProxyName
        r.getAttributes().put("Endpoint", proxy.getEndpoint());   // GetAtt "Endpoint" (bare host)
        r.getAttributes().put("DBProxyArn", proxy.getDbProxyArn());
        if (proxy.getVpcId() != null) {
            r.getAttributes().put("VpcId", proxy.getVpcId());
        }
    }

    private io.github.hectorvent.floci.services.rds.model.DbProxy updateDbProxy(
            StackResource resource, String name, String engineFamily, String defaultAuthScheme,
            boolean requireTls, int idleClientTimeout, boolean debugLogging, String roleArn,
            List<String> subnetIds, List<String> securityGroupIds, List<DbProxyAuth> auth,
            Map<String, String> tags, String region, String endpointNetworkType,
            String targetConnectionNetworkType) {
        var existing = rdsService.getDbProxy(resource.getPhysicalId(), region);
        // ModifyDBProxy has no EndpointNetworkType/TargetConnectionNetworkType parameters; AWS
        // documents both as requiring CloudFormation replacement, same as DBProxyName, EngineFamily,
        // and VpcSubnetIds.
        String effectiveEndpointNetworkType = endpointNetworkType != null && !endpointNetworkType.isBlank()
                ? endpointNetworkType.toUpperCase() : "IPV4";
        String effectiveTargetConnectionNetworkType =
                targetConnectionNetworkType != null && !targetConnectionNetworkType.isBlank()
                ? targetConnectionNetworkType.toUpperCase() : "IPV4";
        String existingEndpointNetworkType = existing.getEndpointNetworkType() != null
                ? existing.getEndpointNetworkType() : "IPV4";
        String existingTargetConnectionNetworkType = existing.getTargetConnectionNetworkType() != null
                ? existing.getTargetConnectionNetworkType() : "IPV4";
        if (!Objects.equals(existing.getDbProxyName(), name)
                || engineFamily == null
                || !existing.getEngineFamily().equalsIgnoreCase(engineFamily)
                || !Set.copyOf(existing.getVpcSubnetIds()).equals(Set.copyOf(subnetIds))
                || !existingEndpointNetworkType.equalsIgnoreCase(effectiveEndpointNetworkType)
                || !existingTargetConnectionNetworkType.equalsIgnoreCase(effectiveTargetConnectionNetworkType)) {
            throw new AwsException("UnsupportedOperation",
                    "Changing DBProxyName, EngineFamily, VpcSubnetIds, EndpointNetworkType, or "
                            + "TargetConnectionNetworkType requires CloudFormation replacement, "
                            + "which is not yet supported by Floci.", 400);
        }
        return rdsService.modifyDbProxy(existing.getDbProxyName(), defaultAuthScheme, auth,
                requireTls, idleClientTimeout, debugLogging, roleArn,
                securityGroupIds, tags, region);
    }

    private void provisionDbProxyTargetGroup(StackResource r, JsonNode props,
                                             CloudFormationTemplateEngine engine, String region) {
        String dbProxyName = resolveOptional(props, "DBProxyName", engine);
        String targetGroupName = resolveOptional(props, "TargetGroupName", engine);
        if (!"default".equals(targetGroupName)) {
            throw new AwsException("InvalidParameterValue",
                    "AWS::RDS::DBProxyTargetGroup TargetGroupName must be default.", 400);
        }
        List<String> clusterIds = resolveStringList(props, "DBClusterIdentifiers", engine);
        List<String> instanceIds = resolveStringList(props, "DBInstanceIdentifiers", engine);
        Integer maxConn = null;
        Integer maxIdle = null;
        Integer connectionBorrowTimeout = null;
        String initQuery = null;
        List<String> sessionPinningFilters = List.of();
        if (props != null && props.has("ConnectionPoolConfigurationInfo")) {
            JsonNode cpc = props.get("ConnectionPoolConfigurationInfo");
            maxConn = parseOptionalIntProp(cpc, "MaxConnectionsPercent", engine);
            maxIdle = parseOptionalIntProp(cpc, "MaxIdleConnectionsPercent", engine);
            connectionBorrowTimeout = parseOptionalIntProp(cpc, "ConnectionBorrowTimeout", engine);
            initQuery = resolveOptional(cpc, "InitQuery", engine);
            sessionPinningFilters = resolveStringList(cpc, "SessionPinningFilters", engine);
        }
        if (maxIdle != null && maxConn == null) {
            throw new AwsException("InvalidParameterValue",
                    "MaxConnectionsPercent is required when MaxIdleConnectionsPercent is specified.",
                    400);
        }
        if (r.getPhysicalId() != null) {
            var existing = rdsService.getDbProxyTargetGroupByArn(r.getPhysicalId(), region);
            if (!Objects.equals(existing.getDbProxyName(), dbProxyName)
                    || !Objects.equals(existing.getTargetGroupName(), targetGroupName)) {
                throw new AwsException("UnsupportedOperation",
                        "Changing DBProxyName or TargetGroupName requires CloudFormation replacement.",
                        400);
            }
        }
        var proxy = rdsService.getDbProxy(dbProxyName, region);
        int effectiveMaxConnections = maxConn != null ? maxConn
                : ("SQLSERVER".equals(proxy.getEngineFamily()) ? 10 : 100);
        int effectiveMaxIdle = maxIdle != null ? maxIdle : effectiveMaxConnections / 2;
        int effectiveBorrowTimeout = connectionBorrowTimeout != null ? connectionBorrowTimeout : 120;
        var tg = rdsService.reconcileDbProxyTargetGroup(
                dbProxyName, targetGroupName, clusterIds, instanceIds,
                effectiveMaxConnections, effectiveMaxIdle, effectiveBorrowTimeout,
                initQuery, sessionPinningFilters, region);
        r.setPhysicalId(tg.getTargetGroupArn());              // Ref -> TargetGroupArn
        r.getAttributes().put("TargetGroupArn", tg.getTargetGroupArn());
        r.getAttributes().put("DBProxyName", tg.getDbProxyName());
    }

    private List<DbProxyAuth> parseProxyAuth(JsonNode props, CloudFormationTemplateEngine engine) {
        List<DbProxyAuth> auth = new ArrayList<>();
        if (props != null && props.has("Auth") && props.get("Auth").isArray()) {
            for (JsonNode a : props.get("Auth")) {
                DbProxyAuth entry = new DbProxyAuth();
                entry.setAuthScheme(resolveOptional(a, "AuthScheme", engine));
                entry.setSecretArn(resolveOptional(a, "SecretArn", engine));
                entry.setIamAuth(resolveOptional(a, "IAMAuth", engine));
                entry.setClientPasswordAuthType(resolveOptional(a, "ClientPasswordAuthType", engine));
                entry.setDescription(resolveOptional(a, "Description", engine));
                entry.setUserName(resolveOptional(a, "UserName", engine));
                auth.add(entry);
            }
        }
        return auth;
    }

    private void validateDbProxyNetworkType(
            String value, String propertyName, boolean dualAllowed, String validValues) {
        if (value == null || "IPV4".equalsIgnoreCase(value)) {
            return;
        }
        boolean supportedAwsValue = "IPV6".equalsIgnoreCase(value)
                || (dualAllowed && "DUAL".equalsIgnoreCase(value));
        if (value.isBlank() || !supportedAwsValue) {
            throw new AwsException("InvalidParameterValue",
                    propertyName + " must be " + validValues + ".", 400);
        }
    }

    private void deleteDbProxySafe(String name, String region) {
        try {
            rdsService.deleteDbProxy(name, region);
        } catch (AwsException e) {
            if (!"DBProxyNotFoundFault".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("DB proxy already gone, treating as deleted: {0}", name);
        }
    }

    private void clearDbProxyTargetGroupSafe(String targetGroupArn, String region) {
        try {
            rdsService.clearDbProxyTargetGroupByArn(targetGroupArn, region);
        } catch (AwsException e) {
            if (!"DBProxyTargetGroupNotFoundFault".equals(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("DB proxy target group already gone, treating as deleted: {0}", targetGroupArn);
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private Integer parseOptionalIntProp(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        String value = resolveOptional(props, name, engine);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new AwsException("InvalidParameterValue", name + " must be an integer.", 400);
        }
    }

    private boolean parseBoolProp(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        return Boolean.parseBoolean(resolveOptional(props, name, engine));
    }

    // ── helpers copied from the monolith, which keeps its own copies for the types still there ──

    private String resolveOptional(JsonNode props, String name, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolve(props.get(name));
    }

    private List<String> resolveStringList(JsonNode props, String field, CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(field)) {
            return new ArrayList<>();
        }
        return new ArrayList<>(engine.resolveStringList(props.get(field)));
    }

    /**
     * Resolves a property without the engine's general dynamic-reference stage, for the two RDS
     * master-credential properties that resolve their own references with {@code ssm-secure}
     * allowed. The general stage rejects {@code ssm-secure}, so running it first would fail the
     * one case AWS permits it. Mirrors the monolith helper of the same name.
     */
    private String resolveOptionalWithoutDynamicReferences(JsonNode props, String name,
                                                           CloudFormationTemplateEngine engine) {
        if (props == null || !props.has(name) || props.get(name).isNull()) {
            return null;
        }
        return engine.resolveWithoutDynamicReferences(props.get(name));
    }

    private String resolveDynamicReferences(String value, String region, boolean allowSsmSecure) {
        return dynamicReferences.resolveDynamicReferences(value, region, allowSsmSecure);
    }

    private Map<String, String> parseCfnTags(JsonNode tagsNode, CloudFormationTemplateEngine engine) {
        tagsNode = engine.resolveNode(tagsNode);
        Map<String, String> out = new HashMap<>();
        if (tagsNode == null || tagsNode.isNull() || !tagsNode.isArray()) {
            return out;
        }
        for (JsonNode entry : tagsNode) {
            JsonNode resolved = engine.resolveNode(entry);
            String key = resolved.path("Key").asText(null);
            String value = resolved.path("Value").asText("");
            if (key != null) {
                out.put(key, value);
            }
        }
        return out;
    }

    private <T> T sameNameExistingResource(String priorPhysicalId, String name,
                                           java.util.function.Function<String, T> lookup) {
        if (priorPhysicalId == null || !priorPhysicalId.equals(name)) {
            return null;
        }
        try {
            return lookup.apply(name);
        } catch (AwsException notFound) {
            // Expected when the resource was deleted out of band since the prior update; the
            // caller falls back to creating it fresh under the same name.
            LOG.debugv(notFound, "No existing {0} found on file, falling back to create", name);
            return null;
        }
    }

    private void deleteRenamedResource(String priorPhysicalId, String newName,
                                       java.util.function.Consumer<String> delete, String resourceKind) {
        if (priorPhysicalId == null || priorPhysicalId.equals(newName)) {
            return;
        }
        try {
            delete.accept(priorPhysicalId);
        } catch (RuntimeException e) {
            LOG.warnv(e, "Failed to delete renamed {0} {1} after replacement by {2}",
                    resourceKind, priorPhysicalId, newName);
        }
    }

    private int parseIntProp(JsonNode props, String name, CloudFormationTemplateEngine engine, int fallback) {
        String value = resolveOptional(props, name, engine);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOG.debugv(e, "Non-numeric {0} value {1}; falling back to {2}", name, value, fallback);
            return fallback;
        }
    }
}
