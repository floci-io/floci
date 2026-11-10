package io.floci.aws.services.cloudformation.provisioners;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.dsql.DsqlService;
import io.floci.aws.services.dsql.model.DsqlCluster;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CloudFormation provisioning for {@code AWS::DSQL::Cluster}. {@code Ref} is the cluster's
 * identifier, the schema's primary identifier, and every read-only property but
 * {@code EncryptionDetails}, an object, is an attribute. Properties are read against the resource
 * schema, so a value of the wrong type fails the resource as on AWS. As CloudFormation's handler
 * does, create waits for the cluster to be ACTIVE, or PENDING_SETUP for a multi-Region one, and an
 * update leaves a property the template no longer sets as it was, but for the tags and the policy,
 * which go (measured, eu-west-1, 2026-10-20).
 */
@ApplicationScoped
public class DsqlCfnProvisioner implements CfnResourceProvisioner {

    private static final String TYPE = "AWS::DSQL::Cluster";
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(5);
    /** Measured: {@code 2026-10-16T15:49:38.488Z}, milliseconds always written. */
    private static final DateTimeFormatter CREATION_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private static final Set<String> SETTLED = Set.of("ACTIVE", "PENDING_SETUP");

    private final DsqlService dsqlService;

    @Inject
    public DsqlCfnProvisioner(DsqlService dsqlService) {
        this.dsqlService = dsqlService;
    }

    @Override
    public Set<String> resourceTypes() {
        return Set.of(TYPE);
    }

    @Override
    public void provision(StackResource resource, JsonNode props, ProvisionContext ctx) {
        String region = ctx.region();
        CfnPropertyTypes.Reader types = new CfnPropertyTypes.Reader(ctx.engine());
        JsonNode protection = read(types, props, "DeletionProtectionEnabled");
        JsonNode kmsKey = read(types, props, "KmsEncryptionKey");
        JsonNode multiRegion = read(types, props, "MultiRegionProperties");
        JsonNode tagList = read(types, props, "Tags");
        JsonNode policyDocument = read(types, props, "PolicyDocument");
        types.finish();
        Boolean deletionProtection = protection == null ? null : protection.booleanValue();
        String kms = kmsKey == null ? null : kmsKey.asText();
        String witnessRegion = multiRegion != null && multiRegion.hasNonNull("WitnessRegion")
                ? multiRegion.get("WitnessRegion").asText() : null;
        List<String> linkedClusters = multiRegion != null && multiRegion.path("Clusters").isArray()
                ? strings(multiRegion.get("Clusters")) : null;
        Map<String, String> tags = tags(tagList);
        String policy = policyDocument == null ? null : policyDocument.asText();

        DsqlCluster cluster;
        if (ctx.priorPhysicalId() != null) {
            String identifier = ctx.priorPhysicalId();
            dsqlService.updateCluster(region, identifier, deletionProtection, kms, witnessRegion, linkedClusters);
            reconcileTags(region, dsqlService.getCluster(region, identifier), tags);
            dsqlService.replaceClusterPolicy(region, identifier, policy);
            cluster = dsqlService.getCluster(region, identifier);
        } else {
            cluster = dsqlService.createCluster(region, deletionProtection, kms, tags, null, witnessRegion,
                    linkedClusters, policy, false);
            cluster = awaitSettled(region, cluster.getIdentifier());
        }

        resource.setPhysicalId(cluster.getIdentifier());
        resource.getAttributes().put("Identifier", cluster.getIdentifier());
        resource.getAttributes().put("ResourceArn", cluster.getArn());
        resource.getAttributes().put("CreationTime", CREATION_TIME.format(cluster.getCreationTime()));
        resource.getAttributes().put("Status", cluster.getStatus());
        resource.getAttributes().put("Endpoint", cluster.getEndpoint());
        resource.getAttributes().put("VpcEndpoint", dsqlService.clusterVpcEndpoint(region, cluster.getIdentifier()));
        resource.getAttributes().put("VpcEndpointServiceName",
                dsqlService.vpcEndpointServiceName(region, cluster.getIdentifier()));
        // Measured: PolicyVersion exists only with a policy.
        if (cluster.getPolicy() != null) {
            resource.getAttributes().put("PolicyVersion", cluster.getPolicyVersion());
        } else {
            resource.getAttributes().remove("PolicyVersion");
        }
    }

    @Override
    public void delete(String resourceType, String physicalId, String region) {
        CfnDeletes.safeDelete("DSQL cluster", physicalId,
                () -> dsqlService.deleteCluster(region, physicalId), "ResourceNotFoundException");
    }

    private static JsonNode read(CfnPropertyTypes.Reader types, JsonNode props, String property) {
        return props == null ? null
                : types.read(property, props.get(property), CfnPropertyTypes.propertyShape(TYPE, property));
    }

    private DsqlCluster awaitSettled(String region, String identifier) {
        Instant deadline = Instant.now().plus(READY_TIMEOUT);
        while (true) {
            DsqlCluster cluster = dsqlService.getCluster(region, identifier);
            if (SETTLED.contains(cluster.getStatus())) {
                return cluster;
            }
            if ("FAILED".equals(cluster.getStatus()) || Instant.now().isAfter(deadline)) {
                throw new AwsException("InternalFailure", "Cluster " + identifier + " did not become ACTIVE"
                        + " (status " + cluster.getStatus() + ")", 500);
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AwsException("InternalFailure", "Interrupted while cluster " + identifier
                        + " was being created", 500);
            }
        }
    }

    private void reconcileTags(String region, DsqlCluster cluster, Map<String, String> wanted) {
        List<String> removed = new ArrayList<>();
        for (String key : cluster.getTags().keySet()) {
            if (!wanted.containsKey(key)) {
                removed.add(key);
            }
        }
        if (!removed.isEmpty()) {
            dsqlService.untagResource(region, cluster.getArn(), removed);
        }
        if (!wanted.isEmpty()) {
            dsqlService.tagResource(region, cluster.getArn(), wanted);
        }
    }

    private static Map<String, String> tags(JsonNode tagList) {
        Map<String, String> tags = new LinkedHashMap<>();
        if (tagList != null && tagList.isArray()) {
            for (JsonNode tag : tagList) {
                tags.put(tag.path("Key").asText(), tag.path("Value").asText(""));
            }
        }
        return tags;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> values.add(item.asText()));
        return values;
    }
}
