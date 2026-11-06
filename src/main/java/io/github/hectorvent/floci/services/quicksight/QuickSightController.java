package io.github.hectorvent.floci.services.quicksight;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.Pagination;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/accounts/{account}")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class QuickSightController {
    private final QuickSightService service;
    private final ObjectReader reader;
    private final RegionResolver regions;

    @Inject
    public QuickSightController(QuickSightService service, ObjectMapper mapper, RegionResolver regions) {
        this.service = service;
        this.regions = regions;
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @POST
    @Path("/search/dashboards")
    public Response searchDashboards(@PathParam("account") String account, String body) {
        requireCallerMayActOnAccount(account);
        return response(service.searchDashboards(account, parse(body)));
    }

    @POST
    @Path("/dashboards/{id}")
    public Response createDashboard(@PathParam("account") String account, @PathParam("id") String id, String body) {
        requireCallerMayActOnAccount(account);
        return response(service.create(account, "dashboard", id, parse(body)));
    }

    @GET
    @Path("/dashboards/{id}")
    public Response describeDashboard(@PathParam("account") String account, @PathParam("id") String id) {
        requireCallerMayActOnAccount(account);
        return response(service.describe(account, "dashboard", id));
    }

    @DELETE
    @Path("/dashboards/{id}")
    public Response deleteDashboard(@PathParam("account") String account, @PathParam("id") String id) {
        requireCallerMayActOnAccount(account);
        return response(service.delete(account, "dashboard", id));
    }

    @POST
    @Path("/data-sets")
    public Response createDataSet(@PathParam("account") String account, String body) {
        requireCallerMayActOnAccount(account);
        JsonNode request = parse(body);
        return response(service.create(account, "dataset", request.path("DataSetId").asText(), request));
    }

    @GET
    @Path("/data-sets/{id}")
    public Response describeDataSet(@PathParam("account") String account, @PathParam("id") String id) {
        requireCallerMayActOnAccount(account);
        return response(service.describe(account, "dataset", id));
    }

    @DELETE
    @Path("/data-sets/{id}")
    public Response deleteDataSet(@PathParam("account") String account, @PathParam("id") String id) {
        requireCallerMayActOnAccount(account);
        return response(service.delete(account, "dataset", id));
    }

    @POST
    @Path("/data-sources")
    public Response createDataSource(@PathParam("account") String account, String body) {
        requireCallerMayActOnAccount(account);
        JsonNode request = parse(body);
        return response(service.create(account, "datasource", request.path("DataSourceId").asText(), request));
    }

    @GET
    @Path("/data-sources/{id}")
    public Response describeDataSource(@PathParam("account") String account, @PathParam("id") String id) {
        requireCallerMayActOnAccount(account);
        return response(service.describe(account, "datasource", id));
    }

    @DELETE
    @Path("/data-sources/{id}")
    public Response deleteDataSource(@PathParam("account") String account, @PathParam("id") String id) {
        requireCallerMayActOnAccount(account);
        return response(service.delete(account, "datasource", id));
    }

    @POST
    @Path("/namespaces/{namespace}/users")
    public Response registerUser(@PathParam("account") String account, @PathParam("namespace") String namespace, String body) {
        requireCallerMayActOnAccount(account);
        return response(service.registerUser(account, namespace, parse(body)));
    }

    @GET
    @Path("/namespaces/{namespace}/users/{name}")
    public Response describeUser(@PathParam("account") String account, @PathParam("namespace") String namespace,
                                 @PathParam("name") String name) {
        requireCallerMayActOnAccount(account);
        return response(service.describeUser(account, namespace, name));
    }

    @PUT
    @Path("/data-sets/{id}/ingestions/{ingestion}")
    public Response createIngestion(@PathParam("account") String account, @PathParam("id") String id,
                                   @PathParam("ingestion") String ingestion, String body) {
        requireCallerMayActOnAccount(account);
        return response(service.createIngestion(account, id, ingestion, parse(body)));
    }

    @GET
    @Path("/data-sets/{id}/ingestions")
    public Response listIngestions(@PathParam("account") String account, @PathParam("id") String id,
                                  @QueryParam("max-results") String maximum,
                                  @QueryParam("next-token") String token) {
        requireCallerMayActOnAccount(account);
        Integer limit = Pagination.parseMaxResults(maximum, "InvalidParameterValueException");
        return response(service.listIngestions(account, id, limit == null ? 100 : limit, token));
    }

    @GET
    @Path("/data-sets/{id}/ingestions/{ingestion}")
    public Response describeIngestion(@PathParam("account") String account, @PathParam("id") String id,
                                     @PathParam("ingestion") String ingestion) {
        requireCallerMayActOnAccount(account);
        return response(service.describeIngestion(account, id, ingestion));
    }

    @POST
    @Path("/embed-url/registered-user")
    public Response generateEmbedUrlForRegisteredUser(@PathParam("account") String account, String body) {
        requireCallerMayActOnAccount(account);
        return response(service.generateEmbedUrl(account, parse(body)));
    }

    private void requireCallerMayActOnAccount(String account) {
        if (!account.equals(regions.getAccountId())) {
            throw new AwsException("AccessDeniedException", "AwsAccountId must match the caller's account.", 401);
        }
    }

    private JsonNode parse(String body) {
        try {
            JsonNode node = reader.readTree(body == null || body.isBlank() ? "{}" : body);
            if (node == null || !node.isObject()) {
                throw new AwsException("InvalidParameterValueException", "Request must be a JSON object.", 400);
            }
            return node;
        } catch (AwsException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AwsException("InvalidParameterValueException", "Request body is not valid JSON.", 400);
        }
    }

    private Response response(ObjectNode body) {
        return Response.status(body.path("Status").asInt(200)).header("x-amzn-RequestId", body.path("RequestId").asText()).entity(body).build();
    }
}
