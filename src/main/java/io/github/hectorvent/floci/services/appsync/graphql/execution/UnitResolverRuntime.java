package io.github.hectorvent.floci.services.appsync.graphql.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncVtlContext;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncVtlEngine;
import io.github.hectorvent.floci.services.appsync.graphql.AppSyncVtlResult;
import io.github.hectorvent.floci.services.appsync.graphql.execution.datasource.DataSourceInvoker;
import io.github.hectorvent.floci.services.appsync.graphql.execution.datasource.DataSourceResult;
import io.github.hectorvent.floci.services.appsync.graphql.execution.datasource.DynamoDbDataSourceInvoker;
import io.github.hectorvent.floci.services.appsync.graphql.execution.datasource.InvalidRequestDocumentException;
import io.github.hectorvent.floci.services.appsync.graphql.execution.datasource.NoneDataSourceInvoker;
import io.github.hectorvent.floci.services.appsync.graphql.execution.datasource.UnsupportedDataSourceOperationException;
import io.github.hectorvent.floci.services.appsync.graphql.util.VtlErrorSignal;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.Resolver;
import io.github.hectorvent.floci.services.appsync.model.ResolverKind;
import io.github.hectorvent.floci.services.appsync.model.ResolverRuntimeName;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes a VTL unit resolver: request mapping template → data source → response mapping template,
 * with the {@code 2018-05-29} execution semantics (the response template always runs, sees the data
 * source error in {@code $ctx.error}, and decides whether to raise, append or ignore it).
 */
@ApplicationScoped
public class UnitResolverRuntime {

    static final String SUPPORTED_TEMPLATE_VERSION = "2018-05-29";

    private final AppSyncVtlEngine vtlEngine;
    private final ObjectMapper objectMapper;
    private final NoneDataSourceInvoker noneInvoker;
    private final DynamoDbDataSourceInvoker dynamoDbInvoker;

    @Inject
    public UnitResolverRuntime(AppSyncVtlEngine vtlEngine, ObjectMapper objectMapper,
                               NoneDataSourceInvoker noneInvoker, DynamoDbDataSourceInvoker dynamoDbInvoker) {
        this.vtlEngine = vtlEngine;
        this.objectMapper = objectMapper;
        this.noneInvoker = noneInvoker;
        this.dynamoDbInvoker = dynamoDbInvoker;
    }

    public FieldOutcome execute(ResolverInvocation invocation) {
        Resolver resolver = invocation.resolver();
        if (resolver.getKind() == ResolverKind.PIPELINE) {
            return FieldOutcome.ofError(FieldError.of("UnsupportedOperation",
                    "Pipeline resolvers are not yet supported by Floci"));
        }
        if (isAppSyncJs(resolver)) {
            return FieldOutcome.ofError(FieldError.of("UnsupportedOperation",
                    "APPSYNC_JS resolvers are not yet supported by Floci; use VTL mapping templates"));
        }
        DataSource dataSource = invocation.dataSource();
        if (dataSource == null) {
            return FieldOutcome.ofError(FieldError.of("InternalFailure",
                    "Data source " + resolver.getDataSourceName() + " not found"));
        }
        DataSourceInvoker invoker = invokerFor(dataSource);
        if (invoker == null) {
            return FieldOutcome.ofError(FieldError.of("UnsupportedOperation",
                    "Data source type " + dataSource.getType() + " is not yet supported by Floci"));
        }
        if (isBlank(resolver.getRequestMappingTemplate())) {
            return FieldOutcome.ofError(FieldError.of("MappingTemplate",
                    "Request mapping template is required for VTL unit resolvers"));
        }
        if (isBlank(resolver.getResponseMappingTemplate())) {
            return FieldOutcome.ofError(FieldError.of("MappingTemplate",
                    "Response mapping template is required for VTL unit resolvers"));
        }

        // Both templates share the same $ctx.stash and accumulate appended errors in order.
        Map<String, Object> stash = new HashMap<>();
        List<FieldError> appended = new ArrayList<>();

        // Request template
        AppSyncVtlResult request;
        try {
            request = vtlEngine.evaluate(resolver.getRequestMappingTemplate(),
                    baseContext(invocation, stash).build());
        } catch (RuntimeException e) {
            return FieldOutcome.ofError(templateFailure(e), appended);
        }
        appended.addAll(toFieldErrors(request.appendedErrors()));
        if (request.hasError()) {
            return FieldOutcome.ofError(fromSignal(request.error()), appended);
        }
        if (request.returned()) {
            return FieldOutcome.ofData(request.output(), appended);
        }
        Map<String, Object> document;
        try {
            document = parseRequestDocument((String) request.output());
        } catch (InvalidRequestDocumentException e) {
            return FieldOutcome.ofError(FieldError.of("MappingTemplate", e.getMessage()), appended);
        }

        // Data source. Emulator capability gaps are terminal here: under 2018-05-29 the response
        // template may legitimately ignore $ctx.error, which must never hide an unimplemented operation.
        DataSourceResult dataSourceResult;
        try {
            dataSourceResult = invoker.invoke(dataSource, document, invocation.requestContext());
        } catch (InvalidRequestDocumentException e) {
            return FieldOutcome.ofError(FieldError.of("MappingTemplate", e.getMessage()), appended);
        } catch (UnsupportedDataSourceOperationException e) {
            return FieldOutcome.ofError(FieldError.of("UnsupportedOperation", e.getMessage()), appended);
        }

        // Response template
        AppSyncVtlResult response;
        try {
            response = vtlEngine.evaluate(resolver.getResponseMappingTemplate(),
                    baseContext(invocation, stash)
                            .result(dataSourceResult.result())
                            .error(toErrorMap(dataSourceResult.error()))
                            .build());
        } catch (RuntimeException e) {
            return FieldOutcome.ofError(templateFailure(e), appended);
        }
        appended.addAll(toFieldErrors(response.appendedErrors()));
        if (response.hasError()) {
            return FieldOutcome.ofError(fromSignal(response.error()), appended);
        }
        if (response.returned()) {
            return FieldOutcome.ofData(response.output(), appended);
        }
        String rendered = (String) response.output();
        if (isBlank(rendered)) {
            return FieldOutcome.ofData(null, appended);
        }
        try {
            return FieldOutcome.ofData(StrictJson.parse(rendered), appended);
        } catch (IOException e) {
            return FieldOutcome.ofError(FieldError.of("MappingTemplate",
                    "Unable to parse the JSON document: " + rootMessage(e)), appended);
        }
    }

    private AppSyncVtlContext.Builder baseContext(ResolverInvocation invocation, Map<String, Object> stash) {
        GraphQlRequestContext rc = invocation.requestContext();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("headers", rc.requestHeaders() != null ? rc.requestHeaders() : Map.of());
        return AppSyncVtlContext.builder(objectMapper)
                .arguments(invocation.arguments())
                .source(invocation.source())
                .identity(rc.identity())
                .request(request)
                .info(invocation.info())
                .stash(stash)
                .authType(rc.authTypeDisplay());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRequestDocument(String rendered) {
        if (isBlank(rendered)) {
            throw new InvalidRequestDocumentException("Request mapping template evaluated to an empty document");
        }
        Object parsed;
        try {
            parsed = StrictJson.parse(rendered);
        } catch (IOException e) {
            throw new InvalidRequestDocumentException("Unable to parse the JSON document: " + rootMessage(e));
        }
        if (!(parsed instanceof Map<?, ?>)) {
            throw new InvalidRequestDocumentException("Request mapping template must evaluate to a JSON object");
        }
        Map<String, Object> document = (Map<String, Object>) parsed;
        Object version = document.get("version");
        if (!SUPPORTED_TEMPLATE_VERSION.equals(version)) {
            throw new InvalidRequestDocumentException(version == null
                    ? "Mapping template version is required; Floci supports " + SUPPORTED_TEMPLATE_VERSION
                    : "Unsupported mapping template version '" + version + "'; Floci supports " + SUPPORTED_TEMPLATE_VERSION);
        }
        return document;
    }

    private DataSourceInvoker invokerFor(DataSource dataSource) {
        if (dataSource.getType() == null) {
            return null;
        }
        return switch (dataSource.getType()) {
            case NONE -> noneInvoker;
            case AMAZON_DYNAMODB -> dynamoDbInvoker;
            default -> null;
        };
    }

    private static boolean isAppSyncJs(Resolver resolver) {
        if (resolver.getRuntime() != null && resolver.getRuntime().getName() == ResolverRuntimeName.APPSYNC_JS) {
            return true;
        }
        return resolver.getCode() != null && !resolver.getCode().isBlank();
    }

    private static Map<String, Object> toErrorMap(FieldError error) {
        if (error == null) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("message", error.message());
        map.put("type", error.errorType());
        map.put("data", error.data());
        return map;
    }

    private static FieldError fromSignal(VtlErrorSignal signal) {
        return new FieldError(signal.getErrorType() != null ? signal.getErrorType() : "Unknown",
                signal.getMessage(), signal.getData(), signal.getErrorInfo());
    }

    private static List<FieldError> toFieldErrors(List<Map<String, Object>> appended) {
        List<FieldError> errors = new ArrayList<>();
        if (appended == null) {
            return errors;
        }
        for (Map<String, Object> entry : appended) {
            Object type = entry.get("type");
            errors.add(new FieldError(type != null ? type.toString() : "Unknown",
                    String.valueOf(entry.get("message")), entry.get("data"), entry.get("errorInfo")));
        }
        return errors;
    }

    private static FieldError templateFailure(RuntimeException e) {
        return FieldError.of("MappingTemplate", rootMessage(e));
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
