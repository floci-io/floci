package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import graphql.execution.DataFetcherResult;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.SelectedField;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.graphql.datasource.AppSyncDataSourceInvokers;
import io.github.hectorvent.floci.services.appsync.graphql.js.AppSyncJsRuntime;
import io.github.hectorvent.floci.services.appsync.graphql.js.JsEvaluation;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.FunctionConfiguration;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.github.hectorvent.floci.services.appsync.model.ResolverKind;
import io.github.hectorvent.floci.services.appsync.model.ResolverRuntimeName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the resolver attached to a field: its handlers, its data source, and, for a PIPELINE
 * resolver, each of its functions in order.
 *
 * <p>The shape follows AppSync's. A UNIT resolver is {@code request() → data source → response()}.
 * A PIPELINE resolver is the resolver's own {@code request()} (its "before" step), then every
 * function as its own request/data-source/response, each seeing the one before it as
 * {@code ctx.prev.result}, and finally the resolver's {@code response()} ("after"). {@code ctx.stash}
 * is threaded through all of it, which is how a before step passes work to the functions.
 *
 * <p>A data source that fails does not fail the field on its own. AppSync calls the stage's
 * {@code response()} handler with {@code ctx.error} set to {@code {message, type}} and
 * {@code ctx.result} null, and the handler decides: re-raise with {@code util.error} (or collect
 * with {@code util.appendError}), or return a value and <em>suppress</em> the error entirely. That
 * suppression is deliberate on AWS's part and is why resolvers are written with an explicit
 * {@code if (ctx.error)} branch: without one, a failed query silently returns whatever the handler
 * returned. A stage with no {@code response()} handler at all has nothing to make that decision, so
 * there the error fails the field.
 *
 * <p>Two things stop a pipeline early. {@code runtime.earlyReturn(value)} makes {@code value} the
 * field's result immediately, skipping everything left including the after step, and
 * {@code util.error()} fails the field. {@code util.appendError} does neither: it collects errors
 * that are returned <em>alongside</em> the data, which is why this returns a
 * {@link DataFetcherResult} rather than a bare value.
 */
@ApplicationScoped
public class AppSyncResolverExecutor {

    private static final Logger LOG = Logger.getLogger(AppSyncResolverExecutor.class);
    private static final String REQUEST = "request";
    private static final String RESPONSE = "response";

    private final AppSyncService appSyncService;
    private final AppSyncJsRuntime jsRuntime;
    private final AppSyncDataSourceInvokers dataSourceInvokers;

    @Inject
    public AppSyncResolverExecutor(AppSyncService appSyncService,
                                   AppSyncJsRuntime jsRuntime,
                                   AppSyncDataSourceInvokers dataSourceInvokers) {
        this.appSyncService = appSyncService;
        this.jsRuntime = jsRuntime;
        this.dataSourceInvokers = dataSourceInvokers;
    }

    /**
     * The resolver for a field, or null when it has none. The caller then falls back to reading
     * the field off its source object, as a GraphQL server does by default.
     */
    public Resolver findResolver(String apiId, String typeName, String fieldName) {
        try {
            return appSyncService.getResolver(apiId, typeName, fieldName);
        } catch (AwsException e) {
            // Only "there is no resolver" means fall back to the default fetcher. Anything else (a schema
            // mid-recreation answers 409) has to surface, or a field would read as
            // unresolved during a deploy and quietly return null.
            if (e.getHttpStatus() == 404) {
                return null;
            }
            throw e;
        }
    }

    public DataFetcherResult<Object> execute(String apiId, Resolver resolver,
                                             DataFetchingEnvironment environment) {
        Execution execution = new Execution(apiId, resolver, environment);
        try {
            return execution.run();
        } catch (ResolverRaisedException e) {
            return execution.failure(e.error());
        } catch (AwsException e) {
            // A failure of the emulator itself (no sidecar, an unusable data source) is reported on
            // the field rather than thrown: one broken field should not abort the whole operation.
            LOG.debugv("Resolver {0}.{1} failed: {2}", resolver.getTypeName(), resolver.getFieldName(),
                    e.getMessage());
            return DataFetcherResult.newResult()
                    .error(new AppSyncResolverError(e.getMessage(), e.getErrorCode(), null, null,
                            errorPath(environment, resolver)))
                    .build();
        }
    }

    /**
     * One stage's code and the runtime it is written for: the resolver itself, or one pipeline
     * function.
     *
     * <p>{@code vtl} is decided from the mapping templates, not from an absent runtime. AppSync
     * leaves {@code Runtime} unset on a VTL resolver, so treating "no runtime" as APPSYNC_JS made
     * the VTL check below unreachable and a VTL stage fell through to the pass-through arm: it ran
     * as if it had no handler instead of saying it is unsupported.
     */
    private record Stage(String code, ResolverRuntimeName runtime, boolean vtl) {

        static Stage of(Resolver resolver) {
            return new Stage(resolver.getCode(), resolver.getRuntime() == null
                    ? null : resolver.getRuntime().getName(),
                    isVtl(resolver.getCode(), resolver.getRuntime() == null
                                    ? null : resolver.getRuntime().getName(),
                            resolver.getRequestMappingTemplate(), resolver.getResponseMappingTemplate()));
        }

        static Stage of(FunctionConfiguration function) {
            return new Stage(function.getCode(), function.getRuntime() == null
                    ? null : function.getRuntime().getName(),
                    isVtl(function.getCode(), function.getRuntime() == null
                                    ? null : function.getRuntime().getName(),
                            function.getRequestMappingTemplate(), function.getResponseMappingTemplate()));
        }

        private static boolean isVtl(String code, ResolverRuntimeName runtime, String requestTemplate,
                                     String responseTemplate) {
            if (runtime == ResolverRuntimeName.VTL) {
                return true;
            }
            boolean hasCode = code != null && !code.isBlank();
            boolean hasTemplate = (requestTemplate != null && !requestTemplate.isBlank())
                    || (responseTemplate != null && !responseTemplate.isBlank());
            return !hasCode && hasTemplate;
        }
    }

    /** One field's resolution, holding the mutable pipeline state. */
    private final class Execution {

        private final String apiId;
        private final Resolver resolver;
        private final DataFetchingEnvironment environment;
        private final List<Object> path;
        private final List<AppSyncResolverError> errors = new ArrayList<>();

        private Map<String, Object> stash = new LinkedHashMap<>();
        private Object previousResult;
        /** Set when a handler called runtime.earlyReturn: the pipeline stops and this is the value. */
        private boolean returned;
        private Object earlyReturnValue;

        private Execution(String apiId, Resolver resolver, DataFetchingEnvironment environment) {
            this.apiId = apiId;
            this.resolver = resolver;
            this.environment = environment;
            this.path = errorPath(environment, resolver);
        }

        private DataFetcherResult<Object> run() {
            if (resolver.getKind() == ResolverKind.PIPELINE) {
                return runPipeline();
            }
            return runUnit();
        }

        private DataFetcherResult<Object> runUnit() {
            Stage stage = Stage.of(resolver);
            Object request = callHandler(stage, REQUEST, null, null, null);
            if (returned) {
                return result(earlyReturnValue);
            }
            Invocation invocation = invokeDataSource(resolver.getDataSourceName(), request);
            Object response = callHandler(stage, RESPONSE,
                    invocation.result(), invocation.error(), invocation.result());
            return result(returned ? earlyReturnValue : response);
        }

        private DataFetcherResult<Object> runPipeline() {
            // The before step's job is usually to fill ctx.stash for the functions; its return value
            // is not the field's result, but it is what the first function sees as ctx.prev.result.
            Stage resolverStage = Stage.of(resolver);
            Object before = callHandler(resolverStage, REQUEST, null, null, null);
            if (returned) {
                return result(earlyReturnValue);
            }
            previousResult = before;

            for (String functionId : pipelineFunctionIds()) {
                FunctionConfiguration function = function(functionId);
                Stage functionStage = Stage.of(function);
                Object request = callHandler(functionStage, REQUEST, null, null, null);
                if (returned) {
                    return result(earlyReturnValue);
                }
                Invocation invocation = invokeDataSource(function.getDataSourceName(), request);
                Object response = callHandler(functionStage, RESPONSE,
                        invocation.result(), invocation.error(), invocation.result());
                if (returned) {
                    return result(earlyReturnValue);
                }
                // Missing response handler: the data source result passes through, which is what a
                // function with only a request handler does on AppSync.
                previousResult = response;
            }

            // The after step has no data source of its own, so it never carries ctx.error: a
            // function whose error went unsuppressed stopped the pipeline before this point.
            Object after = callHandler(resolverStage, RESPONSE, previousResult, null, null);
            return result(returned ? earlyReturnValue : after);
        }

        private List<String> pipelineFunctionIds() {
            Map<String, Object> pipelineConfig = resolver.getPipelineConfig();
            Object functions = pipelineConfig == null ? null : pipelineConfig.get("functions");
            List<String> ids = new ArrayList<>();
            if (functions instanceof List<?> list) {
                list.forEach(id -> ids.add(String.valueOf(id)));
            }
            return ids;
        }

        private FunctionConfiguration function(String functionId) {
            try {
                return appSyncService.getFunction(apiId, functionId);
            } catch (AwsException e) {
                // Only "no such function" is that. A 409 from a schema mid-recreation, or any other
                // control-plane failure, has to surface as itself or the real cause is lost.
                if (e.getHttpStatus() != 404) {
                    throw e;
                }
                throw new AwsException("InternalFailureException",
                        "Pipeline resolver " + resolver.getTypeName() + "." + resolver.getFieldName()
                                + " names function " + functionId + ", which does not exist", 500);
            }
        }

        private Invocation invokeDataSource(String dataSourceName, Object request) {
            if (dataSourceName == null || dataSourceName.isBlank()) {
                // A pipeline resolver's own before/after steps have no data source, and neither do
                // the local functions AppSync allows: the request is the result.
                return new Invocation(request, null);
            }
            DataSource dataSource;
            try {
                dataSource = appSyncService.getDataSource(apiId, dataSourceName);
            } catch (AwsException e) {
                // Not a data source error the resolver could handle (the data source it names does
                // not exist), so this fails the field rather than reaching ctx.error.
                throw new AwsException("InternalFailureException",
                        "Data source " + dataSourceName + " does not exist on API " + apiId, 500);
            }
            try {
                return new Invocation(dataSourceInvokers.invoke(dataSource, request, regionOf(dataSource)),
                        null);
            } catch (AwsException e) {
                // What the store or function answered: the resolver sees it as ctx.error and decides.
                // Deliberately only AwsException: an unexpected RuntimeException is a defect in the
                // emulator, and routing it somewhere a resolver can swallow it would hide it.
                LOG.debugv("Data source {0} failed for {1}.{2}: {3}", dataSourceName,
                        resolver.getTypeName(), resolver.getFieldName(), e.getMessage());
                return new Invocation(null,
                        new JsEvaluation.JsError(e.getMessage(), e.getErrorCode(), null, null));
            }
        }

        /**
         * One data source call's outcome: its result, or the error {@code ctx.error} carries into the
         * response handler. Never both.
         */
        private record Invocation(Object result, JsEvaluation.JsError error) {}

        private String regionOf(DataSource dataSource) {
            String arn = dataSource.getDataSourceArn();
            if (arn != null && arn.startsWith("arn:")) {
                String[] parts = arn.split(":", 5);
                if (parts.length > 3 && !parts[3].isBlank()) {
                    return parts[3];
                }
            }
            return null;
        }

        /**
         * Calls one handler. A handler the module does not export is not an error: AppSync treats a
         * missing {@code response()} as "pass the data source result through", which is
         * {@code passThrough} here.
         */
        private Object callHandler(Stage stage, String handler, Object result,
                                  JsEvaluation.JsError error, Object passThrough) {
            if (stage.vtl()) {
                // Said out loud rather than resolved to null: a null that means "not implemented"
                // is indistinguishable from a null that means "no rows".
                throw new AwsException("InternalFailureException",
                        "Floci runs APPSYNC_JS resolvers only; " + resolver.getTypeName() + "."
                                + resolver.getFieldName() + " uses VTL mapping templates", 500);
            }
            String code = stage.code();
            if (code == null || code.isBlank()) {
                // Neither code nor templates: nothing to run, and nothing that could have decided
                // to suppress a data source error either.
                if (error != null) {
                    throw new ResolverRaisedException(new AppSyncResolverError(error.message(),
                            error.type(), error.data(), error.errorInfo(), path));
                }
                return passThrough;
            }

            JsEvaluation evaluation = jsRuntime.evaluate(code, handler, context(result, error));
            collect(evaluation.appendedErrors());
            if (evaluation.missingHandler()) {
                // Nothing here could have decided to suppress the data source error, so it stands.
                if (error != null) {
                    throw new ResolverRaisedException(new AppSyncResolverError(error.message(),
                            error.type(), error.data(), error.errorInfo(), path));
                }
                return passThrough;
            }
            if (evaluation.failed()) {
                JsEvaluation.JsError raised = evaluation.error();
                // util.error() is the resolver deliberately failing the field, so it becomes the
                // field's error with the type the resolver chose, and the pipeline stops. This is
                // also the path a resolver takes when it re-raises ctx.error.
                throw new ResolverRaisedException(new AppSyncResolverError(raised.message(),
                        raised.type(), raised.data(), raised.errorInfo(), path));
            }
            if (evaluation.stash() != null) {
                this.stash = new LinkedHashMap<>(evaluation.stash());
            }
            if (evaluation.earlyReturn()) {
                this.returned = true;
                this.earlyReturnValue = evaluation.result();
            }
            return evaluation.result();
        }

        private void collect(List<JsEvaluation.JsError> appended) {
            if (appended == null) {
                return;
            }
            appended.forEach(error -> errors.add(new AppSyncResolverError(error.message(),
                    error.type(), error.data(), error.errorInfo(), path)));
        }

        /** The {@code ctx} a handler receives. */
        private Map<String, Object> context(Object result, JsEvaluation.JsError error) {
            Map<String, Object> context = new LinkedHashMap<>();
            Map<String, Object> arguments = environment.getArguments() == null
                    ? Map.of() : environment.getArguments();
            context.put("arguments", arguments);
            context.put("args", arguments);
            context.put("source", environment.getSource());
            context.put("stash", stash);
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("result", previousResult);
            context.put("prev", prev);
            context.put("identity", environment.getGraphQlContext().get("identity"));
            context.put("request", requestContext());
            context.put("info", info());
            context.put("env", environmentVariables());
            if (result != null) {
                context.put("result", result);
            }
            if (error != null) {
                // AppSync's shape: message and type, which is what a resolver forwards to
                // util.error(ctx.error.message, ctx.error.type).
                Map<String, Object> contextError = new LinkedHashMap<>();
                contextError.put("message", error.message());
                contextError.put("type", error.type());
                context.put("error", contextError);
            }
            return context;
        }

        private Map<String, Object> requestContext() {
            Map<String, Object> request = new LinkedHashMap<>();
            // Headers are not on the GraphQL context, so ctx.request.headers is empty rather than
            // wrong. authType is, and a resolver keying off it behaves as it would on AWS.
            request.put("headers", Map.of());
            request.put("authType", environment.getGraphQlContext().get("authType"));
            request.put("domainName", null);
            return request;
        }

        private Map<String, Object> info() {
            Map<String, Object> info = new LinkedHashMap<>();
            // The resolver's own field name rather than the step info's: identical in every case
            // that reaches here, and available whether or not the caller supplied step info.
            info.put("fieldName", resolver.getFieldName());
            info.put("parentTypeName", resolver.getTypeName());
            info.put("variables", environment.getVariables() == null ? Map.of() : environment.getVariables());
            List<String> selectionSet = new ArrayList<>();
            if (environment.getSelectionSet() != null) {
                for (SelectedField field : environment.getSelectionSet().getFields()) {
                    selectionSet.add(field.getQualifiedName());
                }
            }
            info.put("selectionSetList", selectionSet);
            info.put("selectionSetGraphQL", null);
            return info;
        }

        private Map<String, String> environmentVariables() {
            try {
                Map<String, String> variables = appSyncService.getEnvironmentVariables(apiId);
                return variables == null ? Map.of() : variables;
            } catch (AwsException e) {
                return Map.of();
            }
        }

        /** The field's outcome when a resolver raised an error: no data, every error collected. */
        private DataFetcherResult<Object> failure(AppSyncResolverError raised) {
            DataFetcherResult.Builder<Object> builder = DataFetcherResult.newResult();
            errors.forEach(builder::error);
            builder.error(raised);
            return builder.build();
        }

        private DataFetcherResult<Object> result(Object data) {
            DataFetcherResult.Builder<Object> builder = DataFetcherResult.newResult().data(data);
            errors.forEach(builder::error);
            return builder.build();
        }
    }

    /**
     * Where in the response an error belongs. The execution path is preferred, since it names the
     * exact list element for a field resolved inside a list, and the field name stands in when a
     * caller drives the executor without full execution step info.
     */
    private static List<Object> errorPath(DataFetchingEnvironment environment, Resolver resolver) {
        try {
            // getExecutionStepInfo() itself throws when the environment carries none, rather than
            // answering null, so this cannot be a null check.
            if (environment.getExecutionStepInfo() != null
                    && environment.getExecutionStepInfo().getPath() != null) {
                return environment.getExecutionStepInfo().getPath().toList();
            }
        } catch (RuntimeException e) {
            LOG.tracev("No execution step info for {0}.{1}; using the field name as the error path",
                    resolver.getTypeName(), resolver.getFieldName());
        }
        return List.of(resolver.getFieldName());
    }

    /** Carries a resolver-raised error out of the pipeline to the field's result. */
    static final class ResolverRaisedException extends RuntimeException {
        private final transient AppSyncResolverError error;

        ResolverRaisedException(AppSyncResolverError error) {
            super(error.message());
            this.error = error;
        }

        AppSyncResolverError error() {
            return error;
        }
    }
}
