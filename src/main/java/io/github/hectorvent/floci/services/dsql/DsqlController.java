package io.github.hectorvent.floci.services.dsql;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.EpochSeconds;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.dsql.model.DsqlCluster;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aurora DSQL's REST-JSON control plane: {@code /cluster}, {@code /cluster/{identifier}} and its
 * policy, and {@code /clusters/{identifier}/vpc-endpoint-service-name}, as the service model
 * (2018-05-10) defines them. Tags go through the shared {@code /tags/{resourceArn}} dispatcher.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DsqlController {

    private static final int MAX_POLICY_LENGTH = 20480;

    private final DsqlService service;
    private final RegionResolver regionResolver;

    @Inject
    public DsqlController(DsqlService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/cluster")
    public Response createCluster(@Context HttpHeaders headers, Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        Map<String, Object> multiRegion = map(request.get("multiRegionProperties"));
        String policy = string(request.get("policy"));
        if (policy != null) {
            requirePolicyLength(policy);
        }
        DsqlCluster cluster = service.createCluster(regionResolver.resolveRegion(headers),
                deletionProtection(request.get("deletionProtectionEnabled")), string(request.get("kmsEncryptionKey")),
                stringMap(request.get("tags")), string(request.get("clientToken")),
                multiRegion == null ? null : string(multiRegion.get("witnessRegion")),
                multiRegion == null ? null : strings(multiRegion.get("clusters")), policy,
                Boolean.TRUE.equals(request.get("bypassPolicyLockoutSafetyCheck")));
        // Measured: the answer reports the cluster being created, however soon it is ACTIVE, and a
        // multi-Region one waiting for its peers.
        Map<String, Object> response = summary(cluster);
        response.put("status", cluster.getWitnessRegion() != null ? DsqlService.PENDING_SETUP : DsqlService.CREATING);
        putMultiRegion(response, cluster);
        response.put("encryptionDetails", encryptionDetails(cluster));
        response.put("deletionProtectionEnabled", cluster.isDeletionProtectionEnabled());
        response.put("endpoint", cluster.getEndpoint());
        return Response.ok(response).build();
    }

    @GET
    @Path("/cluster")
    public Response listClusters(@Context HttpHeaders headers, @QueryParam("max-results") Integer maxResults,
                                 @QueryParam("next-token") String nextToken) {
        DsqlService.Page page = service.listClusters(regionResolver.resolveRegion(headers), maxResults, nextToken);
        List<Map<String, Object>> clusters = new ArrayList<>();
        for (DsqlCluster cluster : page.clusters()) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("identifier", cluster.getIdentifier());
            entry.put("arn", cluster.getArn());
            clusters.add(entry);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        if (page.nextToken() != null) {
            response.put("nextToken", page.nextToken());
        }
        response.put("clusters", clusters);
        return Response.ok(response).build();
    }

    @GET
    @Path("/cluster/{identifier}")
    public Response getCluster(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        DsqlCluster cluster = service.getCluster(regionResolver.resolveRegion(headers), identifier);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("identifier", cluster.getIdentifier());
        response.put("arn", cluster.getArn());
        response.put("status", cluster.getStatus());
        response.put("creationTime", epochSeconds(cluster));
        response.put("deletionProtectionEnabled", cluster.isDeletionProtectionEnabled());
        putMultiRegion(response, cluster);
        if (!cluster.getTags().isEmpty()) {
            response.put("tags", cluster.getTags());
        }
        response.put("encryptionDetails", encryptionDetails(cluster));
        response.put("endpoint", cluster.getEndpoint());
        return Response.ok(response).build();
    }

    @POST
    @Path("/cluster/{identifier}")
    public Response updateCluster(@Context HttpHeaders headers, @PathParam("identifier") String identifier,
                                  Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        Map<String, Object> multiRegion = map(request.get("multiRegionProperties"));
        DsqlCluster cluster = service.updateCluster(regionResolver.resolveRegion(headers), identifier,
                deletionProtection(request.get("deletionProtectionEnabled")), string(request.get("kmsEncryptionKey")),
                multiRegion == null ? null : string(multiRegion.get("witnessRegion")),
                multiRegion == null ? null : strings(multiRegion.get("clusters")));
        // Measured: the answer reports the update in progress; the cluster is ACTIVE right after.
        Map<String, Object> response = summary(cluster);
        response.put("status", DsqlService.UPDATING);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/cluster/{identifier}")
    public Response deleteCluster(@Context HttpHeaders headers, @PathParam("identifier") String identifier,
                                  @QueryParam("client-token") String clientToken) {
        DsqlCluster cluster = service.deleteCluster(regionResolver.resolveRegion(headers), identifier);
        Map<String, Object> response = summary(cluster);
        // Measured: a multi-Region cluster's deletion first waits on its peers.
        response.put("status", cluster.getWitnessRegion() != null ? DsqlService.PENDING_DELETE : DsqlService.DELETING);
        return Response.ok(response).build();
    }

    @GET
    @Path("/clusters/{identifier}/vpc-endpoint-service-name")
    public Response getVpcEndpointServiceName(@Context HttpHeaders headers,
                                              @PathParam("identifier") String identifier) {
        String region = regionResolver.resolveRegion(headers);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("serviceName", service.vpcEndpointServiceName(region, identifier));
        response.put("clusterVpcEndpoint", service.clusterVpcEndpoint(region, identifier));
        return Response.ok(response).build();
    }

    @POST
    @Path("/cluster/{identifier}/policy")
    public Response putClusterPolicy(@Context HttpHeaders headers, @PathParam("identifier") String identifier,
                                     Map<String, Object> body) {
        Map<String, Object> request = body == null ? Map.of() : body;
        String policy = string(request.get("policy"));
        if (policy == null) {
            throw policyConstraint("Member must not be null");
        }
        requirePolicyLength(policy);
        String version = service.putClusterPolicy(regionResolver.resolveRegion(headers), identifier, policy,
                string(request.get("expectedPolicyVersion")),
                Boolean.TRUE.equals(request.get("bypassPolicyLockoutSafetyCheck")));
        return Response.ok(Map.of("policyVersion", version)).build();
    }

    @GET
    @Path("/cluster/{identifier}/policy")
    public Response getClusterPolicy(@Context HttpHeaders headers, @PathParam("identifier") String identifier) {
        DsqlCluster cluster = service.getClusterPolicy(regionResolver.resolveRegion(headers), identifier);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("policy", cluster.getPolicy());
        response.put("policyVersion", cluster.getPolicyVersion());
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/cluster/{identifier}/policy")
    public Response deleteClusterPolicy(@Context HttpHeaders headers, @PathParam("identifier") String identifier,
                                        @QueryParam("expected-policy-version") String expectedPolicyVersion,
                                        @QueryParam("client-token") String clientToken) {
        String version = service.deleteClusterPolicy(regionResolver.resolveRegion(headers), identifier,
                expectedPolicyVersion);
        return Response.ok(Map.of("policyVersion", version)).build();
    }

    // ── Shapes ────────────────────────────────────────────────────────────────

    private static Map<String, Object> summary(DsqlCluster cluster) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("identifier", cluster.getIdentifier());
        response.put("arn", cluster.getArn());
        response.put("status", cluster.getStatus());
        response.put("creationTime", epochSeconds(cluster));
        return response;
    }

    private static void putMultiRegion(Map<String, Object> response, DsqlCluster cluster) {
        if (cluster.getWitnessRegion() == null && cluster.getLinkedClusterArns() == null) {
            return;
        }
        Map<String, Object> multiRegion = new LinkedHashMap<>();
        if (cluster.getWitnessRegion() != null) {
            multiRegion.put("witnessRegion", cluster.getWitnessRegion());
        }
        if (cluster.getLinkedClusterArns() != null) {
            multiRegion.put("clusters", cluster.getLinkedClusterArns());
        }
        response.put("multiRegionProperties", multiRegion);
    }

    static Map<String, Object> encryptionDetails(DsqlCluster cluster) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (cluster.getKmsEncryptionKey() != null) {
            details.put("encryptionType", "CUSTOMER_MANAGED_KMS_KEY");
            details.put("kmsKeyArn", cluster.getKmsEncryptionKey());
        } else {
            details.put("encryptionType", "AWS_OWNED_KMS_KEY");
        }
        details.put("encryptionStatus", "ENABLED");
        return details;
    }

    private static double epochSeconds(DsqlCluster cluster) {
        return EpochSeconds.fromInstant(cluster.getCreationTime());
    }

    private static String string(Object value) {
        return value instanceof String text ? text : null;
    }

    /**
     * deletionProtectionEnabled as DSQL reads it (measured, eu-west-1, 2026-10-20): a boolean, or
     * text that is false when it spells false in any case and true otherwise ("yes", "TRUE", "1");
     * null leaves it out. Empty text, a number, an object and a list fail to deserialize.
     */
    private static Boolean deletionProtection(Object value) {
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String text) {
            if (text.isEmpty()) {
                throw serialization("Unexpected token received from parser");
            }
            return !"false".equalsIgnoreCase(text);
        }
        if (value instanceof Number) {
            throw serialization("NUMBER_VALUE cannot be converted to Boolean");
        }
        throw serialization(value instanceof Map<?, ?> ? "Start of structure or map found where not expected" : "");
    }

    /** The model's bounds on a policy, refused with the texts measured on DSQL. */
    private static void requirePolicyLength(String policy) {
        if (policy.isEmpty()) {
            throw policyConstraint("Member must have length greater than or equal to 1");
        }
        if (policy.length() > MAX_POLICY_LENGTH) {
            throw policyConstraint("Member must have length less than or equal to " + MAX_POLICY_LENGTH);
        }
    }

    private static AwsException policyConstraint(String constraint) {
        return new AwsException("ValidationException", "1 validation error detected: Value at 'policy' failed to"
                + " satisfy constraint: " + constraint, 400);
    }

    private static AwsException serialization(String message) {
        return new AwsException("SerializationException", message, 400);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, entry) -> result.put(String.valueOf(key), entry == null ? "" : String.valueOf(entry)));
        return result;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        list.forEach(item -> result.add(String.valueOf(item)));
        return result;
    }
}
