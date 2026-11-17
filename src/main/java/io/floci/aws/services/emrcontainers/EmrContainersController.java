package io.floci.aws.services.emrcontainers;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.services.emrcontainers.EmrContainersService.ListQuery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/** EMR on EKS's routes, under the prefix {@link EmrContainersRouteFilter} moves them to. */
@Path(EmrContainersRouteFilter.INTERNAL_PREFIX)
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class EmrContainersController {

    private final EmrContainersService service;
    private final ObjectMapper mapper;

    @Inject
    public EmrContainersController(EmrContainersService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @POST
    @Path("/virtualclusters")
    public ObjectNode createVirtualCluster(String body) {
        return service.createVirtualCluster(parse(body));
    }

    @GET
    @Path("/virtualclusters")
    public ObjectNode listVirtualClusters(@QueryParam("containerProviderId") String containerProviderId,
                                          @QueryParam("containerProviderType") String containerProviderType,
                                          @QueryParam("createdAfter") String createdAfter,
                                          @QueryParam("createdBefore") String createdBefore,
                                          @QueryParam("states") List<String> states,
                                          @QueryParam("maxResults") String maxResults,
                                          @QueryParam("nextToken") String nextToken,
                                          @QueryParam("eksAccessEntryIntegrated") String eksAccessEntryIntegrated) {
        return service.listVirtualClusters(ListQuery.of(createdAfter, createdBefore, states, maxResults, nextToken),
                containerProviderId, containerProviderType, booleanQuery("eksAccessEntryIntegrated",
                        eksAccessEntryIntegrated));
    }

    @GET
    @Path("/virtualclusters/{virtualClusterId}")
    public ObjectNode describeVirtualCluster(@PathParam("virtualClusterId") String virtualClusterId) {
        return service.describeVirtualCluster(virtualClusterId);
    }

    @PATCH
    @Path("/virtualclusters/{virtualClusterId}")
    public ObjectNode updateVirtualCluster(@PathParam("virtualClusterId") String virtualClusterId, String body) {
        return service.updateVirtualCluster(virtualClusterId, parse(body));
    }

    @DELETE
    @Path("/virtualclusters/{virtualClusterId}")
    public ObjectNode deleteVirtualCluster(@PathParam("virtualClusterId") String virtualClusterId) {
        return service.deleteVirtualCluster(virtualClusterId);
    }

    @POST
    @Path("/virtualclusters/{virtualClusterId}/jobruns")
    public ObjectNode startJobRun(@PathParam("virtualClusterId") String virtualClusterId, String body) {
        return service.startJobRun(virtualClusterId, parse(body));
    }

    @GET
    @Path("/virtualclusters/{virtualClusterId}/jobruns")
    public ObjectNode listJobRuns(@PathParam("virtualClusterId") String virtualClusterId,
                                  @QueryParam("createdBefore") String createdBefore,
                                  @QueryParam("createdAfter") String createdAfter,
                                  @QueryParam("name") String name,
                                  @QueryParam("states") List<String> states,
                                  @QueryParam("maxResults") String maxResults,
                                  @QueryParam("nextToken") String nextToken) {
        return service.listJobRuns(virtualClusterId,
                ListQuery.of(createdAfter, createdBefore, states, maxResults, nextToken), name);
    }

    @GET
    @Path("/virtualclusters/{virtualClusterId}/jobruns/{jobRunId}")
    public ObjectNode describeJobRun(@PathParam("virtualClusterId") String virtualClusterId,
                                     @PathParam("jobRunId") String jobRunId) {
        return service.describeJobRun(virtualClusterId, jobRunId);
    }

    @DELETE
    @Path("/virtualclusters/{virtualClusterId}/jobruns/{jobRunId}")
    public ObjectNode cancelJobRun(@PathParam("virtualClusterId") String virtualClusterId,
                                   @PathParam("jobRunId") String jobRunId) {
        return service.cancelJobRun(virtualClusterId, jobRunId);
    }

    @POST
    @Path("/virtualclusters/{virtualClusterId}/endpoints")
    public ObjectNode createManagedEndpoint(@PathParam("virtualClusterId") String virtualClusterId, String body) {
        return service.createManagedEndpoint(virtualClusterId, parse(body));
    }

    @GET
    @Path("/virtualclusters/{virtualClusterId}/endpoints")
    public ObjectNode listManagedEndpoints(@PathParam("virtualClusterId") String virtualClusterId,
                                           @QueryParam("createdBefore") String createdBefore,
                                           @QueryParam("createdAfter") String createdAfter,
                                           @QueryParam("types") List<String> types,
                                           @QueryParam("states") List<String> states,
                                           @QueryParam("maxResults") String maxResults,
                                           @QueryParam("nextToken") String nextToken) {
        return service.listManagedEndpoints(virtualClusterId,
                ListQuery.of(createdAfter, createdBefore, states, maxResults, nextToken), types);
    }

    @GET
    @Path("/virtualclusters/{virtualClusterId}/endpoints/{endpointId}")
    public ObjectNode describeManagedEndpoint(@PathParam("virtualClusterId") String virtualClusterId,
                                              @PathParam("endpointId") String endpointId) {
        return service.describeManagedEndpoint(virtualClusterId, endpointId);
    }

    @DELETE
    @Path("/virtualclusters/{virtualClusterId}/endpoints/{endpointId}")
    public ObjectNode deleteManagedEndpoint(@PathParam("virtualClusterId") String virtualClusterId,
                                            @PathParam("endpointId") String endpointId) {
        return service.deleteManagedEndpoint(virtualClusterId, endpointId);
    }

    @POST
    @Path("/virtualclusters/{virtualClusterId}/endpoints/{endpointId}/credentials")
    public ObjectNode getManagedEndpointSessionCredentials(@PathParam("virtualClusterId") String virtualClusterId,
                                                           @PathParam("endpointId") String endpointId,
                                                           String body) {
        return service.getManagedEndpointSessionCredentials(virtualClusterId, endpointId, parse(body));
    }

    @POST
    @Path("/jobtemplates")
    public ObjectNode createJobTemplate(String body) {
        return service.createJobTemplate(parse(body));
    }

    @GET
    @Path("/jobtemplates")
    public ObjectNode listJobTemplates(@QueryParam("createdAfter") String createdAfter,
                                       @QueryParam("createdBefore") String createdBefore,
                                       @QueryParam("maxResults") String maxResults,
                                       @QueryParam("nextToken") String nextToken) {
        return service.listJobTemplates(ListQuery.of(createdAfter, createdBefore, null, maxResults, nextToken));
    }

    @GET
    @Path("/jobtemplates/{templateId}")
    public ObjectNode describeJobTemplate(@PathParam("templateId") String templateId) {
        return service.describeJobTemplate(templateId);
    }

    @DELETE
    @Path("/jobtemplates/{templateId}")
    public ObjectNode deleteJobTemplate(@PathParam("templateId") String templateId) {
        return service.deleteJobTemplate(templateId);
    }

    @POST
    @Path("/securityconfigurations")
    public ObjectNode createSecurityConfiguration(String body) {
        return service.createSecurityConfiguration(parse(body));
    }

    @GET
    @Path("/securityconfigurations")
    public ObjectNode listSecurityConfigurations(@QueryParam("createdAfter") String createdAfter,
                                                 @QueryParam("createdBefore") String createdBefore,
                                                 @QueryParam("maxResults") String maxResults,
                                                 @QueryParam("nextToken") String nextToken) {
        return service.listSecurityConfigurations(
                ListQuery.of(createdAfter, createdBefore, null, maxResults, nextToken));
    }

    @GET
    @Path("/securityconfigurations/{securityConfigurationId}")
    public ObjectNode describeSecurityConfiguration(
            @PathParam("securityConfigurationId") String securityConfigurationId) {
        return service.describeSecurityConfiguration(securityConfigurationId);
    }

    @DELETE
    @Path("/securityconfigurations/{securityConfigurationId}")
    public ObjectNode deleteSecurityConfiguration(
            @PathParam("securityConfigurationId") String securityConfigurationId) {
        return service.deleteSecurityConfiguration(securityConfigurationId);
    }

    // A query parameter the model types Boolean, refused unless it reads true or false.
    private static Boolean booleanQuery(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
            return Boolean.valueOf(value);
        }
        throw new AwsException("ValidationException", name + " must be true or false.", 400);
    }

    private ObjectNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode parsed = mapper.readTree(body);
            if (parsed instanceof ObjectNode object) {
                return object;
            }
        } catch (JsonProcessingException malformed) {
            throw new AwsException("ValidationException", "The request body is not valid JSON.", 400);
        }
        throw new AwsException("ValidationException", "The request body must be a JSON object.", 400);
    }
}
