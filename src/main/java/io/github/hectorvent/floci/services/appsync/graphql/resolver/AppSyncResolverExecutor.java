package io.github.hectorvent.floci.services.appsync.graphql.resolver;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.AppSyncService;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncVtlContext;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncVtlEngine;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncVtlResult;
import io.github.hectorvent.floci.services.appsync.graphql.datasource.AppSyncDataSourceInvokers;
import io.github.hectorvent.floci.services.appsync.graphql.js.AppSyncJsRuntime;
import io.github.hectorvent.floci.services.appsync.graphql.js.JsEvaluation;
import io.github.hectorvent.floci.services.appsync.graphql.util.VtlErrorSignal;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
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
 * <p>Two things stop resolver execution early. {@code runtime.earlyReturn(value)} or VTL
 * {@code #return(value)} makes {@code value} the field's result immediately, skipping everything
 * left including the after step, and {@code util.error()} fails the field.
 * {@code util.appendError} does neither: it collects errors that are returned <em>alongside</em>
 * the data, which is why a {@link ResolverOutcome} carries both rather than being a bare value.
 */
@ApplicationScoped
public class AppSyncResolverExecutor {

    private static final Logger LOG = Logger.getLogger(AppSyncResolverExecutor.class);
    private static final String REQUEST = "request";
    private static final String RESPONSE = "response";

    private final AppSyncService appSyncService;
    private final AppSyncJsRuntime jsRuntime;
    private final AppSyncDataSourceInvokers dataSourceInvokers;
    private final AppSyncVtlEngine vtlEngine;
    private final ObjectMapper objectMapper;

    @Inject
    public AppSyncResolverExecutor(AppSyncService appSyncService,
                                   AppSyncJsRuntime jsRuntime,
                                   AppSyncDataSourceInvokers dataSourceInvokers,
                                   AppSyncVtlEngine vtlEngine,
                                   ObjectMapper objectMapper) {
        this.appSyncService = appSyncService;
        this.jsRuntime = jsRuntime;
        this.dataSourceInvokers = dataSourceInvokers;
        this.vtlEngine = vtlEngine;
        this.objectMapper = objectMapper;
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

    public ResolverOutcome execute(Resolver resolver, ResolverInvocation invocation) {
        Execution execution = new Execution(resolver, invocation);
        try {
            return execution.run();
        } catch (ResolverRaisedException e) {
            return execution.failure(e.error());
        } catch (AwsException e) {
            // A failure of the emulator itself (no sidecar, an unusable data source) is reported on
            // the field rather than thrown: one broken field should not abort the whole operation.
            LOG.debugv("Resolver {0}.{1} failed: {2}", resolver.getTypeName(), resolver.getFieldName(),
                    e.getMessage());
            return new ResolverOutcome(null, new AppSyncResolverError(e.getMessage(), e.getErrorCode(),
                    null, null, invocation.path()), List.of());
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
    private record Stage(String code, ResolverRuntimeName runtime, boolean vtl,
                         String requestTemplate, String responseTemplate) {

        static Stage of(Resolver resolver) {
            return new Stage(resolver.getCode(), resolver.getRuntime() == null
                    ? null : resolver.getRuntime().getName(),
                    isVtl(resolver.getCode(), resolver.getRuntime() == null
                                    ? null : resolver.getRuntime().getName(),
                            resolver.getRequestMappingTemplate(), resolver.getResponseMappingTemplate()),
                    resolver.getRequestMappingTemplate(), resolver.getResponseMappingTemplate());
        }

        static Stage of(FunctionConfiguration function) {
            return new Stage(function.getCode(), function.getRuntime() == null
                    ? null : function.getRuntime().getName(),
                    isVtl(function.getCode(), function.getRuntime() == null
                                    ? null : function.getRuntime().getName(),
                            function.getRequestMappingTemplate(), function.getResponseMappingTemplate()),
                    function.getRequestMappingTemplate(), function.getResponseMappingTemplate());
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
        private final ResolverInvocation invocation;
        private final List<Object> path;
        private final List<AppSyncResolverError> errors = new ArrayList<>();

        private Map<String, Object> stash = new LinkedHashMap<>();
        private Object previousResult;
        /** Set when a handler called runtime.earlyReturn: the pipeline stops and this is the value. */
        private boolean returned;
        private Object earlyReturnValue;

        private Execution(Resolver resolver, ResolverInvocation invocation) {
            this.apiId = invocation.apiId();
            this.resolver = resolver;
            this.invocation = invocation;
            this.path = invocation.path();
        }

        private ResolverOutcome run() {
            if (resolver.getKind() == ResolverKind.PIPELINE) {
                return runPipeline();
            }
            return runUnit();
        }

        private ResolverOutcome runUnit() {
            Stage stage = Stage.of(resolver);
            DataSource vtlDataSource = stage.vtl()
                    ? requireVtlNoneDataSource(resolver.getDataSourceName()) : null;
            Object request = callHandler(stage, REQUEST, null, null, null);
            if (returned) {
                return result(earlyReturnValue);
            }
            Invocation invocation = stage.vtl()
                    ? invokeDataSource(vtlDataSource, normalizeVtlNoneRequest(request))
                    : invokeDataSource(resolver.getDataSourceName(), request);
            Object response = callHandler(stage, RESPONSE,
                    invocation.result(), invocation.error(), invocation.result());
            return result(returned ? earlyReturnValue : response);
        }

        private ResolverOutcome runPipeline() {
            // The before step's job is usually to fill ctx.stash for the functions; its return value
            // is not the field's result, but it is what the first function sees as ctx.prev.result.
            Stage resolverStage = Stage.of(resolver);
            rejectVtlPipelineStage(resolverStage, "resolver");
            Object before = callHandler(resolverStage, REQUEST, null, null, null);
            if (returned) {
                return result(earlyReturnValue);
            }
            List<FunctionConfiguration> functions = pipelineFunctions();
            previousResult = before;

            for (FunctionConfiguration function : functions) {
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

        private List<FunctionConfiguration> pipelineFunctions() {
            List<FunctionConfiguration> functions = new ArrayList<>();
            for (String functionId : pipelineFunctionIds()) {
                FunctionConfiguration function = function(functionId);
                rejectVtlPipelineStage(Stage.of(function), "function " + function.getName());
                functions.add(function);
            }
            return functions;
        }

        private void rejectVtlPipelineStage(Stage stage, String stageName) {
            if (stage.vtl()) {
                throw new AwsException("UnsupportedOperation",
                        "Floci does not yet execute VTL " + stageName + " stages in PIPELINE resolvers", 400);
            }
        }

        private DataSource requireVtlNoneDataSource(String dataSourceName) {
            if (dataSourceName == null || dataSourceName.isBlank()) {
                throw new AwsException("UnsupportedOperation",
                        "Floci currently executes VTL UNIT resolvers only with NONE data sources", 400);
            }
            DataSource dataSource = dataSource(dataSourceName);
            if (dataSource.getType() != DataSourceType.NONE) {
                throw new AwsException("UnsupportedOperation",
                        "Floci currently executes VTL UNIT resolvers only with NONE data sources; "
                                + dataSourceName + " uses " + dataSource.getType(), 400);
            }
            return dataSource;
        }

        private Invocation invokeDataSource(String dataSourceName, Object request) {
            if (dataSourceName == null || dataSourceName.isBlank()) {
                // A pipeline resolver's own before/after steps have no data source, and neither do
                // the local functions AppSync allows: the request is the result.
                return new Invocation(request, null);
            }
            return invokeDataSource(dataSource(dataSourceName), request);
        }

        private DataSource dataSource(String dataSourceName) {
            DataSource dataSource;
            try {
                dataSource = appSyncService.getDataSource(apiId, dataSourceName);
            } catch (AwsException e) {
                // Not a data source error the resolver could handle (the data source it names does
                // not exist), so this fails the field rather than reaching ctx.error.
                throw new AwsException("InternalFailureException",
                        "Data source " + dataSourceName + " does not exist on API " + apiId, 500);
            }
            if (dataSource == null) {
                throw new AwsException("InternalFailureException",
                        "Data source " + dataSourceName + " does not exist on API " + apiId, 500);
            }
            return dataSource;
        }

        private Invocation invokeDataSource(DataSource dataSource, Object request) {
            try {
                return new Invocation(dataSourceInvokers.invoke(dataSource, request, regionOf(dataSource)),
                        null);
            } catch (AwsException e) {
                // What the store or function answered: the resolver sees it as ctx.error and decides.
                // Deliberately only AwsException: an unexpected RuntimeException is a defect in the
                // emulator, and routing it somewhere a resolver can swallow it would hide it.
                LOG.debugv("Data source {0} failed for {1}.{2}: {3}", dataSource.getName(),
                        resolver.getTypeName(), resolver.getFieldName(), e.getMessage());
                return new Invocation(null,
                        new JsEvaluation.JsError(e.getMessage(), e.getErrorCode(), null, null));
            }
        }

        private Object normalizeVtlNoneRequest(Object request) {
            if (request instanceof Map<?, ?> map && !map.containsKey("payload")) {
                return null;
            }
            return request;
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
                return callVtlHandler(stage, handler, result, error);
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

        private Object callVtlHandler(Stage stage, String handler, Object result,
                                      JsEvaluation.JsError error) {
            String template = REQUEST.equals(handler) ? stage.requestTemplate() : stage.responseTemplate();
            if (template == null || template.isBlank()) {
                throw mappingTemplateError("VTL " + handler + " mapping template is required");
            }

            Map<String, Object> previous = new LinkedHashMap<>();
            previous.put("result", previousResult);
            AppSyncVtlContext context = AppSyncVtlContext.builder(objectMapper)
                    .arguments(invocation.arguments())
                    .source(invocation.source())
                    .identity(identityMap())
                    .request(requestContext())
                    .info(info())
                    .stash(stash)
                    .prev(previous)
                    .result(result)
                    .error(errorMap(error))
                    .authType(invocation.authType())
                    .build();
            AppSyncVtlResult evaluation;
            try {
                evaluation = vtlEngine.evaluate(template, context);
            } catch (RuntimeException e) {
                throw mappingTemplateError("VTL mapping template evaluation failed: " + rootMessage(e));
            }
            collectVtl(evaluation.appendedErrors());
            if (evaluation.hasError()) {
                VtlErrorSignal raised = evaluation.error();
                throw resolverError(raised.getMessage(), raised.getErrorType(),
                        raised.getData(), raised.getErrorInfo());
            }

            if (evaluation.returned()) {
                this.returned = true;
                this.earlyReturnValue = evaluation.output();
                return evaluation.output();
            }

            Object value = parseRenderedJson(evaluation.output(), handler);
            if (REQUEST.equals(handler)) {
                validateVtlRequest(value);
            }
            return value;
        }

        private Map<String, Object> identityMap() {
            if (invocation.identity() instanceof Map<?, ?> values) {
                Map<String, Object> identity = new LinkedHashMap<>();
                values.forEach((key, value) -> identity.put(String.valueOf(key), value));
                return identity;
            }
            return null;
        }

        private Map<String, Object> errorMap(JsEvaluation.JsError error) {
            if (error == null) {
                return null;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("message", error.message());
            value.put("type", error.type());
            return value;
        }

        private Object parseRenderedJson(Object output, String handler) {
            if (!(output instanceof CharSequence rendered)) {
                return output;
            }
            String text = rendered.toString().trim();
            if (text.isEmpty()) {
                return null;
            }
            try {
                return objectMapper.readValue(text, Object.class);
            } catch (JsonProcessingException e) {
                throw mappingTemplateError("VTL " + handler + " mapping template did not render valid JSON");
            }
        }

        private void validateVtlRequest(Object request) {
            if (!(request instanceof Map<?, ?> map)) {
                throw mappingTemplateError("VTL request mapping template must render a JSON object");
            }
            Object version = map.get("version");
            if (!"2018-05-29".equals(version)) {
                throw mappingTemplateError("VTL request mapping template must use version 2018-05-29");
            }
            for (Object key : map.keySet()) {
                if (!"version".equals(key) && !"payload".equals(key)) {
                    throw mappingTemplateError("VTL NONE request mapping template supports only version and payload");
                }
            }
        }

        private ResolverRaisedException mappingTemplateError(String message) {
            return resolverError(message, "MappingTemplate", null, null);
        }

        private ResolverRaisedException resolverError(String message, String type,
                                                      Object data, Object errorInfo) {
            return new ResolverRaisedException(new AppSyncResolverError(message, type, data,
                    errorInfo, path));
        }

        private String rootMessage(RuntimeException failure) {
            Throwable cause = failure;
            while (cause.getCause() != null) {
                cause = cause.getCause();
            }
            return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        }

        private void collectVtl(List<Map<String, Object>> appended) {
            if (appended == null) {
                return;
            }
            appended.forEach(error -> errors.add(new AppSyncResolverError(
                    String.valueOf(error.get("message")),
                    String.valueOf(error.getOrDefault("type", "Unknown")),
                    error.get("data"), error.get("errorInfo"), path)));
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
            Map<String, Object> arguments = invocation.arguments();
            context.put("arguments", arguments);
            context.put("args", arguments);
            context.put("source", invocation.source());
            context.put("stash", stash);
            Map<String, Object> prev = new LinkedHashMap<>();
            prev.put("result", previousResult);
            context.put("prev", prev);
            context.put("identity", invocation.identity());
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
            // The callback does not carry the client's headers, so ctx.request.headers is empty
            // rather than wrong. authType it does, and a resolver keying off it behaves as on AWS.
            request.put("headers", Map.of());
            request.put("authType", invocation.authType());
            request.put("domainName", null);
            return request;
        }

        private Map<String, Object> info() {
            Map<String, Object> info = new LinkedHashMap<>();
            // The resolver's own field name rather than the step info's: identical in every case
            // that reaches here, and available whether or not the caller supplied step info.
            info.put("fieldName", resolver.getFieldName());
            info.put("parentTypeName", resolver.getTypeName());
            info.put("variables", invocation.variables());
            // Already qualified names (slash-separated for a nested selection) when they reach here:
            // the sidecar builds them from graphql-java's own SelectedField.getQualifiedName().
            info.put("selectionSetList", invocation.selectionSetList());
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
        private ResolverOutcome failure(AppSyncResolverError raised) {
            return new ResolverOutcome(null, raised, errors);
        }

        private ResolverOutcome result(Object data) {
            return new ResolverOutcome(data, null, errors);
        }
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
