package io.floci.aws.services.redshift.spectrum;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.JsonNodeFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServicePrincipals;
import io.floci.aws.services.iam.RoleResourceAccessAuthorizer;
import io.floci.aws.services.iam.ServiceRoleAuthorizer;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

/**
 * Calls the Lambda function behind a scalar Lambda UDF with batches of argument rows, over the JSON interface
 * Redshift documents: a request of {@code request_id}, {@code cluster}, {@code user}, {@code database},
 * {@code external_function}, {@code query_id}, {@code num_records} and {@code arguments}, and a response of
 * {@code success}, {@code error_msg}, {@code num_records} and {@code results}.
 *
 * <p>Rows go in and results come back as the text PostgreSQL holds the values as, one result per row in the
 * order of the rows, so the evaluator can read them straight from and into the backend. A batch holds at most the
 * function's {@code MAX_BATCH_ROWS} rows and about its {@code MAX_BATCH_SIZE} bytes of arguments; a throttled
 * call is retried for the function's {@code RETRY_TIMEOUT}. The function's role must be allowed to invoke the
 * Lambda function when IAM enforcement is on.
 */
@ApplicationScoped
public class LambdaUdfInvoker {

    private static final Logger LOG = Logger.getLogger(LambdaUdfInvoker.class);
    private static final String REDSHIFT_PRINCIPAL = ServicePrincipals.of("redshift");
    private static final String INVOKE_ACTION = "lambda:InvokeFunction";
    private static final String FUNCTION_ERROR = "XX000";
    private static final String NOT_AUTHORIZED = "42501";
    private static final long FIRST_RETRY_DELAY_MILLIS = 50;
    private static final long MAX_RETRY_DELAY_MILLIS = 1_000;
    // floats stay exact: a numeric result read as a double would lose digits, and stripping zeroes loses scale
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
            .build();
    private static final Pattern ACCOUNT_FUNCTION = Pattern.compile("\\d{12}:function:.+");

    private final LambdaService lambda;
    private final ServiceRoleAuthorizer authorizer;
    private final RoleResourceAccessAuthorizer roleAccess;
    private final EmulatorConfig config;
    private final RegionResolver regionResolver;
    private final Clock clock;
    private final LongConsumer sleeper;

    @Inject
    public LambdaUdfInvoker(LambdaService lambda, ServiceRoleAuthorizer authorizer,
                            RoleResourceAccessAuthorizer roleAccess, EmulatorConfig config,
                            RegionResolver regionResolver, Clock clock) {
        this(lambda, authorizer, roleAccess, config, regionResolver, clock, LambdaUdfInvoker::sleep);
    }

    LambdaUdfInvoker(LambdaService lambda, ServiceRoleAuthorizer authorizer,
                     RoleResourceAccessAuthorizer roleAccess, EmulatorConfig config, RegionResolver regionResolver,
                     Clock clock, LongConsumer sleeper) {
        this.lambda = lambda;
        this.authorizer = authorizer;
        this.roleAccess = roleAccess;
        this.config = config;
        this.regionResolver = regionResolver;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /**
     * Calls the function for every row and returns one result per row, in order, each as the text of the value or
     * null for SQL NULL.
     *
     * @param clusterRegion the Region of the cluster, which Redshift calls the function from; null where the cluster
     *                      is not one Floci knows, which leaves the default Region
     * @param user the database user whose query makes the call
     * @param queryId the id of that query
     * @param rows the argument rows as PostgreSQL prints them, each with one text (or null) per argument
     */
    public List<String> invoke(ExternalFunctionBinding function, SpectrumSession session, String clusterRegion,
                               String user, long queryId, List<List<String>> rows) {
        String region = clusterRegion != null ? clusterRegion : regionResolver.getDefaultRegion();
        String functionArn = functionArn(function, session.accountId(), region);
        requireAuthorized(function, session.accountId(), functionArn);
        String clusterArn = new AwsArnUtils.Arn(regionResolver.partitionForRegion(region), "redshift", region,
                session.accountId(), "cluster:" + session.clusterIdentifier()).toString();
        List<String> results = new ArrayList<>(rows.size());
        for (List<ArrayNode> batch : batches(function, rows)) {
            ObjectNode request = request(function, session, clusterArn, user, queryId, batch);
            results.addAll(call(function, functionArn, request, batch.size()));
        }
        return results;
    }

    /** Splits the rows into the batches the function's limits allow; a row over the size limit goes alone. */
    private static List<List<ArrayNode>> batches(ExternalFunctionBinding function, List<List<String>> rows) {
        List<List<ArrayNode>> batches = new ArrayList<>();
        List<ArrayNode> batch = new ArrayList<>();
        // arguments serializes as "[" + records joined by "," + "]"
        long bytes = 2;
        for (List<String> row : rows) {
            ArrayNode record = record(function, row);
            long size = record.toString().getBytes(StandardCharsets.UTF_8).length;
            long added = batch.isEmpty() ? size : size + 1;
            if (!batch.isEmpty() && (batch.size() >= function.maxBatchRows()
                    || bytes + added > function.maxBatchSizeBytes())) {
                batches.add(batch);
                batch = new ArrayList<>();
                bytes = 2;
                added = size;
            }
            batch.add(record);
            bytes += added;
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }
        return batches;
    }

    private static ArrayNode record(ExternalFunctionBinding function, List<String> row) {
        if (row == null || row.size() != function.argumentTypes().size()) {
            throw new SpectrumSqlException(FUNCTION_ERROR, "A call of " + function.functionName() + " has "
                    + (row == null ? 0 : row.size()) + " values for " + function.argumentTypes().size()
                    + " arguments");
        }
        ArrayNode record = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < function.argumentTypes().size(); i++) {
            record.add(ExternalFunctionValues.toJson(function.argumentTypes().get(i), row.get(i)));
        }
        return record;
    }

    private ObjectNode request(ExternalFunctionBinding function, SpectrumSession session, String clusterArn,
                               String user, long queryId, List<ArrayNode> batch) {
        ObjectNode request = JsonNodeFactory.instance.objectNode();
        request.put("request_id", UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        request.put("cluster", clusterArn);
        request.put("user", user);
        request.put("database", session.databaseName());
        request.put("external_function", function.schemaName() + "." + function.functionName());
        request.put("query_id", queryId);
        request.put("num_records", batch.size());
        ArrayNode arguments = request.putArray("arguments");
        for (ArrayNode record : batch) {
            arguments.add(record);
        }
        return request;
    }

    private List<String> call(ExternalFunctionBinding function, String functionArn, ObjectNode request,
                              int expected) {
        byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
        InvokeResult result = invokeWithRetry(function, functionArn, payload);
        if (result.isTimedOut()) {
            throw new SpectrumSqlException(FUNCTION_ERROR, "The Lambda function " + function.lambdaFunction()
                    + " timed out");
        }
        if (result.getFunctionError() != null) {
            throw new SpectrumSqlException(FUNCTION_ERROR, "The Lambda function " + function.lambdaFunction()
                    + " failed: " + functionErrorMessage(result));
        }
        return results(function, parse(function, result.getPayload()), expected);
    }

    /** A call the Lambda service throttles is repeated with a growing pause until the function's retry budget ends. */
    private InvokeResult invokeWithRetry(ExternalFunctionBinding function, String functionArn, byte[] payload) {
        long start = clock.millis();
        long budget = Math.max(0, function.retryTimeoutMillis());
        long delay = FIRST_RETRY_DELAY_MILLIS;
        while (true) {
            try {
                return lambda.invokeArn(functionArn, payload, InvocationType.RequestResponse);
            } catch (AwsException e) {
                if (!"TooManyRequestsException".equals(e.getErrorCode())) {
                    throw new SpectrumSqlException(FUNCTION_ERROR, "Cannot call the Lambda function "
                            + function.lambdaFunction() + ": " + e.getMessage());
                }
                // compared as an elapsed time, so a huge RETRY_TIMEOUT cannot overflow a deadline
                long remaining = budget - (clock.millis() - start);
                if (remaining <= 0) {
                    throw new SpectrumSqlException(FUNCTION_ERROR, "The Lambda function "
                            + function.lambdaFunction() + " stayed throttled for " + function.retryTimeoutMillis()
                            + " ms: " + e.getMessage());
                }
                long pause = Math.min(delay, remaining);
                LOG.debugv("Lambda UDF {0} throttled, retrying in {1} ms", function.lambdaFunction(), pause);
                sleeper.accept(pause);
                delay = Math.min(delay * 2, MAX_RETRY_DELAY_MILLIS);
            } catch (SpectrumSqlException e) {
                throw e;
            } catch (RuntimeException e) {
                throw new SpectrumSqlException(FUNCTION_ERROR, "Cannot call the Lambda function "
                        + function.lambdaFunction() + ": " + e.getMessage());
            }
        }
    }

    private static JsonNode parse(ExternalFunctionBinding function, byte[] payload) {
        try {
            JsonNode response = JSON.readTree(payload == null ? new byte[0] : payload);
            // Lambda handlers following the AWS Python docs often return json.dumps(body), which arrives as a JSON string
            if (response != null && response.isTextual()) {
                response = JSON.readTree(response.textValue());
            }
            if (response == null || !response.isObject()) {
                throw new SpectrumSqlException(FUNCTION_ERROR, "The Lambda function " + function.lambdaFunction()
                        + " did not return a JSON object");
            }
            return response;
        } catch (IOException e) {
            throw new SpectrumSqlException(FUNCTION_ERROR, "The Lambda function " + function.lambdaFunction()
                    + " returned a response that is not JSON: " + e.getMessage());
        }
    }

    private static List<String> results(ExternalFunctionBinding function, JsonNode response, int expected) {
        if (!response.path("success").isBoolean() || !response.path("success").booleanValue()) {
            JsonNode message = response.get("error_msg");
            throw new SpectrumSqlException(FUNCTION_ERROR, "The Lambda function " + function.lambdaFunction()
                    + " reported a failure: " + (message != null && message.isTextual() ? message.textValue()
                            : "no error_msg"));
        }
        JsonNode results = response.get("results");
        if (results == null || !results.isArray()) {
            throw new SpectrumSqlException(FUNCTION_ERROR, "The Lambda function " + function.lambdaFunction()
                    + " returned no results array");
        }
        if (results.size() != expected || !response.path("num_records").isIntegralNumber()
                || response.path("num_records").asLong(-1) != expected) {
            throw new SpectrumSqlException(FUNCTION_ERROR, "The Lambda function " + function.lambdaFunction()
                    + " returned " + results.size() + " results (num_records " + response.path("num_records")
                    + ") for " + expected + " records");
        }
        List<String> texts = new ArrayList<>(expected);
        for (JsonNode value : results) {
            texts.add(ExternalFunctionValues.fromJson(function.returnType(), value));
        }
        return texts;
    }

    /** The message of a Lambda function error, which the runtime reports as a JSON object of errorMessage. */
    private static String functionErrorMessage(InvokeResult result) {
        byte[] payload = result.getPayload();
        if (payload != null && payload.length > 0) {
            try {
                JsonNode body = JSON.readTree(payload);
                JsonNode message = body == null ? null : body.get("errorMessage");
                if (message != null && message.isTextual()) {
                    return message.textValue();
                }
            } catch (IOException expected) {
                // the body of a function error is whatever the runtime wrote; the error type below still says why
            }
        }
        return result.getFunctionError();
    }

    /** A bare function name is one of the cluster's account in the cluster's Region, as Lambda resolves it. */
    private String functionArn(ExternalFunctionBinding function, String accountId, String region) {
        String reference = function.lambdaFunction();
        if (AwsArnUtils.looksLikeArn(reference)) {
            AwsArnUtils.Arn arn;
            try {
                arn = AwsArnUtils.parse(reference);
            } catch (IllegalArgumentException e) {
                throw notAFunctionArn(reference);
            }
            if (!"lambda".equals(arn.service()) || !arn.resource().startsWith("function:")) {
                throw notAFunctionArn(reference);
            }
            return reference;
        }
        // Lambda also takes <account>:function:<name>, which is a function of that account in this region
        if (ACCOUNT_FUNCTION.matcher(reference).matches()) {
            int separator = reference.indexOf(':');
            return new AwsArnUtils.Arn(regionResolver.partitionForRegion(region), "lambda", region,
                    reference.substring(0, separator), reference.substring(separator + 1)).toString();
        }
        return new AwsArnUtils.Arn(regionResolver.partitionForRegion(region), "lambda", region, accountId,
                "function:" + reference).toString();
    }

    private static SpectrumSqlException notAFunctionArn(String reference) {
        return new SpectrumSqlException(FUNCTION_ERROR, "\"" + reference + "\" is not a Lambda function ARN");
    }

    private void requireAuthorized(ExternalFunctionBinding function, String accountId, String functionArn) {
        Optional<String> refusal = authorizer.refusal(function.iamRoleArn(), accountId, REDSHIFT_PRINCIPAL, null,
                List.of());
        if (refusal.isPresent()) {
            throw new SpectrumSqlException(NOT_AUTHORIZED, refusal.get());
        }
        if (!config.services().iam().enforcementEnabled()) {
            return;
        }
        refusal = roleAccess.refusal(function.iamRoleArn(), INVOKE_ACTION, functionArn,
                AwsArnUtils.parse(functionArn).region(), Map.of());
        if (refusal.isPresent()) {
            throw new SpectrumSqlException(NOT_AUTHORIZED, refusal.get());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpectrumSqlException(FUNCTION_ERROR, "Interrupted while waiting to call the Lambda function");
        }
    }
}
