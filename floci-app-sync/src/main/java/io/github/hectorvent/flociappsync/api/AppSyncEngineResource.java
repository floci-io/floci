package io.github.hectorvent.flociappsync.api;

import io.github.hectorvent.flociappsync.graphql.AppSyncErrorFormatter;
import io.github.hectorvent.flociappsync.graphql.AppSyncTransportException;
import io.github.hectorvent.flociappsync.graphql.QueryExecutor;
import io.github.hectorvent.flociappsync.graphql.SchemaCompileException;
import io.github.hectorvent.flociappsync.graphql.SchemaRegistry;
import io.github.hectorvent.flociappsync.graphql.auth.AppSyncAuthContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The whole surface Floci talks to. Everything AppSync-specific that doesn't touch
 * graphql-java (CRUD storage, top-level request auth, content-type/body parsing) stays in
 * Floci; this only compiles schemas and executes queries against them.
 *
 * <p>Resolver/data-source dispatch (Floci's own docs list this as "Not Implemented" — Phase
 * 8/9) isn't wired in yet: fields resolve via graphql-java's default property fetcher unless
 * the auth wrapper intercepts them, exactly like Floci's in-process engine does today. When
 * that lands, it belongs here (dispatching to Floci's own DynamoDB/Lambda/HTTP endpoints,
 * the same public APIs any external caller would use), fed by resolver-wiring metadata that
 * Floci gathers fresh from its own storage and bundles into each {@code /execute} call —
 * mirroring how Athena bundles live Glue metadata into each call to floci-duck, rather than
 * this service keeping its own persisted replica of resolvers/data sources.
 */
@Path("/schemas/{apiId}")
@Produces(MediaType.APPLICATION_JSON)
public class AppSyncEngineResource {

    private static final Logger LOG = Logger.getLogger(AppSyncEngineResource.class);

    private final SchemaRegistry schemaRegistry;
    private final QueryExecutor queryExecutor;
    private final AppSyncErrorFormatter errorFormatter;

    @Inject
    public AppSyncEngineResource(SchemaRegistry schemaRegistry, QueryExecutor queryExecutor,
                                 AppSyncErrorFormatter errorFormatter) {
        this.schemaRegistry = schemaRegistry;
        this.queryExecutor = queryExecutor;
        this.errorFormatter = errorFormatter;
    }

    /** Compiles and registers a schema — backs {@code StartSchemaCreation}. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response compile(@PathParam("apiId") String apiId, SchemaCompileRequest request) {
        try {
            schemaRegistry.register(apiId, request.sdl());
            return Response.ok(Map.of("status", "ok")).build();
        } catch (SchemaCompileException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("message", e.getMessage());
            if (e.getExtendedData() != null) {
                body.put("extendedData", e.getExtendedData());
            }
            return Response.status(400).entity(body).build();
        }
    }

    /** Evicts a compiled schema — backs {@code DeleteGraphqlApi}. */
    @DELETE
    public Response evict(@PathParam("apiId") String apiId) {
        schemaRegistry.remove(apiId);
        return Response.noContent().build();
    }

    /** Executes a query against the compiled schema — backs {@code POST /v1/apis/{apiId}/graphql}. */
    @POST
    @Path("/execute")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response execute(@PathParam("apiId") String apiId, ExecuteRequest request) {
        var graphQLOpt = schemaRegistry.getGraphQL(apiId);
        if (graphQLOpt.isEmpty()) {
            return Response.status(502)
                    .entity(errorFormatter.transportError("GraphQLSchemaException", AppSyncErrorFormatter.MSG_NO_SCHEMA))
                    .build();
        }
        try {
            Map<String, Object> result = queryExecutor.execute(
                    graphQLOpt.get(), request.query(), request.variables(), request.operationName(),
                    graphQlContext(request.authContext()));
            return Response.ok(result).build();
        } catch (AppSyncTransportException e) {
            return Response.status(e.getHttpStatus())
                    .entity(errorFormatter.transportError(e.getErrorType(), e.getMessage()))
                    .build();
        } catch (RuntimeException e) {
            LOG.errorv(e, "Unexpected error executing GraphQL for API {0}", apiId);
            return Response.status(500)
                    .entity(errorFormatter.transportError("InternalFailure", "InternalFailure"))
                    .build();
        }
    }

    private static Map<Object, Object> graphQlContext(AppSyncAuthContext authContext) {
        Map<Object, Object> context = new HashMap<>();
        if (authContext == null) {
            return context;
        }
        context.put(AppSyncAuthContext.KEY, authContext);
        if (authContext.identity() != null) {
            context.put("identity", authContext.identity());
        }
        context.put("authType", authContext.authType());
        context.put("deniedFields", authContext.deniedFieldsList());
        return context;
    }
}
