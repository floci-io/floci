package io.floci.aws.services.glue.iceberg;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * AWS Glue's Iceberg REST endpoint ({@code https://glue.<region>.amazonaws.com/iceberg}): the twelve operations of the
 * Apache Iceberg REST catalog specification AWS lists for it, under the {@code /v1/catalogs/{catalog}} prefix.
 * {@link GlueIcebergRouteFilter} moves requests signed for Glue onto {@link GlueIcebergRouteFilter#INTERNAL_PREFIX}.
 *
 * <p>Under IAM enforcement each route is checked as the Glue operation AWS's Glue Developer Guide documents it as, its
 * IAM action and CloudTrail event, from the routes vendored in {@code aws/rest-routes.json}, before it reaches this
 * controller. A bearer token Floci minted for its own query engine is not checked again: Floci's Athena hands it to
 * the engine for a caller IAM already checked.
 */
@Path(GlueIcebergRouteFilter.INTERNAL_PREFIX + GlueIcebergRouteFilter.ICEBERG_PATH + "/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GlueIcebergController {

    private final GlueIcebergCatalog catalog;

    @Inject
    public GlueIcebergController(GlueIcebergCatalog catalog) {
        this.catalog = catalog;
    }

    @GET
    @Path("/config")
    public ObjectNode getConfig(@QueryParam("warehouse") String warehouse) {
        return catalog.config(warehouse);
    }

    @GET
    @Path("/catalogs/{catalog}/namespaces")
    public ObjectNode listNamespaces(@PathParam("catalog") String catalogId, @QueryParam("parent") String parent) {
        return catalog.listNamespaces(catalogId, parent);
    }

    @POST
    @Path("/catalogs/{catalog}/namespaces")
    public ObjectNode createNamespace(@PathParam("catalog") String catalogId, JsonNode request) {
        return catalog.createNamespace(catalogId, request);
    }

    @GET
    @Path("/catalogs/{catalog}/namespaces/{namespace}")
    public ObjectNode loadNamespaceMetadata(@PathParam("catalog") String catalogId,
                                            @PathParam("namespace") String namespace) {
        return catalog.loadNamespace(catalogId, namespace);
    }

    @POST
    @Path("/catalogs/{catalog}/namespaces/{namespace}/properties")
    public ObjectNode updateNamespaceProperties(@PathParam("catalog") String catalogId,
                                                @PathParam("namespace") String namespace, JsonNode request) {
        return catalog.updateNamespaceProperties(catalogId, namespace, request);
    }

    @DELETE
    @Path("/catalogs/{catalog}/namespaces/{namespace}")
    public Response dropNamespace(@PathParam("catalog") String catalogId, @PathParam("namespace") String namespace) {
        catalog.dropNamespace(catalogId, namespace);
        return Response.noContent().build();
    }

    @GET
    @Path("/catalogs/{catalog}/namespaces/{namespace}/tables")
    public ObjectNode listTables(@PathParam("catalog") String catalogId, @PathParam("namespace") String namespace) {
        return catalog.listTables(catalogId, namespace);
    }

    @POST
    @Path("/catalogs/{catalog}/namespaces/{namespace}/tables")
    public ObjectNode createTable(@PathParam("catalog") String catalogId, @PathParam("namespace") String namespace,
                                  JsonNode request) {
        return catalog.createTable(catalogId, namespace, request);
    }

    @GET
    @Path("/catalogs/{catalog}/namespaces/{namespace}/tables/{table}")
    public ObjectNode loadTable(@PathParam("catalog") String catalogId, @PathParam("namespace") String namespace,
                                @PathParam("table") String table, @QueryParam("snapshots") String snapshots) {
        return catalog.loadTable(catalogId, namespace, table, snapshots);
    }

    @HEAD
    @Path("/catalogs/{catalog}/namespaces/{namespace}/tables/{table}")
    public Response tableExists(@PathParam("catalog") String catalogId, @PathParam("namespace") String namespace,
                                @PathParam("table") String table) {
        catalog.tableExists(catalogId, namespace, table);
        return Response.noContent().build();
    }

    @POST
    @Path("/catalogs/{catalog}/namespaces/{namespace}/tables/{table}")
    public ObjectNode updateTable(@PathParam("catalog") String catalogId, @PathParam("namespace") String namespace,
                                  @PathParam("table") String table, JsonNode request) {
        return catalog.commitTable(catalogId, namespace, table, request);
    }

    @DELETE
    @Path("/catalogs/{catalog}/namespaces/{namespace}/tables/{table}")
    public Response dropTable(@PathParam("catalog") String catalogId, @PathParam("namespace") String namespace,
                              @PathParam("table") String table) {
        catalog.dropTable(catalogId, namespace, table);
        return Response.noContent().build();
    }
}
