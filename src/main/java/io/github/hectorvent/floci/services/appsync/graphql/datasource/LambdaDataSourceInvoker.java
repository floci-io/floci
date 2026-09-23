package io.github.hectorvent.floci.services.appsync.graphql.datasource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.appsync.model.DataSource;
import io.github.hectorvent.floci.services.appsync.model.DataSourceType;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code AWS_LAMBDA} data source: invokes the function synchronously and returns its parsed
 * payload.
 *
 * <p>The request is AppSync's {@code {operation: "Invoke", payload: …}}, or, for
 * {@code BatchInvoke}, a list payload the function answers with a list in the same order. A
 * function that returns a handled error (a Lambda {@code errorMessage}) fails the field rather than
 * resolving to the error object, which is what AppSync does with an unhandled function error.
 */
@ApplicationScoped
public class LambdaDataSourceInvoker implements AppSyncDataSourceInvoker {

    private final LambdaService lambdaService;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaDataSourceInvoker(LambdaService lambdaService, ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.objectMapper = objectMapper;
    }

    @Override
    public DataSourceType type() {
        return DataSourceType.AWS_LAMBDA;
    }

    @Override
    public Object invoke(DataSource dataSource, Object request, String region) {
        String functionArn = functionArn(dataSource);
        Object payload = payloadOf(request);
        if ("BatchInvoke".equals(operationOf(request)) && payload instanceof List<?> batch) {
            List<Object> results = new ArrayList<>();
            // One invocation with the whole list, as AppSync does: the function is expected to
            // answer with a list of the same length, in order.
            Object answered = invokeOnce(functionArn, batch, region);
            if (answered instanceof List<?> list) {
                return list;
            }
            results.add(answered);
            return results;
        }
        return invokeOnce(functionArn, payload, region);
    }

    private Object invokeOnce(String functionArn, Object payload, String region) {
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload == null ? Map.of() : payload);
        } catch (Exception e) {
            throw new AwsException("InternalFailureException",
                    "Could not serialise the Lambda data source payload: " + e.getMessage(), 500);
        }
        InvokeResult result =
                lambdaService.invokeArn(functionArn, body, InvocationType.RequestResponse);
        String response = result.getPayload() == null
                ? null
                : new String(result.getPayload(), StandardCharsets.UTF_8);
        if (result.getFunctionError() != null && !result.getFunctionError().isBlank()) {
            throw new AwsException("LambdaExecutionException",
                    "The Lambda data source function failed: " + response, 400);
        }
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            return objectMapper.convertValue(objectMapper.readTree(response), Object.class);
        } catch (Exception e) {
            // A function that answered something other than JSON: hand back the raw text rather
            // than failing, since a String-typed field is a legitimate shape.
            return response;
        }
    }

    private String functionArn(DataSource dataSource) {
        Map<String, Object> config = dataSource.getLambdaConfig();
        Object arn = config == null ? null : config.get("lambdaFunctionArn");
        if (arn == null || String.valueOf(arn).isBlank()) {
            throw new AwsException("InternalFailureException",
                    "Data source " + dataSource.getName() + " has no lambdaConfig.lambdaFunctionArn", 500);
        }
        return String.valueOf(arn);
    }

    private String operationOf(Object request) {
        return request instanceof Map<?, ?> map && map.get("operation") != null
                ? String.valueOf(map.get("operation"))
                : "Invoke";
    }

    /**
     * AppSync sends {@code {operation, payload}}; a resolver that returns a bare object with no
     * {@code operation} is treated as the payload itself, which is how the older function-request
     * shape behaved.
     */
    private Object payloadOf(Object request) {
        if (request instanceof Map<?, ?> map && map.containsKey("payload")) {
            return map.get("payload");
        }
        return request;
    }
}
